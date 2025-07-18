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

package com.normation.rudder.users

import better.files.File
import com.normation.errors.Inconsistency
import com.normation.errors.IOResult
import com.normation.errors.PureResult
import com.normation.errors.SystemError
import com.normation.errors.Unexpected
import com.normation.rudder.*
import com.normation.rudder.api.*
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.facts.nodes.NodeSecurityContext
import com.normation.rudder.rest.RoleApiMapping
import com.normation.rudder.users.*
import com.normation.utils.XmlSafe
import com.normation.zio.*
import enumeratum.*
import java.io.InputStream
import java.security.SecureRandom
import org.springframework.security.crypto.password.PasswordEncoder
import org.xml.sax.SAXParseException
import scala.collection.immutable.SortedMap
import scala.xml.Elem
import scala.xml.parsing.ConstructingParser
import zio.*
import zio.syntax.*

/**
 * This file contains data structure defining Rudder "user details" and how to
 * build them from "rudder-users.xml" file.
 */

// Our user file can come from either classpath of filesystem. That class abstract that fact.
final case class UserFile(
    name:        String,
    inputStream: () => InputStream
)

// Password encoder definition of parsing values
sealed abstract class PasswordEncoderType(override val entryName: String) extends EnumEntry {
  def name: String = entryName
}

object PasswordEncoderType extends Enum[PasswordEncoderType] {
  case object BCRYPT   extends PasswordEncoderType("BCRYPT")   {
    val defaultCost = 12

    // 16 bytes = 128 bits
    val saltSize = 16
  }
  case object ARGON2ID extends PasswordEncoderType("ARGON2ID") {
    // Defaults for user-controlled settings
    // 19 MiB, from OWASP recommendations
    val minimumMemory      = Argon2Memory(19 * 1024)
    // 128 MiB
    val defaultMemory      = Argon2Memory(128 * 1024)
    val defaultIterations  = Argon2Iterations(3)
    val defaultParallelism = Argon2Parallelism(1)
  }

  // Default value, same as in RudderPasswordEncoder
  val DEFAULT: PasswordEncoderType = ARGON2ID

  override def values: IndexedSeq[PasswordEncoderType] = findValues
}

class PasswordEncoderDispatcher(bcryptCost: Int, argon2Params: Argon2EncoderParams) {
  def dispatch(encoderType: PasswordEncoderType): PasswordEncoder = {
    encoderType match {
      case PasswordEncoderType.BCRYPT   => RudderPasswordEncoder.bcryptEncoder(bcryptCost)
      case PasswordEncoderType.ARGON2ID =>
        RudderPasswordEncoder.argon2Encoder(argon2Params)
    }
  }

}

/////////////////////////////////////////
// The PasswordEncoder is inspired by the modern Spring Security way, using DelegatingPasswordEncoder.
// The RudderPasswordEncoder is basically a DelegatingPasswordEncoder but using our password storage formats
// (and way simpler as we don't need much genericity).
//
// We make it configurable to allow only using proper hashes whenever possible.
/////////////////////////////////////////

object RudderPasswordEncoder {

  import org.bouncycastle.crypto.generators.OpenBSDBCrypt

  private val secureRandom = new SecureRandom()

