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

import java.io.File

import _root_.zio.duration._
import com.normation.box._
import com.normation.errors.IOResult
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.services.policies.TestNodeConfiguration
import com.normation.zio._
import net.liftweb.actor.LAScheduler
import _root_.zio._

// -Dplease.use.only.one.core=true
object TestAccumulate {
  def main(args: Array[String]): Unit = {

    println("here is: " + new File(".").getAbsolutePath)

    val data = new TestNodeConfiguration("webapp/sources/rudder/rudder-core/")

    def prog(s: String) = IOResult.effect{println(s); Thread.sleep(1*1000)}.forever.forkDaemon.unit


    val pid = new java.io.File("/proc/self").getCanonicalFile().getName()
    println(s"Test starts with PID ${pid} on ${java.lang.Runtime.getRuntime().availableProcessors()} cores")
    println("start")

    LAScheduler.execute(() => data.techniqueRepository.update(ModificationId("plop"), EventActor("plop"), None))
   // on ZIO blocking threadpool
    (1 to 10).foreach(i => prog(s"zio $i").runNow)
    // on Lift threadpool
    (1 to 18).foreach(i => LAScheduler.execute(() => prog (s"lift $i").toBox))
    (1 to 5).foreach(i => IOResult.effect(prog(s"mixed $i").toBox).runNow)


    val wait = 40.seconds

    println(s"now wait ${wait.render} on main thread")
    Thread.sleep(wait.toMillis)
    println("done")
    java.lang.Runtime.getRuntime.exit(0)
  }
}


object ThatDoesNotBlock {
  def main(args: Array[String]): Unit = {

    def prog(s: String) = IOResult.effect{println(s); Thread.sleep(2*1000)}.forever.forkDaemon.unit

    val pid = new java.io.File("/proc/self").getCanonicalFile().getName()
    println(s"Test starts with PID ${pid} on ${java.lang.Runtime.getRuntime().availableProcessors()} cores")
    println("start")
    // on Lift threadpool
    (1 to 18).foreach(i => LAScheduler.execute(() => prog (s"lift $i").toBox))
   // on ZIO blocking threadpool
    (1 to 10).foreach(i => prog(s"zio $i").runNow)
    (1 to 5).foreach(i => IOResult.effect(prog(s"mixed $i").toBox).runNow)


    val wait = 40.seconds

    println(s"now wait ${wait.render} on main thread")
    Thread.sleep(wait.toMillis)
    println("done")
    java.lang.Runtime.getRuntime.exit(0)
  }
}

object SemaphoreReentrant {
  def main(args: Array[String]): Unit = {

    val sem = Semaphore.make(1).runNow

    val prog1 = sem.withPermit(IOResult.effect(println("prog1")))
    val prog2 = sem.withPermit(IOResult.effectM{
      for {
        _ <- IOResult.effect(println("in prog2"))
        _ <- prog1
      } yield ()
    })

    println("start")
    prog2.runNow
    println("done")
    java.lang.Runtime.getRuntime.exit(0)
  }
}
