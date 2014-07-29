/**

Overview
========

1. Intro: Abstraction Without Regret
1. Common Compiler Optimizations
1. Delite: An End-to-End System for Embedded Parallel DSLs
    1. Building a Simple DSL
    1. Code Generation
    1. The Delite Compiler Framework and Runtime
1. Control Abstraction
    1. Leveraging Higher-Order Functions in the Generator
    1. Using Continuations in the Generator to Implement Backtracking
    1. Using Continuations in the Generator to Generate Async Code Patterns
        1. CPS and Staging
        1. CPS for Interruptible Traversals
        1. Defining the Ajax API
        1. CPS for Parallelism
    1. Guarantees by Construction
1. Data Abstraction
    1. Static Data Structures
    1. Dynamic Data Structures with Partial Evaluation
    1. Generic Programming with Type Classes
    1. Unions and Inheritance
    1. Struct of Array and Other Data Format Conversions
    1. Loop Fusion and Deforestation
    1. Extending the Framework
    1. Lowering Transforms}
1. Case Studies
    1. OptiML Stream Example
        1. Downsampling in Bioinformatics
    1. OptiQL Struct Of Arrays Example
    1. Fast Fourier Transform Example
        1. Implementing Optimizations
        1. Running the Generated Code
    1. Regular Expression Matcher Example



# (Chapter 0) Intro: Abstraction Without Regret
\label{chap:400}


LMS is a dynamic multi-stage programming approach: We have the full Scala language
at our disposal to compose fragments of object code. In fact, DSL programs are program
\emph{generators} that produce an object program IR when run. 
DSL or library authors and application programmers can exploit this multi-level nature
to perform computations explicitly at staging time, so that the generated program does
not pay a runtime cost. 
Multi-stage programming shares some similarities with partial evaluation \cite{jones93partial},
but instead of an automatic binding-time analysis, the programmer makes
binding times explicit in the program.
We have seen how LMS uses \code{Rep} types for this purpose:
\begin{listing}
val s: Int = ...      // a static value: computed at staging time
val d: Rep[Int] = ... // a dynamic value: computed when generated program is run
\end{listing}

Unlike with automatic partial evaluation, the programmer obtains a guarantee about 
which expressions will be evaluated at staging time.

While moving \emph{computations} from run time to staging time is an interesting
possibility, many computations actually depend on dynamic input and cannot be done
before the input is available.
Nonetheless, explicit staging can be used to \emph{combine} dynamic computations more
efficiently.
Modern programming languages provide indispensable constructs for abstracting and
combining program functionality. Without higher-order features such as first-class
functions or object and module systems, software development at scale would not be possible. 
However, these abstraction mechanisms have a cost and make it much harder for the 
compiler to generate efficient code.

Using explicit staging, we can use abstraction in the generator stage to remove 
abstraction in the generated program. This holds both for control (e.g.\ functions, continuations)
and data abstractions (e.g.\ objects, boxing). Some of the material in this chapter
is taken from \cite{DBLP:journals/corr/abs-1109-0778}.


# Common Compiler Optimizations

We have seen in Part~\ref{part:P2} how many classic compiler optimizations can be 
applied to the IR generated from embedded programs in a straightforward way. 
Among those generic optimizations are common subexpression elimination, dead 
code elimination, constant folding and code motion. Due to the structure of the IR, 
these optimizations all
operate in an essentially global way, at the level of domain operations.
An important difference to regular general-purpose compilers is that
IR nodes carry information about effects they incur (see Section~\ref{sec:321}). 
This permits to use quite
precise dependency tracking that provides the code generator with a lot of
freedom to group and rearrange operations. Consequently, optimizations like
common subexpression elimination and dead code elimination will
easily remove complex DSL operations that contain internal control-flow and
may span many lines of source code. 

Common subexpression elimination (CSE) / global value numbering (GVN) for
pure nodes is handled by \code{toAtom}: whenever the \code{Def} in question has
been encountered before, its existing symbol is returned instead of a new
one (see Section~\ref{sec:320cse}). Since the operation is pure, we do not need to check via data flow analysis
whether its result is available on the current path. Instead we just insert a
dependency and let the later code motion pass (see Section~\ref{sec:320codemotion}) 
schedule the operation in a correct order. 
Thus, we achieve a similar effect as partial redundancy
elimination (PRE~\cite{DBLP:journals/toplas/KennedyCLLTC99}) but in a simpler way. 

Based on frequency information for block expression, code motion will hoist 
computation out of loops and push computation into conditional branches. 
Dead code elimination is trivially included.  Both optimizations are coarse 
grained and work on the level of domain operations. For example, whole data 
parallel loops will happily be hoisted out of other loops.

Consider the following user-written code:
\begin{listing}
v1 map { x =>
  val s = sum(v2.length) { i => v2(i) }
  x/s
}
\end{listing}
This snippet scales elements in a vector \code{v1} relative to the sum
of \code{v2}'s elements. Without any extra work, the generic code motion
transform places the calculation of \code{s} (which is itself a loop) outside
the loop over \code{v1} because it
does not depend on the loop variable \code{x}.
\begin{listing}
val s = sum(v2.length) { i => v2(i) }
v1 map { x =>
  x/s
}
\end{listing}






# Delite: An End-to-End System for Embedded Parallel DSLs
\label{sec:delite}

This section gives an overview of our approach to developing and executing
embedded DSLs in parallel and on heterogeneous devices. A more thorough
description of Delite can be found in Section~\ref{chap:600delite} of Part~\ref{part:P4}.

Delite seeks to alleviate the burden of building a high performance
DSL by providing reusable infrastructure. Delite DSLs are embedded in 
Scala using LMS. On top of this layer, Delite is structured into a
\emph{framework} and a \emph{runtime} component.  The framework provides
primitives for parallel operations such as \code{map} or \code{reduce} that DSL
authors can use to define higher-level operations. Once a DSL author uses
Delite operations, Delite handles code generating to multiple platforms (e.g.
Scala and CUDA), and handles difficult but common issues such as device
communication and synchronization. These capabilities are enabled by exploiting
the domain-specific knowledge and restricted semantics of the DSL compiler.



## Building a Simple DSL
\label{subsec:lms}

On the surface, DSLs implemented on top of Delite appear very similar to
purely-embedded (i.e.\ library only) Scala-based DSLs.
However, a key aspect of LMS and hence Delite is that DSLs are split in two parts,
\emph{interface} and \emph{implementation}. Both parts can be assembled from components in
the form of Scala traits.
DSL programs are written in terms of
the DSL interface, agnostic of the implementation. Part of each DSL interface is an abstract
type constructor \code{Rep[_]} that is used to wrap types in DSL programs.
For example, DSL programs use \code{Rep[Int]} wherever a regular program would
use \code{Int}. The DSL operations defined in the DSL interface (most of them are
abstract methods) are all expressed in terms of \code{Rep} types. 

The DSL \emph{implementation} provides a concrete instantiation of \code{Rep} as
expression trees (or graphs). The DSL operations left abstract in the interface are
implemented to create an expression representation of the operation.
Thus, as a result of executing the DSL program, we obtain an analyzable
representation of the very DSL program which we will refer to as IR
(intermediate representation).

To substantiate the description, let us consider an example step by step.
A simple (and rather pointless) program that calculates the average of 
100 random numbers, written in a prototypical DSL \code{MyDSL} that includes numeric
vectors and basic console IO could look like this:
\begin{slisting}
  object HelloWorldRunner extends MyDSLApplicationRunner with HelloWorld
  trait HelloWorld extends MyDSLApplication {
    def main() = {
      val v = Vector.rand(100)
      println("today's lucky number is: ")
      println(v.avg)
    }
  }
\end{slisting}
Programs in our sample DSL live within traits that inherit from \code{MyDSLApplication},
with method \code{main} as the entry point. 

\code{MyDSLApplication} is a trait provided by the DSL that defines the DSL interface.
In addition to the actual DSL program, there is a singleton object that inherits
from \code{MyDSLApplicationRunner} and mixes in the trait that contains the program.
As the name implies, this object will be responsible for directing the
staged execution of the DSL application.

Here is the definition of \code{MyDSL}'s components encountered so far:
\begin{slisting}
  trait MyDSLApplication extends DeliteApplication with MyDSL
  trait MyDSLApplicationRunner extends DeliteApplicationRunner with MyDSLExp

  trait MyDSL extends ScalaOpsPkg with VectorOps
  trait MyDSLExp extends ScalaOpsPkgExp with VectorOpsExp with MyDSL
\end{slisting}

\code{MyDSLApplicationRunner} inherits
the mechanics for invoking code generation from DeliteApplication. We discuss how
Delite provides these facilities in section~\ref{subsec:delite}.
We observe a structural split in the inheritance hierarchy that is rather fundamental:
\code{MyDSL} defines the DSL \emph{interface}, \code{MyDSLExp} the \emph{implementation}.
A DSL program is written with respect to the interface but it knows nothing
about the implementation. The main reason for this separation is safety.
If a DSL program could observe its own structure, optimizing rewrites
that maintain semantic but not structural equality of DSL expressions
could no longer be applied safely.\footnote{In fact, this is the main 
reason why MSP languages do not allow inspection of staged code at all \cite{DBLP:conf/pepm/Taha00}.}
Our sample DSL includes a set of common Scala operations that are provided 
by the core LMS library as trait \code{ScalaOpsPkg}. These operations
include conditionals, loops, variables and also \code{println}.
On top of this set of generic things that are inherited from Scala,
the DSL contains vectors and associated operations. 
The corresponding interface is defined as follows:

\begin{slisting}
trait VectorOps extends Base {
  abstract class Vector[T]                    // placeholder ("phantom") type
  object Vector {
    def rand(n: Rep[Int]) = vector_rand(n)    // invoked as: Vector.rand(n)
  }
  def vector_rand(n: Rep[Int]): Rep[Vector[Double]]
  def infix_length[T](v: Rep[Vector[T]]): Rep[Int]      // invoked as: v.length
  def infix_sum[T:Numeric](v: Rep[Vector[T]]): Rep[T]   // invoked as: v.sum
  def infix_avg[T:Numeric](v: Rep[Vector[T]]): Rep[T]   // invoked as: v.avg
  ...
}
\end{slisting}

There is an abstract class \code{Vector[T]} for vectors with element type \code{T}. The notation
\code{T:Numeric} means that \code{T} may only range over numeric types such as \code{Int} or \code{Double}.
Operations on vectors are not declared as instance methods of \code{Vector[T]} but as external functions over
values of type \code{Rep[Vector[T]]}.



Returning to our sample DSL, this is the definition of \code{VectorOpsExp}, the
implementation counterpart to the interface defined above in \code{VectorOps}:

\begin{slisting}
trait VectorOpsExp extends DeliteOpsExp with VectorOps {
  case class VectorRand[T](n: Exp[Int]) extends Def[Vector[Double]]
  case class VectorLength[T](v: Exp[Vector[T]]) extends Def[Int]

  case class VectorSum[T:Numeric](v: Exp[Vector[T]]) extends DeliteOpLoop[Exp[T]] {
    val range = v.length
    val body = DeliteReduceElem[T](v)(_ + _) // scalar addition (impl not shown)
  }

  def vector_rand(n: Rep[Int]) = VectorRand(n)
  def infix_length[T](v: Rep[Vector[T]]) = VectorLength(v)
  def infix_sum[T:Numeric](v: Rep[Vector[T]]) = VectorSum(v)
  def infix_avg[T:Numeric](v: Rep[Vector[T]]) = v.sum / v.length
  ...
}
\end{slisting}
The constructor \code{rand} and the function \code{length} are implemented as
new plain IR nodes (extending \code{Def}). Operation \code{avg} is implemented directly in terms of \code{sum}
and \code{length} whereas \code{sum} is implemented as a \code{DeliteOpLoop} 
with a \code{DeliteReduceElem} body. 
These special classes of structured IR nodes are provided by the Delite framework
and are inherited via \code{DeliteOpsExp}.





## Code Generation
\label{subsec:codegen}

The LMS framework provides a code generation infrastructure that includes a program scheduler
and a set of base code generators. The program scheduler uses the data
and control dependencies encoded by IR nodes to determine the sequence of nodes that should be
generated to produce the result of a block. 
%The scheduler performs code motion optimizations,
%including hoisting computation out of loops and pushing it into conditionals when possible. Since programs
%are scheduled only by their dependencies, dead code is also eliminated at this stage. 
After the scheduler has determined a schedule, it invokes
the code generator on each node in turn. There is one \emph{code generator} object
per target platform (e.g. Scala, CUDA, C++) that mixes together traits that describe how to generate
platform-specific code for each IR node. This organization makes it easy for DSL authors to modularly extend the base code generators;
they only have to define additional traits to be mixed in with the base generator. 


Therefore, DSL designers only have to add code generators for their own
domain-specific types. They inherit the common functionality of scheduling and
callbacks to the generation methods, and can also build on top of code
generator traits that have already been defined. In many cases, though, DSL authors do not
have to write code generators at all; the next section describes how Delite 
takes over this responsibility for most operations.




## The Delite Compiler Framework and Runtime
\label{subsec:delite}

On top of the LMS framework that provides the basic means to construct IR nodes
for DSL operations, the Delite Compiler Framework provides high-level
representations of execution patterns through \code{DeliteOp} IR, which
includes a set of common parallel execution patterns (e.g. map, zipWith,
reduce).

\code{DeliteOp} extends \code{Def}, and DSL operations may extend one of the
\code{DeliteOps} that best describes the operation.  For example, since 
\code{VectorSum} has the semantics of iterating over the elements of the input
Vector and adding them to reduce to a single value, it can be
implemented by extending \code{DeliteOpLoop} with a reduction operation as its
body. This significantly reduces the amount of work in implementing a
DSL operation since the DSL developers only need to specify the
necessary fields of the \code{DeliteOp} (\code{range} and \code{body} in the case of
\code{DeliteOpLoop}) instead of fully implementing the operation.  

\code{DeliteOpLoop}s are intended as parallel for-loops. Given an
integer index range, the runtime guarantees to execute the loop body exactly once 
for each index but does not guarantee any execution order. 
Mutating global state from within a loop is only safe at disjoint indexes. 
There are specialized constructs to define loop bodies for map and reduce patterns (\code{DeliteCollectElem}, \code{DeliteReduceElem})
that transform a collection of elements point-wise or perform aggregation. 
An optional predicate can be added to perform filter-style operations, i.e.\ select or aggregate only those
elements for which the predicate is true. All loop constructs can be fused into 
\code{DeliteOpLoops} that do several operations at once.

Given the relaxed ordering guarantees, the framework can automatically generate 
efficient parallel code for \code{DeliteOps}, targeting heterogeneous parallel hardware.  
Therefore, DSL developers can easily implement parallel DSL operations by
extending one of the parallel \code{DeliteOps}, and only focus on the
language design without knowing the low-level details of the target
hardware.  


The Delite Compiler Framework currently supports Scala, C++, and CUDA targets.
The framework provides code generators for each target in addition to a main
generator (\emph{Delite generator}) that controls them. The \emph{Delite generator} iterates
over the list of available target generators to emit the target-specific
kernels. By generating multiple target implementations of the kernels and
deferring the decision of which one to use, the framework provides the runtime with
enough flexibility in scheduling the kernels based on dynamic information such as 
resource availability and input size. In addition to the kernels, the
Delite generator also generates the \emph{Delite Execution Graph} (DEG) of the
application. The DEG is a high-level representation of the program that
encodes all necessary information for its execution, including
the list of inputs, outputs, and interdependencies of all kernels.  
After all the kernels are generated, the Delite Runtime starts analyzing the
DEG and emits execution plans for each target hardware. Further details are
available in Section~\ref{chap:600delite} of Part~\ref{part:P4}.




# (Chapter 1) Control Abstraction
\label{chap:450}

Among the most useful control abstractions are
higher order functions.  We can implement support for higher order functions in
DSLs while keeping the generated IR strictly first order. This vastly simplifies
the compiler implementation and makes optimizations much more effective since
the compiler does not have to reason about higher order control flow. 
We can implement a higher order function \code{foreach} over Vectors 
as follows:
\begin{listing}
def infix_foreach[A](v: Rep[Vector[A]])(f: Rep[A] => Rep[Unit]) = {
  var i = 0; while (i < v.length) { f(v(i)); i += 1 }
}
// example:
Vector.rand(100) foreach { i => println(i) }
\end{listing}
The generated code from the example will be strictly first order, consisting
of the unfolded definition of \code{foreach} with the application 
of \code{f} substituted with the \code{println}
statement:
\begin{listing}
while (i < v.length) { println(v(i)); i += 1 }
\end{listing}
The unfolding is guaranteed by the Scala type system since \code{f} has type
\code{Rep[A]=>Rep[Unit]}, meaning it will be executed statically but it
operates on staged values.
In addition to simplifying the compiler, the generated code does not 
pay any extra overhead. There are no closure allocations and 
no inlining problems \cite{cliffinlining}.

Other higher order functions like \code{map} or \code{filter} could be expressed
on top of foreach. Section~\ref{subsec:lms} and Chapter~\ref{chap:600delite} show how actual Delite DSLs implement
these operations as data parallel loops.  The rest of this chapter shows how other control
structures such as continuations can be supported in the same way.



# Leveraging Higher-Order Functions in the Generator

Higher-order functions are extremely useful to structure programs but also
pose a significant obstacle for compilers, recent
advances on higher-order control-flow analysis notwithstanding \cite{DBLP:conf/esop/VardoulakisS10,DBLP:journals/corr/abs-1007-4268}.
While we would like to retain the structuring aspect for DSL programs,
we would like to avoid higher-order control flow in generated code. 
Fortunately, we can use higher-order functions in the generator stage to
compose first-order DSL programs.

Consider the following program that prints the number of elements greater than 7 in some vector:
\begin{listing}
val xs: Rep[Vector[Int]] = ...
println(xs.count(x => x > 7))
\end{listing}

The program makes essential use of a higher-order function \code{count} to
count the number of elements in a vector that fulfill a predicate given as
a function object. 
Ignoring
for the time being that we would likely want to use a \code{DeliteOp} for
parallelism, a good and natural way to implement \code{count} would be to
first define a higher-order function \code{foreach} to iterate over vectors,
as shown at the beginning of the chapter:
\begin{listing}
def infix_foreach[A](v: Rep[Vector[A]])(f: Rep[A] => Rep[Unit]) = {
  var i: Rep[Int] = 0
  while (i < v.length) {
    f(v(i))
    i += 1
  }
}
\end{listing}

The actual counting can then be implemented in terms of the traversal:
\begin{listing}
def infix_count[A](v: Rep[Vector[A]])(f: Rep[A] => Rep[Boolean]) = {
  var c: Rep[Int] = 0
  v foreach { x => if (f(x)) c += 1 }
  c
}
\end{listing}

It is important to note that \code{infix_foreach} and \code{infix_count}
are static methods, i.e.\ calls will happen at staging time and result
in inserting the computed DSL code in the place of the call.
Likewise, while the argument vector \code{v} is a dynamic
value, the function argument \code{f} is again static. However, \code{f} operates
on dynamic values, as made explicit by its type \code{Rep[A] => Rep[Boolean]}.
By contrast, a dynamic function value would have type \code{Rep[A => B]}.

This means that the code generated for the example program will look roughly
like this, assuming that vectors are represented as arrays in the generated code:

\begin{listing}
val v: Array[Int] = ...
var c = 0
var i = 0
while (i < v.length) {
  val x = v(i)
  if (x > 7)
    c += 1
  i += 1
}
println(c)
\end{listing}

All traces of higher-order control flow have been removed and the program is
strictly first-order. Moreover, the programmer can be sure that the DSL program
is composed in the desired way.

This issue of higher-order functions is a real problem for regular Scala
programs executed on the JVM. The Scala collection library uses essentially the
same \code{foreach} and count \code{abstractions} as above and the JVM, which applies 
optimizations based on per-call-site profiling, will identify the call site \emph{within} \code{foreach} as
a hot spot. However, since the number of distinct functions called from foreach is
usually large, inlining or other optimizations cannot be applied and every iteration
step pays the overhead of a virtual method call \cite{cliffinlining}.


# Using Continuations in the Generator to Implement Backtracking
\label{sec:450bam}

Apart from pure performance improvements, we can use functionality of the generator stage
to enrich the functionality of DSLs without any work on the DSL-compiler side. As an example
we consider adding backtracking nondeterministic computation to a DSL using a simple variant of
McCarthy's \code{amb} operator \cite{McCarthy63abasis}. Here is a nondeterministic program that uses \code{amb}
to find pythagorean triples from three given vectors:
\begin{listing}
val u,v,w: Rep[Vector[Int]] = ...
nondet {
  val a = amb(u)
  val b = amb(v)
  val c = amb(w)
  require(a*a + b*b == c*c)
  println("found:")
  println(a,b,c)
}
\end{listing}

We can use Scala's support for delimited continuations \cite{DBLP:conf/icfp/RompfMO09}
and the associated control operators \code{shift} and \code{reset} \cite{DBLP:journals/mscs/DanvyF92,DBLP:conf/lfp/DanvyF90} to implement
the necessary primitives. 
The scope delimiter \code{nondet} is just the regular \code{reset}. The other operators are defined 
as follows:

\begin{listing}
def amb[T](xs: Rep[Vector[T]]): Rep[T] @cps[Rep[Unit]] = shift { k =>
  xs foreach k
}  
def require(x: Rep[Boolean]): Rep[Unit] @cps[Rep[Unit]] = shift { k => 
  if (x) k() else ()
}
\end{listing}

Since the implementation of \code{amb} just calls the previously defined method \code{foreach}, the
generated code will be first-order and consist of three nested \code{while} loops:
%\setlength{\columnseprule}{0.5pt}
\begin{multicols}{3}
\begin{listing}
val u,v,w:Rep[Vector[Int]]=...
var i = 0
while (i < u.length) {
  val a = u(i)
  val a2 = a*a
  var j = 0
  while (j < v.length) {
    val b = v(j)
    val b2 = b*b
    val a2b2 = a2+b2
    var k = 0
    while (k < w.length) {
      val c = w(k)
      val c2 = c*c
      if (a2b2 == c2) {
        println("found:")
        println(a,b,c)
      }
      k += 1
    }
    j += 1
  }
  i += 1
}
\end{listing}
\end{multicols}

Besides the advantage of not having to implement \code{amb} as part of the DSL compiler,
all common optimizations that apply to plain \code{while} loops are automatically applied to the
unfolded backtracking implementation. For example, the loop invariant hoisting performed by code motion has
moved the computation of \code{a*a} and \code{b*b} out of the innermost loop.

The given implementation of \code{amb} is not the only possibility, though. For
situations where we know the number of choices (but not necessarily the actual values) for 
a particular invocation of \code{amb} at staging time, we can implement an 
alternative operator that takes a (static) list of
dynamic values and unfolds into specialized code paths for each option at compile
time:

\begin{listing}
def bam[T](xs: List[Rep[T]]): Rep[T] @cps[Rep[Unit]] = shift { k =>
  xs foreach k
}
\end{listing}

Here, \code{foreach} is not a DSL operation but a plain traversal of the static
argument list xs. The \code{bam} operator must be employed with some care because
it incurs the risk of code explosion. However, static specialization of 
nondeterministic code paths can be beneficial if it allows aborting many paths early
based on static criteria or merging computation between paths.

\begin{listing}
val u: Rep[Vector[Int]] = ...
nondet {
  val a = amb(u)
  val b = bam(List(x1), List(x2))
  val c = amb(v)
  require(a + c = f(b))  // assume f(b) is expensive to compute
  println("found:")
  println(a,b,c)
}
\end{listing}

If this example was implemented as three nested loops, \code{f(x1)} and \code{f(x2)} would
need to be computed repeatedly in each iteration of the second loop as they depend on the
loop-bound variable \code{b}. However, the use of \code{bam} will
remove the loop over \code{x1,x2} and expose the expensive computations as
redundant so that code motion can extract them from the loop:

\begin{listing}
val fx1 = f(x1)
val fx2 = f(x2)
while (...) { // iterate over u
  while (...) { // iterate over v
    if (a + c == fx1) // found
  }
  while (...) { // iterate over v
    if (a + c == fx2) // found
  }
}
\end{listing}

In principle, the two adjacent inner loops could be subjected to the loop fusion optimization discussed
in Section~\ref{sec:360fusionComp}. This would remove the duplicate traversal of \code{v}.
In this particular case fusion is currently not applied since it would change the order of the
side-effecting \code{println} statements.



# Using Continuations in the Generator to Generate Async Code Patterns
\label{sec:cpsAsync}

The previous section used continuations that were completely translated away during generation. In this section,
we will use a CPS-transformed program generator to generate code that is in CPS.
While the previous section generated only loops, we will generate actual functions in this section, using
the mechanisms described in Section~\ref{sec:220functions}. The example is taken from~\cite{DBLP:conf/ecoop/KossakowskiARO12} and concerned with generating JavaScript but the techniques apply to any target.\credits{Design (mostly) by the author, impl. and
presentation by Grzegorz Kossakowski and Nada Amin}

A callback-driven programming style is pervasive in JavaScript programs. Because of lack of thread support, callbacks are used for I/O,
scheduling and event-handling. For example, in an Ajax call (Asynchronous JavaScript and XML), one has to provide a callback that will be called once the requested data arrives
from the server. This style of programming is known to be unwieldy in more complicated scenarios. To give a specific example, let us consider a
scenario where we have an array of Twitter account names and we want to ask Twitter for the latest tweets of each account. Once we obtain the
tweets of all users, we would like to log that fact in a console.

We implement this program both directly in JavaScript and in the embedded JavaScript DSL \cite{DBLP:conf/ecoop/KossakowskiARO12}. Let us start by implementing logic that fetches tweets for a single user by using
the jQuery library for Ajax calls:

%\lstset{basicstyle=\ttfamily\scriptsize}
%\begin{figure}
\setlength{\columnseprule}{0.5pt}
%\begin{changemargin}{-1.5cm}{-1.5cm}
\begin{multicols}{2}
\begin{lstlisting}[language=JavaScript,caption=Fetching tweets in JavaScript,label=code:js:twitter_fetch]
function fetchTweets(user, callback) {
  jQuery.ajax({
    url: "http://api.twitter.com/1/"
     + "statuses/user_timeline.json/",
    type: "GET",
    dataType: "jsonp",
    data: {
      screen_name: user,
      include_rts: true,
      count: 5,
      include_entities: true
    },
    success: callback
  })
}  
\end{lstlisting}
\begin{lstlisting}[escapechar=|,caption=Fetching tweets in DSL,label=code:twitter_fetch]
def fetchTweets(user: Rep[String]) = 
  (ajax.get { new JSLiteral {
    val url = "http://api.twitter.com/1/"
          + "statuses/user_timeline.json"
    val `type` = "GET"
    val dataType = "jsonp"
    val data = new JSLiteral {
      val screen_name = user
      val include_rts = true
      val count = 5
      val include_entities = true
    }
  }
}).as[TwitterResponse]
type TwitterResponse = 
  Array[JSLiteral {val text: String}]
\end{lstlisting}
\end{multicols}
%\end{changemargin}
%\end{figure}

Note that the JavaScript version takes a callback as second argument that will 
be used to process the fetched tweets. We provide the rest of the
logic that iterates over array of users and makes Ajax requests:

%\begin{figure}
%\begin{changemargin}{-0.7cm}{-0.7cm}
%\noindent\begin{minipage}{\textwidth}
\setlength{\columnseprule}{0.5pt}
\begin{multicols}{2}
\begin{lstlisting}[language=JavaScript,caption=Twitter example in JavaScript,label=code:js:twitter_example]
var processed = 0
var users = ["gkossakowski", 
  "odersky", "adriaanm"]
users.forEach(function (user) {
  console.log("fetching " + user)
  fetchTweets(user, function(data) {
    console.log("finished " + user)
    data.forEach(function (t) {
      console.log("fetched " + t.text)
    })
    processed += 1
    if (processed == users.length) {
      console.log("done")
    }
  })
})
\end{lstlisting}
\begin{lstlisting}[escapechar=|,caption=Twitter example in DSL,label=code:twitter_example]
val users = array("gkossakowski", 
  "odersky", "adriaanm")
for (user <- users.parSuspendable) {
  console.log("fetching " + user)
  val tweets = fetchTweets(user)
  console.log("finished " + user)
  for (t <- tweets)
    console.log("fetched " + t.text)
}
console.log("done")


|$\quad$|
\end{lstlisting}
\end{multicols}
%\end{minipage}
%\end{changemargin}
%\end{figure}

%\lstset{basicstyle=\ttfamily}

Because of the inverted control flow of callbacks, synchronization between callbacks has to be handled manually. Also, the inverted control flow
leads to a code structure that is distant from the programmer's intent. Notice that the in JavaScript version, the call to console that prints
``done" is put inside of the foreach loop. If it was put it after the loop, we would get ``done'' printed \emph{before} any Ajax call has been
made leading to counterintuitive behavior.

As an alternative to the callback-driven programming style, one can use an explicit monadic style, possibly sugared by a Haskell-like
``do''-notation. However, this requires rewriting possibly large parts of a program into monadic style when a single async operation is added.
Another possibility is to automatically transform the program into continuation passing style (CPS), enabling the programmer to express the
algorithm in a straightforward, sequential way and creating all the necessary callbacks and book-keeping code automatically. Links~\cite{links}
uses this approach. However, a whole-program CPS transformation can cause performance degradation, code size blow-up, and stack overflows. In
addition, it is not clear how to interact with existing non-CPS libraries as the whole program needs to adhere to the CPS style. Here we use a
\emph{selective} CPS transformation, which precisely identifies expressions
that need to be CPS transformed.

In fact, the Scala compiler already does selective, \code{@suspendable} type-annotation-driven CPS transformations of Scala programs~\cite{DBLP:conf/icfp/RompfMO09,DBLP:conf/lfp/DanvyF90,DBLP:journals/mscs/DanvyF92}. 
We show how this mechanism can be used for transforming our DSL code before staging and stripping out
most CPS abstractions at staging time. The generated JavaScript code does not contain any CPS-specific code but is written in CPS-style by use of JavaScript anonymous functions.




## CPS and Staging
As an example, we will consider a seemingly blocking \code{sleep} method implemented in a non-blocking, asynchronous style. In JavaScript, there are no threads and there is no notion of blocking. However the technique is useful in other circumstances as well, for example when using thread pools, as no thread is being blocked during the sleep period. Let us see how our CPS transformed \code{sleep} method can be used:
\begin{lstlisting}
def foo() = {
  sleep(1000)
  println("Called foo")
}
reset {
  println("look, Ma ...")
  foo()
  sleep(1000)
  println(" no callbacks!")
}
\end{lstlisting}

We define \code{sleep} to use JavaScript's asynchronous \code{setTimeout} function, which takes an explicit callback:
\begin{lstlisting}
def sleep(delay: Rep[Int]) = shift { k: (Rep[Unit]=>Rep[Unit]) =>
  window.setTimeout(lambda(k), delay)
}
\end{lstlisting}

The \code{setTimeout} method expects an argument of type \code{Rep[Unit=>Unit]} i.e.\ a staged function of type
\code{Unit=>Unit}. The \code{shift} method offers us a function of type \code{Rep[Unit]=>Rep[Unit]}, so we need to reify it to obtain the
desired representation. The reification is achieved by the \code{fun} function (called \code{lambda} in \ref{sec:220functions}) provided by our framework and performed at staging time.

It is important to note that reification preserves function composition. Specifically, let \code{f: Rep[A] => Rep[B]} and \code{g: Rep[B] =>
Rep[C]} then {\tt\small lambda(g compose f) == (lambda(g) compose lambda(f))} where we consider two reified functions to be equal if they yield the same
observable effects at runtime. That property of function reification is at the core of reusing the continuation monad in our DSL. Thanks to the
fact that the continuation monad composes functions, we can just insert reification at some places (like in a \code{sleep}) and make sure that we
reify \emph{effects} of the continuation monad without the need to reify the monad itself.

%Note that the continuation monad (as implemented in Scala) include exception handling. We don't go into details how reification of exception handling can be achieved as it is not essential to understanding how CPS transformation from host language is being reused at DSL level. It is sufficient to say that exception handling in CPS context can be done with a bit of technical work that does not require any new tools or abstractions apart from what we present here.

% i like the section, because it demystifies how continuation monad works with virtualization by just reducing its preservation to the preservation of function composition. Is that really all there is it to?
% outstanding comments:
% - i swapped g and f to make the types work out. please verify.
% - ...continuation monad mostly composes functions...: does it do anything else? if so, why are you glossing over it? this 'mostly' gives a feeling that you're hiding something, and is the only reason why the section is not entirely satisfying as an explanation.
% - ... and make sure that we reify ...: replace by just ''to reify''?

## CPS for Interruptible Traversals
We need to be able to interrupt our execution while traversing an array in order to implement
the functionality from listing \ref{code:twitter_example}. Let us consider a simplified example where we want to 
sleep during each iteration. We present both JavaScript and DSL code that achieves that
(listings~\ref{code:js:sleep_iter}~\&~\ref{code:sleep_iter}).
%
Both programs, when executed, will print the following output:
\begin{lstlisting}
//pause for 1s
1
//pause for 2s
2
//pause for 3s
3
done
\end{lstlisting}

\begin{figure}
%\lstset{basicstyle=\ttfamily\scriptsize}
%\begin{changemargin}{-0.7cm}{-0.7cm}
\setlength{\columnseprule}{0.5pt}
\begin{multicols}{2}
\begin{lstlisting}[caption=JavaScript,label=code:js:sleep_iter]
var xs = [1, 2, 3]
var i = 0
var msg = null
function f1() {
  if (i < xs.length) {
    window.setTimeout(f2, xs[i]*1000)
    msg = xs[i]
    i++
  }
}
function f2() {
  console.log(msg)
  f1()
}
f1()
\end{lstlisting}
\begin{lstlisting}[escapechar=|,caption=DSL,label=code:sleep_iter]
val xs = array(1, 2, 3)
// shorthand for xs.suspendable.foreach
for (x <- xs.suspendable) {
  sleep(x * 1000)
  console.log(String.valueOf(x))
}
log("done")






|$\quad$|
\end{lstlisting}
\end{multicols}
%\end{changemargin}
\lstset{basicstyle=\ttfamily}
\end{figure}

In the DSL code, we use a \code{suspendable} variant of arrays, which is achieved through an implicit conversion from regular arrays:
\begin{lstlisting}
implicit def richArray(xs: Rep[Array[A]]) = new {
  def suspendable: SArrayOps[A] = new SArrayOps[A](xs)
}
\end{lstlisting}
The idea behind \code{suspendable} arrays is that iteration over them can be interrupted. 
We will have a closer look at how to achieve that with the help of CPS. The \code{suspendable} 
method returns a new instance of the \code{SArrayOps} class defined here:

\begin{lstlisting}[caption=Suspendable foreach,label=code:suspendable_foreach]
class SArrayOps(xs: Rep[Array[A]]) {
  def foreach(yld: Rep[A] => Rep[Unit] @suspendable):
    Rep[Unit] @suspendable = {
      var i = 0
      suspendableWhile(i < xs.length) { yld(xs(i)); i += 1 }
    }
}
\end{lstlisting}
Note that one cannot use while loops in CPS but we can simulate them with recursive functions. 
Let us see how regular while loop can be simulated with a recursive function:
\begin{lstlisting}
def recursiveWhile(cond: => Boolean)(body: => Unit): Unit = {
  def rec = () => if (cond) { body; rec() } else {}
  rec()
}
\end{lstlisting}

By adding CPS-related declarations and control abstractions, we implement \code{suspendableWhile}:

\begin{lstlisting}
def suspendableWhile(cond: => Rep[Boolean])(
  body: => Rep[Unit] @suspendable): Rep[Unit] @suspendable =
  shift { k =>
    def rec = fun { () =>
      if (cond) reset { body; rec() } else { k() }
    }
    rec()
  }
\end{lstlisting}

## Defining the Ajax API

With the abstractions for interruptible loops and traversals at hand,
what remains to complete the Twitter example from the beginning of the section
is the actual Ajax request/response cycle.

The Ajax interface component provides a type \code{Request} that captures the 
request structure expected by the underlying JavaScript/jQuery implementation and the
necessary object and method definitions to enable the use of \code{ajax.get} in user
code:
\begin{lstlisting}
trait Ajax extends JS with CPS {
  type Request = JSLiteral {
    val url: String
    val `type`: String
    val dataType: String
    val data: JSLiteral
  }
  type Response = Any
  object ajax {
    def get(request: Rep[Request]) = ajax_get(request)
  }
  def ajax_get(request: Rep[Request]): Rep[Response] @suspendable
}
\end{lstlisting}

Notice that the \code{Request} type is flexible enough to support an arbitrary object literal 
type for the \code{data} field through subtyping. The \code{Response} type alias points at \code{Any} 
which means that it is the user's responsibility to either use \code{dynamic} or 
perform an explicit cast to the expected data type.

The corresponding implementation component implements \code{ajax_get} to capture a 
continuation, reify it as a staged function using \code{fun} and store it in an \code{AjaxGet} IR node. 
\begin{lstlisting}
trait AjaxExp extends JSExp with Ajax {
  case class AjaxGet(request: Rep[Request],
    success: Rep[Response => Unit]) extends Def[Unit]
  def ajax_get(request: Rep[Request]): Rep[Response] @suspendable = 
    shift { k =>
      reflectEffect(AjaxGet(request, lambda(k)))
    }
}
\end{lstlisting}

During code generation, we emit code to attach the captured continuation as a 
callback function in the \code{success} field of the request object:
\begin{lstlisting}
trait GenAjax extends JSGenBase {
  val IR: AjaxExp
  import IR._
  override def emitNode(sym: Sym[Any], rhs: Def[Any])(
    implicit stream: PrintWriter) = rhs match {
      case AjaxGet(req, succ) => 
        stream.println(quote(req) + ".success = " + quote(succ)) 
        emitValDef(sym, "jQuery.ajax(" + quote(req) + ")")
    case _ => super.emitNode(sym, rhs)
  }
}
\end{lstlisting}

It is interesting to note that, since the request already has the right structure for the \code{jQuery.ajax} function, 
we can simply pass it to the framework-provided \code{quote} method, which knows how to generate JavaScript 
representations of any \code{JSLiteral}.

The Ajax component completes the functionality required to run the Twitter example with one caveat:
The outer loop in listing \ref{code:twitter_example} uses \code{parSuspendable} to traverse arrays
 instead of the \code{suspendable} traversal variant we have defined in listing \ref{code:suspendable_foreach}. 

In fact, if we change the code to use \code{suspendable} instead of \code{parSuspendable} and run the 
generated JavaScript program, we will get following output printed to the JavaScript console:
\begin{lstlisting}
fetching gkossakowski
finished fetching gkossakowski
fetched [...]
fetched [...]
fetching odersky
finished fetching odersky
fetched [...]
fetched [...]
fetching adriaanm
finished fetching adriaanm
fetched [...]
fetched [...]
done
\end{lstlisting}

Notice that all Ajax requests were done sequentially. Specifically,
there was just one Ajax request active at a given time; when the
callback to process one request is called, it would resume the
continuation to start another request, and so on. In many cases this
is exactly the desired behavior, however, we will most likely
want to perform our Ajax request in parallel.

## CPS for Parallelism
The goal of this section is to implement a parallel variant of the
\code{foreach} method from listing~\ref{code:suspendable_foreach}. We will start 
with defining a few primitives like futures and dataflow cells. 
We start with cells, which we
decide to define in JavaScript, as another example of
integrating external libraries with our DSL:

\begin{lstlisting}[language=JavaScript,caption=JavaScript-based implementation of a non-blocking Cell]
function Cell() {
  this.value = undefined
  this.isDefined = false
  this.queue = []
  this.get = function (k) {
    if (this.isDefined) {
      k(this.value)
    } else {
      this.queue.push(k)
    }
  }
  this.set = function (v) {
    if (this.isDefined) {
      throw "can't set value twice"
    } else {
      this.value = v
      this.isDefined = true
      this.queue.forEach(function (f) { 
        f(v) //non-trivial spawn could be used here
      })
    }
  }
}
\end{lstlisting}

A cell object allows us to track dependencies between values. Whenever the \code{get} method is 
called and the value is not in the cell yet, the continuation will be added to a queue so it 
can be suspended until the value arrives. The \code{set} method takes care of resuming queued 
continuations. We expose \code{Cell} as external library using our typed API mechanism 
and we use it for implementing an abstraction for futures.

\begin{lstlisting}
def createCell(): Rep[Cell[A]]
trait Cell[A]
trait CellOps[A] {
  def get(k: Rep[A => Unit]): Rep[Unit]
  def set(v: Rep[A]): Rep[Unit]
}
implicit def repToCellOps(x: Rep[Cell[A]]): CellOps[A] =
  repProxy[Cell[A],CellOps[A]](x)

def spawn(body: => Rep[Unit] @suspendable): Rep[Unit] = {
  reset(body) //non-trivial implementation uses
              //trampolining to prevent stack overflows
}
def future(body: => Rep[A] @suspendable) = {
  val cell = createCell[A]()
  spawn { cell.set(body) }
  cell
}
\end{lstlisting}

The last bit of general functionality we need is \code{RichCellOps} that ties \code{Cell}s 
and continuations together inside of our DSL.

\begin{lstlisting}
class RichCellOps(cell: Rep[Cell[A]]) {
  def apply() = shift { k: (Rep[A] => Rep[Unit]) =>
    cell.get(lambda(k))
  }
}
implicit def richCellOps(x: Rep[Cell[A]]): RichCell[A] =
  new RichCellOps(x)
\end{lstlisting}

It is worth noting that \code{RichCellOps} is not reified so it will be dropped at 
staging time and its method will get inlined whenever used. Also, it contains CPS-specific 
code that allows us to capture the continuation. The \code{fun} function reifies the captured continuation.

We are ready to present the parallel version of \code{foreach} defined in listing \ref{code:suspendable_foreach}.

\begin{lstlisting}
def foreach(yld: Rep[A] => Rep[Unit] @suspendable):
  Rep[Unit] @suspendable = {
    val futures = xs.map(x => future(yld(x)))
    futures.suspendable.foreach(_.apply())
  }
\end{lstlisting}

We instantiate each future separately so they can be executed in parallel. 
As a second step we make sure that all futures are evaluated before we leave the \code{foreach} 
method by forcing evaluation of each future and ``waiting'' for its completion. 
Thanks to CPS transformations, all of that will be implemented in a non-blocking style.

The only difference between the parallel and serial versions of the 
Twitter example~\ref{code:twitter_example} is the use of \code{parSuspendable} 
instead of \code{suspendable} so the parallel implementation of the \code{foreach} 
method is used. The rest of the code stays the same. It is easy to switch 
between both versions, and users are free to make their choice according 
to their needs and performance requirements.






# Guarantees by Construction

Making staged functions explicit through the use of \code{lambda}
(as described in Section~\ref{sec:220functions})
enables tight control over how functions are structured and composed. 
For example, functions with multiple 
parameters can be specialized for a subset of the parameters. 
Consider the following implementation of Ackermann's function:
\begin{slisting}
  def ack(m: Int): Rep[Int=>Int] = lambda { n =>
    if (m == 0) n+1 else
    if (n == 0) ack(m-1)(1) else
    ack(m-1)(ack(m)(n-1))
  }
\end{slisting}
Calling \code{ack(m)(n)} will produce a set of mutually recursive
functions, each specialized to a particular value of \code{m} 
(example \code{m}=2):

\begin{slisting}
def ack_2(n: Int) = if (n == 0) ack_1(1) else ack_1(ack_2(n-1))
def ack_1(n: Int) = if (n == 0) ack_0(1) else ack_0(ack_1(n-1))
def ack_0(n: Int) = n+1
acc_2(n)
\end{slisting}

In essence, this pattern implements what is known as ``polyvariant specialization'' 
in the partial evaluation community. But unlike automatic partial evaluation,
which might or might not be able to discover the \emph{right} specialization,
the use of staging provides a strong guarantee about the structure of the
generated code.

Other strong guarantees can be achieved by restricting the interface
of function definitions. Being of type \code{Rep[A=>B]}, the result
of \code{lambda} is a first-class value in the generated code that
can be stored or passed around in arbitrary ways.
However we might want to avoid higher-order control flow in generated
code for efficiency reasons, or to simplify subsequent analysis passes.
In this case, we can define a new function constructor \code{fundef} as 
follows:
\begin{slisting}
  def fundef[A,B](f: Rep[A] => Rep[B]): Rep[A] => Rep[B] = 
    (x: Rep[A]) => lambda(f).apply(x)
\end{slisting}
Using \code{fundef} instead of \code{lambda} produces a restricted
function that can only be applied but not passed around in
the generated code (type \code{Rep[A]=>Rep[B]}). At the same time, a 
result of \code{fundef} is still a first class value in the code
\emph{generator}.
If we do not expose \code{lambda} and \code{apply} at all to client
code, we obtain a guarantee that each function call site unambiguously 
identifies the function definition being called and no closure
objects will need to be created at runtime.






# (Chapter 2) Data Abstraction
\label{chap:455data-abstraction}

High level data structures are a cornerstone of modern programming
and at the same time stand in the way of compiler optimizations.

As a running example we consider implementing a complex number datatype in a DSL.
The usual approach of languages executed on the JVM is to represent every non-primitive value as 
a heap-allocated reference object. The space overhead, reference indirection as well as the 
allocation and garbage collection cost are a burden for performance critical code.
Thus, we want to be sure that our complex numbers can be manipulated as efficiently
as two individual doubles. In the following, we explore different ways to achieve that.







# Static Data Structures
\label{subsubsec:complexA}

The simplest approach is to implement complex numbers as a fully static data type, that
only exists at staging time. Only the actual \code{Double}s that constitute the
real and imaginary components of a complex number are dynamic values:

\begin{listing}
case class Complex(re: Rep[Double], im: Rep[Double])
def infix_+(a: Complex, b: Complex) = 
  Complex(a.re + b.re, a.im + b.im)
def infix_*(a: Complex, b: Complex) = 
  Complex(a.re*b.re - a.im*b.im, a.re*b.im + a.im*b.re)
\end{listing}

Given two complex numbers \code{c1,c2}, an expression like
\begin{listing}
c1 + 5 * c2  // assume implicit conversion from Int to Complex
\end{listing}
will generate code that is free of \code{Complex} objects and only contains arithmetic 
on \code{Double}s.

However the ways we can use \code{Complex} objects are rather limited. Since they
only exists at staging time we cannot, for example, express dependencies on dynamic
conditions:

\begin{listing}
val test: Rep[Boolean] = ...
val c3 = if (test) c1 else c2 // type error: c1/c2 not a Rep type
\end{listing}

It is worthwhile to point out that nonetheless, purely static data structures
have important use cases. To give an example, the fast fourier transform (FFT) \cite{cooley1965algorithm}
is branch-free for a fixed input size. The definition of complex numbers
given above can be used to implement a staged FFT that computes the well-known
butterfly shaped computation circuits from the textbook Cooley-Tukey recurrences 
(see Section~\ref{sec:Afft}).

To make complex numbers work across conditionals, 
we have have to split the control flow explicitly (another option would be
using mutable variables). 
There are multiple ways to achieve this splitting. 
We can either duplicate the test and create a single
result object:
  
\begin{listing}
val test: Rep[Boolean] = ...
val c3 = Complex(if (test) c1.re else c2.re, if (test) c1.im else c2.im)
\end{listing}

Alternatively we can use a single test and duplicate the rest of the program:

\begin{listing}
val test: Rep[Boolean] = ...
if (test) {
  val c3 = c1
  // rest of program
} else {
  val c3 = c2
  // rest of program
}
\end{listing}

While it is awkward to apply this transformation manually, we can use continuations (much like
for the \code{bam} operator in Section~\ref{sec:450bam}) to generate two specialized computation paths:

\begin{listing}
def split[A](c: Rep[Boolean]) = shift { k: (Boolean => A) =>
  if (c) k(true) else k(false) // "The Trick"
}
val test: Rep[Boolean] = ... 
val c3 = if (split(test)) c1 else c2
\end{listing}

The generated code will be identical to the manually duplicated, specialized version above. 








# Dynamic Data Structures with Partial Evaluation
\label{sec:455struct}

We observe that we can increase the amount of statically possible computation (in a sense,
applying binding-time improvements) for dynamic values with domain-specific rewritings:
\begin{listing}
val s: Int = ...            // static  
val d: Rep[Int] = ...       // dynamic

val x1 = s + s + d          // left assoc: s + s evaluated statically, 
                            // one dynamic addition
val x2 = s + (d + s)        // naively: two dynamic additions, 
                            // using pattern rewrite: only one
\end{listing}

In computing \code{x1}, there is only one dynamic addition because the left associativity of
the plus operator implies that the two static values will be added together at staging time.
Computing \code{x2} will incur two dynamic additions, because both additions have at least
one dynamic summand. However we can add rewriting rules that first replace \code{d+c}
(\code{c} denoting a dynamic value that is know to be a static constant, i.e.\ an IR
node of type \code{Const}) with \code{c+d} and then \code{c+(c+d)} with \code{(c+c)+d}.
The computation \code{c+c} can again be performed statically.

We have seen in Section~\ref{sec:361struct} how we can define a generic
framework for data structures that follows a similar spirit.
The interface for field accesses \code{field} pattern matches
on its argument and, if that is a \code{Struct} creation, looks up the desired value
from the embedded hash map.

An implementation of complex numbers in terms of \code{Struct} could look like this:
\begin{listing}
trait ComplexOps extends ComplexBase with ArithOps {
  def infix_+(x: Rep[Complex], y: Rep[Complex]): Rep[Complex] = 
    Complex(x.re + y.re, x.im + y.im)
  def infix_*(x: Rep[Complex], y: Rep[Complex]): Rep[Complex] = 
    Complex(a.re*b.re - ...)
}
trait ComplexBase extends Base {
  class Complex
  def Complex(re: Rep[Double], im: Rep[Double]): Rep[Complex]
  def infix_re(c: Rep[Complex]): Rep[Double]
  def infix_im(c: Rep[Complex]): Rep[Double]
}
trait ComplexStructExp extends ComplexBase with StructExp {
  def Complex(re: Rep[Double], im: Rep[Double]) =
    struct[Complex](classTag("Complex"), Map("re"->re, "im"->im))
  def infix_re(c: Rep[Complex]): Rep[Double] = field[Double](c, "re")
  def infix_im(c: Rep[Complex]): Rep[Double] = field[Double](c, "im")
}
\end{listing}

Note how complex arithmetic is defined completely within the interface trait \code{ComplexOps},
which inherits double arithmetic from \code{ArithOps}. Access to the components via
\code{re} and \code{im} is implemented using \code{struct}.

Using virtualized record types (see Section~\ref{sec:211struct}) that map to \code{struct} internally, 
we can express the type definition more conveniently as
\begin{listing}
class Complex extends Struct { val re: Double, val im: Double }
\end{listing}
and remove the need for methods \code{infix_re} and \code{infix_im}. The
Scala-Virtualized compiler will automatically provide staged field accesses like
\code{c.re} and \code{c.im}. It is still useful to add a simplified constructor
method
\begin{listing}
def Complex(r: Rep[Double], i: Rep[Double]) = 
  new Complex { val re = re; val im = im }
\end{listing}
to enable using \code{Complex(re,im)} instead of the \code{new Complex}
syntax.

In contrast to the completely static implementation of complex numbers presented in 
Section~\ref{subsubsec:complexA} above, complex numbers are a fully dynamic
DSL type now. The previous restrictions are gone and we can write the following
code without compiler error:
\begin{listing}
val c3 = if (test) c1 else c2
println(c3.re)
\end{listing}

The conditional \code{ifThenElse} is overridden to split itself for each field
of a struct. Internally the above will be represented as:
\begin{listing}
val c3re = if (test) c1re else c2re
val c3im = if (test) c1im else c2im   // removed by dce
val c3 = Complex(c3re, c3im)          // removed by dce
println(c3re)
\end{listing}
The computation of the imaginary component as well as the struct creation for
the result of the conditional are never used and thus they will be removed
by dead code elimination.



# Generic Programming with Type Classes

The type class pattern \cite{DBLP:conf/popl/WadlerB89}, which decouples
data objects from generic dispatch, fits naturally with a staged
programming model as type class instances can be implemented as
static objects.

%DSL operations that can be statically dispatched are more amenable to the
%optimizations discussed in the previous sections.  Useful methods for
%abstracting over data representations include OO-style inheritance and type
%classes \cite{DBLP:conf/popl/WadlerB89}.

%\subsection{Type Classes}
%We discuss type classes first. 
Extending the Vector example, we might want to be able to add vectors that contain 
numeric values. We can use a lifted variant of the \code{Numeric} type class from the Scala library
\begin{listing}
class Numeric[T] {
  def num_plus(a: Rep[T], b: Rep[T]): Rep[T]
}
\end{listing}
and provide a type class instance for complex numbers:
\begin{listing}
implicit def complexIsNumeric = new Numeric[Complex] { 
  def num_plus(a: Rep[Complex], b: Rep[Complex]) = a + b
}
\end{listing}

Generic addition on Vectors is straightforward, assuming we
have a method \code{zipWith} already defined:
\begin{listing}
def infix_+[T:Numeric](a: Rep[Vector[T]], b: Rep[Vector[T]]) = {
  val m = implicitly[Numeric[T]] // access type class instance
  a.zipWith(b)((u,v) => m.num_plus(u,v))
}
\end{listing}
With that definition at hand we can add a type class instance
for numeric vectors:
\begin{listing}
implicit def vecIsNumeric[T:Numeric] = new Numeric[Vector[T]] {
  def num_plus(a: Rep[Vector[T]], b: Rep[Vector[T]]) = a + b
\end{listing}
which allows us to pass, say, a \code{Rep[Vector[Complex]]} to any function
that works over generic types \code{T:Numeric} including vector addition
itself. The same holds for nested vectors of type \code{Rep[Vector[Vector[Complex]]]}.
Usually, type classes are implemented by passing an implicit dictionary, the
type class instance, to generic functions. Here, type classes are a purely
stage-time concept. All generic code is specialized to the concrete types and no
type class instances exist (and hence no virtual dispatch occurs) when the
DSL program is run. 

An interesting extension of the type class model is the notion of polytypic 
staging, studied on top of LMS \cite{slesarenko12polytypic}.





# Unions and Inheritance
\label{sec:455inherit}

The struct abstraction from Section~\ref{sec:361struct} can be extended to sum types and
inheritance using a tagged union approach \cite{nystrom11firepile,DBLP:conf/fsttcs/JonesLKC08}.
We add a \code{clzz} field to each struct that refers to
an expression that defines the object's class. Being a regular struct field,
it is subject to all common optimizations.
We extend the complex number example with two subclasses:
\begin{listing}
abstract class Complex
class Cartesian extends Complex with Struct { val re: Double, val im: Double }
class Polar extends Complex with Struct { val r: Double, val phi: Double }
\end{listing}
Splitting transforms work as before: e.g.\ conditional expressions are forwarded to the 
fields of the struct. But now the result struct will contain the union of the fields found 
in the two branches, inserting null values as appropriate. A conditional is created for
the \code{clzz} field only if the exact class is not known at staging time.
As an example, the expression
\begin{listing}
val a = Cartesian(1.0, 2.0); val b = Polar(3.0, 4.0)
if (x > 0) a else b
\end{listing}
produces this generated code:
\begin{listing}
val (re, im, r, phi, clzz) = 
  if (x > 0) (1.0, 2.0, null, null, classOf[Cartesian]) 
  else (null, null, 3.0, 4.0, classOf[Polar])
struct("re"->re, "im"->im, "r"->r, "phi"->phi, "clzz"->clzz)
\end{listing}

The \code{clzz} fields allows virtual dispatch via type tests and type casting, 
e.g.\ to convert any complex number to its cartesian representation:
\begin{listing}
def infix_toCartesian(c: Rep[Complex]): Rep[Cartesian] =
  if (c.isInstanceOf[Cartesian]) c.asInstanceOf[Cartesian]
  else { val p = c.asInstanceOf[Polar]
    Cartesian(p.r * cos(p.phi), p.r * sin(p.phi)) }
\end{listing}
Appropriate rewrites ensure that if the argument is known to be 
a Cartesian, the conversion is a no-op. The type test that
inspects the clzz field is only generated if the type cannot
be determined statically. If the clzz field is never used
it will be removed by DCE.




# Struct of Array and Other Data Format Conversions
\label{sec:455structUse}

There is another particularly interesting use case for the splitting of data structures: 
Let us assume we want to create a vector of
complex numbers. Just as with the if-then-else example above, we can override the vector
constructors such that a \code{Vector[Cartesian]} is represented as a struct that contains
two separate arrays, one for the real and one for the imaginary components. A more
general \code{Vector[Complex]} that contains both polar and cartesian values will
be represented as five arrays, one for each possible data field plus the \code{clzz} tag
for each value.
In fact, we have expressed our conceptual array of structs as a struct of arrays (AoS to SoA transform, 
see Section~\ref{sec:360soa}).
This data layout is beneficial in many cases. Consider for example calculating complex
conjugates (i.e.\ swapping the sign of the imaginary components) over a vector of complex numbers.
\begin{listing}
def conj(c: Rep[Complex]) = if (c.isCartesian) {
  val c2 = c.toCartesian; Cartesian(c2.re, -c2.im)
} else {
  val c2 = c.toPolar; Polar(c2.r, -c2.phi)
}
\end{listing}
To make the test case more interesting we perform the calculation only in one branch
of a conditional.
\begin{listing}
val vector1 = ... // only Cartesian values
if (test) {
  vector1.map(conj)
} else {
  vector1
}
\end{listing}
All the real parts remain unchanged so the array holding them need not be touched at all.
Only the imaginary parts have to be transformed, cutting the total required memory bandwidth
in half. Uniform array operations like this are also a much better fit for SIMD execution.
The generated intermediate code is:
\begin{listing}
val vector1re = ...
val vector1im = ...
val vector1clzz = ... // array holding classOf[Cartesian] values
val vector2im = if (test) { 
  Array.fill(vector1size) { i => -vector1im(i) }
} else {
  vector1im
}
struct(ArraySoaTag(Complex,vector1size), 
  Map("re"->vector1re, "im"->vector2im, "clzz"->vector1clzz))
\end{listing}
Note how the conditionals for the \code{re} and \code{clzz} fields have been eliminated since
the fields do not change (the initial array contained cartesian numbers only). If the
struct expression will not be referenced in the final code, dead code elimination removes the
\code{clzz} array.

In the presence of conditionals that produce array elements of different types, it can be
beneficial to use a sparse representation for arrays that make up the result struct-of-array,
similar to the approach in Data Parallel Haskell~\cite{DBLP:conf/fsttcs/JonesLKC08}. Of course 
no choice of layout is optimal in all cases, so the usual sparse versus dense tradeoffs 
regarding memory use and access time apply here as well.

We conclude this section by taking note that we can actually guarantee that no dynamic \code{Complex} or 
\code{Struct} object is ever created just by not implementing code generation logic for \code{Struct}
and \code{Field} IR nodes and signaling an error instead. This is a good example of a performance-oriented
DSL compiler rejecting a program as ill-formed because it cannot be executed in the desired,
efficient way.


# Loop Fusion and Deforestation
\label{subsec:fusion}

Building complex bulk operations out of simple ones often leads to inefficient generated code.  For example consider the simple vector code

\begin{listing}
val a: Rep[Double] = ...
val x: Rep[Vector[Double]] = ...
val y: Rep[Vector[Double]] = ...

a*x+y
\end{listing} 

Assuming we have provided the straightforward loop-based implementations of scalar-times-vector and vector-plus-vector, the resulting code for
this program will perform two loops and allocate a temporary vector to store \code{a*x}.  A more efficient implementation will only use
a single loop (and no temporary vector allocations) to compute \code{a*x(i)+y(i)}.

In addition to operations that are directly dependent as illustrated above, side-by-side operations also appear frequently.
As an example, consider a DSL that provides mean and variance methods.

\begin{listing}
def mean(x: Rep[Vector[Double]]) = 
    sum(x.length) { i => x(i) } / x.length
def variance(x: Rep[Vector[Double]]) =
    sum(x.length) { i => square(x(i)) } / x.length - square(mean(x))

val data = ...

val m = mean(data)
val v = variance(data)
\end{listing}


The DSL developer wishes to provide these two functions separately, but many applications will compute both the mean and variance of a 
dataset together.  In this case we again want to perform all the work with a single pass over \code{data}.  In both of the above example situations,
fusing the operations into a single loop greatly improves cache behavior and reduces the total number of loads and stores required. 
It also creates coarser-grained functions out of fine-grained ones, which will likely improve parallel scalability.  

Our framework handles all situations like these two examples uniformly and for all DSLs.  Any non-effectful loop IR node 
is eligible for fusing with other loops.  In order to handle all the interesting loop fusion cases, the fusing algorithm uses
simple and general criteria: It fuses all pairs of loops where either both loops have the exact same size or one loop iterates over
a data structure the other loop creates, as long as fusing will not create any cyclic dependencies. The exact rules are
presented in Section~\ref{sec:360fusionComp}.
When it finds two eligible loops the algorithm creates a new loop with a body composed of both of the original bodies.  
Merging loop bodies includes array contraction, i.e.\ the fusing transform modifies dependencies so that all results produced 
within a loop iteration are consumed directly rather than by reading an output data structure.
%In the case of groupBy operations, contraction also works across hash tables.
Whenever this renders an output data structure unnecessary (it does not escape the fused loop) it is removed automatically by dead code elimination.  
All DeliteOpLoops are parallel loops, which allows the fused loops to be parallelized in the same manner as the original loops.     

The general heuristic is to apply fusion greedily wherever possible. For dominantly imperative code more 
refined heuristics might be needed \cite{DBLP:conf/sc/BelterJKS09}. 
However, our loop abstractions are dominantly functional and
many loops create new data structures. Removing intermediate data buffers,
which are potentially large and many of which are used only once is clearly a win, 
so fusing seems to be beneficial in almost all cases.

Our fusion mechanism is similar but not identical to deforestation \cite{DBLP:journals/tcs/Wadler90} and related 
approaches \cite{DBLP:conf/icfp/CouttsLS07}. 
Many of these approaches only consider expressions that are directly dependent (vertical fusion), whereas we
are able to handle both dependent and side-by-side expressions (horizontal fusion) with one general mechanism.  This is critical for situations such as the
mean and variance example, where the only other efficient alternative would be to explicitly create a composite function that returns
both results simultaneously.  This solution additionally requires the application writer to always remember to use the composite version 
when appropriate.  It is generally difficult to predict all likely operation compositions as well as onerous to provide efficient, specialized 
implementations of them.  Therefore fusion is key for efficient compositionality in both applications and DSL libraries.







# Extending the Framework

A framework for building DSLs must be easily extensible in order for the DSL developer to exploit domain
knowledge starting from a general-purpose IR design.  Consider a simple DSL for linear algebra with a 
Vector type.  Now we want to add norm and dist functions to the DSL. The first possible implementation
is to simply implement the functions as library methods.

\begin{listing}
def norm[T:Numeric](v: Rep[Vector[T]]) = {
  sqrt(v.map(j => j*j).sum)
}
def dist[T:Numeric](v1: Rep[Vector[T]], v2: Rep[Vector[T]]) = {
  norm(v1 - v2)
}
\end{listing}

Whenever the dist method is called the implementation will be added to the application IR in terms of vector subtraction,
vector map, vector sum, etc. (assuming each of these methods is built-in to the language rather than also being provided
as a library method).  This version is very straightforward to write but the knowledge that the application wishes to 
find the distance between two vectors is lost.

By defining norm explicitly in the IR implementation trait (where Rep[T] = Exp[T]) we gain ability to perform pattern matching
on the IR nodes that compose the arguments.

\begin{listing}
override def norm[T:Numeric](v: Exp[Vector[T]]) = v match {
  case Def(ScalarTimesVector(s,u)) => s * norm(u)
  case Def(ZeroVector(n)) => 0
  case _ => super.norm(v)
}
\end{listing}

In this example there are now three possible implementations of \code{norm}.  The first case factors scalar-vector multiplications out 
of \code{norm} operations, the second short circuits the norm of a ZeroVector to be simply the constant 0, and the third falls back 
on the default implementation defined above.  With this method we can have a different implementation of \code{norm} for each 
\emph{occurrence} in the application.

An even more powerful alternative is to implement \code{norm} and \code{dist} as custom IR nodes.  This enables the DSL to include these nodes
when optimizing the application via pattern matching and IR rewrites as illustrated above.  For example, we can add a rewrite
rule for calculating the norm of a unit vector: if  $v_1 = \frac{v}{\|v\|}$ then $\left\|v_1\right\|=1$.
In order to implement this optimization we need to add cases both for the new \code{norm} operation as well as to the
existing scalar-times-vector operation to detect the first half of the pattern.

\begin{listing}
case class VectorNorm[T](v: Exp[Vector[T]]) extends Def[T]
case class UnitVector[T](v: Exp[Vector[T]]) extends Def[Vector[T]]

override def scalar_times_vector[T:Numeric](s: Exp[T], v: Exp[Vector[T]]) = 
(s,v) match {
  case (Def(Divide(Const(1), Def(VectorNorm(v1)))), v2) 
    if v1 == v2 => UnitVector(v)
  case _ => super.scalar_times_vector(s,v)
}
override def norm[T:Numeric](v: Exp[Vector[T]]) = v match {
  case Def(UnitVector(v1)) => 1
  case _ => super.norm(v)
}
\end{listing}


In this example the scalar-times-vector optimization requires vector-norm to exist as an IR node to detect\footnote{The \code{==}
operator tests structural equality of IR nodes.
The test is cheap because we only need to look at symbols, one level deep. 
Value numbering/CSE ensures that intensionally equal IR nodes get assigned the same symbol.}  and short-circuit 
the operation to simply create and mark unit vectors.  The vector-norm optimization then detects unit vectors and short circuits the norm operation
to simply add the constant 1 to the IR.  In every other case it falls back on the default implementation, which is to create a new \code{VectorNorm} IR node.

The default constructor for \code{VectorNorm} uses delayed rewriting (see Section~\ref{sec:330delayed}) 
to specify the desired lowering of the IR node:
\begin{listing}
def norm[T:Numeric](v: Rep[Vector[T]]) = VectorNorm(v) atPhase(lowering) {
  sqrt(v.map(j => j*j).sum)
}
\end{listing}
The right hand side of this translation is exactly the initial norm implementation we started with.


# Lowering Transforms

In our running example, we would like to treat linear algebra 
operations symbolically first,
with individual IR nodes like \code{VectorZeros} and \code{VectorPlus}.
In Figure~\ref{fig:vectorImpl}, the smart constructor \code{vec_plus} implements a 
rewrite that simplifies \code{v+zero} to \code{v}. CSE, DCE, etc. will all
be performed on these high level nodes. 

After all those optimizations are applied, we want to 
transform our operations to the low-level
array implementation from Figure~\ref{fig:stagedArrays}
in a separate lowering pass. Trait \code{LowerVectors} in Figure~\ref{fig:vectorImpl}
implements this transformation by delegating back
to user-space code, namely method \code{vec_plus_ll} in
trait \code{VectorsLowLevel}.

\begin{listing}
// Vector interface
trait Vectors extends Base { 
  // elided implicit enrichment boilerplate: 
  //   Vector.zeros(n) = vec_zeros(n), v1 + v2 = vec_plus(a,b)
  def vec_zeros[T:Numeric](n: Rep[Int]): Rep[Vector[T]]
  def vec_plus[T:Numeric](a: Rep[Vector[T]], b: Rep[Vector[T]]): Rep[Vector[T]]
}
// low level translation target
trait VectorsLowLevel extends Vectors {
  def vec_zeros_ll[T:Numeric](n: Rep[Int]): Rep[Vector[T]] =
    Vector.fromArray(Array.fill(n) { i => zero[T] })
  def vec_plus_ll[T:Numeric](a: Rep[Vector[T]], b: Rep[Vector[T]]) =
    Vector.fromArray(a.data.zipWith(b.data)(_ + _))
}
// IR level implementation
trait VectorsExp extends BaseExp with Vectors {
  // IR node definitions and constructors
  case class VectorZeros(n: Exp[Int]) extends Def[Vector[T]]
  case class VectorPlus(a: Exp[Vector[T]],b: Exp[Vector[T]]) extends Def[Vector[T]]
  def vec_zeros[T:Numeric](n: Rep[Int]): Rep[Vector[T]] = VectorZeros(n)
  def vec_plus[T:Numeric](a: Rep[Vector[T]], b: Rep[Vector[T]]) = VectorPlus(a,b)
  // mirror: transformation default case
  def mirror[T](d: Def[T])(t: Transformer) = d match {
    case VectorZeros(n) => Vector.zeros(t.transformExp(n))
    case VectorPlus(a,b) => t.transformExp(a) + t.transformExp(b)
    case _ => super.mirror(d)
  }
}
// optimizing rewrites (can be specified separately)
trait VectorsExpOpt extends VectorsExp {
  override def vec_plus[T:Numeric](a:Rep[Vector[T]],b:Rep[Vector[T]])=(a,b)match{
    case (a, Def(VectorZeros(n))) => a
    case (Def(VectorZeros(n)), b) => b
    case _ => super.vec_plus(a,b)
  }
}
// transformer: IR -> low level impl
trait LowerVectors extends ForwardTransformer {
  val IR: VectorsExp with VectorsLowLevel; import IR._
  def transformDef[T](d: Def[T]): Exp[T] = d match {
    case VectorZeros(n) => vec_zeros_ll(transformExp(n))
    case VectorPlus(a,b) => vec_plus_ll(transformExp(a), transformExp(b))
    case _ => super.transformDef(d)
  }
}
\end{listing}



The result of the transformation is a staged program
fragment just like in Figure~\ref{fig:stagedArrays}.

This setup greatly simplifies the definition of the 
lowering transform, which would otherwise need to assemble
the \code{fill} or \code{zipWith} code using low level IR manipulations.
Instead we benefit directly from the staged \code{zipWith} definition
from Figure~\ref{fig:stagedArrays}. Also, further rewrites
will take place automatically. Essentially all simplifications
are performed eagerly, after each transform phase.
Thus we guarantee that CSE, DCE, etc. have been applied on
high-level operations before they are translated into
lower-level equivalents, on which optimizations would
be much harder to apply.
To give a quick example, the initial program
\begin{listing}
val v1 = ...
val v2 = Vector.zeros(n)
val v3 = v1 + v2
v1 + v3
\end{listing}
will become
\begin{listing}
val v1 = ...
Vector.fromArray(v1.data.zipWith(v1.data)(_ + _))
\end{listing}
after lowering (modulo unfolding of staged zipWith).




# (Chapter 3) Case Studies
\label{chap:460fusionUse}

This chapter presents case studies for Delite apps (using the OptiML and OptiQL DSLs) as
well as classical staging use cases (FFT specialization and regular expression matching).
The Delite apps are real-world examples for the loop fusion algorithm from 
Section~\ref{sec:360fusionComp} and the struct conversion from Section~\ref{sec:361struct}.


# OptiML Stream Example

\credits{Design and presentation by Arvind Sujeeth, fusion implementation by the author}
OptiML is an embedded DSL for machine learning (ML) developed on
top of LMS and Delite.  It provides a MATLAB-like programming model with
ML-specific abstractions. OptiML is a prototypical example of how the techniques
described in this thesis can be used to construct productive, high performance
DSLs targeted at heterogeneous parallel machines. 

\label{sec:optiml}
%\subsection{Streaming Matrix}
## Downsampling in Bioinformatics

In this example, we will demonstrate how the optimization and code generation
techniques discussed in previous sections come together to produce efficient
code in real applications. SPADE is a bioinformatics application
that builds tree representations of large, high-dimensional flow cytometry datasets.
Consider the following small but compute-intensive snippet from SPADE (C++):

\begin{listing}
std::fill(densities, densities+obs, 0);
#pragma omp parallel for shared(densities)  
for (size_t i=0; i<obs; i++) {
  if (densities[i] > 0)
    continue;
  std::vector<size_t> apprxs;  // Keep track on observations we can approximate
  Data_t *point = &data[i*dim];
  Count_t c = 0;

  for (size_t j=0; j<obs; j++) {
    Dist_t d = distance(point, &data[j*dim], dim);
    if (d < apprx_width) {
      apprxs.push_back(j);
      c++;
    } else if (d < kernel_width) c++;
  }
  // Potential race condition on other density entries, use atomic
  // update to be safe
  for (size_t j=0; j<apprxs.size(); j++)
    __sync_bool_compare_and_swap(densities+apprxs[j],0,c);
  densities[i] = c;
}
\end{listing}

This snippet represents a downsampling step that computes a set of values,
densities, that represents the number of samples within a bounded distance
(kernel\_width) from the current sample. Furthermore, any distances within
apprx\_width of the current sample are considered to be equivalent, and the
density for the approximate group is updated as a whole. Finally, the loop is
run in parallel using OpenMP. This snippet represents hand-optimized, high
performance, low-level code. It took a systems and C++ expert to port the
original MATLAB code (written by a bioinformatics researcher) to this
particular implementation. In contrast, consider the equivalent snippet of
code, but written in OptiML:

\begin{listing}
val distances = Stream[Double](data.numRows, data.numRows) { 
  (i,j) => dist(data(i),data(j)) 
}
val densities = Vector[Int](data.numRows, true)

for (row <- distances.rows) {
  if(densities(row.index) == 0) {
    val neighbors = row find { _ < apprxWidth }
    densities(neighbors) = row count { _ < kernelWidth }
  }
}
densities
\end{listing}

This snippet is expressive and easy to write. It is not obviously high
performance. However, because we have abstracted away implementation detail,
and built-in high-level semantic knowledge into the OptiML compiler, we can
generate code that is essentially the same as the hand-tuned C++ snippet. Let's
consider the OptiML code step by step.

Line 1 instantiates a Stream, which is an OptiML data structure that is
buffered; it holds only a chunk of the backing data in memory at a time, and
evaluates operations one chunk at a time. Stream only supports iterator-style
access and bulk operations. These semantics are necessary to be able to express
the original problem in a more natural way without adding overwhelming
performance overhead. The foreach implementation for stream.rows is:

\begin{listing}
def stream_foreachrow[A:Manifest](x: Exp[Stream[A]], 
              block: Exp[StreamRow[A]] => Exp[Unit]) = {
  var i = 0
  while (i < numChunks) {
    val rowsToProcess = stream_rowsin(x, i)
    val in = (0::rowsToProcess)
    val v = fresh[Int]

    // fuse parallel initialization and foreach function
    reflectEffect(StreamInitAndForeachRow(in, v, x, i, block))   // parallel
    i += 1
  }
}
\end{listing}

This method constructs the IR nodes for iterating over all of the chunks in the
Stream, initalizing each row, and evaluating the user-supplied foreach
anonymous function. We first obtain the number of rows in the current chunk by
calling a method on the Stream instance (\code{stream_rowsin}). We then call
the StreamInitAndForeachRow op, which is a DeliteOpForeach, over all of the
rows in the chunk.  OptiML unfolds the foreach function and the stream
initialization function while building the IR, inside StreamInitAndForeachRow.
The stream initialization function (\code{(i,j) => dist(data(i),data(j)})
constructs a StreamRow, which is the input to the foreach function. The
representation of the foreach function consists of an IfThenElse operation,
where the then branch contains the VectorFind, VectorCount, and
VectorBulkUpdate operations from lines 6-7 of the OptiML SPADE snippet.
VectorFind and VectorCount both extend DeliteOpLoop. Since they are both
DeliteOpLoops over the same range with no cyclic dependencies, they are fused
into a single DeliteOpLoop. This eliminates an entire pass (and the
corresponding additional memory accesses) over the row, which is a non-trivial
235,000 elements in one typical dataset.

Fusion helps to transform the generated code into the iterative structure of
the C++ code. One important difference remains: we only want to compute the
distance if it hasn't already been computed for a neighbor. In the streaming
version, this corresponds to only evaluating a row of the Stream if the
user-supplied if-condition is true. In other words, we need to optimize the
initialization function \emph{together with} the anonymous function supplied to
the foreach. LMS does this naturally since the foreach implementation and the
user code written in the DSL are all uniformly represented with the same IR.
When the foreach block is scheduled, the stream initialization function is
pushed inside the user conditional because the StreamRow result is not required
anywhere else. Furthermore, once the initialization function is pushed inside
the conditional, it is then fused with the existing DeliteOpLoop, eliminating
another pass. We can go even further and remove all dependencies on the
StreamRow instance by bypassing field accesses on the row, using the pattern
matching mechanism described earlier:

\begin{listing}
trait StreamOpsExpOpt extends StreamOpsExp {
  this: OptiMLExp with StreamImplOps =>

  override def stream_numrows[A:Manifest](x: Exp[Stream[A]]) = x match {
    case Def(Reflect(StreamObjectNew(numRows, numCols, 
                      chunkSize, func, isPure),_,_)) => numRows
    case _ => super.stream_numrows(x)
  }
  // similar overrides for other stream fields
}
trait VectorOpsExpOpt extends VectorOpsExp {
  this: OptiMLExp with VectorImplOps =>
  // accessing an element of a StreamRow directly accesses the underlying Stream
  override def vector_apply[A:Manifest](x: Exp[Vector[A]], n: Exp[Int]) = x match {
    case Def(StreamChunkRow(x, i, offset)) => stream_chunk_elem(x,i,n)
    case _ => super.vector_apply(x,n)
  }
}
\end{listing}

Now as the row is computed, the results of VectorFind and VectorCount are also
computed in a pipelined fashion. All accesses to the StreamRow are
short-circuited to their underlying data structure (the Stream), and no
StreamRow object is ever allocated in the generated code. The following listing
shows the final code generated by OptiML for the ``then'' branch (comments and
indentation added for clarity):


\begin{multicols}{2}
\begin{listing}
// ... initialization code omitted ...
// -- FOR EACH ELEMENT IN ROW --
while (x155 < x61) {  
  val x168 = x155 * x64
  var x185: Double = 0
  var x180 = 0

  // -- INIT STREAM VALUE (dist(i,j))
  while (x180 < x64) {  
    val x248 = x164 + x180
    val x249 = x55(x248)
    val x251 = x168 + x180
    val x252 = x55(x251)
    val x254 = x249 - x252
    val x255 = java.lang.Math.abs(x254)
    val x184 = x185 + x255
    x185 = x184
    x180 += 1
  } 
  val x186 = x185
  val x245 = x186 < 6.689027961000001
  val x246 = x186 < 22.296759870000002

  // -- VECTOR FIND --
  if (x245) x201.insert(x201.length, x155)

  // -- VECTOR COUNT --
  if (x246) {
    val x207 = x208 + 1
    x208 = x207
  }
  x155 += 1
} 

// -- VECTOR BULK UPDATE --
var forIdx = 0
while (forIdx < x201.size) { 
  val x210 = x201(forIdx)
  val x211 = x133(x210) = x208
  x211
  forIdx += 1
} 
\end{listing}
\end{multicols}

This code, though somewhat obscured by the compiler generated names, closely
resembles the hand-written C++ snippet shown earlier. It was generated from a
simple, 9 line description of the algorithm written in OptiML, making heavy use
of the building blocks we described in previous sections to produce the final
result.




A more thorough performance evaluation is given in Section~\ref{sec:600perfOptiML}.



# OptiQL Struct Of Arrays Example
\label{sec:460optiqlSoa}

OptiQL is a DSL for data querying of in-memory collections, inspired by LINQ~\cite{meijer06linq}.
We consider querying a data set with roughly 10 columns, similar to the table lineItems from
the TPCH benchmark. The example is slightly trimmed down from TPCH Query 1:
\begin{listing}
val res = lineItems Where(_.l_shipdate <= Date("1998-12-01")) 
GroupBy(l => l.l_returnflag) Select(g => new Result {
  val returnFlag = g.key
  val sumQty = g.Sum(_.l_quantity)
})
\end{listing}

A straightforward implementation is rather slow. There are multiple traversals
that compute intermediate data structures. There is also a nested \code{Sum} operation
inside the \code{Select} that follows the \code{groupBy}.

We can translate this code to a single while loop that does not construct any intermediate
data structures and furthermore ignores all columns that are not part of the result.
First, the complete computation is split into separate loops, one for each column. Unnecessary ones
are removed. Then the remaining component loops are reassembled via loop fusion. 
For the full TPCH Query 1, these transformations provide a speed up of 5.5x single
threaded and 8.7x with 8 threads over the baseline array-of-struct version (see Section~\ref{sec:600perf}).

We use two hash tables in slightly different ways: one to accumulate the keys (so it is really a 
hash set) and the other one to accumulate partial sums. 
Internally there is only one hash table that maps keys to positions. The partial sums
are just kept in an array that shares the same indices with the key array.

Below is the annotated generated code:

\begin{listing}
  val x11 = x10.column("l_returnflag")
  val x20 = x10.column("l_shipdate")
  val x52 = generated.scala.util.Date("1998-12-01")
  val x16 = x10.columns("l_quantity")
  val x283 = x264 + x265
  
  // hash table constituents, grouped for both x304,x306
  var x304x306_hash_to_pos: Array[Int] = alloc_htable // actual hash table
  var x304x306_hash_keys: Array[Char] = alloc_buffer  // holds keys
  var x304_hash_data: Array[Char] = alloc_buffer      // first column data
  var x306_hash_data: Array[Double] = alloc_buffer    // second column data
  val x306_zero = 0.0
  var x33 = 0
  while (x33 < x28) {  // begin fat loop x304,x306
    val x35 = x11(x33)
    val x44 = x20(x33)
    val x53 = x44 <= x52
    val x40 = x16(x33)

    // group conditionals
    if (x53) {
      val x35_hash_val = x35.hashCode
      val x304x306_hash_index_x35 = {
        // code to lookup x35_hash_val 
        // in hash table x304x306_hash_to_pos 
        // with key table x304x306_hash_keys
        // (growing hash table if necessary)
      }

      if (x304x306_hash_index_x35 >= x304x306_hash_keys.length) { // not found
        // grow x304x306_hash_keys and add key
        // grow x304_hash_data
        // grow x306_hash_data and set to x306_zero
      }
      x304_hash_data (x304x306_hash_index_x35) = x35

      val x264 = x306_hash_data (x304x306_hash_index_x35)
      val x265 = x40
      val x283 = x264 + x265
      x304_hash_data (x304x306_hash_index_x35) = x283
    }
  } // end fat loop x304,x306
  val x304 = x304_hash_data
  val x305 = x304x306_hash_to_pos.size
  val x306 = x306_hash_data

  val x307 = Map("returnFlag"->x304,"sumQty"->x306) //Array Result
  val x308 = Map("data"->x307,"size"->x305) //DataTable
\end{listing}





# Fast Fourier Transform Example
\label{sec:Afft}

We consider staging a fast fourier
transform (FFT) algorithm. % \citep{cooley1965algorithm}.
A staged FFT, implemented in MetaOCaml, has been presented
by Kiselyov et~al.\ \cite{DBLP:conf/emsoft/KiselyovST04}
Their work is a very good example for how staging allows to transform
a simple, unoptimized algorithm into an efficient program generator.
Achieving this in the context of MetaOCaml, however, required restructuring
the program into monadic style and adding a front-end layer for
performing symbolic rewritings.
Using our approach of just adding \code{Rep} types, we can go from the
naive textbook-algorithm to the staged version (shown in Figure~\ref{fig:fftcode})
by changing literally two lines of code:
\begin{slisting}
  trait FFT { this: Arith with Trig =>
    case class Complex(re: Rep[Double], im: Rep[Double])
    ...
  }
\end{slisting}
All that is needed is adding the self-type annotation to import
arithmetic and trigonometric operations and changing the type of the real
and imaginary components of complex numbers from \code{Double}
to \code{Rep[Double]}.


\begin{figure}
\begin{slisting}
trait FFT { this: Arith with Trig =>
  case class Complex(re: Rep[Double], im: Rep[Double]) {
    def +(that: Complex) = Complex(this.re + that.re, this.im + that.im)
    def *(that: Complex) = ...
  }
  def omega(k: Int, N: Int): Complex = {
    val kth = -2.0 * k * Math.Pi / N
    Complex(cos(kth), sin(kth))
  }
  def fft(xs: Array[Complex]): Array[Complex] = xs match {
    case (x :: Nil) => xs
    case _ =>
      val N = xs.length // assume it's a power of two
      val (even0, odd0) = splitEvenOdd(xs)
      val (even1, odd1) = (fft(even0), fft(odd0))
      val (even2, odd2) = (even1 zip odd1 zipWithIndex) map {
        case ((x, y), k) =>
          val z = omega(k, N) * y
          (x + z, x - z)
      }.unzip;
      even2 ::: odd2
  }
}
\end{slisting}
\caption{\label{fig:fftcode} FFT code. Only the real and imaginary components
of complex numbers need to be staged.}
\end{figure}

\begin{figure}\centering
\includegraphics[scale=0.5]{papers/cacm2012/figures/test2-fft2-x-dot.pdf}
\caption{\label{fig:fftgraph} Computation graph for size-4 FFT. Auto-generated from
staged code in Figure~\ref{fig:fftcode}.}
\end{figure}



Merely changing the types %will remove the interpretive overhead of the program but 
will not provide us with %all of 
the desired optimizations yet. 
We will see below how we can add the transformations described by Kiselyov et~al.\ to generate the same fixed-size
FFT code, corresponding to the famous FFT butterfly networks
(see Figure~\ref{fig:fftgraph}).
Despite the seemingly naive algorithm, this staged code is free
of branches, intermediate data structures and redundant computations.
The important point here is that we can add these transformations
without any further changes to the code in Figure~\ref{fig:fftcode},
just by mixing in the trait \code{FFT} with a few others.



\begin{figure}[t]
\begin{slisting}
trait ArithExpOptFFT extends ArithExp {
  override def infix_*(x:Exp[Double],y:Exp[Double]) = (x,y) match {
    case (Const(k), Def(Times(Const(l), y))) => Const(k * l) * y
    case (x, Def(Times(Const(k), y))) => Const(k) * (x * y))
    case (Def(Times(Const(k), x)), y) => Const(k) * (x * y))
    ...
    case (x, Const(y)) => Times(Const(y), x)
    case _ => super.infix_*(x, y)
  }
}
\end{slisting}
\caption{\label{fig:expOpt}Extending the generic implementation from Section~\ref{sec:308addOpts}
with FFT-specific optimizations.}
\end{figure}




## Implementing Optimizations

As already discussed in Section~\ref{sec:308addOpts}, some profitable optimizations
are very generic (CSE, DCE, etc), whereas others are specific to the actual program.
In the FFT case, Kiselyov et al.\ \cite{DBLP:conf/emsoft/KiselyovST04} describe 
a number of rewritings that are particularly
effective for the patterns of code generated by the FFT algorithm
but not as much for other programs.

What we want to achieve again is modularity, such that
optimizations can be combined in a way that is most useful for a given task. 
This can be achieved by overriding smart constructors, 
as shown by trait \code{ArithExpOptFFT} (see Figure~\ref{fig:expOpt}). 
Note that the use of \code{x*y} within
the body of \code{infix_*} will apply the optimization 
recursively.



## Running the Generated Code


\begin{figure}
\begin{slisting}
trait FFTC extends FFT { this: Arrays with Compile =>
  def fftc(size: Int) = compile { input: Rep[Array[Double]] =>
    assert(<size is power of 2>) // happens at staging time
    val arg = Array.tabulate(size) { i => 
      Complex(input(2*i), input(2*i+1))
    }
    val res = fft(arg)
    updateArray(input, res.flatMap {
      case Complex(re,im) => Array(re,im)
    })
  }
}
\end{slisting}
\caption{\label{fig:fftc}Extending the FFT component from Figure~\ref{fig:fftcode}
with explicit compilation.}
\end{figure}

Using the staged FFT implementation as part of some larger Scala program
is straightforward but requires us to interface the generic algorithm
with a concrete data representation.
The algorithm in Figure~\ref{fig:fftcode} expects
an array of \code{Complex} objects as input, each of which contains
fields of type \code{Rep[Double]}. The algorithm itself has no
notion of staged arrays but uses arrays only in the generator stage,
which means that it is agnostic to how data is stored.
The enclosing program, however, will store arrays of complex numbers
in some native format which we will need to feed into the algorithm.
A simple choice of representation is to use \code{Array[Double]} with
the complex numbers flattened into adjacent slots.
When applying \code{compile}, we will thus receive 
input of type \code{Rep[Array[Double]]}. 
Figure~\ref{fig:fftc} shows how we can 
extend trait \code{FFT} to \code{FFTC} to obtain compiled FFT 
implementations that realize the necessary data interface for a 
fixed input size.


We can then define code that creates and uses compiled 
FFT ``codelets'' by extending \code{FFTC}:
\begin{slisting}
  trait TestFFTC extends FFTC {
    val fft4: Array[Double] => Array[Double] = fftc(4) 
    val fft8: Array[Double] => Array[Double] = fftc(8) 

    // embedded code using fft4, fft8, ...
  }
\end{slisting}
Constructing an instance of this subtrait (mixed in with the
appropriate LMS traits) will execute the embedded code:
\begin{slisting}
  val OP: TestFFC = new TestFFTC with CompileScala
    with ArithExpOpt  with ArithExpOptFFT with ScalaGenArith
    with TrigExpOpt   with ScalaGenTrig 
    with ArraysExpOpt with ScalaGenArrays
\end{slisting}
We can also use the compiled methods from outside the
object:
\begin{slisting}
  OP.fft4(Array(1.0,0.0, 1.0,0.0, 2.0,0.0, 2.0,0.0))
  $\hookrightarrow$ Array(6.0,0.0,-1.0,1.0,0.0,0.0,-1.0,-1.0)
\end{slisting}
Providing an explicit type in the definition \code{val OP: TestFFC = ...}
ensures that the internal representation is not accessible
from the outside, only the members defined by \code{TestFFC}.







# Regular Expression Matcher Example
\label{sec:Aregex}

Specializing string matchers and parsers is a popular benchmark in the partial evaluation and supercompilation literature
\cite{DBLP:journals/ipl/ConselD89,DBLP:journals/toplas/AgerDR06,DBLP:journals/toplas/SperberT00,DBLP:journals/toplas/Turchin86,
DBLP:journals/jfp/SorensenGJ96}.
%
We consider ``multi-threaded'' regular expression matchers, that spawn a new conceptual thread
to process alternatives in parallel. Of course these matchers do not actually spawn OS-level threads,
but rather need to be advanced manually by client code. Thus, they are similar to coroutines.

Here is a simple example for the fixed regular expression \code{.*AAB}:
\begin{listing}
def findAAB(): NIO = {
  guard(Set('A')) {
    guard(Set('A')) {
      guard(Set('B'), Found)) {
        stop()
  }}} ++
  guard(None) { findAAB() } // in parallel...
}
\end{listing}
We can easily add combinators on top of the core abstractions that take
care of producing matchers from textual regular expressions. However 
the point here is to demonstrate how the implementation works.

The given matcher uses an API that models
nondeterministic finite automata (NFA):
\begin{listing}
type NIO = List[Trans]   // state: many possible transitions
case class Trans(c: Set[Char], x: Flag, s: () => NIO)

def guard(cond: Set[Char], flag: Flag)(e: => NIO): NIO =
  List(Trans(cond, flag, () => e))
def stop(): NIO = Nil
\end{listing}
An NFA state consists of a list of possible transitions.
Each transition may be guarded by a set of characters and it may 
have a flag to be signaled if the transition is taken.
It also knows how to compute the following state.
We use \code{Char}s for simplicity, but of course we could 
use generic types as well. Note that the API
does not mention where input is obtained from (files, 
streams, etc).


We will translate NFAs to DFAs using staging. This is the unstaged DFA API:
\begin{listing}
abstract class DfaState {
  def hasFlag(x: Flag): Boolean
  def next(c: Char): DfaState
}
def dfaFlagged(flag: Flag, link: DfaState) = new DfaState {
  def hasFlag(x: Flag) = x == flag || link.hasFlag(x)
  def next(c: Char) = link.next(c)
}
def dfaState(f: Char => DfaState) = new DfaState {
  def hasFlag(x: Flag) = false
  def next(c: Char) = f(c)
}
\end{listing}

The staged API is just a thin wrapper:
\begin{listing}
type DIO = Rep[DfaState]
def dfa_flag(x: Flag)(link: DIO): DIO
def dfa_trans(f: Rep[Char] => DIO): DIO
\end{listing}

Translating an NFA to a DFA is accomplished
by creating a DFA state for each encountered
NFA configuration (removing duplicate states
via \code{canonicalize}):
\begin{listing}
def convertNFAtoDFA(states: NIO): DIO = {
  val cstates = canonicalize(state)
  dfa_trans { c: Rep[Char] =>
    exploreNFA(cstates, c)(dfa_flag) { next =>
      convertNFAtoDFA(next)
    }
  }
}
iterate(findAAB())
\end{listing}
The LMS framework memoizes 
functions (see Section~\ref{sec:220functions}) which ensures 
termination if the NFA is indeed finite.

We use a separate function to explore the NFA space (see Figure~\ref{fig:NFAexplore}), advancing the automaton
by a symbolic character \code{cin} to invoke its continuations \code{k} with a new automaton,
i.e.\ the possible set of states after consuming \code{cin}.
The given implementation assumes character sets contain either zero
or one characters, the empty set \code{Set()} denoting a wildcard match.
More elaborate cases such as character ranges are easy to add.
The algorithm tries to remove as many redundant checks and impossible branches as possible.
This only works because the character guards are staging time values.

\begin{figure}[t]
\begin{listing}
def exploreNFA[A](xs: NIO, cin: Rep[Char])(flag: Flag => Rep[A] => Rep[A])
                                          (k: NIO => Rep[A]):Rep[A] = xs match {
  case Nil => k(Nil)
  case Trans(Set(c), e, s)::rest =>
    if (cin == c) {
      // found match: drop transitions that look for other chars and
      // remove redundant checks
      val xs1 = rest collect { case Trans(Set(`c`)|None,e,s) => Trans(Set(),e,s) }
      val maybeFlag = e map flag getOrElse (x=>x)
      maybeFlag(exploreNFA(xs1, cin)(acc => k(acc ++ s())))
    } else {
      // no match, drop transitions that look for same char
      val xs1 = rest filter { case Trans(Set(`c`),_,_) => false case _ => true }
      exploreNFA(xs1, cin)(k)
    }
  case Trans(Set(), e, s)::rest =>
    val maybeFlag = e map flag getOrElse (x=>x)
    maybeFlag(exploreNFA(rest, cin)(acc => k(acc ++ s())))
}
\end{listing}
\caption{\label{fig:NFAexplore}NFA Exploration}
\end{figure}

The generated code is shown in Figure~\ref{fig:regexGen}. Each function corresponds to one DFA state. Note how 
negative information has been used to prune the transition space: Given input such as \code{...AAB} the 
automaton jumps back to the initial state, i.e.\ it recognizes that the last character B cannot 
also be A and starts looking for two As after the B.

\clearpage
The generated code can be used as follows:
\begin{listing}
var state = stagedFindAAB()
var input = ...
while (input.nonEmpty) {
  state = state.next(input.head)
  if (state.hasFlag(Found))
    println("found AAB. rest: " + input.tail)
  input = input.tail
}
\end{listing}

If the matcher and input iteration logic is generated together, further 
translations can be applied to transform the mutually recursive lambdas
into tight imperative state machines.\credits{Optimizations implemented by Nada Amin}



\begin{figure}[t]
\begin{multicols}{2}
\begin{listing}
def stagedFindAAB(): DfaState = {
  val x7 = { x8: (Char) =>  
    // matched AA
    val x9 = x8 == B
    val x15 = if (x9) {
      x11
    } else {
      val x12 = x8 == A
      val x14 = if (x12) {
        x13
      } else {
        x10
      }
      x14
    }
    x15
  }
  val x13 = dfaState(x7)
  val x4 = { x5: (Char) => 
    // matched A
    val x6 = x5 == A
    val x16 = if (x6) {
      x13
    } else {
      x10
    }
    x16
  }
  val x17 = dfaState(x4)
  val x1 = { x2: (Char) => 
    // matched nothing
    val x3 = x2 == A
    val x18 = if (x3) {
      x17
    } else {
      x10
    }
    x18
  }
  val x10 = dfaState(x1)
  val x11 = dfaFlagged(Found, x10)
  x10
}
\end{listing}
\end{multicols}
\caption{\label{fig:regexGen}Generated matcher code for regular expression \code{.*AAB}}
\end{figure}

*/