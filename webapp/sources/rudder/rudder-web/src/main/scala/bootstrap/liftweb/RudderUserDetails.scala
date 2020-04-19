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

package bootstrap.liftweb

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collection

import com.normation.rudder._
import com.normation.rudder.api._
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.domain.logger.PluginLogger
import com.normation.rudder.rest.RoleApiMapping
import org.bouncycastle.util.encoders.Hex
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.xml.sax.SAXParseException

import scala.jdk.CollectionConverters._
import scala.xml.Elem

/**
 * This file contains data structure defining Rudder "user details" and how to
 * build them from "rudder-users.xml" file.
 */

// data structure to handle errors related to users file
final case class UserConfigFileError(msg: String, exception: Option[Throwable])

// Our user file can come from either classpath of filesystem. That class abstract that fact.
final case class UserFile(
    name: String
  , inputStream: () => InputStream
)

//Password encoder type definition. Done like that to avoid
//a deprecation warning on `PasswordEncoder` thanks to @silent annotation
object PasswordEncoder {
  type Rudder = org.springframework.security.crypto.password.PasswordEncoder

  import org.bouncycastle.crypto.generators.OpenBSDBCrypt
  import org.springframework.security.crypto.password.PasswordEncoder

  val random = new SecureRandom()

  class DigestEncoder(digestName: String) extends PasswordEncoder {
    override def encode(rawPassword: CharSequence): String = {
      val digest = MessageDigest.getInstance(digestName)
      new String(Hex.encode(digest.digest(rawPassword.toString.getBytes(StandardCharsets.UTF_8))), StandardCharsets.UTF_8)
    }
    override def matches(rawPassword: CharSequence, encodedPassword: String): Boolean = {
      if(null == rawPassword) {
        false
      } else {
        encode(rawPassword) == encodedPassword
      }
    }
  }

  val PlainText = new PasswordEncoder() {
    override def encode(rawPassword: CharSequence): String = rawPassword.toString
    override def matches(rawPassword: CharSequence, encodedPassword: String): Boolean = rawPassword.toString == encodedPassword
  }
  // Unsalted hash functions :
  val MD5    = new DigestEncoder("MD5"    )
  val SHA1   = new DigestEncoder("SHA-1"  )
  val SHA256 = new DigestEncoder("SHA-256")
  val SHA512 = new DigestEncoder("SHA-512")
  // Salted hash functions :
  val BCRYPT = new PasswordEncoder() {
    override def encode(rawPassword: CharSequence): String = {
      val salt: Array[Byte] = new Array(16)
      random.nextBytes(salt)

      // The version of bcrypt used is "2b". See https://en.wikipedia.org/wiki/Bcrypt#Versioning_history
      // It prevents the length (unsigned char) of a long password to overflow and wrap at 256. (Cf https://marc.info/?l=openbsd-misc&m=139320023202696)
      OpenBSDBCrypt.generate("2b", rawPassword.toString.toCharArray, salt, RudderConfig.RUDDER_BCRYPT_COST)
    }
    override def matches(rawPassword: CharSequence, encodedPassword: String): Boolean = {
      try {
        OpenBSDBCrypt.checkPassword(encodedPassword, rawPassword.toString.toCharArray)
      } catch {
        case e: Exception =>
          ApplicationLogger.error(s"Invalid password format: ${e.getMessage}")
          false
      }
    }
  }
}

/**
 * An user list is a parsed list of users with their authorisation
 */
final case class UserDetailList(
    encoder    : PasswordEncoder.Rudder
  , users      : Map[String, RudderUserDetail]
)

object UserDetailList {
  def fromRudderAccount(roleApiMapping: RoleApiMapping, encoder: PasswordEncoder.Rudder, users: List[(RudderAccount.User,Seq[Role])]): UserDetailList = {
    new UserDetailList(encoder, users.map { case (user, roles) =>
      (user.login, RudderUserDetail(user, roles.toSet, ApiAuthorization.ACL(roleApiMapping.getApiAclFromRoles(roles))))
    }.toMap)
  }
}


/**
 * This is the class that defines the user management level.
 * Without the plugin, by default only "admin" role is know.
 * A user with an unknow role has no rights.
 */
trait UserAuthorisationLevel {
  def userAuthEnabled: Boolean
  def name: String
}

// and default implementation is: no
class DefaultUserAuthorisationLevel() extends UserAuthorisationLevel {
  // Alternative level provider
  private[this] var level: Option[UserAuthorisationLevel] = None

