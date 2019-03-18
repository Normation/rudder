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

package com.normation.history.impl

import org.junit._
import org.junit.Assert._
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import org.joda.time.DateTime
import org.apache.commons.io.FileUtils
import java.io.File

import com.normation.errors.RudderError
import com.normation.inventory.domain.InventoryResult._
import com.normation.zio.ZioRuntime
import scalaz.zio._
import scalaz.zio.syntax._

final case class SystemError(cause: Throwable) extends RudderError {
  def msg = "Error in test"
}

object StringMarshaller extends FileMarshalling[String] {
  //simply read / write file content
  override def fromFile(in:File) : InventoryResult[String] = IO.effect(FileUtils.readFileToString(in,"UTF-8")).mapError(SystemError)
  override def toFile(out:File, data: String) : InventoryResult[String] = IO.effect {
    FileUtils.writeStringToFile(out,data, "UTF-8")
    data
  }.mapError(SystemError)
}

object StringId extends IdToFilenameConverter[String] {
  override def idToFilename(id:String) : String = id
  override def filenameToId(name:String) : String = name
}

import TestFileHistoryLogRepository._

@RunWith(classOf[BlockJUnit4ClassRunner])
class TestFileHistoryLogRepository {

  val repos = new FileHistoryLogRepository(rootDir, StringMarshaller,StringId)

  implicit class RunThing[R,E,T](thing: ZIO[Any,E,T]) {
    def run = ZioRuntime.unsafeRun(thing.either)
  }

  @Test def basicTest: Unit = {
    val id1 = "data1"
    assertEquals(Right(List()), repos.getIds.map(_.toList).run)
    assertEquals(_:Left[RudderError, Any], repos.versions(id1).run)

    val data1 = "Some data 1\nwith multiple lines"

    //save first revision
    val data1time1 = DateTime.now()
    assertEquals(Right(DefaultHLog(id1, data1time1,data1)), repos.save(id1, data1, data1time1).run)

    //now we have exaclty one id, with one revision, equals to data1time1
    assertEquals(Right(List(id1)), repos.getIds.map(_.toList).run)
    assertEquals(Right(List(data1time1)), repos.versions(id1).map(_.toList).run)

    //save second revision
    val data1time2 = DateTime.now()
    assertEquals(Right(DefaultHLog(id1, data1time2, data1)), repos.save(id1, data1, data1time2).run)

    //now we have exaclty one id1, with two revisions, and head is data1time2
    assertEquals(Right(List(id1)), repos.getIds.map(_.toList).run)
    assertEquals(Right(data1time2 :: data1time1 :: Nil), repos.versions(id1).map(_.toList).run)

  }
}

object TestFileHistoryLogRepository {
  val rootDir = System.getProperty("java.io.tmpdir") + "/testFileHistoryLogRepo"

  def clean: Unit = {
    FileUtils.deleteDirectory(new File(rootDir))
  }

  @BeforeClass def before = clean
  @AfterClass def after = clean

}