  // Proper password hash functions
  class bcryptEncoder(cost: Int)                          extends PasswordEncoder {
    override def encode(rawPassword: CharSequence):                           String  = {
      val salt: Array[Byte] = new Array(16)
      secureRandom.nextBytes(salt)

      // The version of bcrypt used is "2b". See https://en.wikipedia.org/wiki/Bcrypt#Versioning_history
      // It prevents the length (unsigned char) of a long password to overflow and wrap at 256. (Cf https://marc.info/?l=openbsd-misc&m=139320023202696)
      OpenBSDBCrypt.generate("2b", rawPassword.toString.toCharArray, salt, cost)
    }
    override def matches(rawPassword: CharSequence, encodedPassword: String): Boolean = {
      try {
        OpenBSDBCrypt.checkPassword(encodedPassword, rawPassword.toString.toCharArray)
      } catch {
        case e: Exception =>
          ApplicationLoggerPure.Auth.debug(s"Invalid password format: ${e.getMessage}")
          false
      }
    }
  }
  class argon2Encoder(encoderParams: Argon2EncoderParams) extends PasswordEncoder {
    override def encode(rawPassword: CharSequence):                           String  = {
      val salt: Array[Byte] = new Array(encoderParams.saltSize.toInt)
      secureRandom.nextBytes(salt)

      val hashParams = Argon2HashParams(
        encoderParams,
        salt = Chunk.fromArray(salt)
      )
      Argon2Hash.generate(hashParams, rawPassword.toString.getBytes)
    }
    override def matches(rawPassword: CharSequence, encodedPassword: String): Boolean = {
      Argon2Hash.checkPassword(rawPassword.toString.getBytes, encodedPassword) match {
        case Left(e)                  =>
          ApplicationLoggerPure.Auth.logEffect.warn(s"Error while checking Argon2 hash: $e")
          false
        case Right(isPasswordCorrect) => isPasswordCorrect
      }
    }
  }

  def getFromEncoded(encodedPassword: String): Either[String, PasswordEncoderType] = {
    // https://github.com/bcgit/bc-java/blob/5b1360854d85fd27b75720015be68f9e172db013/core/src/main/java/org/bouncycastle/crypto/generators/OpenBSDBCrypt.java#L41C1-L48C1
    // Allow types: 2, 2x, 2y, 2a, 2b
    val bcryptRegex    = "^\\$2[abxy]?\\$.+".r
    val argon2idPrefix = "$argon2id"

    if (encodedPassword.matches(bcryptRegex.regex)) {
      Right(PasswordEncoderType.BCRYPT)
    } else if (encodedPassword.startsWith(argon2idPrefix)) {
      Right(PasswordEncoderType.ARGON2ID)
    } else {
      Left("Could not recognize a known hash format from encoded password")
    }
  }
}

case class RudderPasswordEncoder(
    passwordEncoderDispatcher: PasswordEncoderDispatcher
) extends PasswordEncoder {

  override def encode(rawPassword: CharSequence): String = {
    // This method should be unused during the login process, but spring security check throws an error when we throw an exception here.
    // And it is weird because sometimes we obtain `userNotFoundPassword` as rawPassword.

    // We could (and likely should) use RudderPasswordProvider for encoding but we need to store the algorithm to use
    // (as DelegatingPasswordEncoder does) based on the user configuration.
    subPasswordEncoder(rawPassword.toString).encode(rawPassword)
  }

  override def matches(rawPassword: CharSequence, encodedPassword: String): Boolean = {
    subPasswordEncoder(encodedPassword).matches(rawPassword, encodedPassword)
  }

  private def subPasswordEncoder(encodedPassword: String): PasswordEncoder = {
    RudderPasswordEncoder
      .getFromEncoded(encodedPassword)
      .map(encoderType => passwordEncoderDispatcher.dispatch(encoderType))
      .getOrElse(passwordEncoderDispatcher.dispatch(PasswordEncoderType.DEFAULT))
  }
}

/**
 * An user list is a parsed list of users with their authorisations
 */
final case class UserDetailFileConfiguration(
    encoder:         RudderPasswordEncoder,
    isCaseSensitive: Boolean,
    customRoles:     List[Role],
    users:           Map[String, (RudderAccount.User, Seq[Role], NodeSecurityContext)]
)

final case class ValidatedUserList(
    encoder:         RudderPasswordEncoder,
    isCaseSensitive: Boolean,
    customRoles:     List[Role],
    users:           Map[String, RudderUserDetail]
)

object ValidatedUserList {

  private val logger = ApplicationLoggerPure.Auth

