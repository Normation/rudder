package zio

/*
* .fail and .succeed were removed in RC18
*/
object syntax {
  implicit class ToZio[A](a: A) {
    def fail = ZIO.fail(a)
    def succeed = ZIO.succeed(a)
  }
}
