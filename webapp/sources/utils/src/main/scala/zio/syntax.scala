package zio

import zio.IO
/*
 * .fail and .succeed were removed in RC18
 */
object syntax {
  implicit class ToZio[A](a: A) {
    def fail:    IO[A, Nothing]       = ZIO.fail(a)
    def succeed: ZIO[Any, Nothing, A] = ZIO.succeed(a)
  }
}
