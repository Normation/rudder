/*
 *************************************************************************************
 * Copyright 2019 Normation SAS
 *************************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *************************************************************************************
 */

/*
 * This class provides common usage for Zio
 */

package com.normation

import _root_.zio.*
import _root_.zio.syntax.*
import com.normation.errors.*
import com.normation.zio.*
import net.liftweb.common.*
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.JUnitRunner
import scala.annotation.nowarn

@RunWith(classOf[JUnitRunner])
class ZioCommonsTest extends Specification {

  "When we use toIO, we should ensure that evaluation is only done at run" >> {
    def write(sb: StringBuffer, what: String): Unit = {
      sb.append(what)
    }

    def produceBox(sb: StringBuffer): Box[Int] = {
      write(sb, "In the method body\n")
      Full({ write(sb, "in the full\n"); 42 })
    }

    val sb = new StringBuffer()

    write(sb, "before io\n")
    val io = produceBox(sb).toIO
    write(sb, "after io\n")

    write(sb, "*** run io\n")
    io.runNow
    write(sb, "*** done\n")

    sb.toString must beEqualTo("""before io
                                 |after io
                                 |*** run io
                                 |In the method body
                                 |in the full
                                 |*** done
                                 |""".stripMargin)
  }

}

object TestImplicits {
  import _root_.zio.*
  import _root_.zio.syntax.*

  object module1 {
    import com.normation.errors.*
    sealed trait M_1_Error extends RudderError
    object M_1_Error {
      final case class Some(msg: String)                                 extends M_1_Error
      final case class Chained[E <: RudderError](hint: String, cause: E) extends M_1_Error with BaseChainError[E]
    }

    object M_1_Result {}

    object service1 {
      def doStuff(param: String): IOResult[String] = param.succeed
    }
  }

  object module2 {
    import com.normation.errors.*
    sealed trait M_2_Error extends RudderError
    object M_2_Error  {
      final case class Some(msg: String)                                 extends M_2_Error
      final case class Chained[E <: RudderError](hint: String, cause: E) extends M_2_Error with BaseChainError[E]
    }
    object M_2_Result {}

    final case class TestError(msg: String) extends RudderError

    object service2 {
      def doStuff(param: Int): IOResult[Int] = TestError("ah ah ah I'm failing").fail
    }
  }

  object testModule {
    import com.normation.errors.*
    import module1.*
    import module2.*
    sealed trait M_3_Error extends RudderError
    object M_3_Error  {
      final case class Some(msg: String)                                 extends M_3_Error
      final case class Chained[E <: RudderError](hint: String, cause: E) extends M_3_Error with BaseChainError[E]
    }
    object M_3_Result {
      // implicits ?
    }

    /*
     * I would like all of that to be possible, with the minimum boilerplate,
     * and the maximum homogeneity between modules
     */
    object service {

      def trace(msg: => AnyRef): UIO[Unit] = effectUioUnit(println(msg))

      def test0(a: String): IOResult[String] = service1.doStuff(a)

      def test1(a: Int): IOResult[Int] = service2.doStuff(a)

      def test2(a: String, b: Int): IOResult[(String, Int)] = {
        (for {
          x <- service1.doStuff(a)
          y <- service2.doStuff(b).chainError("Oups, I did it again")
        } yield {
          (x, y)
        })
      }

      def prog: ZIO[Any, Nothing, (String, Int)] =
        test2("plop", 42) catchAll (err => trace(err.fullMsg) *> ("success", 0).succeed)
    }
  }

  import testModule.service.prog
  def main(args: Array[String]): Unit = {
    println(ZioRuntime.runNow(prog))
  }
}

object SimpleEvalTest {

  val hello: IO[SystemError, Unit] = IOResult.attempt(println("plop"))

  def main(args: Array[String]): Unit = {
    // write a first time
    hello.runNow
    // write a second time
    hello.runNow
  }
}

