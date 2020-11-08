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
import zio.clock.Clock

import zio._

object LdapLockLogger extends NamedZioLogger {
  override def loggerName: String = "ldap-rw-lock"
}

trait ScalaReadWriteLock {
  def readLock  : ScalaLock
  def writeLock : ScalaLock

}

trait ScalaLock {
  def lock(): Unit
  def unlock(): Unit
  def clock: Clock

  def name: String

  def apply[T](block: => IOResult[T]): IOResult[T]
}



object ScalaLock {
  import java.util.concurrent.locks.Lock

  protected def pureZioSemaphore(n: String, _not_used: Any) : ScalaLock = new ScalaLock {
    // we set a timeout here to avoid deadlock. We prefer to identify them with errors
    val semaphore = Semaphore.make(1)
    override def lock(): Unit = ()
    override def unlock(): Unit = ()
    override def clock: Clock = ZioRuntime.environment
    override val name: String = n
    override def apply[T](block: => IOResult[T]): IOResult[T] = {
      for {
        sem <- semaphore
               //here, we would like to have a timeout on the lock aquisition time, but not on the whole
               //block execution time. See https://issues.rudder.io/issues/16839
        res <- sem.withPermit(block)
      } yield (res)
    }
  }

  protected def noopLock(n: String, javaLock: Lock) : ScalaLock = new ScalaLock {
    override def lock(): Unit = ()
    override def unlock(): Unit = ()
    override def clock: Clock = ZioRuntime.environment
    override def name: String = n
    override def apply[T](block: => IOResult[T]): IOResult[T] = block
  }

  def java2ScalaRWLock(name: String, lock: ReadWriteLock) : ScalaReadWriteLock = new ScalaReadWriteLock {
    override def readLock = noopLock(name, lock.readLock)
    // for now, even setting a simple semaphore lead to dead lock (most likelly because not re-entrant)
    override def writeLock = pureZioSemaphore(name, lock.writeLock)
  }

}

