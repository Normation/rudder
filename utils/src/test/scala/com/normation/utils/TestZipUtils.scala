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

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.matcher._
import org.specs2.runner.JUnitRunner
import net.liftweb.common.Loggable
import java.io.File
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import java.util.zip.ZipFile
import net.liftweb.common._
import ZipUtils.Zippable
import scala.collection.JavaConversions._
import java.util.zip.ZipEntry
import java.io.FileOutputStream

@RunWith(classOf[JUnitRunner])
class TestZipUtils extends Specification with Loggable {

  // prepare data
  val directory = new File(System.getProperty("java.io.tmpdir"), "test-zip/"+ DateTime.now.toString(ISODateTimeFormat.dateTime))

  directory.mkdirs()

  logger.debug("Unzipping test in directory: " + directory.getPath())

  val zip = new ZipFile(new File("src/test/resources/test.zip"))


  "When we dezip the test file 'test.zip', we" should {
    //intersting files
    val root = new File(directory, "some-dir")
    val foo = new File(root, "foo.txt")
    val bar = new File(root, "bar.txt")
    val a_sub = new File(root, "a-subdirectory")
    val b_sub = new File(root, "b-subdirectory")
    val a_pdf = new File(b_sub, "a-file.pdf")
    val b_pdf = new File(b_sub, "b-file.pdf")

    "correctly execute" in {
      ZipUtils.unzip(zip, directory) match {
        case Full(_) => success
        case eb:EmptyBox =>
          val e = (eb ?~! s"Error when unzipping file into '${directory.getPath}'")
          logger.debug(e)
          failure(e.messageChain)
      }
    }
    "have a directory named some-dir" in {
      root.exists() must beTrue
    }

    "...which contains the file 'foo.txt'" in {
      foo.exists() must beTrue
    }
    "...which contains the file 'bar.txt'" in {
      bar.exists() must beTrue
    }

    "...whith size of 24kB" in {
      foo.length must beEqualTo(23L)
    }
    "have a subdirectory named 'a-subdirectory'" in {
      (a_sub.exists must beTrue) and (a_sub.isDirectory must beTrue)
    }
    "have a subdirectory named 'b-subdirectory'" in {
      (b_sub.exists must beTrue) and (b_sub.isDirectory must beTrue)
    }
    "have a binary file named 'a-file.pdf'" in {
      a_pdf.exists must beTrue
    }
    "have a binary file named 'b-file.pdf'" in {
      b_pdf.exists must beTrue
    }
    "... with size of 8442" in {
      a_pdf.length must beEqualTo(8442L)
    }
  }

  "Building zippable from a directory" should {
    ZipUtils.unzip(zip, directory)
    val zippable = ZipUtils.toZippable(new File(directory, "some-dir"))

    "create a list whith size of all elements" in {
      zippable must haveSize(7)
    }

    "create the list of names in order" in {
      zippable.toList match {
        case Zippable("some-dir/", _) ::
             Zippable("some-dir/bar.txt", _) ::
             Zippable("some-dir/foo.txt", _) ::
             Zippable("some-dir/a-subdirectory/", _) ::
             Zippable("some-dir/b-subdirectory/", _) ::
             Zippable("some-dir/b-subdirectory/a-file.pdf", _) ::
             Zippable("some-dir/b-subdirectory/b-file.pdf", _) ::
             Nil => success
        case _ => failure("Bad content : " + zippable)
      }
    }
  }

  "When we zip a file, it" should {
    ZipUtils.unzip(zip, directory)
    val zippable = ZipUtils.toZippable(new File(directory, "some-dir"))
    val dest = new File(directory, "test2.zip")
    ZipUtils.zip(new FileOutputStream(dest), zippable)
    val zip2 = new ZipFile(dest)

    "have 7 entries" in {
      zip2.size must beEqualTo(7)
    }

    "with names 'some-dir/', 'some-dir/bar.txt', 'some-dir/foo.txt', 'some-dir/a-subdirectory/'\n,"+
    "'some-dir/b-subdirectory/', 'some-dir/b-subdirectory/a-file.pdf, 'some-dir/b-subdirectory/b-file.pdf'" in {
      val l = zip2.entries.toList
      l.map( _.getName ) must haveTheSameElementsAs(List("some-dir/", "some-dir/bar.txt", "some-dir/foo.txt", "some-dir/a-subdirectory/", "some-dir/b-subdirectory/", "some-dir/b-subdirectory/a-file.pdf", "some-dir/b-subdirectory/b-file.pdf"))
    }

    "'some-dir/b-subdirectory/a-file.pdf' has size 8442" in {
      zip2.getEntry("some-dir/b-subdirectory/a-file.pdf").getSize must beEqualTo(8442L)
    }

    "'some-dir/foo.pdf' has size 23" in {
      zip2.getEntry("some-dir/foo.txt").getSize must beEqualTo(23L)
    }
  }

}