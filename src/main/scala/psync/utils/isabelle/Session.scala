package psync.utils.isabelle

// inspired by
// https://github.com/larsrh/libisabelle/blob/master/examples/src/main/scala/Hello_PIDE.scala
// https://github.com/fthomas/libisabelle-example/blob/master/src/main/scala/libisabelle/example/Main.scala

import psync.formula._
import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import edu.tum.cs.isabelle._
import edu.tum.cs.isabelle.api._
import edu.tum.cs.isabelle.setup._
import dzufferey.utils.Logger
import dzufferey.utils.LogLevel._

object Session {

  val version = Version("2016")
  val timeout = Duration.Inf

  def await[T](a: Awaitable[T]): T = {
    Await.result(a, timeout)
  }

}

class Session {

  import Session._

  protected var system: System = null

  def start {
    Logger.assert(system == null, "isabelle.Session", "session has already started")
    val resources = Resources.dumpIsabelleResources()
    val config = resources.makeConfiguration(Nil, "Protocol")
    val setup = Setup.defaultSetup(version) match {
      case cats.data.Xor.Left(err) =>
        sys.error(err.toString)
      case cats.data.Xor.Right(future) =>
        await(future)
    }
    val env = await(setup.makeEnvironment)
    system = await(System.create(env, config))
  }

  def stop {
    Logger.assert(system != null, "isabelle.Session", "session has already ended")
    await(system.dispose)
    system = null
  }

  /* hello world operation to test the system */
  def hello = {
    val response = runCommand(Operation.Hello, "world")
    response.unsafeGet
  }

  protected def runCommand[I, O](op: Operation[I, O], arg: I) = {
    Logger.assert(system != null, "isabelle.Session", "session not yet started or already ended")
    val future = system.invoke(op)(arg)
    await(future)
  }

  // TODO load additional theories
  def loadTheory(theoryName: String) = {
    ???
  }

  /* get the current state of Isabelle */
  def getCurrentState = {
    ???
  }

  def lemma(name: String,
            hypotheses: List[Formula],
            conclusion: Formula,
            proof: Option[String]) = {
    //XXX should the proof be more structured ?
    //XXX do something with the name on the isabelle side ?
    Logger("isabelle.Session", Info, "trying to prove " + name)
    val statement = hypotheses match {
      case _ :: _ :: _  => Implies(And(hypotheses:_*), conclusion)
      case h :: Nil => Implies(h, conclusion)
      case Nil => conclusion
    }
    val asTem = TranslateFormula(statement)
    runCommand(Operations.prove, asTem -> proof)
  }

  //TODO commands and stuffs

}
