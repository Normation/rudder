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

package com.normation.license

import org.joda.time.DateTime
import java.nio.file.Files
import java.nio.charset.Charset
import scala.util.control.NonFatal
import java.nio.file.Paths
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import java.security.spec.X509EncodedKeySpec
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import java.security.KeyFactory
import org.joda.time.format.ISODateTimeFormat
import ca.mrvisser.sealerate.values
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.util.encoders.Hex
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.time.format.DateTimeFormatter
import org.joda.time.format.DateTimeFormat


/*
 * This file define services responsible to manage licenses in Rudder:
 * - check if a file is correctly signed
 * - create a signed license file
 *
 * It is not responsible for stoping something to run, or periodically check
 * the validity of a license. Only its consistency.
 */

/*
 * This is an example of a signed license file. There two parts:
 * - the one between "----" (excluded) is the signed part grouping all
 *   the license information.
 * - all the remaining part are meta-data. For now, all the meta-data
 *   are about signing: when signing happen, with which key, what is
 *   actually the signature.
 *
 * - min version is included
 * - max version is ALSO included. So one should issue licenses for
 *   a range like [3.0.0, 3.99.99], *NOT* [3.0.0, 4.0.0]
 *
 * The content exemple is below (with " * " at the begining of lines to
 * be removed and digest on multiline which is not in real files!
 *
 * header=rudder-license-v1
 * algorithm=SHA512withRSA
 * digest=5b2b184e560a6edb452931f391b54260f0e70517655502670d6600a0b0c42b227abc3
 * ef688544ae8f831b22213a7a3e184cae5a408c41375805428af3c21b543d017ccae55fa1b235
 * a2f750efbb629f58d01afd61572f98771cd11c90c6a9855ff500a8b4e6b56219f5d1c5febc46
 * 4613c43980e9f9f4fa4276754ef82b4d78cefcf599ab413cd562138f920071f4e3f81f132594
 * dc454ac37db97e95c6df491db225116c51d9378e43003847d9cdc1a9d2c19df20e748f0d89d8
 * 5a5d8a53e5e26909594c8528f22c8fe053782485b2996cc352c37ab4c1a18e6491c5105f2714
 * 6b09c61966a91dd421320c6d93580f502dca7a3c8a71b29b96985f611c2cbed
 * digestdate=2017-06-02T16:10:53+02:00
 * keyid=640F9347
 * ---- signed information about license
 * licensee=François ARMAND, NORMATION SAS
 * softwareid=rudder-plugin-datasources
 * minversion=1.0
 * maxversion=99.0
 * startdate=2017-06-01T00:00:00Z
 * enddate=2018-05-31T00:00:00Z
 * ----
 */


/**
 * A structure that allows to keep a field key name to
 * allow parsing/reading them (serialization is such a
 * joy to deal with!)
 */
final object LicenseField {
  sealed trait Key { def name: String ; override def toString() = name }

  sealed abstract class LicenseField[A](val key: Key) {
    def value: A
  }

  // license info
  sealed abstract class InformationKeys(val name: String) extends Key
  final object InformationKeys {
    final case object Licensee   extends InformationKeys("licensee")
    final case object SoftwareId extends InformationKeys("softwareid")
    final case object MinVersion extends InformationKeys("minversion")
    final case object MaxVersion extends InformationKeys("maxversion")
    final case object StartDate  extends InformationKeys("startdate")
    final case object EndDate    extends InformationKeys("enddate")

    def allValues: Set[InformationKeys] = values[InformationKeys]
  }

  final case class Licensee  (value: String  ) extends LicenseField[String]   (InformationKeys.Licensee)
  final case class SoftwareId(value: String  ) extends LicenseField[String]   (InformationKeys.SoftwareId)
  final case class MinVersion(value: Version ) extends LicenseField[Version]  (InformationKeys.MinVersion)
  final case class MaxVersion(value: Version ) extends LicenseField[Version]  (InformationKeys.MaxVersion)
  final case class StartDate (value: DateTime) extends LicenseField[DateTime] (InformationKeys.StartDate)
  final case class EndDate   (value: DateTime) extends LicenseField[DateTime] (InformationKeys.EndDate)

  // license signature
  sealed abstract class SignatureKeys(val name: String) extends Key
  final object SignatureKeys {
    final case object Algorithm  extends SignatureKeys("algorithm")
    final case object Digest     extends SignatureKeys("digest")
    final case object DigestDate extends SignatureKeys("digestdate")
    final case object KeyId      extends SignatureKeys("keyid")

