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

import java.net.URL

import com.github.ghik.silencer.silent
import com.normation.errors.IOResult
import com.normation.errors.RudderError
import com.normation.errors._
import com.normation.zio.ZioRuntime
import com.normation.zio._
import net.liftweb.common._
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import scalaz.zio._
import scalaz.zio.blocking.Blocking
import scalaz.zio.clock.Clock
import scalaz.zio.duration.Duration
import scalaz.zio.syntax._

@RunWith(classOf[JUnitRunner])
class ZioCommonsTest extends Specification {


  "When we use toIO, we should ensure that evaluation is only done at run" >> {
    def write(sb: StringBuffer, what: String): Unit = {
      sb.append(what)
    }

    def produceBox(sb: StringBuffer): Box[Int] = {
      write(sb, "In the method body\n")
      Full({write(sb, "in the full\n") ; 42})
    }

    val sb = new StringBuffer()

    write(sb, "before io\n")
    val io = produceBox(sb).toIO
    write(sb, "after io\n")

    write(sb, "*** run io\n")
    io.runNow
    write(sb, "*** done\n")

    sb.toString must beEqualTo(
      """before io
        |after io
        |*** run io
        |In the method body
        |in the full
        |*** done
        |""".stripMargin)
  }


}


object TestImplicits {
  import scalaz.zio._
  import scalaz.zio.syntax._

  object module1 {
    import com.normation.errors._
    sealed trait M_1_Error extends RudderError
    object M_1_Error {
      final case class Some(msg: String) extends M_1_Error
      final case class Chained[E <: RudderError](hint: String, cause: E) extends M_1_Error with BaseChainError[E]
    }

    object M_1_Result {
    }

    object service1 {
      def doStuff(param: String): IOResult[String] = param.succeed
    }
  }

  object module2 {
    import com.normation.errors._
    sealed trait M_2_Error extends RudderError
    object M_2_Error {
      final case class Some(msg: String) extends M_2_Error
      final case class Chained[E <: RudderError](hint: String, cause: E) extends M_2_Error with BaseChainError[E]
    }
    object M_2_Result {
    }

    final case class TestError(msg: String) extends RudderError

    object service2 {
      def doStuff(param: Int): IOResult[Int] = TestError("ah ah ah I'm failing").fail
    }
  }

  object testModule {
    import com.normation.errors._
    import module1._
    import module2._
    sealed trait M_3_Error extends RudderError
    object M_3_Error {
      final case class Some(msg: String) extends M_3_Error
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

      def trace(msg: => AnyRef): UIO[Unit] = IOResult.effectRunUnit(println(msg))

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

      def prog = test2("plop", 42) catchAll( err => trace(err.fullMsg) *> ("success", 0).succeed)
    }
  }


  import testModule.service.prog
  def main(args: Array[String]): Unit = {
    println(ZioRuntime.unsafeRun(prog))
  }
}


object TestSream {

  val log = NamedZioLogger("test-logger")


  def main(args: Array[String]): Unit = {
    val prog =
      log.error("wouhou") *>
      ZIO.bracket(Task.effect{
      val checkRelativePath = "file:///tmp/plop.txt"
      val url = new URL(checkRelativePath)
      url.openStream()
    })(is =>
      Task.effect(is.close).run // here, if I put `UIO.unit`, I can have the content
    )(is =>
      Task.effect(println(new String(is.readAllBytes(), "utf-8") ))
    ) <* log.warn("some plop plop")
    ZioRuntime.unsafeRun(prog)
    // A checked error was not handled:
    //  java.base/java.io.BufferedInputStream.getBufIfOpen(BufferedInputStream.java:176)
    // IOException("Stream closed");
  }

}

object TestLog {

  val log = NamedZioLogger("test-logger")

  def main(args: Array[String]): Unit = {


    def oups: IO[String, Int] = "oups error".fail

    val prog = oups.catchAll(e => log.error("I got an error!") *> e.fail) *> UIO.succeed(42)

    ZioRuntime.unsafeRun(prog)
  }
}


object TestSemaphore {

