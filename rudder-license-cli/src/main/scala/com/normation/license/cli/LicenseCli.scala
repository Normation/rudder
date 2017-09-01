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

import scopt.OptionParser
import ca.mrvisser.sealerate.values
import com.normation.license.MaybeLicenseError._
import com.normation.license.LicenseSigner
import com.normation.license.LicenseReader
import com.normation.license.RudderLicense
import com.normation.license.RSAKeyManagement
import com.normation.license.LicenseChecker
import com.normation.license.LicenseError

/**
 * The configuration object for our CLI.
 * The basic process is to take one file in input for the definition of variables, one set of files as template to change.
 *
 * By default, the files are generated with the same name in the current folder.
 *
 * - add the possibility to take directories as input (but a good shell can do it, so not very important)
 */


// there is no good way to define a command as the sum of sub commands
// see: https://github.com/scopt/scopt/issues/134
// we will use product + convention ("convention" == "do the right things with the chainsaw")

final case class CheckConfig(
    pubkeyPath     : String = "rudder.pub"
  , licenseInPath  : String = "license.in"
  , algorithm      : String = "SHA512WithRSA"
)

final case class SignConfig(
    privkeyPath    : String = "rudder.pub"
  , licenseOutPath : String = "license.sign"
  , licenseInPath  : String = "license.in"
  , algorithm      : String = "SHA512WithRSA"
)

sealed trait CliMode
final object CliMode {
  final case object Check extends CliMode
  final case object Sign  extends CliMode

  val all = values[CliMode]
}

final case class Config(
    mode       : CliMode     = CliMode.Check
  , checkConfig: CheckConfig = CheckConfig()
  , signConfig : SignConfig  = SignConfig()
)

object LicenseCli {

  //read version

  val checkParser = new OptionParser[CheckConfig]("Check a Rudder related license") {
    head("rudder-license-cli-check", "...")

    opt[String]("key").abbr("k").action( (x, c) =>
      c.copy(pubkeyPath = x) ).text("path toward public key to use to check signature")
    arg[String]("input license file").action( (x, c) =>
      c.copy(licenseInPath = x) ).text("path of license file to check.")

  }

  val signParser = new OptionParser[SignConfig]("Sign a Rudder related license") {
    head("rudder-license-cli-check", "...")
    opt[String]("key").abbr("k").action( (x, c) =>
      c.copy(privkeyPath = x) ).text("path toward private key to use for signature")
    opt[String]("output").abbr("o").action( (x, c) =>
      c.copy(licenseOutPath = x) ).text("file name for the signed license")
    arg[String]("input license file").action( (x, c) =>
      c.copy(licenseInPath = x) ).text("path of license file to sign")
  }

  val parser = new OptionParser[Config]("Rudder License Manager") {
    head("rudder-license-cli", "4.1.x")

    def cfgSign(c: Config, f: SignConfig => SignConfig): Config = {
      c.copy(signConfig = f(c.signConfig))
    }
    def cfgCheck(c: Config, f: CheckConfig => CheckConfig): Config = {
      c.copy(checkConfig = f(c.checkConfig))
    }

    cmd("sign").action( (_, c) => c.copy(mode = CliMode.Sign) ).
      text("Write a new signed license file from the input license information.").
      children(
          opt[String]("key").abbr("k").action( (x, c) =>
            cfgSign(c, _.copy(privkeyPath = x)) ).text("path toward private key to use for signature")
        , opt[String]("output").abbr("o").action( (x, c) =>
            cfgSign(c, _.copy(licenseOutPath = x)) ).text("file name for the signed license")
        , arg[String]("input license file").action( (x, c) =>
            cfgSign(c, _.copy(licenseInPath = x)) ).text("path of license file to sign")
    )

    cmd("check").action( (_, c) => c.copy(mode = CliMode.Check) ).
      text("Check that a license file signature is correct with the given public key.").
      children(
          opt[String]("key").abbr("k").action( (x, c) =>
            cfgCheck(c, _.copy(pubkeyPath = x) )).text("path toward public key to use to check signature")
        , arg[String]("input license file").action( (x, c) =>
            cfgCheck(c, _.copy(licenseInPath = x) )).text("path of license file to check.")
    )

    help("help") text("prints this usage text")

  }

  def main(args: Array[String]): Unit = {
    process(args) match {
      case Left(err) =>
        System.err.println(s"Error when executing command: ${err}")
        System.exit(1)

      case Right(res) =>
        System.exit(0)
    }
  }


  /**
   * An utility method so that I can actually test things,
   * because you know, maven doesn't allow to have exit(1)
   * anywhere: http://maven.apache.org/surefire/maven-surefire-plugin/faq.html#vm-termination
   * """Surefire does not support tests or any referenced libraries calling System.exit() at any time."""
   */
  def process(args: Array[String]) = {
    for {
      config <- parser.parse(args, Config()) match {
                  case None         => Left(LicenseError.IO("Bad command parameters. Usage:\n" + parser.usage))
                  case Some(config) => Right(config)
                }
      done    <- config match {
                   case Config(CliMode.Sign, _, SignConfig(privkeyPath, licenseOutPath, licenseInPath, algorithm)) =>
                     sign(licenseInPath, privkeyPath, algorithm, licenseOutPath)

                   case Config(CliMode.Check, CheckConfig(pubkeyPath, licenseInPath, algorithm), _) =>
                     check(licenseInPath, pubkeyPath, algorithm)

                 }
    } yield {
      done
    }
  }


  /**
   * Sign logic
   */
  def sign(licenseInPah: String, privateKeyPath: String, algorithm: String, licenseOutPath: String): Maybe[RudderLicense.ValidSignature] = {
    for {
      lines      <- LicenseReader.readFile(licenseInPah)
      privateKey <- RSAKeyManagement.readPKCS8PrivateKey(privateKeyPath)
      signed     <- LicenseSigner.sign(lines, privateKey, algorithm)
      written    <- LicenseSigner.writeLicenseFile(licenseOutPath, signed)
    } yield {
      signed
    }
  }

  /*
   * check logic
   */
  def check(licenseInPath: String, publicKeyPath: String, algorithm: String): Maybe[RudderLicense.ValidSignature] = {
    for {
      unchecked <- LicenseReader.readLicense(licenseInPath)
      publicKey <- RSAKeyManagement.readPKCS8PublicKey(publicKeyPath)
      checked   <- LicenseChecker.checkSignature(unchecked, publicKey)
    } yield {
      checked
    }
  }
}