  /**
   * Filter the list of users by checking if the username
   * is unique according to case sensitivity:
   *
   *   - if `case sensitivity` is enable
   *     log a warn if there is potential collision when
   *     this parameter will be disabled
   *
   *   - users with same username (according to the case sensitivity)
   *     will be removed from the returned list
   */
  def filterByCaseSensitivity(userDetails: List[RudderUserDetail], isCaseSensitive: Boolean): Map[String, RudderUserDetail] = {
    userDetails.groupBy(_.getUsername.toLowerCase).flatMap {
      // User name is unique with or without case sensitivity, accept
      case (k, u :: Nil)                  => (u.getUsername, u) :: Nil
      // User name is not unique with case sensitivity disabled, refuse everything
      case (_, users) if !isCaseSensitive =>
        logger.error(
          s"Several users have the same username case-insensitively equals to '${users.toList(0).getUsername}': they will be ignored"
        )
        Nil
      // Disabled case sensitivity is treated above, putting a guard here make a non exhaustive match, do without guard,
      case (_, users)                     =>
        // Remove user with exact same name, not only with case sensitivity
        val res = users.groupBy(_.getUsername).flatMap {
          // User name is unique, accept
          case (k, u :: Nil) => (k, u) :: Nil
          // User name is not unique with case sensitivity disabled, Refuse login
          case (_, users)    =>
            logger.error(s"Users with duplicates username will be ignored: ${users.map(_.getUsername).mkString(", ")}")
            Nil
        }

        // Notice that some users  with name different only by their case are defined, and that disabling case sensitivity would break those users
        // (not a warning, since we are in the case sensitive case, it's ok)
        logger
          .info(s"Users with potential username collision if case sensitivity is disabled: ${res.keys.mkString(", ")}")
        res
    }
  }

  /*
   * A method used to derive API acl from user roles and filter them according to case sensitivity parameter.
   * At that point, it is assumed that roleApiMapping is up-to-date with custom role knowledge
   * that the user may be using, and that these custom roles are well behaving: basically,
   * the step on role check/update is done.
   */
  def fromRudderAccountList(
      roleApiMapping: RoleApiMapping,
      accountConfig:  UserDetailFileConfiguration
  ): ValidatedUserList = {

    val userDetails   = accountConfig.users.map {
      case (_, (user, roles, nodeSecurityContext)) =>
        // for users, we don't have the possibility to order APIs. So we just sort them from most specific to less
        // (ie from longest path to shorted)
        // but still group by first part so that we have all nodes together, etc.
        val acls = roleApiMapping
          .getApiAclFromRoles(roles)
          .groupBy(_.path.parts.head)
          .flatMap {
            case (_, seq) =>
              seq.sortBy(_.path)(AclPath.orderingaAclPath).sortBy(_.path.parts.head.value)
          }
          .toList
        // init status to deleted, it will be set correctly latter on
        RudderUserDetail(user, UserStatus.Deleted, roles.toSet, ApiAuthorization.ACL(acls), nodeSecurityContext)
    }
    val filteredUsers = filterByCaseSensitivity(userDetails.toList, accountConfig.isCaseSensitive)
    ValidatedUserList(accountConfig.encoder, accountConfig.isCaseSensitive, accountConfig.customRoles, filteredUsers)
  }
}

/*
 * A callback holder class with an operation to execute when (after) the user file is reloaded.
 */
final case class RudderAuthorizationFileReloadCallback(name: String, exec: ValidatedUserList => IOResult[Unit])

trait UserDetailListProvider {
  def authConfig: ValidatedUserList

  /*
   * Utility method to retrieve user by name.
   * A generic implementation is provided for simplicity
   */
  def getUserByName(username: String): PureResult[RudderUserDetail] = {
    val conf = authConfig
    conf.users
      .get(username)
      .orElse(
        conf.users.collectFirst {
          case (u, userDetail) if !conf.isCaseSensitive && u.toLowerCase() == username.toLowerCase() => userDetail
        }
      )
      .toRight(Unexpected(s"User with username '${username}' was not found"))

  }
}

