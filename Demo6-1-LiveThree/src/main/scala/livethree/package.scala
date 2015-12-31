import rx.lang.scala.subjects.BehaviorSubject
import rx.lang.scala.{Subject, Observable}

/**
  * @author Eron Wright
  */
package object livethree {

  implicit class RichVector(v: Vector[Double]) {

    def norm(): Vector[Double] = {
      val sum = Math.sqrt((0.0 /: v.map(x => x * x)) {_ + _})
      v.map(x => x / sum)
    }

    def dot(other: Vector[Double]): Double = {
      (v zip other).map { case (x,y) => x * y }.fold(0.0) { _ + _ }
    }
  }

  implicit class RichObservable[T](o: Observable[T]) {

    def toSubject(defaultValue: T): Subject[T] = {
      val s = BehaviorSubject[T](defaultValue)
      o.subscribe(s)
      s
    }
  }
}
