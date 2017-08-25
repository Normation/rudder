/*
*************************************************************************************
* Copyright 2017 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.license.cli

import org.specs2.runner.JUnitRunner
import com.normation.license.ISODate
import com.normation.license.LicenseError
import com.normation.license.MaybeLicenseError._
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Paths
import java.security.Key
import java.security.KeyPairGenerator
import java.security.Security
import org.apache.commons.io.FileUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.matcher.ContentMatchers
import org.specs2.mutable.Specification
import org.specs2.specification.AfterAll

@RunWith(classOf[JUnitRunner])
class TemplateCliTest extends Specification with ContentMatchers with AfterAll {

  Security.addProvider(new BouncyCastleProvider())
  sequential


  val testDir = new File("/tmp/test-rudder-license-cli/" + DateTime.now.toString(ISODate.formatter))
  testDir.mkdirs()

  override def afterAll(): Unit = {
    if(System.getProperty("tests.clean.tmp") != "false") {
      FileUtils.deleteDirectory(testDir)
    }
  }

  implicit class DieOnLeft[A](maybe: Maybe[A]) {
    def orDie = maybe match {
      case Right(_) => // nothing
      case Left(err) => throw new RuntimeException(s"Error: ${err}")
    }
  }

  //generate 2 couples of rsa keys
  val keyGen = KeyPairGenerator.getInstance("RSA")
  keyGen.initialize(1024) //512 is quicker for test, and this is for test only
  generateKeyPair(keyGen, dir("pair1")).orDie
  generateKeyPair(keyGen, dir("pair2")).orDie

  "The main programm" should {
    "correctly sign a license file and read it back" in {

      val info =
        """licensee=François ARMAND, NORMATION SAS
          |softwareid=rudder-plugin-datasources
          |minversion=1.0
          |maxversion=99.0
          |startdate=2017-06-01T00:00:00Z
          |enddate=2018-05-31T00:00:00Z""".stripMargin

      Files.write(Paths.get(dir("license.info")), info.getBytes("UTF-8"))

      (
        LicenseCli.process(Array(
            "sign"
          , "-k", dir("pair1/private.pem")
  //        , "-k", "/tmp/test-rudder-license-cli/default/private.pem"
          , "-o", dir("license.sign")
          , dir("license.info")
        )
      ) must beRight) and (
        LicenseCli.process(Array(
            "check"
          , "-k", dir("pair1/public.pem")
          , dir("license.sign")
        )
      ) must beRight)
    }

    "correctly report an error when checking with an other key" in {
      LicenseCli.process(Array(
            "check"
          , "-k", dir("pair2/public.pem")
          , dir("license.sign")
        )
      ) must beLeft
    }
  }

  ////////////////////////
  //////////////////////// utilities ////////////////////////
  ////////////////////////

  def dir(path: String) = (new File(testDir, path)).getAbsolutePath

  // keys will be named private.pem / public.pem in directory keyDirs
  def generateKeyPair(keyGen: KeyPairGenerator, keyDir: String) = {
    def writePem(key: Key, header: String, destination: File): Maybe[Unit] = {
      {
        val pemObject = new PemObject(header, key.getEncoded())
        val pemWriter = new PemWriter(new OutputStreamWriter(new FileOutputStream(destination)))
        pemWriter.writeObject(pemObject)
        pemWriter.close()
      } withError { ex =>
        LicenseError.IO(s"Error when trying to create a RSA key pair: ${ex.getMessage}")
      }
    }

    for {
      dir     <- { (new File(keyDir)).mkdirs } withError { ex => LicenseError.IO(s"Error when trying to create a RSA key pair: ${ex.getMessage}") }
      keyPair <- { keyGen.generateKeyPair() } withError { ex => LicenseError.IO(s"Error when trying to create a RSA key pair: ${ex.getMessage}") }
      priv    =  keyPair.getPrivate()
      pub     =  keyPair.getPublic()
      // write in PKCS8, so "PRIVATE KEY", *not* "RSA PRIVATE KEY"
      privOk  <- writePem(priv, "PRIVATE KEY", new File(keyDir, "private.pem"))
      pubOk   <- writePem(pub, "PUBLIC KEY", new File(keyDir, "public.pem"))
    } yield {
      "ok"
    }
  }


}
