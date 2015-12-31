package liveblock

import scala.concurrent.duration._
import scala.math._
import scala.util.Random

import rx.lang.scala._
import rx.lang.scala.subjects.BehaviorSubject

/**
  * @author Eron Wright
  */
object Main extends App {

  val environment = new Environment()
  environment.debugObserver.subscribe { (i) =>
    println(f"environment: outputQuantity=${i.outputQuantity}%.2f, disturbance=${i.disturbance}%.2f, inputQuantity=${i.inputQuantity}%.2f")
  }
  println("env up")

  val controller = new Controller(environment.inputQuantity)
  controller.debugObserver.subscribe { (i) =>
    println(f"controller:  perceptualSignal=${i.perceptualSignal}%.2f, referenceSignal=${i.referenceSignal}%.2f, errorSignal=${i.errorSignal}%.2f, outputQuantity=${i.outputQuantity}%.2f")
  }
  println("controller up")

  println("wire-up")
  environment.connectTo(controller.outputQuantity)

  println("running...")

  println("--------------------------------------------------")
  println("setting reference value to 0.0")
  controller.referenceSignal.onNext(0.0)
  Thread.sleep((5 second).toMillis)

  println("--------------------------------------------------")
  println("setting reference value to 25.0")
  controller.referenceSignal.onNext(25.0)
  Thread.sleep((5 second).toMillis)

  println("--------------------------------------------------")
  println("setting reference value to 0.0")
  controller.referenceSignal.onNext(0.0)
  Thread.sleep((5 second).toMillis)

  println("--------------------------------------------------")
  println("setting disturbance to 10.0")
  environment.disturbance.onNext(10.0)
  Thread.sleep((5 second).toMillis)

  println("--------------------------------------------------")
  println("enabling auto-disturbance")
  Observable
    .interval(5 second)
    .scan(0.0) { (d,_) => d + 10 * (Random.nextDouble - 0.5) }
    .subscribe(environment.disturbance)

  readLine
}

object Parameters {
  val dt = 1/60.0
  val inputDelay = (0.133 seconds)
  val debounceTime = (0.133 seconds)
  val errorSignalRange = (-1000.0, 1000.0)
  val outputQuantityRange = (-10000.0, 10000.0)
}

class Controller(val inputQuantity: Observable[Double]) extends ControllerLike[Double] {

  import Parameters._

  val inputGain = BehaviorSubject[Double](1.0)

  private val perceptualSignal = inputQuantity
    .delay(inputDelay)
    .combineLatestWith(inputGain) { (i, g) => i * g }

  val referenceSignal = BehaviorSubject[Double](0.0)

  private val errorSignal = referenceSignal
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
    .toSubject(0.0)

  case class DebugOutput(perceptualSignal: Double, referenceSignal: Double, errorSignal: Double, outputQuantity: Double)
  val debugObserver = Observable
    .combineLatest(Seq(perceptualSignal, referenceSignal, errorSignal, outputQuantity)) { v =>
      new DebugOutput(v(0),v(1),v(2),v(3))
    }
}

class Environment() extends EnvironmentLike[Double] {
  import Parameters._

  def connectTo(o: Observable[Double]) = {
    outputQuantitySource.onNext(o)
  }

  private val outputQuantitySource = BehaviorSubject[Observable[Double]]()

  private val outputQuantity = outputQuantitySource.switch

  val feedbackGain = BehaviorSubject[Double](10.0)

  private val feedbackEffect = outputQuantity.combineLatestWith(feedbackGain) { (o, g) => o * g }

  val disturbance = BehaviorSubject[Double](0.0)

  val inputQuantity = feedbackEffect
    .combineLatestWith(disturbance) { (f, d) => f + d }
    .debounce(debounceTime)
    .share
    .toSubject(0.0)

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