    def allValues: Set[SignatureKeys] = values[SignatureKeys]
  }
  final case class Algorithm (value: String     ) extends LicenseField[String]      (SignatureKeys.Algorithm)
  final case class Digest    (value: Array[Byte]) extends LicenseField[Array[Byte]] (SignatureKeys.Digest)
  final case class DigestDate(value: DateTime   ) extends LicenseField[DateTime]    (SignatureKeys.DigestDate)
  final case class KeyId     (value: Array[Byte]) extends LicenseField[Array[Byte]] (SignatureKeys.KeyId)

}

/**
 * Data structure that old the thing to wich the license is applied.
 * The goal is to use it with a notion of software, so something that
 * has a version.
 * The license is issued to someone, identified by a string (issuer name),
 * for a limited period of time (always, even if that limit is 1000 years),
 * and to a limited number of version (idem: always limited, even if it's from
 * version 0 to 1000).
 */
final case class LicenseInformation(
    licensee  : LicenseField.Licensee
  , softwareId: LicenseField.SoftwareId
  , minVersion: LicenseField.MinVersion
  , maxVersion: LicenseField.MaxVersion
  , startDate : LicenseField.StartDate
  , endDate   : LicenseField.EndDate
)



final case class LicenseSignature(
    algorithm : LicenseField.Algorithm
  , digest    : LicenseField.Digest
  , digestDate: LicenseField.DigestDate
  , keyId     : LicenseField.KeyId
)

/**
 * A data-structure to hold a license file format context
 */
sealed trait LicenseFileFormat {
  def header        : String
  def startSeparator: String
  def endSeparator  : String
}

final case object LicenseFileFormatV1 extends LicenseFileFormat {
  val header         : String = "header=rudder-license-v1"
  val separatorPrefix: String = "----"
  val startSeparator : String = separatorPrefix + " signed information about the license"
  val endSeparator   : String = separatorPrefix
}


/**
 * A (parsed) license object.
 * We have a fileFormat to be able to produce back the license file,
 * parsed signature meta-data, parsed license actual content information,
 * and the raw signed part (which must be isomorphe to license content
 * but is kept to ease tracability and debuging, without having to deals
 * with plateform encoding or other subtilities).
 */
sealed trait RudderLicense {
  def fileFormat      : LicenseFileFormat
  def signature       : LicenseSignature
  def content         : LicenseInformation
  def rawSignedContent: String
}

final object RudderLicense {


  /*
   * A license file group a format, signature, and signed content
   * for which the signature was not yet checked
   */
  final case class Unchecked(
      fileFormat      : LicenseFileFormat
    , signature       : LicenseSignature
    , content         : LicenseInformation
    , rawSignedContent: String
  ) extends RudderLicense {
    def toValid   = ValidSignature  (fileFormat, signature, content, rawSignedContent)
    def toInvalid = InvalidSignature(fileFormat, signature, content, rawSignedContent)
  }

  sealed trait CheckedLicense extends RudderLicense

  /**
   * A licence file for which the signature was checked and is valid.
   */
  final case class ValidSignature(
      fileFormat      : LicenseFileFormat
    , signature       : LicenseSignature
    , content         : LicenseInformation
    , rawSignedContent: String
  ) extends CheckedLicense

  final case class InvalidSignature(
      fileFormat      : LicenseFileFormat
    , signature       : LicenseSignature
    , content         : LicenseInformation
    , rawSignedContent: String
  ) extends CheckedLicense
}

sealed trait LicenseError {
  def msg: String
}

/*
 * Reason for which a license may be invalid
 */
sealed trait InvalidityCause
final case object InvalidityCause {
  final case object BadSignature extends InvalidityCause
  final case class BadSoftwareVersion(current: Version) extends InvalidityCause
  final case class BadDateTime(current: DateTime) extends InvalidityCause
}

final object LicenseError {
  final case class IO       (msg: String)                                                 extends LicenseError
  final case class Parsing  (msg: String)                                                 extends LicenseError
  final case class Crypto   (msg: String)                                                 extends LicenseError
  final case class Signature(msg: String, license: RudderLicense.InvalidSignature)        extends LicenseError
  final case class Validity (msg: String, cause: InvalidityCause, license: RudderLicense) extends LicenseError
}

