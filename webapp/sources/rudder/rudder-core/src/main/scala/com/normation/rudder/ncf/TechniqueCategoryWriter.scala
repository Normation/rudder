/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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

package com.normation.rudder.ncf

import better.files.File
import com.normation.cfclerk.domain.RootTechniqueCategoryId
import com.normation.cfclerk.domain.SubTechniqueCategoryId
import com.normation.cfclerk.domain.TechniqueCategory
import com.normation.cfclerk.domain.TechniqueCategoryId
import com.normation.cfclerk.domain.TechniqueCategoryMetadata
import com.normation.cfclerk.domain.TechniqueCategoryName
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.cfclerk.services.UpdateTechniqueLibrary
import com.normation.errors.*
import com.normation.rudder.repository.xml.TechniqueArchiver
import com.normation.rudder.tenants.ChangeContext
import com.normation.utils.FileUtils
import zio.*
import zio.syntax.*

/*
 * User technique category (whose written with technique editor under `techniques/ncf_techniques`).
 */
object UserTechniqueCategory {
  val name: TechniqueCategoryName  = TechniqueCategoryName("ncf_techniques")
  val id:   SubTechniqueCategoryId = SubTechniqueCategoryId(name, RootTechniqueCategoryId)
  val path: String                 = name.value

  // that category itself, or one of its descendants
  def contains(catId: TechniqueCategoryId): Boolean = catId.getIdPathFromRoot.contains(id)
}

/*
 * Category directory name. It must be FS valid, and we are quite restrictive in addition.
 * Non authorized chars are replaced by "_".
 * Lower cased so that uniqueness is maintained even in case-insensitive FS.
 */
final case class TechniqueCategoryDirName private (value: String)

object TechniqueCategoryDirName {

  private val unsafeChars    = """[^a-z0-9_-]""".r
  private val underscoreRuns = """_+""".r
  private val trimmed        = """^[._]+|[._]+$""".r
  // ext4/xfs/ntfs all stop at 255 bytes for a single path segment
  private val maxLength      = 255

  def fromDisplayName(displayName: String): PureResult[TechniqueCategoryDirName] = {
    // lower case first, so that replacing the unsafe chars is what has the last word
    val dirName = trimmed
      .replaceAllIn(
        underscoreRuns.replaceAllIn(unsafeChars.replaceAllIn(displayName.toLowerCase(), "_"), "_").take(maxLength),
        ""
      )

    if (dirName.isEmpty) {
      Left(
        Inconsistency(
          s"Category name '${displayName}' can not be used to build a directory name: it has no letter, digit, '-' nor '_'"
        )
      )
    } else Right(new TechniqueCategoryDirName(dirName))
  }
}

/*
 * Create, rename and delete user technique categories.
 * A category is a directory holding a `category.xml` descriptor and changes need to be commited in git.
 */
trait TechniqueCategoryWriter {

  /*
   * Create a sub-category of `parent`. Its ID is derived from `name` and must be free.
   */
  def createCategory(parent: TechniqueCategoryId, name: String, description: String)(implicit
      cc: ChangeContext
  ): IOResult[TechniqueCategoryInfo]

  /*
   * Update a category. Its ID (the directory name) never changes.
   *
   * An absent field is left as it is; a field that is there is written, so an empty description
   * removes it. A name is never allowed to be empty and category ID is then used.
   */
  def updateCategory(id: TechniqueCategoryId, name: Option[String], description: Option[String])(implicit
      cc: ChangeContext
  ): IOResult[TechniqueCategoryInfo]

  /*
   * Delete a category and all the sub-categories it holds. It fails if a technique is defined anywhere below it.
   */
  def deleteCategory(id: TechniqueCategoryId)(implicit cc: ChangeContext): IOResult[Unit]
}

final case class TechniqueCategoryInfo(id: TechniqueCategoryId, name: String, description: String)

