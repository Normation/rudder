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
/**
 * Pimp^Wextend my Java Lock
 */
trait ScalaLock {
  def lock(): Unit
  def unlock(): Unit
  def clock: Clock

  def apply[T](name: String)(block: => IOResult[T]): IOResult[T] = ZIO.bracket(
    LdapLockLogger.logPure.error("Get lock") *>
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

trait ScalaReadWriteLock {

  def readLock  : ScalaLock
  def writeLock : ScalaLock

}

object ScalaLock {
  import java.util.concurrent.locks.Lock
  import language.implicitConversions


  implicit def java2ScalaLock(javaLock:Lock) : ScalaLock = new ScalaLock {
    override def lock() = javaLock.lock()
    override def unlock() = javaLock.unlock()
    override def clock = ZioRuntime.Environment
  }

  implicit def java2ScalaRWLock(lock:ReadWriteLock) : ScalaReadWriteLock = new ScalaReadWriteLock {
    override def readLock = lock.readLock
    override def writeLock = lock.writeLock
  }

}