/**
 * Just a little bit of syntax to be able to tell "...: Maybe[MyType]" in
 * place of Either[LicenseError, MyType].
 * And write { ... } withError { ex => LicenseError.Crypto(s"my error is ${ex.getMessage}) }
 * in place of try { Right(...) } catch { case NonFatal(ex) => Left(...) }
 */
final object MaybeLicenseError {

  type Maybe[A] = Either[LicenseError, A]

  //syntax with either
  final implicit class rightMaybe[A](steps: =>A) {
    def apply() = Right(steps)
    def withError(error: Throwable => LicenseError): Maybe[A] = {
      try { Right(steps) } catch {
        case NonFatal(ex) => Left(error(ex))
      }
    }
  }

  //syntax with either
  final implicit class eitherMaybe[A](steps: =>Either[LicenseError, A]) {
    def apply() = steps
    def withError(error: Throwable => LicenseError): Maybe[A] = {
      try { steps } catch {
        case NonFatal(ex) => Left(error(ex))
      }
    }
  }
}

/*
 * An helper object that holds ISO date reader/writer
 */
final object ISODate {
  import MaybeLicenseError._

  val pattern = "yyyy-MM-dd'T'HH:mm:ssZZ" // because no way to get back from formatter to pattern...
  val formatter = DateTimeFormat.forPattern(pattern)

  def write(date: DateTime) = date.toString(formatter)
  def read(date: String) = {
    formatter.parseDateTime(date)
  } withError { ex =>
    LicenseError.Parsing(s"Provided date/time string '${date}' is not an ISO 8601 format (expected: ${pattern}). Message is: ${ex.getMessage}")
  }
}


final object LicenseReader {
  import MaybeLicenseError._

  /*
   * Utility method that parses given lines into a list of
   * key/value map.
   * Correct lines are formatted as: key=value,
   * where key is only alphaNum (word) chars, no space around '=',
   * and value is anything until end of line.
   * All invalid lines are ignored.
   * Always succeed, but the resulting map may be empty.
   */
  def parseKeyValues(lines: List[String]): Map[String, String] = {
    val regex = """^(\w+)=(.*)$""".r

    lines.collect { case regex(key, value) => (key, value) }.toMap
  }

  //read (naively) the whole license file as an ordered list of UTF-8 string (lines).
  def readFile(path: String): Maybe[List[String]] = {
    {
      Files.readAllLines(Paths.get(path), Charset.forName("UTF-8")).toArray(Array[String]()).toList
    } withError { ex =>
      LicenseError.IO(s"Can not read license file frome '${path}': ${ex.getMessage}")
    }
  }

  /**
   * Parse a string representing the license file content (by line) into
   * a license. Check that it's well formed, that all fields are present,
   * and create corresponding data object.
   */
  def parseLicense(licenseFileContent: List[String]): Maybe[RudderLicense.Unchecked] = {
    //shortcut
    def error(msg: String) = Left(LicenseError.Parsing(msg))
    val v1 = LicenseFileFormatV1



    //only support header version 1 for now
    licenseFileContent match {
      case v1.header :: content =>
        val sep = licenseFileContent.collect { case line if(line.startsWith(v1.separatorPrefix)) => line }
        if(sep.size == 2) {
          val predicate = (line: String) => !line.startsWith(v1.separatorPrefix)
          //now split in two parts: metadata and license info
          val metadata1 = licenseFileContent.takeWhile( predicate )
          val metadata2 = licenseFileContent.dropWhile( predicate ).drop(1).dropWhile( predicate ).drop(1)
          val metadata = parseKeyValues(metadata1 ::: metadata2)

          val contentLines = licenseFileContent.dropWhile( predicate ).drop(1).takeWhile( predicate )

          // license mandatory fields
          val missingFields = LicenseField.SignatureKeys.allValues.filterNot(k => metadata.keySet.contains(k.name))

          if(missingFields.nonEmpty) {
            error(s"The license file is missing mandatory fields: ${missingFields.map(_.name).mkString(", ")}")
          } else {
            //we know that all the fields are their, so we can use themap("key") directly
            import LicenseField._

            def decodeHex(s: String) = { Hex.decode(s.getBytes("UTF-8")) } withError { ex =>
              LicenseError.Parsing(s"Following value can't be decoded as a Base64 string. Error message is: '${ex.getMessage}': ${s}")
            }

            for {
              digestDate  <- ISODate.read(metadata(SignatureKeys.DigestDate.name))
              digest      <- decodeHex(metadata(SignatureKeys.Digest.name))
              keyId       <- decodeHex(metadata(SignatureKeys.KeyId.name))
              couple      <- readLicenseInformation(contentLines) //because of withFilter scalac pb like https://github.com/scalaz/scalaz/issues/968
              (info, raw) = couple
            } yield {
              val signature = LicenseSignature(
                  Algorithm(metadata(SignatureKeys.Algorithm.name))
                , Digest(digest)
                , DigestDate(digestDate)
                , KeyId(keyId)
              )

              RudderLicense.Unchecked(
                  LicenseFileFormatV1
                , signature
                , info
                , raw
              )
            }
          }
        } else {
          error(s"The license file must contains exactly two lines starting with '${v1.separatorPrefix}'")
        }

      case _ => error(s"The license file must start with header line: ${v1.header}")
    }
  }