object TestLog {

  val log: NamedZioLogger = NamedZioLogger("test-logger")

  def main(args: Array[String]): Unit = {

    def oups: IO[String, Int] = "oups error".fail

    val prog = oups.catchAll(e => log.error("I got an error!") *> e.fail) *> ZIO.succeed(42)

    ZioRuntime.unsafeRun(prog)
  }
}

object TestSemaphore {

  val log: NamedZioLogger = NamedZioLogger("test-logger")
  trait ScalaLock {
    def apply[T](block: IOResult[T]): ZIO[Any, RudderError, T]
  }
  def pureZioSemaphore(name: String): ScalaLock = new ScalaLock {
    val semaphore = Semaphore.make(1)
    override def apply[T](block: IOResult[T]): ZIO[Any, RudderError, T] = {
      for {
        _    <- log.logPure.error(s"*****zio** getting semaphore for lock '${name}'")
        sem  <- semaphore
        _    <- log.logPure.error(s"*****zio** wait for lock '${name}'")
        exec <- sem.withPermit(block).timeout(Duration(5, java.util.concurrent.TimeUnit.MILLISECONDS)).fork
        res  <- exec.join
        _    <- log.logPure.error(s"*****zio** done lock '${name}'")
        xxx  <- res match {
                  case Some(x) => x.succeed
                  case None    => Unexpected(s"Error: semaphore '${name}' timeout on section").fail
                }
      } yield (xxx)
    }
  }

  val semaphore: UIO[Semaphore]              = Semaphore.make(1)
  def inSem():   ZIO[Any, SystemError, Unit] = for {
    _   <- log.logPure.error("before sem")
    sem <- semaphore
    _   <- log.logPure.error("sem get")
    a   <- sem.withPermit(IOResult.attempt(println("Hello world sem"))).fork
    b   <- sem.withPermit(IOResult.attempt({ println("sleeping now"); Thread.sleep(2000); println("after sleep") })).fork
    c   <- sem
             .withPermit(IOResult.attempt(println("third hello")))
             .timeout(Duration(5, java.util.concurrent.TimeUnit.MILLISECONDS))
             .fork
    _   <- a.join
    _   <- b.join
    x   <- c.join
    _   <- x match {
             case None    => log.error("Oh noes! Timeout!")
             case Some(y) => log.error("yes! No Timeout! Or is it a noes?")
           }
    _   <- log.logPure.error("after sem")
  } yield ()

  val lock: ScalaLock = pureZioSemaphore("plop")

  def inLock: ZIO[Any, RudderError, Unit] = for {
    _ <- log.logPure.error("lock get")
    _ <- lock(IOResult.attempt(println("Hello world lock")))
    _ <- lock(IOResult.attempt({ println("sleeping now"); Thread.sleep(2000); println("after sleep") }))
    x <- lock(IOResult.attempt(println("third hello"))).uninterruptible
    _ <- log.logPure.error("after lock")
  } yield ()

  def main(args: Array[String]): Unit = {
    ZioRuntime.runNow(inLock.provideLayer(ZioRuntime.layers))
  }

}

/*
 * This show that even if we lock a ZIO execution in a given threadpool, that does not
 * work well with Java lock because we don't have a garantee that the same thread will
 * do the first and second part of the bracket.
 *
 */
object TestJavaLockWithZio {
  def log(s: String): ZIO[Any, Nothing, Unit] = ZIO.succeed(println(s))

  trait ScalaLock {
    def lock():   Unit
    def unlock(): Unit

    def name: String

