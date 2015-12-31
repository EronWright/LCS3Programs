package livethree

import java.util

import rx.plugins.{DebugNotification, DebugNotificationListener, DebugHook, RxJavaPlugins}

import scala.collection.JavaConversions._

import scala.concurrent.duration._
import scala.math._

import rx.lang.scala._
import rx.lang.scala.subjects.BehaviorSubject

import scala.util.Random

object Parameters {

  val numControllers = 3
  val numEnvironmentVariables = 3

  val dt = 1/60.0
  val inputDelay = (0.133 seconds) //(0.133 seconds)
  val debounceTime = (0.133 seconds)
  val errorSignalRange = (-1000.0, 1000.0)
  val outputQuantityRange = (-10000.0, 10000.0)
}

object Main extends App {

  import Parameters._

  object Listener extends DebugNotificationListener[String] {

    //val labels = scala.collection.mutable.HashMap.empty[AnyRef, String]
    val labels = new util.IdentityHashMap[AnyRef, String]()
    def label(o: AnyRef) = labels.getOrElse(o, o.toString)

    var counter: Long = 0

    override def start[T](n: DebugNotification[T]): String = {
      //println(s"start: ${n}")
      n.getKind match {
        case DebugNotification.Kind.Subscribe =>
          println(n)
         // println(s"subscribe: source=${label(n.getSource)} observer=${label(n.getObserver)}")
        case _ => //println(n.toString)
      }

      "continue"
    }

    override def onNext[T](n: DebugNotification[T]): T = {
      counter = counter + 1
      super.onNext(n)
    }

    implicit class DebugObservable[T](o: Observable[T]) {
      def labeled(s: String) = {
        Listener.labels += (o -> s)
        o
      }
    }
  }

  //RxJavaPlugins.getInstance().registerObservableExecutionHook(new DebugHook(Listener))

  val environmentVariables = (0 until numEnvironmentVariables).map { idx =>
    val environment = new EnvironmentSimple()
    //environment.disturbance.subscribe { (i) => println(s"environment $idx says: disturbance = $i") }
    //environment.inputQuantity.subscribe { (i) => println(s"environment $idx says: inputQuantity = $i") }
    environment
  }.toArray
  println("env initialized")

  //println("observing environment signals...")
  //val environment = environmentVariables(0)

  val controllers = (0 until numControllers).map { idx =>
    val controller = new MultiDimensionalController(environmentVariables)
    //controller.Qo.subscribe { (i) => println(s"controller $idx says: Qo = $i") }

    controller.controller.perceptualSignal.subscribe { (i) => println(s"controller $idx says: perceptualSignal = $i") }
    //controller.controller.referenceSignal.subscribe { (i) => println(s"controller $idx says: referenceSignal = $i") }
    //controller.controller.errorSignal.subscribe { (i) => println(s"controller $idx says: errorSignal = $i") }
    //controller.controller.outputQuantity.subscribe { (i) => println(s"controller $idx says: outputQuantity = $i") }
    controller
  }
  println("controllers initialized")

  println("wire-up")
  (0 until numEnvironmentVariables).map { idx =>
    val Qo = new MultiDimensionalEnvironmentVariable(idx, controllers)
    environmentVariables(idx).connectTo(Qo.outputQuantity)
  }

  println("observing controller signals...")

  //println("--------------------------------------------------")
  //controller.referenceSignal.onNext(0.0)
  //Thread.sleep((5 second).toMillis)

  println("--------------------------------------------------")
  controllers(0).controller.referenceSignal.onNext(5.0)
  controllers(1).controller.referenceSignal.onNext(-5.0)
  Thread.sleep((15 second).toMillis)

  println("--------------------------------------------------")
  controllers(2).controller.referenceSignal.onNext(-5.0)

//  println("--------------------------------------------------")
//  controllers(0).controller.referenceSignal.onNext(0.0)
//  Thread.sleep((5 second).toMillis)
//
//  println("--------------------------------------------------")
//  environmentVariables(0).disturbance.onNext(10.0)

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
    //.distinctUntilChanged
    .toSubject(0.0)


  //Qo.subscribe { (i) => println(s"controller says: Qo = $i") }

}

class Controller(val inputQuantity: Observable[Double])
extends ControllerLike[Double] {

  import Main.Listener._
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
    //.filter { e => abs(e) >= 0.1 } // damping

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
    .labeled("outputQuantity")
}

class EnvironmentSimple() extends EnvironmentLike[Double] {
  import Main.Listener._
  import Parameters._

  val outputQuantitySource = BehaviorSubject[Observable[Double]]() // BehaviorSubject(Observable.just(0.0))

  def connectTo(o: Observable[Double]) = {
    outputQuantitySource.onNext(o)
  }

  val inputQuantity = outputQuantitySource.switch
    .debounce(debounceTime)
}

class Environment() extends EnvironmentLike[Double] {

  val outputQuantitySource = BehaviorSubject[Observable[Double]]() // BehaviorSubject(Observable.just(0.0))

  def connectTo(o: Observable[Double]) = {
    outputQuantitySource.onNext(o)
  }

  val outputQuantity = outputQuantitySource.switch

  val feedbackGain = BehaviorSubject[Double](1.0)

  val feedbackEffect = outputQuantity.combineLatestWith(feedbackGain) { (o, g) => o * g }

  val disturbance = BehaviorSubject[Double](0.0)

  val inputQuantity = feedbackEffect
    .combineLatestWith(disturbance) { (f, d) => f + d }
    //.distinctUntilChanged
    .toSubject(0.0)
}

trait ControllerLike[T] {
  def outputQuantity: Observable[T]
}

trait EnvironmentLike[T] {
  def inputQuantity: Observable[T]
}