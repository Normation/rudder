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

import com.normation.NamedZioLogger
import com.normation.errors._
import com.normation.zio.ZioRuntime
import zio.clock.Clock
import com.normation.zio._
import zio.stm.TReentrantLock

object LdapLockLogger extends NamedZioLogger {
  override def loggerName: String = "ldap-rw-lock"
}

trait ScalaReadWriteLock {
  def readLock  : ScalaLock
  def writeLock : ScalaLock

}

trait ScalaLock {
  def clock: Clock

  def name: String

  def apply[T](block: => IOResult[T]): IOResult[T]
}


class ZioTReentrantLock(name: String) extends ScalaReadWriteLock {
  parent =>
  val lock = TReentrantLock.make.commit.runNow

  override def readLock: ScalaLock = new ScalaLock {
    override def clock: Clock = ZioRuntime.environment
    override val name: String = parent.name
    override def apply[T](block: => IOResult[T]): IOResult[T] = {
      println("readlock " + name)
      lock.readLock.use(_ => block)

    }
  }

  override def writeLock: ScalaLock = new ScalaLock {
    override val clock: Clock = ZioRuntime.environment
    override val name: String = parent.name
    override def apply[T](block: => IOResult[T]): IOResult[T] = {
      println("Writelock  " + name)
      lock.writeLock.use(_ => block)
    }
  }
}