trait UserFileHashTypeMigration {
  def file: UserFile

  /**
    * Migrate the provided file to the modern security level by enforcing that legacy unsafe hashes are no longer supported
    */
  def enforceModern(file: File): IOResult[Unit]
}

final class FileUserDetailListProvider(
    roleApiMapping:            RoleApiMapping,
    override val file:         UserFile,
    passwordEncoderDispatcher: PasswordEncoderDispatcher
) extends UserDetailListProvider with UserFileHashTypeMigration {

  private val logger = ApplicationLoggerPure.Auth

  /**
   * Initialize user details list when class is instantiated with an empty list.
   * We also set case sensitivity to false as a default (which will be updated with the actual data from the file on reload).
   */
  private val cache = {
    Ref
      .make(
        ValidatedUserList(
          RudderPasswordEncoder(passwordEncoderDispatcher),
          isCaseSensitive = false,
          customRoles = Nil,
          users = Map()
        )
      )
      .runNow
  }

  /**
   * Callbacks for who need to be informed of a successfully users list reload
   */
  private val callbacks = Ref.make(List.empty[RudderAuthorizationFileReloadCallback]).runNow

  /**
   * Reload the list of users. Only update the cache if there is no errors.
   */
  def reloadPure(): IOResult[Unit] = {
    for {
      config <- UserFileProcessing.parseUsers(roleApiMapping, passwordEncoderDispatcher, file, reload = true)
      _      <- cache.set(config)
      cbs    <- callbacks.get
      _      <- ZIO.foreach(cbs) { cb =>
                  cb.exec(config)
                    .catchAll(err => logger.warn(s"Error when executing user authorization call back '${cb.name}': ${err.fullMsg}"))
                }
    } yield ()
  }

  def reload(): Unit = {
    reloadPure()
      .catchAll(err => logger.error(s"Error when reloading users and roles authorisation configuration file: ${err.fullMsg}"))
      .runNow
  }

  def registerCallback(cb: RudderAuthorizationFileReloadCallback): Unit = {
    callbacks.update(_ :+ cb).runNow
  }

  override def authConfig: ValidatedUserList = cache.get.runNow

  override def enforceModern(file: File): IOResult[Unit] = migrateAuthentication(file, "argon2id")

  // directly changes the file content!
  private def migrateAuthentication(sourceTargetFile: File, hash: String): IOResult[Unit] = {
    // replace "hash" value and remove "unsafe-hashes" value
    for {
      parsedFile <- IOResult.attempt(ConstructingParser.fromFile(sourceTargetFile.toJava, preserveWS = true))
      userXML    <- IOResult.attempt(parsedFile.document().children)

      toUpdate <- IOResult.attempt((userXML \\ "authentication").head)

      _ <- toUpdate match {
             case e: Elem =>
               val newXml = {
                 e.copy(attributes = {
                   e.attributes
                     .append(
                       new scala.xml.UnprefixedAttribute("hash", hash, scala.xml.Null)
                     ) // will override existing hash attribute
                     .remove(
                       "unsafe-hashes"
                     )
                 })
               }

               UserManagementIO.replaceXml(userXML, newXml, sourceTargetFile)
             case _ =>
               Unexpected(s"Wrong formatting : ${sourceTargetFile.path}").fail
           }
    } yield {}
  }

}

object UserFileProcessing {

  private val JVM_AUTH_FILE_KEY = "rudder.authFile"
  val DEFAULT_AUTH_FILE_NAME    = "demo-rudder-users.xml"

  private val logger = ApplicationLoggerPure.Auth

  // utility classes for a parsed custom role/user/everything before sanity check is done on them
  final case class ParsedRole(name: String, permissions: List[String])
  final case class ParsedUser(
      name:        String,
      password:    UserPassword,
      permissions: List[String],
      tenants:     Option[List[String]]
  )
  final case class ParsedUserFile(
      encoder:         PasswordEncoderType,
      isCaseSensitive: Boolean,
      customRoles:     List[UncheckedCustomRole],
      users:           List[ParsedUser]
  )

