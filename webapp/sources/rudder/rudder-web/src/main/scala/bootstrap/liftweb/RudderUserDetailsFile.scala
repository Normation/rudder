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

import com.normation.errors.Inconsistency
import com.normation.errors.IOResult
import com.normation.errors.PureResult
import com.normation.errors.SystemError
import com.normation.errors.Unexpected
import com.normation.rudder._
import com.normation.rudder.api._
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.facts.nodes.NodeSecurityContext
import com.normation.rudder.rest.RoleApiMapping
import com.normation.rudder.tenants.TenantId
import com.normation.rudder.users._
import com.normation.zio._
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import org.bouncycastle.util.encoders.Hex
import org.joda.time.DateTime
import org.xml.sax.SAXParseException
import scala.collection.immutable.SortedMap
import scala.xml.Elem
import zio._
import zio.syntax._

/**
 * This file contains data structure defining Rudder "user details" and how to
 * build them from "rudder-users.xml" file.
 */

// Our user file can come from either classpath of filesystem. That class abstract that fact.
final case class UserFile(
    name:        String,
    inputStream: () => InputStream
)

//Password encoder type definition. Done like that to avoid
//a deprecation warning on `PasswordEncoder` thanks to @silent annotation
object PasswordEncoder {
  type Rudder = org.springframework.security.crypto.password.PasswordEncoder

  import org.bouncycastle.crypto.generators.OpenBSDBCrypt
  import org.springframework.security.crypto.password.PasswordEncoder

  val random = new SecureRandom()

  // see https://stackoverflow.com/a/44227131
  // produce a random hexa string of 32 chars
  def randomHexa32: String = {
    // here, we can be unlucky with the chosen token which convert to an int starting with one or more 0.
    // In that case, just complete the string
    def randInternal: String = {
      val token = new Array[Byte](16)
      random.nextBytes(token)
      new java.math.BigInteger(1, token).toString(16)
    }
    var s = randInternal
    while (s.size < 32) { // we can be very unlucky and keep drawing 000s
      s = s + randInternal.substring(0, 32 - s.size)
    }
    s
  }

  class DigestEncoder(digestName: String) extends PasswordEncoder {
    override def encode(rawPassword: CharSequence):                           String  = {
      val digest = MessageDigest.getInstance(digestName)
      new String(Hex.encode(digest.digest(rawPassword.toString.getBytes(StandardCharsets.UTF_8))), StandardCharsets.UTF_8)
    }
    override def matches(rawPassword: CharSequence, encodedPassword: String): Boolean = {
      if (null == rawPassword) {
        false
      } else {
        encode(rawPassword) == encodedPassword
      }
    }
  }

  val PlainText: PasswordEncoder = new PasswordEncoder() {
    override def encode(rawPassword: CharSequence):                           String  = rawPassword.toString
    override def matches(rawPassword: CharSequence, encodedPassword: String): Boolean = rawPassword.toString == encodedPassword
  }
  // Unsalted hash functions :
  val MD5 = new DigestEncoder("MD5")
  val SHA1   = new DigestEncoder("SHA-1")
  val SHA256 = new DigestEncoder("SHA-256")
  val SHA512 = new DigestEncoder("SHA-512")
  // Salted hash functions :
  val BCRYPT: PasswordEncoder = new PasswordEncoder() {
    override def encode(rawPassword: CharSequence):                           String  = {
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
          ApplicationLogger.debug(s"Invalid password format: ${e.getMessage}")
          false
      }
    }
  }

  def parse(name: String): Either[String, PasswordEncoder] = {
    name.toLowerCase match {
      case "sha" | "sha1"       => Right(SHA1)
      case "sha256" | "sha-256" => Right(SHA256)
      case "sha512" | "sha-512" => Right(SHA512)
      case "md5"                => Right(MD5)
      case "bcrypt"             => Right(BCRYPT)
      case "plain"              => Right(PlainText)
      case _                    => Left(s"Password encoder identifier '${name}' is unknown")
    }
  }
}

