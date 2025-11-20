/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.rudder.AuthorizationType
import com.normation.rudder.Rights
import com.normation.rudder.Role
import com.normation.rudder.Role.Custom
import com.normation.rudder.RudderRoles
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.repository.xml.RudderPrettyPrinter
import com.normation.rudder.users.UserFileProcessing.ParsedUser
import com.normation.rudder.users.UserManagementIO.getUserFilePath
import com.normation.rudder.users.UserManagementService.addUserXmlRewriteRule
import com.normation.rudder.users.UserManagementService.deleteUserXmlRewriteRule
import com.normation.rudder.users.UserManagementService.updateUserXmlRewriteRule
import com.normation.zio.*
import java.util.concurrent.TimeUnit
import org.springframework.core.io.ClassPathResource as CPResource
import org.springframework.security.crypto.password.PasswordEncoder
import scala.xml.Elem
import scala.xml.Node
import scala.xml.NodeSeq
import scala.xml.parsing.ConstructingParser
import scala.xml.transform.RewriteRule
import scala.xml.transform.RuleTransformer
import zio.*
import zio.syntax.*

object UserManagementIO {

  private val logger = ApplicationLoggerPure.Auth

  private val semaphore = Semaphore.make(1).runNow

  // name of the root element in our root file
  val AUTHENTICATION_ROOT_ELT = "authentication"

  /*
   * Rewrite the XML file located at `xmlFile` by replacing the main `authentication` root tag
   * by what the RuleTransformer gives, ie it's equivalent to:
   * ```
   *   new RuleTransformer(new RewriteRule { ...}).transform(<authentication>... existing content...</autentication>)
   * ```
   * We also provide the original <authentication> Elem since it can be necessary for some transformation.
   * The actual rewrite logic is delegated to `replaceXml`.
   *
   * That method is atomic:
   * - only one can be executed in parallel,
   * - in case of error, a back-up (the original file) is written back.
   */
  def transformUserFile(xmlFile: File, transformer: Elem => PureResult[RewriteRule]): IOResult[Unit] = {
    semaphore.withPermit(for {
      readable   <- IOResult.attempt(xmlFile.isReadable)
      _          <- ZIO.when(!readable)(Unexpected(s"${xmlFile.path} is not readable").fail)
      writable   <- IOResult.attempt(xmlFile.isWritable)
      _          <- ZIO.when(!writable)(Unexpected(s"${xmlFile.path} is not writable").fail)
      parsedFile <- IOResult.attempt(ConstructingParser.fromFile(xmlFile.toJava, preserveWS = true))
      userXML    <- IOResult.attempt(parsedFile.document().children)
      toReplace  <- replaceXml(userXML, transformer, xmlFile.pathAsString).toIO
      p           = new RudderPrettyPrinter(300, 4)
      xmlString   = p.formatNodes(toReplace)
      _          <- withBackup(xmlFile)(IOResult.attempt(xmlFile.overwrite(xmlString)))
    } yield ())
  }

  /*
   * This pure function rewrite `originalXml` according to `RewriteRule`.
   * In addition, we provide access in transformer to `originalXml` to:
   * - be able to look around it for things in other nodes (like "hash" value)
   * - check for a property, like "user does not exist" before adding it.
   */
  def replaceXml(
      originalXml:   NodeSeq,
      transformer:   Elem => PureResult[RewriteRule],
      debugFileName: String
  ): PureResult[NodeSeq] = {
    (originalXml \\ AUTHENTICATION_ROOT_ELT).headOption match {
      // that file should always have exactly one `authentication` root tag (plus comments around, maybe)
      case Some(e: Elem) =>
        for {
          rewriteRule <- transformer(e)
        } yield {
          // here, `RuleTransformer` is necessary to get the recursion on children
          val newXml = new RuleTransformer(rewriteRule).transform(e)
          originalXml.flatMap { x =>
            if (x.label == AUTHENTICATION_ROOT_ELT) {
              newXml
            } else {
              Seq(x)
            }
          }
        }
      case _             =>
        Left(Unexpected(s"User file has invalid syntax: ${debugFileName}"))
    }
  }