    def apply[T](block: => IOResult[T]): IOResult[T] = {
      println(s"*calling lock '${name}'")

      (ZIO.acquireReleaseWith(
        log(s"Get lock '${name}'") *>
//        log(Thread.currentThread().getStackTrace.mkString("\n")) *>
        ZIO
          .attemptBlockingIO(this.lock())
          .timeout(Duration.Finite(100 * 1000 * 1000 /* ns */ ))
          .mapError(ex => SystemError(s"Error when trying to get LDAP lock", ex))
      )(_ => {
        log(s"Release lock '${name}'") *>
        ZIO
          .attemptBlockingIO(this.unlock())
          .tapError(t =>
            log(s"${t.getClass.getName}:${t.getMessage}") *> ZIO.foreach(t.getStackTrace.toList)(s => log(s.toString))
          )
          .ignore
      })(_ => {
        log(s"Do things in lock '${name}'") *>
        block
      }))
    }
  }
  val lock: lock = new lock
  class lock extends ScalaLock {
    val jrwlock = new java.util.concurrent.locks.ReentrantReadWriteLock(true)
    override def lock(): Unit = {
      println(s"lock info before get: ${jrwlock.toString}")
      println(s"Thread info: " + Thread.currentThread().toString)
      jrwlock.writeLock().lock
    }

    override def unlock(): Unit = {
      println(s"lock info before release: ${jrwlock.toString}")
      println(s"Thread info: " + Thread.currentThread().toString)
      jrwlock.writeLock().unlock()
      println(s"lock info after release: ${jrwlock.toString}")
    }

    override def name: String = "test-scala-lock"
  }

  def prog1(c: ZLayer[Any, Nothing, Any]): ZIO[Any, RudderError, Unit] = for {
    _ <- log("sem get 1")
    a <- lock(IOResult.attempt(println("Hello world 1")))
    _ <- log("sem get 2")
    b <- lock(IOResult.attempt({ println("sleeping now"); Thread.sleep(2000); println("after sleep: second hello") }))
    _ <- log("sem get 3")
    // at tham point, the semaphore is free because b is fully executed, so no timeout
    c <- lock(IOResult.attempt(println("third hello")))
           .timeout(Duration(5, java.util.concurrent.TimeUnit.MILLISECONDS))
           .provideLayer(c)
    _ <- c match {
           case None    => log("---- A timeout happened")
           case Some(y) => log("++++ No timeout")
         }
  } yield ()

  def main(args: Array[String]): Unit = {
    ZioRuntime.runNow(prog1(ZioRuntime.layers))
  }

}

/*
 * This test show that without a fork, execution is purely mono-fiber and sequential.
 */
object TestZioSemantic {
  val rt = ZioRuntime.internal
  trait LOG {
    def apply(s: String): UIO[Unit]
  }
  def makeLog: Task[LOG] = ZIO.attempt(new LOG {
    val zero = java.lang.System.currentTimeMillis()
    def apply(s: String) = ZIO.succeed(println(s"[${java.lang.System.currentTimeMillis() - zero}] $s"))
  })

  val semaphore:                           UIO[Semaphore]            = Semaphore.make(1)
  def prog1(c: ZLayer[Any, Nothing, Any]): ZIO[Any, Throwable, Unit] = for {
    sem <- semaphore
    log <- makeLog
    _   <- log("sem get 1")
    a   <- sem.withPermit(ZIO.attempt(println("Hello world 1")))
    _   <- log("sem get 2")
    b   <- sem.withPermit(ZIO.attempt({ println("sleeping now"); Thread.sleep(2000); println("after sleep: second hello") }))
    _   <- log("sem get 3")
    // at tham point, the semaphore is free because b is fully executed, so no timeout
    c   <- sem
             .withPermit(ZIO.attempt(println("third hello")))
             .timeout(Duration(5, java.util.concurrent.TimeUnit.MILLISECONDS))
             .provideLayer(c)
    _   <- c match {
             case None    => log("---- A timeout happened")
             case Some(y) => log("++++ No timeout")
           }
  } yield ()
  def prog2(c: ZLayer[Any, Nothing, Any]): ZIO[Any, Throwable, Unit] = for {
    sem <- semaphore
    log <- makeLog
    _   <- log("sem get 1")
    a   <- sem.withPermit(ZIO.attempt(println("Hello world 1"))).fork
    _   <- log("sem get 2")
    b   <- sem.withPermit(ZIO.attempt({ println("sleeping now"); Thread.sleep(2000); println("after sleep: second hello") })).fork
    _   <- log("sem get 3")
    c   <- sem
             .withPermit(ZIO.attempt(println("third hello")))
             .timeout(Duration(5, java.util.concurrent.TimeUnit.MILLISECONDS))
             .provideLayer(c)
             .fork
    _   <- a.join
    _   <- b.join
    x   <- c.join
    _   <- x match {
             case None    => log("---- A timeout happened")
             case Some(y) => log("++++ No timeout")
           }
  } yield ()