/**
 * An user list is a parsed list of users with their authorisations
 */
final case class UserDetailFileConfiguration(
    encoder:         PasswordEncoder.Rudder,
    isCaseSensitive: Boolean,
    customRoles:     List[Role],
    users:           Map[String, (RudderAccount.User, Seq[Role], NodeSecurityContext)]
)

final case class ValidatedUserList(
    encoder:         PasswordEncoder.Rudder,
    isCaseSensitive: Boolean,
    customRoles:     List[Role],
    users:           Map[String, RudderUserDetail]
)

object ValidatedUserList {

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
        ApplicationLogger.error(
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
            ApplicationLogger.error(s"Users with duplicates username will be ignored: ${users.map(_.getUsername).mkString(", ")}")
            Nil
        }

        // Notice that some users  with name different only by their case are defined, and that disabling case sensitivity would break those users
        // (not a warning, since we are in the case sensitive case, it's ok)
        ApplicationLogger
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

/*
 * A callback that is in charge of updating the list of UserInfo managed by the file authenticator.
 */
object UserRepositoryUpdateOnFileReload {
  def createCallback(userRepository: UserRepository): RudderAuthorizationFileReloadCallback = {
    RudderAuthorizationFileReloadCallback(
      "update-pg-users-on-xml-file-reload",
      userList => {
        userRepository.setExistingUsers(
          DefaultAuthBackendProvider.FILE,
          userList.users.keys.toList,
          EventTrace(RudderEventActor, DateTime.now(), "Updating users because `rudder-users.xml` was reloaded")
        )
      }
    )
  }
}

trait UserDetailListProvider {
  def authConfig: ValidatedUserList

  /*
   * Utility method to retrieve user by name.
   * A generic implementation is provided for simplicity
   */
  def getUserByName(username: String): PureResult[RudderUserDetail] = {
    val conf = authConfig
    val u    = if (conf.isCaseSensitive) username else username.toLowerCase()
    conf.users.get(u).toRight(Unexpected(s"User with username '${username}' was not found"))
  }
}

final class FileUserDetailListProvider(roleApiMapping: RoleApiMapping, authorisationLevel: UserAuthorisationLevel, file: UserFile)
    extends UserDetailListProvider {

  /**
   * Initialize user details list when class is instantiated with an empty list.
   * You will have to "reload" after application full init (to allows plugin override);
   * We also set case sensitivity to false as a default (which will be updated with the actual data from the file on reload).
   */
  private[this] val cache = Ref.make(ValidatedUserList(PasswordEncoder.PlainText, false, Nil, Map())).runNow

  /**
   * Callbacks for who need to be informed of a successfully users list reload
   */
  private[this] val callbacks = Ref.make(List.empty[RudderAuthorizationFileReloadCallback]).runNow

  /**
   * Reload the list of users. Only update the cache if there is no errors.
   */
  def reloadPure(): IOResult[Unit] = {
    for {
      config <- UserFileProcessing.parseUsers(roleApiMapping, file, authorisationLevel.userAuthEnabled, reload = true)
      _      <- cache.set(config)
      cbs    <- callbacks.get
      _      <- ZIO.foreach(cbs) { cb =>
                  cb.exec(config)
                    .catchAll(err =>
                      ApplicationLoggerPure.warn(s"Error when executing user authorization call back '${cb.name}': ${err.fullMsg}")
                    )
                }
    } yield ()
  }

  def reload(): Unit = {
    reloadPure()
      .catchAll(err =>
        ApplicationLoggerPure.error(s"Error when reloading users and roles authorisation configuration file: ${err.fullMsg}")
      )
      .runNow
  }

  def registerCallback(cb: RudderAuthorizationFileReloadCallback): Unit = {
    callbacks.update(_ :+ cb).runNow
  }

  override def authConfig: ValidatedUserList = cache.get.runNow

}

object UserFileProcessing {