  // create a backup of rudder-user.xml and roll it back in case of errors during update
  private[users] def withBackup(source: File)(mod: IOResult[Unit]) = {
    for {
      stamp  <- ZIO.clockWith(_.currentTime(TimeUnit.MILLISECONDS))
      backup <- IOResult.attempt(File(source.pathAsString + s"_backup_${stamp}"))
      _      <- IOResult.attempt(source.copyTo(backup)) // in case of error here, stop
      _      <- mod.foldZIO(
                  // failure: we try to restore the backup
                  err =>
                    logger.error(
                      s"Error when trying to save updated rudder user authorizations, roll-backing to back-upped version. Error was: ${err.fullMsg}"
                    ) *>
                    IOResult
                      .attempt(backup.copyTo(source, overwrite = true))
                      .foldZIO(
                        err2 => {
                          // there, we are in big problem: error in rollback too, likely an FS problem, advice admin
                          val msg =
                            s"Error when reverting rudder-users.xml, you will likely need to have a manual action. Backup file is here and won't be deleted automatically: ${backup.pathAsString}. Error was: ${err2.fullMsg}"
                          logger.error(msg) *> Unexpected(msg).fail
                        },
                        ok => {
                          effectUioUnit(backup.delete(swallowIOExceptions = true)) *> logger
                            .info(s"User file correctly roll-backed") *> Unexpected(
                            s"An error happened when trying to save rudder-users.xml file, backup version was restored. Error was: ${err.fullMsg}"
                          ).fail
                        }
                      ),
                  // in case of update success, we just delete the backup file
                  ok => effectUioUnit(backup.delete(swallowIOExceptions = true))
                )
    } yield ()
  }

  def getUserFilePath(resourceFile: UserFile): File = {
    if (resourceFile.name.startsWith("classpath:"))
      File(new CPResource(UserFileProcessing.DEFAULT_AUTH_FILE_NAME).getPath)
    else
      File(resourceFile.name)
  }
}

// a class used as a data continainer to pass the hash name and password to hash around together
final case class PasswordEncoderData(hashName: String, password: CharSequence)

object UserManagementService {

  /**
   * Parse a list of permissions and split it so that we have a representation of users permissions as :
   * - a set of roles that could be parsed from the permissions
   * - a minimal set of authorization types that are complementary to roles and not present yet in the set of roles
   * - a set of unknown permissions that could neither parsed as roles nor authorization types
   */
  def parsePermissions(
      permissions: Set[String]
  )(implicit allRoles: Set[Role]): (Set[Role], Set[AuthorizationType], Set[String]) = {
    // Everything that is not a role is an authz and remaining authz are put into custom role
    val allRolesByName     = allRoles.map(r => r.name -> r).toMap
    val (remaining, roles) = permissions.partitionMap(r => allRolesByName.get(r).toRight(r))
    val allRoleAuthz       = roles.flatMap(_.rights.authorizationTypes)
    val (unknowns, authzs) = remaining.partitionMap(a => {
      AuthorizationType
        .parseRight(a)
        .map(_.filter(!allRoleAuthz.contains(_)))
        .left
        .map(_ => a)
    })

    (roles, authzs.flatten, unknowns)
  }

  def computeRoleCoverage(roles: Set[Role], authzs: Set[AuthorizationType]): Option[Set[Role]] = {

    def compareRights(r: Role, as: Set[AuthorizationType]): Option[Role] = {
      if (r == Role.NoRights) {
        None
      } else {
        val commonRights = r.rights.authorizationTypes.intersect(as)
        commonRights match {
          // Intersection is total
          case cr if cr == r.rights.authorizationTypes => Some(r)
          case cr if cr.nonEmpty                       =>
            Some(Custom(Rights(cr)))
          case _                                       => None
        }
      }
    }

    if (authzs.isEmpty || roles.isEmpty || authzs.contains(AuthorizationType.NoRights)) {
      None
    } else if (authzs.contains(AuthorizationType.AnyRights)) {
      // only administrator can have that right, and it encompasses every other ones
      Some(Set(Role.Administrator))
    } else {
      val (rs, custom) = roles.flatMap(r => compareRights(r, authzs)).partition {
        case Custom(_) => false
        case _         => true
      }

      val customAuthz     = custom.flatMap(_.rights.authorizationTypes)
      // remove authzs taken by a role in custom's rights
      val minCustomAuthz  = customAuthz.diff(rs.flatMap(_.rights.authorizationTypes))
      val rsRights        = rs.flatMap(_.rights.authorizationTypes)
      val leftoversRights = authzs.diff(rsRights.union(minCustomAuthz))
      val leftoversCustom: Option[Role]      = {
        if (leftoversRights.nonEmpty)
          Some(Custom(Rights(leftoversRights)))
        else
          None
      }
      val data:            Option[Set[Role]] = {
        if (minCustomAuthz.nonEmpty) {
          Some(rs + Custom(Rights(minCustomAuthz)))
        } else if (rs == RudderRoles.getAllRoles.runNow.values.toSet.diff(Set(Role.NoRights: Role))) {
          Some(Set(Role.Administrator))
        } else if (rs.nonEmpty)
          Some(rs)
        else
          None
      }
      leftoversCustom match {
        case Some(c) =>
          data match {
            case Some(r) => Some(r + c)
            case None    => Some(Set(c))
          }
        case None    => data
      }
    }
  }