  def main(args: Array[String]): Unit = {
    Unsafe.unsafe(implicit unsafe => rt.unsafe.run(prog1(ZioRuntime.layers)).getOrThrowFiberFailure())
    println("****************")
    Unsafe.unsafe(implicit unsafe => rt.unsafe.run(prog2(ZioRuntime.layers)).getOrThrowFiberFailure())

    /* exec prints:

[2] sem get 1
Hello world sem
[50] sem get 2
sleeping now
after sleep: second hello
[2053] sem get 3
third hello
[2178] ++++ No timeout
 ****************
[2] sem get 1
Hello world sem
[6] sem get 2
sleeping now
[10] sem get 3
after sleep: second hello
[2015] ---- A timeout happened

Process finished with exit code 0

     */
  }

}

/*
 * Testing accumulation
 */
object TestAccumulate {
  import cats.implicits.*

  def main(args: Array[String]): Unit = {

    val l = List("ok", "ok", "booo", "ok", "plop")

    def f(s: String) = if (s == "ok") 1.succeed else Unexpected(s).fail

    val res = ZIO.foreach(l)(s => f(s).either)

    val res2 = for {
      ll <- res
    } yield {
      ll.traverse(_.toValidatedNel)
    }

    println(ZioRuntime.unsafeRun(res))
    println(ZioRuntime.unsafeRun(res2))
    println(ZioRuntime.unsafeRun(l.accumulate(f)))

  }

}

@nowarn // dead code / local val
object TestThrowError {

  def main(args: Array[String]): Unit = {

    case class BusinessError(msg: String)

    val prog1 = ZIO.attempt {
      val a = "plop"
      val b = throw new RuntimeException("foo bar bar")
      val c = "replop"
      a + c
    } mapError (e => BusinessError(e.getMessage))

    val prog2 = ZIO.attempt {
      val a = "plop"
      val b = throw new Error("I'm an java.lang.Error!")
      val c = "replop"
      a + c
    } mapError (e => BusinessError(e.getMessage))

    // println(ZioRuntime.unsafeRun(prog1))
    println(ZioRuntime.unsafeRun(prog2))

  }
}

object TestAsyncRun {
  /*
start prog
after async prog, wait
start long process...
... end
last line of main

Process finished with exit code 0
   */
  def main(args: Array[String]): Unit = {

    println("start prog")
    ZioRuntime.runNow(
      IOResult.attempt {
        println("start long process...")
        Thread.sleep(2000)
        println("... end")
      }.unit.forkDaemon
    )
    println("after async prog, wait")

    Thread.sleep(3000)
    println("last line of main")
  }
}

object CollectAllSemantic {

  def main(args: Array[String]): Unit = {

    val effects = (1 to 10).map(i => IOResult.attempt(println(s"hello $i"))).toList
    val all     = effects.take(5) ::: List(Unexpected("oups").fail) ::: effects.drop(5)

    // ZIO.collectAll(all).runNow // that fails after the 5th

    // ZIO.collectAll(all.map(_.run)).runNow that work all the way
    ZIO.collectAllPar(List(Unexpected("oups1").fail) ::: all).runNow
  }
}