  val JVM_AUTH_FILE_KEY      = "rudder.authFile"
  val DEFAULT_AUTH_FILE_NAME = "demo-rudder-users.xml"

  // utility classes for a parsed custom role/user/everything before sanity check is done on them
  final case class ParsedRole(name: String, permissions: List[String])
  final case class ParsedUser(name: String, password: String, permissions: List[String], tenants: Option[List[String]])
  final case class ParsedUserFile(
      encoder:         PasswordEncoder.Rudder,
      isCaseSensitive: Boolean,
      customRoles:     List[UncheckedCustomRole],
      users:           List[ParsedUser]
  )

  def getUserResourceFile(): IOResult[UserFile] = {
    java.lang.System.getProperty(JVM_AUTH_FILE_KEY) match {
      case null | "" => // use default location in classpath
        ApplicationLoggerPure.Authz.info(
          s"JVM property -D${JVM_AUTH_FILE_KEY} is not defined, using configuration file '${DEFAULT_AUTH_FILE_NAME}' in classpath"
        ) *>
        UserFile(
          "classpath:" + DEFAULT_AUTH_FILE_NAME,
          () => this.getClass().getClassLoader.getResourceAsStream(DEFAULT_AUTH_FILE_NAME)
        ).succeed
      case x         => // so, it should be a full path, check it
        val config = new File(x)
        if (config.exists && config.canRead) {
          ApplicationLoggerPure.Authz.info(
            s"Using configuration file defined by JVM property -D${JVM_AUTH_FILE_KEY} : ${config.getPath}"
          ) *>
          UserFile(config.getAbsolutePath, () => new FileInputStream(config)).succeed
        } else {
          ApplicationLoggerPure.Authz.error(
            s"Can not find configuration file specified by JVM property ${JVM_AUTH_FILE_KEY}: ${config.getPath}; aborting"
          ) *> Unexpected(s"rudder-users configuration file not found at path: '${config.getPath}'").fail
        }
    }
  }

  def parseUsers(
      roleApiMapping: RoleApiMapping,
      resource:       UserFile,
      extendedAuthz:  Boolean,
      reload:         Boolean
  ): IOResult[ValidatedUserList] = {
    val optXml = {
      try {
        scala.xml.XML.load(resource.inputStream()).succeed
      } catch {
        case e: SAXParseException =>
          SystemError(
            s"User definitions: XML in file /opt/rudder/etc/rudder-users.xml is incorrect, error message is: ${e
                .getMessage()} (line ${e.getLineNumber()}, column ${e.getColumnNumber()})",
            e
          ).fail
        case e: Exception         =>
          SystemError(
            "User definitions: An error occurred while parsing /opt/rudder/etc/rudder-users.xml. Logging in to the Rudder web interface will not be possible until this is fixed and the application restarted.",
            e
          ).fail
      }
    }

    for {
      xml <- optXml
      res <- parseXml(roleApiMapping, xml, resource.name, extendedAuthz, reload)
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
      roleApiMapping: RoleApiMapping,
      xml:            Elem,
      debugFileName:  String,
      extendedAuthz:  Boolean,
      reload:         Boolean
  ): IOResult[ValidatedUserList] = {
    for {
      parsed <- parseXmlNoResolve(xml, debugFileName)
      known  <- if (reload) RudderRoles.builtInRoles.get else RudderRoles.getAllRoles
      roles  <- resolveRoles(known, parsed.customRoles, extendedAuthz)
      _      <- RudderRoles.register(roles, resetExisting = reload)
      users  <- resolveUsers(parsed.users, extendedAuthz, debugFileName)
    } yield {
      val config = {
        UserDetailFileConfiguration(
          parsed.encoder,
          parsed.isCaseSensitive,
          roles,
          users.map { case (u, rs, nsc) => (u.login, (u, rs, nsc)) }.toMap
        )
      }
      ValidatedUserList.fromRudderAccountList(roleApiMapping, config)
    }
  }