  private[users] def userExists(id: String, xml: Elem): Boolean = {
    (xml \\ "user" \\ "@name").map(_.text).exists(_ == id)
  }

  // adding a user is just appending it to root node children
  def addUserXmlRewriteRule(
      newUser:         User,
      isPreHashed:     Boolean,
      passwordEncoder: PasswordEncoderData => String
  ): Elem => PureResult[RewriteRule] = (origXml: Elem) => {
    if (userExists(newUser.username, origXml)) {
      Left(Inconsistency(s"User with id '${newUser.username}' already exists and can't be added"))
    } else {
      Right(new RewriteRule {
        override def transform(n: Node): Seq[Node] = {
          n match {
            case e: Elem if (e.label == UserManagementIO.AUTHENTICATION_ROOT_ELT) =>
              val hashedUser = {
                if (isPreHashed) {
                  newUser
                } else {
                  newUser.copy(password = passwordEncoder(PasswordEncoderData((origXml \ "@hash").text, newUser.password)))
                }
              }
              e.copy(child = e.child ++ hashedUser.toNode)

            case _ => n
          }
        }
      })
    }
  }

  // deleting a user is replacing its node by NodeSeq.empty
  def deleteUserXmlRewriteRule(userName: String): Elem => PureResult[RewriteRule] = _ => {
    Right(new RewriteRule {
      override def transform(n: Node): NodeSeq = n match {
        case user: Elem if (user \ "@name").text == userName => NodeSeq.Empty
        case other => other
      }
    })
  }

  // updating a user is changing its node by the updated ones
  def updateUserXmlRewriteRule(
      id:              String,
      username:        String,
      password:        String,
      permissions:     Option[Set[String]],
      isPreHashed:     Boolean,
      passwordEncoder: PasswordEncoderData => String
  ): Elem => PureResult[RewriteRule] = (origXml: Elem) => {
    if (!userExists(id, origXml)) {
      Left(Inconsistency(s"User with id '${id}' does not exist and can't be updated"))
    } else {
      Right(new RewriteRule {
        override def transform(n: Node): NodeSeq = n match {
          case user: Elem if (user \ "@name").text == id =>
            // for each user's parameters, if a new user's parameter is empty we decide to keep the original one
            val newUsername = if (username.isEmpty) id else username
            val newPassword = if (password.isEmpty) {
              (user \ "@password").text
            } else {
              if (isPreHashed) password
              else passwordEncoder(PasswordEncoderData((origXml \ "@hash").text, password))
            }
            // return the user to update in the file with the resolved permissions
            User
              .make(
                newUsername,
                newPassword,
                permissions.getOrElse(((user \ "@role") ++ (user \ "@permissions")).text.split(",").toSet),
                (user \ "@tenants").text
              )
              .toNode
          case other => other
        }
      })
    }
  }

}