  def overrideLevel(l: UserAuthorisationLevel): Unit = {
    PluginLogger.info(s"Update User Authorisations level to '${l.name}'")
    level = Some(l)
  }
  override def userAuthEnabled: Boolean = level.map( _.userAuthEnabled ).getOrElse(false)

  override def name: String = level.map( _.name ).getOrElse("Default implementation (only 'admin' right)")
}

trait UserDetailListProvider {
  def authConfig: UserDetailList
}

final class FileUserDetailListProvider(roleApiMapping: RoleApiMapping, authorisationLevel: UserAuthorisationLevel, file: UserFile) extends UserDetailListProvider {

  /**
   * Initialize user details list when class is instantiated with an empty list.
   * You will have to "reload" after application full init (to allows plugin override)
   */
  private[this] var cache = UserDetailList(PasswordEncoder.PlainText, Map())

  /**
   * Callbacks for who need to be informed of a successufully users list reload
   */
  private[this] var callbacks = List.empty[UserDetailList => Unit]

  /**
   * Reload the list of users. Only update the cache if there is no errors.
   */
  def reload(): Either[UserConfigFileError, Unit] = {
    UserFileProcessing.parseUsers(roleApiMapping, file, authorisationLevel.userAuthEnabled) match {
      case Right(config) =>
        cache = config
        // callbacks
        callbacks.foreach { cb =>
          cb(config)
        }
        Right(())
      case Left(err) => Left(err)
    }
  }

  def registerCallback(cb: UserDetailList => Unit): Unit = {
    callbacks = callbacks :+ cb
  }

  override def authConfig: UserDetailList = cache
}


/**
 * We don't use at all Spring Authority to implements
 * our authorizations.
 * That because we want something more typed than String for
 * authority, and as a bonus, that allows to be able to switch
 * from Spring more easily
 *
 * So we have one Authority type known by Spring Security for
 * authenticated user: ROLE_USER
 * And one other for API accounts: ROLE_REMOTE
 */
sealed trait RudderAuthType {
  def grantedAuthorities: Collection[GrantedAuthority]
}

final object RudderAuthType {
  // build a GrantedAuthority from the string
  private def buildAuthority(s: String): Collection[GrantedAuthority] = {
    Seq(new GrantedAuthority { override def getAuthority: String = s }).asJavaCollection
  }

  final case object User extends RudderAuthType {
    override val grantedAuthorities = buildAuthority("ROLE_USER")
  }
  final case object Api extends RudderAuthType {
    override val grantedAuthorities = buildAuthority("ROLE_REMOTE")

    val apiRudderRights = new Rights(AuthorizationType.NoRights)
    val apiRudderRole: Set[Role] = Set(Role.NoRights)
  }
}

/**
 * Our simple model for for user authentication and authorizations.
 * Note that authorizations are not managed by spring, but by the
 * 'authz' token of RudderUserDetail.
 */
final case class RudderUserDetail(
    account : RudderAccount
  , roles   : Set[Role]
  , apiAuthz: ApiAuthorization
) extends UserDetails {
  // merge roles rights
  val authz = new Rights(roles.flatMap(_.rights.authorizationTypes).toSeq:_*)
  override val (getUsername, getPassword, getAuthorities) = account match {
    case RudderAccount.User(login, password) => (login         , password       , RudderAuthType.User.grantedAuthorities)
    case RudderAccount.Api(api)              => (api.name.value, api.token.value, RudderAuthType.Api.grantedAuthorities)
  }
  override val isAccountNonExpired        = true
  override val isAccountNonLocked         = true
  override val isCredentialsNonExpired    = true
  override val isEnabled                  = true
}


object UserFileProcessing {

  val JVM_AUTH_FILE_KEY = "rudder.authFile"
  val DEFAULT_AUTH_FILE_NAME = "demo-rudder-users.xml"

  def getUserResourceFile() : Either[UserConfigFileError, UserFile] = {
    System.getProperty(JVM_AUTH_FILE_KEY) match {
      case null | "" => //use default location in classpath
        ApplicationLogger.info(s"JVM property -D${JVM_AUTH_FILE_KEY} is not defined, using configuration file '${DEFAULT_AUTH_FILE_NAME}' in classpath")
        Right(UserFile("classpath:" + DEFAULT_AUTH_FILE_NAME, () => this.getClass().getClassLoader.getResourceAsStream(DEFAULT_AUTH_FILE_NAME)))
      case x => //so, it should be a full path, check it
        val config = new File(x)
        if(config.exists && config.canRead) {
          ApplicationLogger.info(s"Using configuration file defined by JVM property -D${JVM_AUTH_FILE_KEY} : ${config.getPath}")
          Right(UserFile(config.getAbsolutePath, () => new FileInputStream(config)))
        } else {
          ApplicationLogger.error(s"Can not find configuration file specified by JVM property ${JVM_AUTH_FILE_KEY}: ${config.getPath}; aborting")
          Left(UserConfigFileError(s"rudder-users configuration file not found at path: '${config.getPath}'", None))
        }
    }
  }