  /*
   * This method just parse XML file & validate its general structure, but it does not resolve roles
   * or rights.
   * This is done in a second pass.
   */
  def parseXmlNoResolve(xml: Elem, debugFileName: String): IOResult[ParsedUserFile] = {
    // what password hashing algo to use ?
    val root = (xml \\ "authentication")
    if (root.size != 1) {
      Inconsistency("Authentication file is malformed, the root tag '<authentication>' was not found").fail
    } else {
      val hash = PasswordEncoder.parse((root(0) \ "@hash").text).getOrElse(PasswordEncoder.BCRYPT)

      val isCaseSensitive: IOResult[Boolean] = (root(0) \ "@case-sensitivity").text.toLowerCase match {
        case "true"  => true.succeed
        case "false" => false.succeed
        case str     =>
          (if (str.isEmpty) {
             ApplicationLoggerPure.Authz.info(
               s"Case sensitivity: in file '${debugFileName}' parameter `case-sensitivity` is missing, set by default on `true`"
             )
           } else {
             ApplicationLoggerPure.Authz.warn(
               s"Case sensitivity: unknown case-sensitivity parameter `$str` in file '${debugFileName}', set by default on `true`"
             )
           }) *> true.succeed
      }

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
      def getCommaSeparatiedList(attrName: String, node: scala.xml.Node): Option[List[String]] = {
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
            r  = getCommaSeparatiedList("permissions", node).getOrElse(Nil)
          } yield {
            UncheckedCustomRole(n, r)
          }) match {
            case Left(err)   =>
              ApplicationLoggerPure.Authz
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
              getCommaSeparatiedList("tenants", node)
            ) match {
              case (Some(name :: Nil), pwd, permissions, tenants) if (name.size > 0) =>
                // password can be optional when an other authentication backend is used.
                // When the tag is omitted, we generate a 10 bytes random value in place of the pass internally
                // to avoid any cases where the empty string will be used if all other backend are in failure.
                // Also forbid empty or all blank passwords.
                // If the attribute is defined several times, use the first occurrence.
                val p = pwd match {
                  case Some(p :: _) if (p.strip().size > 0) => p
                  case _                                    => PasswordEncoder.randomHexa32
                }
                Some(ParsedUser(name, p, permissions, tenants)).succeed

              case _ =>
                ApplicationLoggerPure.Authz.error(
                  s"Ignore user line in authentication file '${debugFileName}', some required attribute is missing: ${node.toString}"
                ) *> None.succeed
            }
          }
          .map(_.flatten)
      }

