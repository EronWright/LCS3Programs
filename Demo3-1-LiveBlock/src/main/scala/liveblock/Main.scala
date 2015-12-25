package liveblock

import scala.concurrent.duration._
import scala.math._

import rx.lang.scala._
import rx.lang.scala.subjects.BehaviorSubject

object Main extends App {

  var outputQuantity: Observable[Double] = null

  var controller: Controller = null

  val environment = new Environment(Observable.defer {
    println(s"environment subscribed to outputQuantity")
    controller.outputQuantity
  })
  println("env up")

  val publishedInputQuantity = environment.inputQuantity.doOnSubscribe {
    println("controller subscribed to inputQuantity")
  }.publish

  controller = new Controller(publishedInputQuantity.doOnSubscribe {
    println("controller subscribed to publishedInputQuantity")
  })
  println("controller up")

  println("observing environment signals...")
  environment.disturbance.subscribe { (i) => println(s"environment says: disturbance = $i") }
  environment.inputQuantity.subscribe { (i) => println(s"environment says: inputQuantity = $i") }

  println("observing controller signals...")
  controller.perceptualSignal.subscribe { (i) => println(s"controller says: perceptualSignal = $i") }
  controller.referenceSignal.subscribe { (i) => println(s"controller says: referenceSignal = $i") }
  controller.errorSignal.subscribe { (i) => println(s"controller says: errorSignal = $i") }
  controller.outputQuantity.subscribe { (i) => println(s"controller says: outputQuantity = $i") }

  println("connecting...")
  publishedInputQuantity.connect

  println("--------------------------------------------------")
  controller.referenceSignal.onNext(0.0)
  Thread.sleep((5 second).toMillis)

  println("--------------------------------------------------")
  controller.referenceSignal.onNext(25.0)
  Thread.sleep((5 second).toMillis)

  println("--------------------------------------------------")
  controller.referenceSignal.onNext(0.0)
  Thread.sleep((5 second).toMillis)

  println("--------------------------------------------------")
  environment.disturbance.onNext(10.0)

  readLine
}

object Parameters {
  val dt = 1/60.0
  val inputDelay = (0.133 seconds)
  val errorSignalRange = (-1000.0, 1000.0)
  val outputQuantityRange = (-10000.0, 10000.0)
}


class Controller(val inputQuantity: Observable[Double]) extends ControllerLike[Double] {

  import Parameters._

  val inputGain = BehaviorSubject[Double](1.0)

  val perceptualSignal = inputQuantity
    .delay(inputDelay).combineLatestWith(inputGain) { (i, g) => i * g }

  val referenceSignal = BehaviorSubject[Double](0.0)

  val errorSignal = referenceSignal
    .combineLatestWith(perceptualSignal) { (r, p) => r - p }
    .map { e => max(errorSignalRange._1, min(e, errorSignalRange._2)) }
    .filter { e => abs(e) >= 0.1 } // damping

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
}

class Environment(val outputQuantity: Observable[Double]) extends EnvironmentLike[Double] {

  val feedbackGain = BehaviorSubject[Double](10.0)

  val feedbackEffect = outputQuantity.combineLatestWith(feedbackGain) { (o, g) => o * g }

  val disturbance = BehaviorSubject[Double](0.0)

  val inputQuantity = feedbackEffect.combineLatestWith(disturbance) { (f, d) => f + d }

}

trait ControllerLike[T] {
  def outputQuantity: Observable[T]
}

trait EnvironmentLike[T] {
  def inputQuantity: Observable[T]
}