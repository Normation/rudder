/*
*************************************************************************************
* Copyright 2011 Normation SAS
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


package com.normation.rudder.repository.ldap

import java.util.concurrent.locks.ReadWriteLock

import com.normation.NamedZioLogger
import com.normation.errors._
import com.normation.zio.ZioRuntime
import scalaz.zio._
import scalaz.zio.clock.Clock
import scalaz.zio.duration.Duration

import scalaz.zio._
import scalaz.zio.syntax._

object LdapLockLogger extends NamedZioLogger {
  override def loggerName: String = "ldap-rw-lock"
}

trait ScalaReadWriteLock {
  def readLock  : ScalaLock
  def writeLock : ScalaLock

}

/**
 * Pimp^Wextend my Java Lock
 */
trait ScalaLock {
  def lock(): Unit
  def unlock(): Unit
  def clock: Clock

  def name: String

  def apply[T](block: => IOResult[T]): IOResult[T] = {
    println(s"***** calling lock '${name}'")
    ZIO.bracket(
      LdapLockLogger.logPure.error(s"Get lock '${name}'") *>
      LdapLockLogger.logPure.error(Thread.currentThread().getStackTrace.mkString("\n")) *>
      IO.effect(this.lock()).timeout(Duration.Finite(100*1000*1000 /* ns */))
        .mapError(ex => SystemError(s"Error when trying to get LDAP lock", ex))
    )(_ =>
      LdapLockLogger.logPure.error("Release lock") *>
      IO.effect(this.unlock()).run.void
    )(_ =>
      LdapLockLogger.logPure.error("Do things in lock") *>
      block
    ).provide(clock)
  }
}



object ScalaLock {
  import java.util.concurrent.locks.Lock
  import language.implicitConversions


  protected def java2ScalaLock(n: String, javaLock: Lock) : ScalaLock = new ScalaLock {
    override def lock() = javaLock.lock()
    override def unlock() = javaLock.unlock()
    override def clock = ZioRuntime.Environment
    override def name: String = name
  }

  protected def pureZioSemaphore(n: String, _not_used: Any) : ScalaLock = new ScalaLock {
    import scala.concurrent.duration.{Duration => _, _}
    // we set a timeout here to avoid deadlock. We prefer to identify them with errors
    val timeout = 5.seconds
    val semaphore = Semaphore.make(1)
    override def lock(): Unit = ()
    override def unlock(): Unit = ()
    override def clock: Clock = ZioRuntime.Environment
    override val name: String = n
    override def apply[T](block: => IOResult[T]): IOResult[T] = {
      for {
        sem  <- semaphore
        exec <- sem.withPermit(block).timeout(Duration.fromScala(timeout))
        res  <- exec match {
                  case None    => SystemError(s"Semaphore '${name}' acquisition timed out after ${timeout.toString()}", new RuntimeException("Time out")).fail
                  case Some(x) => x.succeed
                }
      } yield (res)
    }
  }

  protected def noopLock(n: String, javaLock: Lock) : ScalaLock = new ScalaLock {
    val semaphore = Semaphore.make(1)
    override def lock(): Unit = ()
    override def unlock(): Unit = ()
    override def clock: Clock = ZioRuntime.Environment
    override def name: String = name
    override def apply[T](block: => IOResult[T]): IOResult[T] = block
  }

  def java2ScalaRWLock(name: String, lock: ReadWriteLock) : ScalaReadWriteLock = new ScalaReadWriteLock {
    override def readLock = noopLock(name, lock.readLock)
    // for now, even setting a simple semaphore lead to dead lock (most likelly because not re-entrant)
    override def writeLock = pureZioSemaphore(name, lock.writeLock)
  }

}