  /*
   * Utility method to read a list of lines and parse them as the
   * content of the license information.
   * That part does not check for other metadata presence and thus
   * can be used to parse an input license info file for signature
   * OR a the information part of a signed license file.
   *
   * It returns both the LicenseInformation object PLUS the "raw content",
   * because the latter may contains more information than the former.
   * In rawContent, we don't want to change number of lines, order,
   * or anything else from which depends the signature, and thus any
   * cleaning/filtering operation must be done before that point
   * (if they make sense).
   */
  def readLicenseInformation(contentLines: List[String]): Maybe[(LicenseInformation, String)] = {
    //utility to parse a version with the expected format
    def parseVersion(version: String) = Version.from(version) match {
      case Some(v) => Right(v)
      case None    => Left(LicenseError.Parsing(s"Error: '${version}' can't be parsed as a software version"))
    }

    val rawContent = contentLines.mkString("\n")
    val content = parseKeyValues(contentLines)

    // license mandatory fields
    val missingFields = LicenseField.InformationKeys.allValues.filterNot(k => content.keySet.contains(k.name))

    if(missingFields.nonEmpty) {
      Left(LicenseError.Parsing(s"The license file is missing mandatory fields: ${missingFields.map(_.name).mkString(", ")}"))
    } else {
      //we know that all the fields are their, so we can use themap("key") directly
      import LicenseField._

      for {
        startDate  <- ISODate.read(content(InformationKeys.StartDate.name))
        endDate    <- ISODate.read(content(InformationKeys.EndDate.name))
        minVersion <- parseVersion(content(InformationKeys.MinVersion.name))
        maxVersion <- parseVersion(content(InformationKeys.MaxVersion.name))
      } yield {
        val info = LicenseInformation(
            Licensee(content(InformationKeys.Licensee.name))
          , SoftwareId(content(InformationKeys.SoftwareId.name))
          , MinVersion(minVersion)
          , MaxVersion(maxVersion)
          , StartDate(startDate)
          , EndDate(endDate)
        )
        // return the couple
        (info, rawContent)
      }
    }
  }




  /*
   * Read a signed license from a file and check format (but not signature)
   */
  def readLicense(licensePath: String): Maybe[RudderLicense.Unchecked] = {
    for {
      fileContent <- readFile(licensePath)
      license     <- parseLicense(fileContent)
    } yield {
      license
    }
  }

}

/**
 * Read public/private RSA key
 */
final object RSAKeyManagement {
  import MaybeLicenseError._

  //bouncy castle provider
  Security.addProvider(new BouncyCastleProvider())

  /**
   * the two methods are highly similar but differ just sufficiently
   * (SubjectPublicKeyInfo/PrivateKeyInfo, keyFactory.generatePublic/generatePrivate...)
   * to be more work to factorize than duplicate :(
   */

