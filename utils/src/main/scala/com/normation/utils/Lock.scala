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


package com.normation.utils
import java.util.concurrent.locks.ReadWriteLock

/**
 * Pimp^Wextend my Java Lock
 */
trait ScalaLock {
  def lock(): Unit
  def unlock(): Unit

  def map[T](f: this.type => T): T = {
    this.lock()
    try {
      f(this)
    } finally {
      this.unlock()
    }
  }

  def flatMap[T](f : this.type => Option[T]) : Option[T] = map(f)

  def foreach(f: this.type => Unit): Unit = map(f)

  def apply[T](block: => T): T = map(_ => block)
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
  }

  implicit def java2ScalaRWLock(lock:ReadWriteLock) : ScalaReadWriteLock = new ScalaReadWriteLock {
    override def readLock = lock.readLock
    override def writeLock = lock.writeLock
  }

}