  val log = NamedZioLogger("test-logger")
  trait ScalaLock {
    def apply[T](block: IOResult[T]): ZIO[Any with Clock, RudderError, T]
  }
  def pureZioSemaphore(name: String) : ScalaLock = new ScalaLock {
    val semaphore = Semaphore.make(1)
    override def apply[T](block: IOResult[T]): ZIO[Any with Clock, RudderError, T] = {
      ZIO.accessM[Clock]( c =>
      for {
        _    <- log.logPure.error(s"*****zio** getting semaphore for lock '${name}'")
       sem   <- semaphore
        _    <- log.logPure.error(s"*****zio** wait for lock '${name}'")
       exec  <- sem.withPermit(block).timeout(Duration(5, java.util.concurrent.TimeUnit.MILLISECONDS)).provide(c).fork
       res   <- exec.join
        _    <- log.logPure.error(s"*****zio** done lock '${name}'")
       xxx   <- res match {
         case Some(x) => x.succeed
         case None    => Unexpected(s"Error: semaphore '${name}' timeout on section").fail
       }
      } yield (xxx)
      )
    }
  }

  val semaphore = Semaphore.make(1)
  def inSem(c: Clock) = for {
    _   <- log.logPure.error("before sem")
    sem <- semaphore
    _   <- log.logPure.error("sem get")
    a   <- sem.withPermit(IOResult.effect(println("Hello world sem"))).fork
    b   <- sem.withPermit(IOResult.effect({println("sleeping now"); Thread.sleep(2000); println("after sleep")})).fork
    c   <- sem.withPermit(IOResult.effect(println("third hello"))).timeout(Duration(5, java.util.concurrent.TimeUnit.MILLISECONDS)).provide(c).fork
    _   <- a.join
    _   <- b.join
    x   <- c.join
    _   <- x match {
             case None => log.error("Oh noes! Timeout!")
             case Some(y) => log.error("yes! No Timeout! Or is it a noes?")
           }
    _   <- log.logPure.error("after sem")
  } yield ()

  val lock = pureZioSemaphore("plop")

  def inLock = for {
    _   <- log.logPure.error("lock get")
    _   <- lock(IOResult.effect(println("Hello world lock")))
    _   <- lock(IOResult.effect({println("sleeping now"); Thread.sleep(2000); println("after sleep")}))
    x   <- lock(IOResult.effect(println("third hello"))).uninterruptible
    _   <- log.logPure.error("after lock")
  } yield ()

  def main(args: Array[String]): Unit = {
    //ZioRuntime.runNow(inSem(ZioRuntime.Environment))
    //println("****************")
    ZioRuntime.runNow(inLock.provide(ZioRuntime.Environment))
  }

}

/*
 * This show that even if we lock a ZIO execution in a given threadpool, that does not
 * work well with Java lock because we don't have a garantee that the same thread will
 * do the first and second part of the bracket.
 *
 */
object TestJavaLockWithZio {
  def log(s : String) = UIO(println(s))

  trait ScalaLock {
    def lock(): Unit
    def unlock(): Unit
    def clock: Clock
    def blocking: Blocking

    def name: String

    def apply[T](block: => IOResult[T]): IOResult[T] = {
      println(s"***** calling lock '${name}'")
      scalaz.zio.blocking.blockingExecutor.flatMap(executor =>
      ZIO.bracket(
        log(s"Get lock '${name}'") *>
//        log(Thread.currentThread().getStackTrace.mkString("\n")) *>
        IO.effect(this.lock()).lock(executor).timeout(Duration.Finite(100*1000*1000 /* ns */))
          .mapError(ex => SystemError(s"Error when trying to get LDAP lock", ex))
      )(_ =>
        log(s"Release lock '${name}'") *>
        IO.effect(this.unlock()).lock(executor).catchAll(t => log(s"${t.getClass.getName}:${t.getMessage}") *> ZIO.foreach(t.getStackTrace) { s => log(s.toString) }).unit
      )(_ =>
        log(s"Do things in lock '${name}'") *>
        block
      ).provide(clock).lock(executor)).provide(blocking)
    }
  }
  val lock = new ScalaLock {
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

    override def clock: Clock = ZioRuntime.Environment
    override def blocking: Blocking = ZioRuntime.Environment
    override def name: String = "test-scala-lock"
  }