  def readPKCS8PublicKey(path: String): Maybe[RSAPublicKey] = {
    for {
                    // the parser able to read PEM files
                    // Parser may be null if the key is invalid
      parser     <- {
                      val p = new PEMParser(Files.newBufferedReader(Paths.get(path)))
                      if(null == p) {
                        Left(LicenseError.Crypto(s"Error when trying to decode the public key at ${path}': the key may be invalid. Expecting PKCS8 format."))
                      } else {
                        Right(p)
                      }
                    } withError { ex =>
                      LicenseError.Crypto(s"Error when trying to read the public key at '${path}': ${ex.getMessage}")
                    }
                    // read the PEM b64 pubkey string
      pubkeyInfo <- {
                      parser.readObject.asInstanceOf[SubjectPublicKeyInfo]
                    } withError {ex =>
                      LicenseError.Crypto(s"Error when trying to read the public key subject info for key '${path}'. The key may be invalid (expecting PKCS8 format): ${ex.getMessage}")
                    }
                    // check that the pubkey info is the one of an RSA key,
                    // retrieve corresponding Key factory.
      keyFactory <- pubkeyInfo.getAlgorithm.getAlgorithm match {
                      case PKCSObjectIdentifiers.rsaEncryption =>
                        Right(KeyFactory.getInstance("RSA"))
                      case algo => //not supported
                        Left(LicenseError.Crypto(s"The CFEngine public key used an unsupported algorithm '${algo}'. Only RSA is supported"))
                    }
      rsaPubkey  <- {
                      // actually decode the key...
                      val keyspec = new X509EncodedKeySpec(pubkeyInfo.getEncoded)
                      // into an RSA public key.
                      Right(keyFactory.generatePublic(keyspec).asInstanceOf[RSAPublicKey])
                    } withError { ex =>
                      LicenseError.Crypto(s"Error when trying to read the public key subject info for key '${path}'. The key may be invalid (expecting PKCS8 format): ${ex.getMessage}")
                    }
    } yield {
      rsaPubkey
    }
  }

  def readPKCS8PrivateKey(path: String): Maybe[RSAPrivateKey] = {
    for {
                     // the parser able to read PEM files
                     // Parser may be null if the key is invalid
      parser      <- {
                       val p = new PEMParser(Files.newBufferedReader(Paths.get(path)))
                       if(null == p) {
                         Left(LicenseError.Crypto(s"Error when trying to decode the private key at ${path}': the key may be invalid (expecting PKCS8 format)"))
                       } else {
                         Right(p)
                       }
                     } withError { ex =>
                       LicenseError.Crypto(s"Error when trying to read the private key at '${path}'. The key may be invalid (expecting PKCS8 format): ${ex.getMessage}")
                     }
                     // read the PEM b64 pubkey string
      privkeyInfo <- {
                       parser.readObject.asInstanceOf[PrivateKeyInfo]
                     } withError {ex =>
                       LicenseError.Crypto(s"Error when trying to read the private key info for key '${path}'. The key may be invalid (expecting PKCS8 format): ${ex.getMessage}")
                     }
                     // check that the pubkey info is the one of an RSA key,
                     // retrieve corresponding Key factory.
      keyFactory <-  privkeyInfo.getPrivateKeyAlgorithm.getAlgorithm match {
                       case PKCSObjectIdentifiers.rsaEncryption =>
                         Right(KeyFactory.getInstance("RSA"))
                       case algo => //not supported
                         Left(LicenseError.Crypto(s"The private key used an unsupported algorithm '${algo}'. Only RSA is supported"))
                     }
      rsaPrivkey <-  {
                         val keyspec = new PKCS8EncodedKeySpec(privkeyInfo.getEncoded)
                         keyFactory.generatePrivate(keyspec).asInstanceOf[RSAPrivateKey]
                     } withError { ex =>
                       LicenseError.Crypto(s"Error when trying to read the private key '${path}' from info: ${ex.getMessage}")
                     }
    } yield {
      rsaPrivkey
    }
  }
}

/**
 * Sign and write the license file
 */
final object LicenseSigner {
  import MaybeLicenseError._

  /**
   * Sign the given license information with the given private key and algorithm.
   */
  def sign(contentLines: List[String], privKey: RSAPrivateKey, algorithm: String): Maybe[RudderLicense.ValidSignature] = {


    for {
      couple      <- LicenseReader.readLicenseInformation(contentLines) //because of withFilter scalac pb like https://github.com/scalaz/scalaz/issues/968
      (info, raw) =  couple
                     //actually sign. Group crypto errors in only one business error
      signed      <- {
                       val engine = Signature.getInstance(algorithm)
                       engine.initSign(privKey)
                       val data = raw.getBytes("UTF-8")
                       engine.update(data)
                       val digest = engine.sign()
                       val digestDate = DateTime.now()
                       // last 4 byte of the modulus
                       val modulus = privKey.getModulus.toByteArray()
                       val keyId = modulus.slice(modulus.size - 4, modulus.size)

                       //no exception - return a valid signature object
                       import LicenseField._
                       RudderLicense.ValidSignature(
                           LicenseFileFormatV1
                         , LicenseSignature(Algorithm(algorithm), Digest(digest), DigestDate(digestDate), KeyId(keyId))
                         , info
                         , raw
                       )

                     } withError { ex =>
                       ex.printStackTrace()
                       LicenseError.Crypto(s"Error when trying to sign license information. Error was: ${ex.getMessage}")
                     }
    } yield {
      signed
    }
  }