class TechniqueCategoryWriterImpl(
    archiver:            TechniqueArchiver,
    techLibUpdate:       UpdateTechniqueLibrary,
    techniqueRepository: TechniqueRepository,
    baseConfigRepoPath:  String // root of the configuration repository
) extends TechniqueCategoryWriter {

  private val techniquesDir: File = File(baseConfigRepoPath) / "techniques"

  override def createCategory(parent: TechniqueCategoryId, name: String, description: String)(implicit
      cc: ChangeContext
  ): IOResult[TechniqueCategoryInfo] = {
    for {
      _       <- checkManageable(parent)
      _       <- techniqueRepository.getTechniqueCategory(parent)
      _       <- checkNameNotEmpty(name)
      dirName <- TechniqueCategoryDirName.fromDisplayName(name).toIO
      _       <- checkDirNameFree(dirName.value)
      id       = SubTechniqueCategoryId(TechniqueCategoryName(dirName.value), parent)
      segments = pathSegments(id)
      _       <- FileUtils.sanitizePath(techniquesDir, segments).chainError(s"'${name}' is not a valid category name")
      _       <- ZIO.whenZIO(IOResult.attempt((techniquesDir / segments.mkString("/")).exists)) {
                   Inconsistency(
                     s"Directory '${segments.mkString("/")}' already exists in the technique library: " +
                     s"please choose another name for category '${name}'"
                   ).fail
                 }
      metadata = TechniqueCategoryMetadata(name, description, isSystem = false, cc.accessGrant.toSecurityTag)
      _       <- archiver.saveTechniqueCategory(
                   segments,
                   metadata,
                   cc.modId,
                   cc.actor,
                   cc.message.getOrElse(s"Add technique category '${segments.mkString("/")}'")
                 )
      _       <- reloadTechniqueLibrary
    } yield TechniqueCategoryInfo(id, name, description)
  }

  override def updateCategory(id: TechniqueCategoryId, name: Option[String], description: Option[String])(implicit
      cc: ChangeContext
  ): IOResult[TechniqueCategoryInfo] = {
    for {
      _       <- checkManageable(id)
      cat     <- techniqueRepository.getTechniqueCategory(id)
      _       <- checkNotSystem(cat)
      _       <- ZIO.foreachDiscard(name)(checkNameNotEmpty)
      newName  = name.getOrElse(cat.name)
      newDesc  = description.getOrElse(cat.description)
      segments = pathSegments(id)
      metadata = TechniqueCategoryMetadata(newName, newDesc, cat.isSystem, cat.security)
      _       <- archiver.saveTechniqueCategory(
                   segments,
                   metadata,
                   cc.modId,
                   cc.actor,
                   cc.message.getOrElse(s"Update technique category '${segments.mkString("/")}'")
                 )
      _       <- reloadTechniqueLibrary
    } yield TechniqueCategoryInfo(id, newName, newDesc)
  }

  override def deleteCategory(id: TechniqueCategoryId)(implicit cc: ChangeContext): IOResult[Unit] = {
    for {
      _       <- checkManageable(id)
      _       <- ZIO.when(id == UserTechniqueCategory.id) {
                   Inconsistency(
                     s"Category '${UserTechniqueCategory.path}' is needed by the technique editor and can not be deleted"
                   ).fail
                 }
      cat     <- techniqueRepository.getTechniqueCategory(id)
      _       <- checkNotSystem(cat)
      _       <- checkNothingToLose(cat)
      segments = pathSegments(id)
      _       <- archiver.deleteCategoryRecursively(
                   id,
                   cc.modId,
                   cc.actor,
                   cc.message.getOrElse(s"Delete technique category '${segments.mkString("/")}'")
                 )
      _       <- reloadTechniqueLibrary
    } yield ()
  }

  /*
   * Path of a category relative to the technique library root, ie what the archiver and the
   * file system use. The root category is not part of it.
   */
  private def pathSegments(id: TechniqueCategoryId): List[String] = id.getPathFromRoot.tail.map(_.value)

  private def checkManageable(id: TechniqueCategoryId): IOResult[Unit] = {
    ZIO
      .unless(UserTechniqueCategory.contains(id)) {
        Inconsistency(
          s"Category '${id.toString}' is not under '${UserTechniqueCategory.path}': " +
          s"only the categories of the technique editor can be managed here"
        ).fail
      }
      .unit
  }

  /*
   * A category directory name must be unique (case-insensitive) in the whole library, whatever its parent.
   */
  private def checkDirNameFree(dirName: String): IOResult[Unit] = {
    techniqueRepository.getAllCategories.keys.collectFirst {
      case id: SubTechniqueCategoryId if id.name.value.equalsIgnoreCase(dirName) => id
    } match {
      case None     => ZIO.unit
      case Some(id) =>
        Inconsistency(
          s"A category with directory name '${dirName}' already exists at '${TechniqueCategoryId.serialize(id)}': " +
          s"directory names must be unique in the whole technique library"
        ).fail
    }
  }

  private def checkNameNotEmpty(name: String): IOResult[Unit] = {
    ZIO.when(name.trim.isEmpty)(Inconsistency("The name of a category can not be empty").fail).unit
  }

  private def checkNotSystem(cat: TechniqueCategory): IOResult[Unit] = {
    ZIO.when(cat.isSystem)(Inconsistency(s"Category '${cat.name}' is a system category and can not be modified").fail).unit
  }

  /*
   * A delete is a `git rm -r`, so nothing of value must be left below: no technique, and nothing
   * on FS but the descriptors of the categories we know about.
   */
  private def checkNothingToLose(cat: TechniqueCategory): IOResult[Unit] = {
    val categories = techniqueRepository.getAllCategories

    def rec(c: TechniqueCategory): IOResult[Unit] = {
      val subs = c.subCategoryIds.flatMap(categories.get)
      for {
        _ <- ZIO.when(c.techniqueIds.nonEmpty) {
               Inconsistency(
                 s"Category '${c.name}' can not be deleted: it still holds ${c.techniqueIds.size} technique(s). " +
                 s"Please delete them or move them to another category first"
               ).fail
             }
        _ <- checkDirOnlyHoldsCategories(c, subs.map(_.id.name.value))
        _ <- ZIO.foreachDiscard(subs)(rec)
      } yield ()
    }

    rec(cat)
  }

  private def checkDirOnlyHoldsCategories(cat: TechniqueCategory, subCategoryDirs: Set[String]): IOResult[Unit] = {
    val dir = techniquesDir / pathSegments(cat.id).mkString("/")
    for {
      children <- IOResult.attempt(dir.children.map(_.name).toSet)
      leftover  = children -- subCategoryDirs - TechniqueCategoryMetadata.FILE_NAME_XML
      _        <- ZIO.when(leftover.nonEmpty) {
                    Inconsistency(
                      s"Category '${cat.name}' can not be deleted: its directory still holds ${leftover.toList.sorted.mkString(", ")}"
                    ).fail
                  }
    } yield ()
  }

  private def reloadTechniqueLibrary(implicit cc: ChangeContext): IOResult[Unit] = {
    techLibUpdate
      .update()
      .toIO
      .chainError("An error occurred while reloading the technique library after a category change")
      .unit
  }
}