  def prog1(c: Clock) = for {
    _   <- log("sem get 1")
    a   <- lock(IOResult.effect(println("Hello world 1")))
    _   <- log("sem get 2")
    b   <- lock(IOResult.effect({println("sleeping now"); Thread.sleep(2000); println("after sleep: second hello")}))
    _   <- log("sem get 3")
           // at tham point, the semaphore is free because b is fully executed, so no timeout
    c   <- lock(IOResult.effect(println("third hello"))).timeout(Duration(5, java.util.concurrent.TimeUnit.MILLISECONDS)).provide(c)
    _   <- c match {
             case None => log("---- A timeout happened")
             case Some(y) => log("++++ No timeout")
           }
  } yield ()

  def main(args: Array[String]): Unit = {
    ZioRuntime.runNow(prog1(ZioRuntime.Environment))
  }

}

/*
 * This test show that without a fork, execution is purely mono-fiber and sequential.
 */
object TestZioSemantic {
  val rt = new DefaultRuntime {}
  trait LOG {
    def apply(s: String): UIO[Unit]
  }
  def makeLog = UIO(new LOG {
    val zero = System.currentTimeMillis()
    def apply(s : String) = UIO(println(s"[${System.currentTimeMillis()-zero}] $s"))
  })

  val semaphore = Semaphore.make(1)
  def prog1(c: Clock) = for {
    sem <- semaphore
    log <- makeLog
    _   <- log("sem get 1")
    a   <- sem.withPermit(IO.effect(println("Hello world 1")))
    _   <- log("sem get 2")
    b   <- sem.withPermit(IO.effect({println("sleeping now"); Thread.sleep(2000); println("after sleep: second hello")}))
    _   <- log("sem get 3")
           // at tham point, the semaphore is free because b is fully executed, so no timeout
    c   <- sem.withPermit(IO.effect(println("third hello"))).timeout(Duration(5, java.util.concurrent.TimeUnit.MILLISECONDS)).provide(c)
    _   <- c match {
             case None => log("---- A timeout happened")
             case Some(y) => log("++++ No timeout")
           }
  } yield ()
  def prog2(c: Clock) = for {
    sem <- semaphore
    log <- makeLog
    _   <- log("sem get 1")
    a   <- sem.withPermit(IO.effect(println("Hello world 1"))).fork
    _   <- log("sem get 2")
    b   <- sem.withPermit(IO.effect({println("sleeping now"); Thread.sleep(2000); println("after sleep: second hello")})).fork
    _   <- log("sem get 3")
    c   <- sem.withPermit(IO.effect(println("third hello"))).timeout(Duration(5, java.util.concurrent.TimeUnit.MILLISECONDS)).provide(c).fork
    _   <- a.join
    _   <- b.join
    x   <- c.join
    _   <- x match {
             case None => log("---- A timeout happened")
             case Some(y) => log("++++ No timeout")
           }
  } yield ()


  def main(args: Array[String]): Unit = {
    rt.unsafeRunSync(prog1(rt.Environment))
    println("****************")
    rt.unsafeRunSync(prog2(rt.Environment))

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
  import cats.implicits._

  def main(args: Array[String]): Unit = {

    val l = List("ok", "ok", "booo", "ok", "plop")

    def f(s: String) = if(s == "ok") 1.succeed else Unexpected(s).fail

    val res = IO.foreach(l) { s => f(s).either }

    val res2 = for {
      ll <- res
    } yield {
      ll.traverse( _.toValidatedNel)
    }

    println(ZioRuntime.unsafeRun(res))
    println(ZioRuntime.unsafeRun(res2))
    println(ZioRuntime.unsafeRun(l.accumulate(f)))

  }

}

@silent // dead code / local val
object TestThrowError {

  def main(args: Array[String]): Unit = {

    case class BusinessError(msg: String)

    val prog1 = Task.effect {
      val a = "plop"
      val b = throw new RuntimeException("foo bar bar")
      val c = "replop"
      a + c
    } mapError(e => BusinessError(e.getMessage))

    val prog2 = Task.effect {
      val a = "plop"
      val b = throw new Error("I'm an java.lang.Error!")
      val c = "replop"
      a + c
    } mapError(e => BusinessError(e.getMessage))

    //println(ZioRuntime.unsafeRun(prog1))
    println(ZioRuntime.unsafeRun(prog2))

  }

}