class UserManagementService(
    userRepository:      UserRepository,
    userService:         FileUserDetailListProvider,
    encoderDispatcher:   PasswordEncoderDispatcher,
    getUserResourceFile: IOResult[UserFile]
) {

  /*
   * For now, when we add an user, we always add it in the XML file (and not only in database).
   * So we let the callback on file reload does what it needs.
   */
  def add(newUser: User, isPreHashed: Boolean): IOResult[Unit] = {
    for {
      file <- getUserResourceFile.map(getUserFilePath(_))
      _    <- UserManagementIO.transformUserFile(
                file,
                addUserXmlRewriteRule(
                  newUser,
                  isPreHashed,
                  data => getHashEncoderOrDefault(data.hashName).encode(data.password)
                )
              )
      _    <- userService.reloadPure()
    } yield ()
  }

  /*
   * When we delete an user, it can be from file or auto-added by OIDC or other backend supporting that.
   * So we let the callback on file reload do the deletion for file users
   */
  def remove(toDelete: String, actor: EventActor, reason: String): IOResult[Unit] = {
    for {
      file <- getUserResourceFile.map(getUserFilePath(_))
      _    <- UserManagementIO.transformUserFile(
                file,
                deleteUserXmlRewriteRule(toDelete)
              )
      _    <- userService.reloadPure()
    } yield ()
  }

  /*
   * This method contains all logic of updating a user, taking into account :
   * - the providers list for the user and their ability to extend roles from user file
   * - the password definition and hashing
   */
  def update(id: String, username: String, password: String, permissions: Option[List[String]], isPreHashed: Boolean)(
      allRoles: Map[String, Role]
  ): IOResult[ParsedUser] = {
    implicit val currentRoles: Set[Role] = allRoles.values.toSet

    // Unknown permissions are trusted and put in file
    val optNewPermissions: Option[Set[String]] = permissions
      .map(p => {
        UserManagementService.parsePermissions(p.toSet) match {
          case (roles, authz, unknown) => roles.map(_.name) ++ authz.map(_.id) ++ unknown
        }
      })

    for {
      // this is only to check user exists
      _     <- userRepository
                 .get(id)
                 .notOptional(s"User '${id}' does not exist therefore cannot be updated")

      // we may have users that where added by other providers, and still want to add them in file
      file  <- getUserResourceFile.map(getUserFilePath(_))
      // This block initializes the user in the file, for permissions and password to be updated below
      _     <- ZIO.when(!(userService.authConfig.users.keySet.contains(id))) {
                 // Apparently we need to do the whole thing to get the file and add it, before doing it again to update
                 UserManagementIO.transformUserFile(
                   file,
                   addUserXmlRewriteRule(User(id, "", Set.empty, None), isPreHashed = true, _ => "")
                 )
               }
      _     <- UserManagementIO.transformUserFile(
                 file,
                 updateUserXmlRewriteRule(
                   id,
                   username,
                   password,
                   optNewPermissions,
                   isPreHashed,
                   data => getHashEncoderOrDefault(data.hashName).encode(data.password)
                 )
               )
      users <- userService.reloadPure()
      res   <- users.parsedUsers.get(id).notOptional(s"User '${id}' was not found after updated")
    } yield {
      res
    }
  }

  /**
   * User information fields in the database
   */
  def updateInfo(id: String, updateUser: UpdateUserInfo): IOResult[Unit] = {
    // always update fields, at worst they will be updated with an empty value
    userRepository.updateInfo(
      id,
      Some(updateUser.name),
      Some(updateUser.email),
      updateUser.otherInfo
    )
  }

  def getAll: IOResult[UserFileInfo] = {
    for {
      file       <- getUserResourceFile.map(getUserFilePath(_))
      parsedFile <- IOResult.attempt(ConstructingParser.fromFile(file.toJava, preserveWS = true))
      userXML    <- IOResult.attempt(parsedFile.document().children)
      res        <- (userXML \\ "authentication").head match {
                      case e: Elem =>
                        val digest = (userXML \\ "authentication" \ "@hash").text.toUpperCase
                        val users  = e
                          .map(u => {
                            val name         = (u \ "@name").text
                            val password     = (u \ "@password").text
                            val permissions  = ((u \ "@role") ++ (u \ "@permissions")).map(_.text).toSet
                            val tenants      = (u \ "@tenants").text
                            val user         =
                              User.make(name, password, permissions, tenants)
                            val hasValidHash = UserOrigin.verifyHash(digest, password)
                            UserOrigin(user, hasValidHash)
                          })
                          .toList
                        UserFileInfo(users, digest).succeed
                      case _ =>
                        Unexpected(s"Wrong formatting : ${file.path}").fail
                    }
    } yield res
  }

  private def getHashEncoderOrDefault(hash: String): PasswordEncoder = {
    encoderDispatcher.dispatch(PasswordEncoderType.withNameInsensitiveOption(hash).getOrElse(PasswordEncoderType.DEFAULT))
  }
}
