import rx.lang.scala.subjects.BehaviorSubject
import rx.lang.scala.{Subject, Observable}

/**
  * @author Eron Wright
  */
package object liveblock {
  implicit class RichObservable[T](o: Observable[T]) {

    def toSubject(defaultValue: T): Subject[T] = {
      val s = BehaviorSubject[T](defaultValue)
      o.subscribe(s)
      s
    }
  }
}