      // weave the whole program together
      for {
        isCS  <- isCaseSensitive
        roles <- customRoles
        users <- getUsers()
      } yield ParsedUserFile(hash, isCS, roles, users)
    }
  }

  /*
   * Roles must be taken all together, because even if we forbid cycles, they may be not sorted and
   * we want to update the set of OK roles just one time.
   */
  def resolveRoles(
      knownRoles:    SortedMap[String, Role],
      roles:         List[UncheckedCustomRole],
      extendedAuthz: Boolean
  ): UIO[List[Role]] = {
    if (!extendedAuthz && roles.nonEmpty) {
      ApplicationLoggerPure.Authz.logEffect.warn(
        s"Custom roles are defined which is not supported without the User management plugin. " +
        s"These custom roles will be ignored: ${roles.map(_.name).mkString(", ")}"
      )
      Nil.succeed
    } else {
      for {
        res <- RudderRoles.resolveCustomRoles(roles, knownRoles)
        _   <- ZIO.foreach(res.invalid) {
                 case (r, err) =>
                   ApplicationLoggerPure.Authz.error(
                     s"Error with custom role definition: custom role '${r.name}' is invalid and will be ignored: ${err}"
                   )
               }
        _   <- ZIO.foreach(res.validRoles) { r =>
                 r match {
                   case Role.NamedCustom(n, l) =>
                     ApplicationLoggerPure.Authz.debug(
                       s"Custom role '${n}' defined as the union of roles: [${l.map(_.debugString).mkString(", ")}]"
                     )
                   // Should not happen, since there should be only custom roles, but to prevent warning here ...
                   case _                      =>
                     ApplicationLoggerPure.Authz.debug(
                       s"Found role ${r.name}, in custom role definition, ignore this message"
                     )
                 }
               }
      } yield res.validRoles
    }
  }

  /*
   * Tenants name are only alphanumeric (mandatory first) + '-' + '_' with two special cases:
   * - '*' means "all"
   * - '-' means "none"
   * None means "all" for compat
   */
  def resolveTenants(tenants: Option[List[String]]): UIO[NodeSecurityContext] = {

    tenants match {
      case None     => NodeSecurityContext.All.succeed // for compatibility with previous versions
      case Some(ts) =>
        (ZIO
          .foldLeft(ts)(NodeSecurityContext.ByTenants(Chunk.empty): NodeSecurityContext) {
            case (t1, t2) =>
              t2.strip() match {
                case "*"                       => t1.plus(NodeSecurityContext.All).succeed
                case "-"                       => NodeSecurityContext.None.succeed
                case TenantId.checkTenantId(v) => t1.plus(NodeSecurityContext.ByTenants(Chunk(TenantId(v)))).succeed
                case x                         =>
                  ApplicationLoggerPure.Authz.warn(
                    s"Value '${x}' is not a valid tenant identifier. It must contains only alpha-num  ascii chars or " +
                    s"'-' and '_' (not in the first) place; or exactly '*' (all tenants) or '-' (none tenants)"
                  ) *> t1.plus(NodeSecurityContext.ByTenants(Chunk.empty)).succeed
              }
          })
          .map {
            case NodeSecurityContext.ByTenants(c) if (c.isEmpty) => NodeSecurityContext.None
            case x                                               => x
          }
    }
  }

  def resolveUsers(
      users:         List[ParsedUser],
      extendedAuthz: Boolean,
      debugFileName: String
  ): UIO[List[(RudderAccount.User, List[Role], NodeSecurityContext)]] = {

    val TODO_MODULE_TENANTS_ENABLED = true

    ZIO.foreach(users) { u =>
      val ParsedUser(name, pwd, roles, tenants) = u

      for {
        nsc <- resolveTenants(u.tenants).flatMap { // check for adequate plugin
                 case NodeSecurityContext.ByTenants(_) if (!TODO_MODULE_TENANTS_ENABLED) =>
                   ApplicationLoggerPure.Authz.warn(
                     s"Tenants definition are only available with the corresponding plugin. To prevent unwanted right escalation, " +
                     s"user '${name}' will be restricted to no tenants"
                   ) *> NodeSecurityContext.None.succeed
                 case x                                                                  => x.succeed
               }
        rs  <-
          RudderRoles
            .parseRoles(roles)
            .flatMap(list => {
              list match {
                case Nil => // 'role' attribute is optional, by default get "none" role
                  List(Role.NoRights).succeed

                case rs =>
                  if (!extendedAuthz && rs.exists(_ != Role.Administrator)) {
                    ApplicationLoggerPure.Authz.warn(
                      s"User '${name}' defined with authorizations different from 'administrator', which is not supported without the User management plugin. " +
                      s"To prevent problem, that user authorization is removed."
                    ) *> List(Role.NoRights).succeed

                  } else {
                    ApplicationLoggerPure.Authz.debug(
                      s"User '${name}' defined with authorizations: ${rs.map(_.debugString).mkString(", ")}"
                    ) *> rs.succeed
                  }
              }
            })
      } yield {
        (RudderAccount.User(name, pwd), rs, nsc)
      }
    }
  }

}