  /**
   * We can write any kind of license, but it may not be very smart to not write valide license file.
   */
  def writeLicenseFile(path: String, license: RudderLicense): Maybe[Unit] = {
    // base64 encode to an utf-8 string
    def encodeHex(bytes: Array[Byte]) =   new String(Hex.encode(bytes), "UTF-8")
    // only errors here are I/O error, not very interesting to discriminate them: group everything in a try/catch
    // digest and keyId are base64 encoded
    val toWrite = s"""${license.fileFormat.header}
        |algorithm=${license.signature.algorithm.value}
        |digest=${encodeHex(license.signature.digest.value)}
        |digestdate=${ISODate.write(license.signature.digestDate.value)}
        |keyid=${encodeHex(license.signature.keyId.value)}
        |${license.fileFormat.startSeparator}
        |${license.rawSignedContent}
        |${license.fileFormat.endSeparator}
        |""".stripMargin

    {
      Files.write(Paths.get(path), toWrite.getBytes("UTF-8"))
      ()
    } withError { ex =>
      LicenseError.IO(s"Error when trying to write signed license file at '${path}': ${ex.getMessage}")
    }
  }

}



final object LicenseChecker {
  import MaybeLicenseError._

  /**
   * Check the given signature with given public key.
   * The public key must be compatible with the signature algorithme.
   */
  def checkSignature(license: RudderLicense.Unchecked, pubKey: RSAPublicKey): Maybe[RudderLicense.ValidSignature] = {
    for {
      engine <- {
                  Signature.getInstance(license.signature.algorithm.value)
                } withError { ex =>
                  LicenseError.Crypto(s"Error when initializing signature engine: ${ex.getMessage}")
                }
      valid  <- {
                  engine.initVerify(pubKey)
                  engine.update(license.rawSignedContent.getBytes)
                  if(engine.verify(license.signature.digest.value)) { // ok !
                    Right(license.toValid)
                  } else {
                    Left(LicenseError.Signature("The signature of the license is not valid", license.toInvalid))
                  }
                } withError { ex =>
                  LicenseError.Crypto(s"Error when initializing signature engine: ${ex.getMessage}")
                }
    } yield {
      valid
    }
  }

  /**
   * Check if a license is valid. To be valid, a license must:
   * - have a verified signed content,
   * - be used at an allowed date/time,
   * - be used for a correct version of the software.
   */
  def checkLicense(license: RudderLicense.CheckedLicense, currentDate: DateTime, softwareVersion: Version): Maybe[RudderLicense.ValidSignature] = {
    license match {
      case invalid: RudderLicense.InvalidSignature => Left(LicenseError.Validity("The license integrity can not be proved (invalid signature)", InvalidityCause.BadSignature, invalid))
      case valid  : RudderLicense.ValidSignature   =>
        if(currentDate.isAfter(license.content.startDate.value) && currentDate.isBefore(license.content.endDate.value)) {
          if ((softwareVersion >= license.content.minVersion.value) && (softwareVersion <= license.content.maxVersion.value) ) {
            Right(valid)
          } else {
            Left(LicenseError.Validity(
                s"The current software version '${softwareVersion}' is not in the ranged of allowed versions [${license.content.maxVersion},${license.content.maxVersion}]"
              , InvalidityCause.BadSoftwareVersion(softwareVersion), license)
            )
          }
        } else {
          Left(LicenseError.Validity(
              s"The current date and time '${ISODate.write(currentDate)}' is not in the ranged of allowed date "+
              s"[${ISODate.write(license.content.startDate.value)},${ISODate.write(license.content.endDate.value)}]"
            , InvalidityCause.BadDateTime(currentDate), license)
          )
        }
    }
  }
}