  def parseUsers(roleApiMapping: RoleApiMapping, resource: UserFile, extendedAuthz: Boolean) : Either[UserConfigFileError, UserDetailList] = {
    val optXml = {
      try {
        Right(scala.xml.XML.load(resource.inputStream()))
      } catch {
        case e : SAXParseException =>
          Left(UserConfigFileError(s"User definitions: XML in file /opt/rudder/etc/rudder-users.xml is incorrect, error message is: ${e.getMessage()} (line ${e.getLineNumber()}, column ${e.getColumnNumber()})", Some(e)))
        case e: Exception =>
          Left(UserConfigFileError("User definitions: An error occured while parsing /opt/rudder/etc/rudder-users.xml. Logging in to the Rudder web interface will not be possible until this is fixed and the application restarted.", Some(e)))
      }
    }

    for {
      xml <- optXml
      res <- parseXml(roleApiMapping, xml, resource.name, extendedAuthz)
    } yield {
      res
    }
  }

  /**
   * Parse the given input stream toward a Rudder XML user file. The expected format is:
   * <authentication hash="sha512">
   *  <user name="admin" password="sha512 of pass" role="administrator" />
   * </authentication>
   *
   * Password can be let empty (but not omitted) in case of the use of a different authentication backend.
   *
   * "resourceName' is the resource name used to get the input stream.
   */
  def parseXml(roleApiMapping: RoleApiMapping, xml: Elem, debugFileName: String, extendedAuthz: Boolean): Either[UserConfigFileError, UserDetailList] = {
    //what password hashing algo to use ?
    val root = (xml \\ "authentication")
    if(root.size != 1) {
      Left(UserConfigFileError("Authentication file is malformed, the root tag '<authentication>' was not found", None))
    } else {
      val hash = (root(0) \ "@hash").text.toLowerCase match {
        case "sha"    | "sha1"    => PasswordEncoder.SHA1
        case "sha256" | "sha-256" => PasswordEncoder.SHA256
        case "sha512" | "sha-512" => PasswordEncoder.SHA512
        case "md5"                => PasswordEncoder.MD5
        case "bcrypt"             => PasswordEncoder.BCRYPT
        case _                    => PasswordEncoder.PlainText
      }

      //now, get users
      val users = ( (xml \ "user").toList.flatMap { node =>
       //for each node, check attribute name (mandatory), password  (mandatory) and role (optional)
       (   node.attribute("name").map(_.toList.map(_.text))
         , node.attribute("password").map(_.toList.map(_.text))
         , node.attribute("role").map(_.toList.map( role => RoleToRights.parseRole(role.text.split(",").toSeq.map(_.trim))))
       ) match {
         case (Some(name :: Nil) , Some(pwd :: Nil), roles ) if(name.size > 0) =>
           val r = roles match {
             case Some(Nil) => // 'role' attribute is optionnal, by default get "administator" role
               Seq(Role.Administrator)

             case Some(roles:: Nil) =>
               if(!extendedAuthz && roles.exists( _ != Role.Administrator)) {
                 ApplicationLogger.warn(s"User '${name}' defined with authorizations different from 'administrator', which is not supported without the User management plugin. " +
                                         s"To prevent problem, that user authorization are removed: ${node.toString()}")
                 Seq(Role.NoRights)

               } else {
                 ApplicationLogger.debug(s"User '${name}' defined with authorizations: ${roles.map(_.name.toLowerCase()).mkString(", ")}")
                 roles
               }
             case _                 =>
               ApplicationLogger.error(s"User '${name}' in authentication file '${debugFileName}' has incorrectly defined authorizations, to prevent problem we removed him all of them: ${node.toString}")
               Seq(Role.NoRights)
           }
           (RudderAccount.User(name, pwd), r) :: Nil

         case _ =>
           ApplicationLogger.error(s"Ignore user line in authentication file '${debugFileName}', some required attribute is missing: ${node.toString}")
           Nil
       }
      })

      Right(UserDetailList.fromRudderAccount(roleApiMapping, hash, users))
    }
  }

}