  def getUserResourceFile(): IOResult[UserFile] = {
    java.lang.System.getProperty(JVM_AUTH_FILE_KEY) match {
      case null | "" => // use default location in classpath
        logger.info(
          s"JVM property -D${JVM_AUTH_FILE_KEY} is not defined, using configuration file '${DEFAULT_AUTH_FILE_NAME}' in classpath"
        ) *>
        UserFile(
          "classpath:" + DEFAULT_AUTH_FILE_NAME,
          () => this.getClass().getClassLoader.getResourceAsStream(DEFAULT_AUTH_FILE_NAME)
        ).succeed
      case x         => // so, it should be a full path, check it
        val config = File(x)
        if (config.exists && config.isReadable) {
          logger
            .info(
              s"Using configuration file defined by JVM property -D${JVM_AUTH_FILE_KEY} : ${config.path}"
            ) *>
          UserFile(config.canonicalPath, () => config.newInputStream).succeed
        } else {
          logger.error(
            s"Can not find configuration file specified by JVM property ${JVM_AUTH_FILE_KEY}: ${config.path}; aborting"
          ) *> Unexpected(s"rudder-users configuration file not found at path: '${config.path}'").fail
        }
    }
  }

  def readUserFile(resource: UserFile): IOResult[Elem] = {
    IOResult.attempt {
      XmlSafe.load(resource.inputStream())
    }.mapError {
      // map a SAXParseException to a technical butmore user-friendly but error message
      case s @ SystemError(_, e: SAXParseException) =>
        s.copy(
          msg = s"User definitions: XML in file /opt/rudder/etc/rudder-users.xml is incorrect, error message is: ${e
              .getMessage()} (line ${e.getLineNumber()}, column ${e.getColumnNumber()})"
        )
      case s                                        =>
        s.copy(
          msg =
            "User definitions: An error occurred while parsing /opt/rudder/etc/rudder-users.xml. Logging in to the Rudder web interface will not be possible until this is fixed and the application restarted."
        )
    }
  }

  def parseUsers(
      roleApiMapping:            RoleApiMapping,
      passwordEncoderDispatcher: PasswordEncoderDispatcher,
      resource:                  UserFile,
      reload:                    Boolean
  ): IOResult[ValidatedUserList] = {

    for {
      xml <- readUserFile(resource)
      res <- parseXml(roleApiMapping, passwordEncoderDispatcher, xml, resource.name, reload)
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
  def parseXml(
      roleApiMapping:            RoleApiMapping,
      passwordEncoderDispatcher: PasswordEncoderDispatcher,
      xml:                       Elem,
      debugFileName:             String,
      reload:                    Boolean
  ): IOResult[ValidatedUserList] = {
    for {
      parsed <- parseXmlNoResolve(xml, debugFileName)
      known  <- if (reload) RudderRoles.builtInRoles.get else RudderRoles.getAllRoles
      roles  <- resolveRoles(known, parsed.customRoles)
      _      <- RudderRoles.register(roles, resetExisting = reload)
      users  <- resolveUsers(parsed.users, debugFileName)
    } yield {
      val encoder = RudderPasswordEncoder(passwordEncoderDispatcher)

      val config = {
        UserDetailFileConfiguration(
          encoder,
          parsed.isCaseSensitive,
          roles,
          users.map { case (u, rs, nsc) => (u.login, (u, rs, nsc)) }.toMap
        )
      }
      ValidatedUserList.fromRudderAccountList(roleApiMapping, config)
    }
  }

  /**
    * Attempt to read the root hash="..." attribute as a known password encoder type. Return an unknown value as a left
    */
  def parseXmlHash(xml: Elem): IOResult[Either[String, PasswordEncoderType]] = {
    for {
      root <- validateAuthenticationTag(xml)
      hash  = (root(0) \ "@hash").text
    } yield {
      PasswordEncoderType.withNameInsensitiveEither(hash).left.map(_.notFoundName)
    }
  }

  /*
   * This method just parse XML file & validate its general structure, but it does not resolve roles
   * or rights.
   * This is done in a second pass.
   */
  private def parseXmlNoResolve(xml: Elem, debugFileName: String): IOResult[ParsedUserFile] = {
    // one unique name attribute, text content non empty
    def getRoleName(node: scala.xml.Node): PureResult[String] = {
      node.attribute("name").map(_.map(_.text.strip())) match {
        case None | Some(Nil)                    => Left(Inconsistency(s"Role can't have an empty `name` attribute: ${node}"))
        case Some(name :: Nil) if (name.isEmpty) =>
          Left(Inconsistency(s"Role can't have an empty `name` attribute: ${node}"))
        case Some(name :: Nil)                   => Right(name)
        case x                                   => Left(Inconsistency(s"Role must have an unique, non-empty `name` attribute: ${x}"))
      }
    }

    // roles are comma-separated list of non-empty string. Several `roles` attribute should be avoid, but are ok and are merged.
    // List is gotten as is (no dedup, no sanity check, no sorting, no "roleToRight", etc)
    def getCommaSeparatedList(attrName: String, node: scala.xml.Node): Option[List[String]] = {
      node
        .attribute(attrName)
        .map(
          _.toList
            .flatMap(permlist => permlist.text.split(",").map(_.strip()).collect { case r if (r.nonEmpty) => r }.toList)
        )
    }

    val customRoles: UIO[List[UncheckedCustomRole]] = (ZIO
      .foreach((xml \ "custom-roles" \ "role").toList) { node =>
        // for each node, check attribute `name` (mandatory) and `roles` (optional), even if the only interest of an empty
        // roles attribute is for aliasing "NoRight" policy.

        (for {
          n <- getRoleName(node)
          r  = getCommaSeparatedList("permissions", node).getOrElse(Nil)
        } yield {
          UncheckedCustomRole(n, r)
        }) match {
          case Left(err)   =>
            logger
              .error(s"Role incorrectly defined in '${debugFileName}': ${err.fullMsg})") *> None.succeed
          case Right(role) =>
            Some(role).succeed
        }
      })
      .map(_.flatten)

    // now, get users
    def getUsers() = {
      def userRoles(node: Option[scala.collection.Seq[scala.xml.Node]]): List[String] = {
        node.map(_.toList.flatMap(role => role.text.split(",").toList.map(_.strip))).getOrElse(Nil)
      }
      ZIO
        .foreach((xml \ "user").toList) { node =>
          // for each node, check attribute name (mandatory), password  (mandatory) and role (optional)
          (
            node
              .attribute("name")
              .map(_.toList.map(_.text)),
            node.attribute("password").map(_.toList.map(_.text)),
            // accept both "role" and "roles"
            userRoles(node.attribute("role")) ++ userRoles(node.attribute("permissions")),
            getCommaSeparatedList("tenants", node)
          ) match {
            case (Some(name :: Nil), pwd, permissions, tenants) if (name.size > 0) =>
              val p = pwd match {
                case Some(UserPassword.checkHashedPassword(p) :: _) => p
                case _                                              => UserPassword.randomHexa32
              }
              Some(ParsedUser(name, p, permissions, tenants)).succeed

            case _ =>
              logger.error(
                s"Ignore user line in authentication file '${debugFileName}', some required attribute is missing: ${node.toString}"
              ) *> None.succeed
          }
        }
        .map(_.flatten)
    }

    for {
      parsedHash <- parseXmlHash(xml)
      defaultHash = PasswordEncoderType.DEFAULT
      // FIXME: maybe here we want to keep the fact that it's an invalid hash value
      // , and defer the logic of writing unsafe-hashes=true to when check/migration of file occurs.
      // For now, we log every time we have an unknown value, and use the default modern hash algorithm.
      hash       <- parsedHash match {
                      case Left(unknownValue) =>
                        logger
                          .warn(
                            s"Attribute hash has an unknown value `${unknownValue}` in file '${debugFileName}', set by default on `${defaultHash.name}`"
                          )
                          .as(defaultHash)

                      case Right(value) =>
                        value.succeed
                    }

      isCaseSensitive <- (xml(0) \ "@case-sensitivity").text.toLowerCase match {
                           case "true"  => true.succeed
                           case "false" => false.succeed
                           case str     =>
                             (if (str.isEmpty) {
                                logger.info(
                                  s"Case sensitivity: in file '${debugFileName}' parameter `case-sensitivity` is not set, set by default on `true`"
                                )
                              } else {
                                logger.warn(
                                  s"Case sensitivity: unknown case-sensitivity parameter `$str` in file '${debugFileName}', set by default on `true`"
                                )
                              }) *> true.succeed
                         }

      roles <- customRoles
      users <- getUsers()

    } yield ParsedUserFile(hash, isCaseSensitive, roles, users)
  }

  /*
   * Roles must be taken all together, because even if we forbid cycles, they may be not sorted and
   * we want to update the set of OK roles just one time.
   */
  private def resolveRoles(
      knownRoles: SortedMap[String, Role],
      roles:      List[UncheckedCustomRole]
  ): UIO[List[Role]] = {
    for {
      res <- RudderRoles.resolveCustomRoles(roles, knownRoles)
      _   <- ZIO.foreach(res.invalid) {
               case (r, err) =>
                 logger.error(
                   s"Error with custom role definition: custom role '${r.name}' is invalid and will be ignored: ${err}"
                 )
             }
      _   <- ZIO.foreach(res.validRoles) { r =>
               r match {
                 case Role.NamedCustom(n, l) =>
                   logger.debug(
                     s"Custom role '${n}' defined as the union of roles: [${l.map(_.debugString).mkString(", ")}]"
                   )
                 // Should not happen, since there should be only custom roles, but to prevent warning here ...
                 case _                      =>
                   logger.debug(
                     s"Found role ${r.name}, in custom role definition, ignore this message"
                   )
               }
             }
    } yield res.validRoles
  }

  private def resolveUsers(
      users:         List[ParsedUser],
      debugFileName: String
  ): UIO[List[(RudderAccount.User, List[Role], NodeSecurityContext)]] = {

    val TODO_MODULE_TENANTS_ENABLED = true

    ZIO.foreach(users) { u =>
      val ParsedUser(name, pwd, roles, tenants) = u

      for {
        nsc <- NodeSecurityContext
                 .parseList(u.tenants)
                 .toIO
                 .flatMap { // check for adequate plugin
                   case NodeSecurityContext.ByTenants(_) if (!TODO_MODULE_TENANTS_ENABLED) =>
                     logger.warn(
                       s"Tenants definition are only available with the corresponding plugin. To prevent unwanted right escalation, " +
                       s"user '${name}' will be restricted to no tenants"
                     ) *> NodeSecurityContext.None.succeed
                   case x                                                                  => x.succeed
                 }
                 .catchAll(err => logger.warn(err.fullMsg) *> NodeSecurityContext.None.succeed)
        rs  <-
          RudderRoles
            .parseRoles(roles)
            .flatMap(list => {
              list match {
                case Nil => // 'role' attribute is optional, by default get "none" role
                  List(Role.NoRights).succeed

                case rs =>
                  logger.debug(
                    s"User '${name}' defined with authorizations: ${rs.map(_.debugString).mkString(", ")}"
                  ) *> rs.succeed
              }
            })
      } yield {
        (RudderAccount.User(name, pwd), rs, nsc)
      }
    }
  }

  def validateAuthenticationTag(xml: Elem): IOResult[Elem] = {
    val root = (xml \\ "authentication")
    if (root.size != 1) {
      Inconsistency("Authentication file is malformed, the root tag '<authentication>' was not found").fail
    } else xml.succeed
  }
}
