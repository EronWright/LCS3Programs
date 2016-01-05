package livethree

import rx.lang.scala._
import rx.lang.scala.subjects.BehaviorSubject
import rx.plugins.{DebugNotification, DebugNotificationListener}

import scala.concurrent.duration._
import scala.math._
import scala.util.Random

object Parameters {

  val numControllers = 3
  val numEnvironmentVariables = 3

  val dt = 1/60.0
  val inputDelay = (0.133 seconds)
  val debounceTime = (0.133 seconds)
  val errorSignalRange = (-1000.0, 1000.0)
  val outputQuantityRange = (-10000.0, 10000.0)
}

/**
  * @author Eron Wright
  */
object Main extends App {

  import Parameters._

  object Listener extends DebugNotificationListener[String] {

    override def start[T](n: DebugNotification[T]): String = {
      println(s"(debug) start: ${n}")
      "continue"
    }

    override def onNext[T](n: DebugNotification[T]): T = {
      println(s"(debug) onNext: ${n}")
      super.onNext(n)
    }
  }

  //RxJavaPlugins.getInstance().registerObservableExecutionHook(new DebugHook(Listener))

  println("initializing...")
  val environmentVariables = (0 until numEnvironmentVariables).map { idx =>
    val environment = new Environment()
    environment.debugObserver.subscribe { (i) =>
      println(f"environment $idx: outputQuantity=${i.outputQuantity}%.2f, disturbance=${i.disturbance}%.2f, inputQuantity=${i.inputQuantity}%.2f")
    }
    environment
  }.toArray

  val controllers = (0 until numControllers).map { idx =>
    val controller = new MultiDimensionalController(environmentVariables)

    // set the initial weights to be mostly independent for test purposes
    idx match {
      case 0 => controller.setWeights(Vector(0.9,0.2,0.1))
      case 1 => controller.setWeights(Vector(0.2,0.9,0.1))
      case 2 => controller.setWeights(Vector(0.3,0.2,0.9))
      case _ =>
    }

    controller.controller.debugObserver.subscribe { (i) =>
      println(f"controller $idx:  perceptualSignal=${i.perceptualSignal}%.2f, referenceSignal=${i.referenceSignal}%.2f, errorSignal=${i.errorSignal}%.2f, outputQuantity=${i.outputQuantity}%.2f")
    }
    controller
  }

  (0 until numEnvironmentVariables).map { idx =>
    val Qo = new MultiDimensionalEnvironmentVariable(idx, controllers)
    environmentVariables(idx).connectTo(Qo.outputQuantity)
  }

  println("running...")


  println("--------------------------------------------------")
  println("setting reference levels to (10.0, -5.0, 1.0)")
  controllers(0).controller.referenceSignal.onNext(10.0)
  controllers(1).controller.referenceSignal.onNext(-5.0)
  controllers(2).controller.referenceSignal.onNext(1.0)

  Thread.sleep((5 second).toMillis)

  println("--------------------------------------------------")
  println("adding disturbances (-10.0, 10.0, 0.0)")
  environmentVariables(0).disturbance.onNext(-10.0)
  environmentVariables(1).disturbance.onNext(10.0)

  readLine
}

class MultiDimensionalEnvironmentVariable(
  idx: Int, controllers: Seq[MultiDimensionalController]) {

  private val c = Observable.combineLatest(controllers.map(_.Qo)) { _.toVector }

  private val w = Observable.combineLatest(controllers.map(_.Wo)) { _.map(_(idx)).toVector }

  // the compound value fed to environment variable $idx
  val outputQuantity = c
    .combineLatestWith(w) { _.dot(_) }
    .toSubject(0.0)

  outputQuantity.subscribe()
}

class MultiDimensionalController(envVariables: Seq[EnvironmentLike[Double]])
{

  val Wi = BehaviorSubject(envVariables.map(_ => 0.0).toVector)
  val Wo = BehaviorSubject(envVariables.map(_ => 0.0).toVector)

  def makeWeights: Unit = {
    // initialize a normalized vector of weights
    val w = envVariables.map(_ => (Random.nextDouble)).toVector.norm()

    println(s"weights = $w")
    setWeights(w)
  }

  def setWeights(w: Vector[Double]): Unit = {
    // note that this is not atomic; Wi will change before Wo
    Wi.onNext(w)
    Wo.onNext(w)
  }

  makeWeights

  // construct Qi (to be used as inputQuantity for a real-valued controller)
  private val EV = Observable.combineLatest(envVariables.map(_.inputQuantity)) { _.toVector }
  private val Qi = EV.combineLatestWith(Wi) { (e,w) => e.dot(w) }

  val controller = new Controller(Qi)

  val Qo = controller.outputQuantity
    .serialize
    .toSubject(0.0)
}

class Controller(val inputQuantity: Observable[Double])
extends ControllerLike[Double] {
  import Parameters._

  val inputGain = BehaviorSubject[Double](1.0)

  val perceptualSignal = inputQuantity
    .delay(inputDelay)
    .combineLatestWith(inputGain) { (i, g) => i * g }
    .toSubject(0.0)
    .share

  val referenceSignal = BehaviorSubject[Double](0.0)

  val errorSignal = referenceSignal
    .combineLatestWith(perceptualSignal) { (r, p) => r - p }
    .map { e => max(errorSignalRange._1, min(e, errorSignalRange._2)) }

  val outputGain = BehaviorSubject[Double](100.0)

  val outputTC = BehaviorSubject[Double](33.0)

  val outputQuantity = Observable
    .combineLatest(Seq(errorSignal, outputGain, outputTC)) { v =>
      new { val errorSignal = v(0); val outputGain = v(1); val outputTC = v(2) }
    }
    .scan(0.0) { (o, v) =>
      o + (v.errorSignal * v.outputGain - o) * dt / v.outputTC
    }
    .map { e => max(outputQuantityRange._1, min(e, outputQuantityRange._2)) }
    .share

  case class DebugOutput(perceptualSignal: Double, referenceSignal: Double, errorSignal: Double, outputQuantity: Double)
  val debugObserver = Observable
    .combineLatest(Seq(perceptualSignal, referenceSignal, errorSignal, outputQuantity)) { v =>
      new DebugOutput(v(0),v(1),v(2),v(3))
    }
}

class Environment() extends EnvironmentLike[Double] {
  import Parameters._

  private val outputQuantitySource = BehaviorSubject[Observable[Double]]()

  def connectTo(o: Observable[Double]) = {
    outputQuantitySource.onNext(o)
  }

  private val outputQuantity = outputQuantitySource.switch

  val feedbackGain = BehaviorSubject[Double](10.0)

  private val feedbackEffect = outputQuantity.combineLatestWith(feedbackGain) { (o, g) => o * g }

  val disturbance = BehaviorSubject[Double](0.0)

  val inputQuantity = feedbackEffect
    .combineLatestWith(disturbance) { (f, d) => f + d }
    .share
    .debounce(debounceTime)

  case class DebugOutput(outputQuantity: Double, disturbance: Double, inputQuantity: Double)
  val debugObserver = Observable
    .combineLatest(Seq(outputQuantity, disturbance, inputQuantity)) { v =>
      new DebugOutput(v(0),v(1),v(2))
    }
}

trait ControllerLike[T] {
  def outputQuantity: Observable[T]
}

trait EnvironmentLike[T] {
  def inputQuantity: Observable[T]
}