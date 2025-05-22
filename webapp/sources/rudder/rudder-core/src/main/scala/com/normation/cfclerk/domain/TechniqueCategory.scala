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

package com.normation.cfclerk.domain

import better.files.File
import com.normation.errors.IOResult
import com.normation.rudder.domain.policies.ActiveTechniqueCategory
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.utils.XmlSafe
import scala.collection.SortedSet
import scala.xml.Elem
import zio.json.*

/**
 * A policy category name.
 * It must be unique only in the context of
 * a parent category (i.e: all subcategories of
 * a given categories must have different names)
 */
final case class TechniqueCategoryName(value: String) extends AnyVal

/*
 * Just the name / description of a technique category without all the
 * parent / subcategories / techniques stuff.
 */
final case class TechniqueCategoryMetadata(name: String, description: String, isSystem: Boolean)

object TechniqueCategoryMetadata {
  implicit val codecTechniqueCategoryMetadata: JsonCodec[TechniqueCategoryMetadata] = DeriveJsonCodec.gen

  implicit class ToActiveTechniqueCategory(metadata: TechniqueCategoryMetadata) {
    def toActiveTechniqueCategory(id: ActiveTechniqueCategoryId): ActiveTechniqueCategory = ActiveTechniqueCategory(
      id,
      metadata.name,
      metadata.description,
      Nil,
      Nil
    )

    def toXml: Elem = {
      <xml>
        <name>{metadata.name}</name>
        <description>{metadata.description}</description>
        {if (metadata.isSystem) <system>true</system> else xml.NodeSeq.Empty}
      </xml>
    }
  }

  def parse(file: File, defaultName: String): IOResult[TechniqueCategoryMetadata] = {
    IOResult.attempt(s"Error when parsing category descriptor for '${defaultName}' at '${file.pathAsString}'") {
      parseXML(XmlSafe.loadFile(file.toJava), defaultName)
    }
  }

  def parseXML(xml: Elem, defaultName: String): TechniqueCategoryMetadata = {
    def nonEmpty(s: String): Option[String] = {
      s match {
        case null | "" => None
        case _         => Some(s)
      }
    }

    val name        = nonEmpty((xml \\ "name").text).getOrElse(defaultName)
    val description = nonEmpty((xml \\ "description").text).getOrElse("")
    val isSystem    = (nonEmpty((xml \\ "system").text).getOrElse("false")).equalsIgnoreCase("true")

    TechniqueCategoryMetadata(name, description, isSystem = isSystem)
  }

  // the default file name for category metadata.
  val FILE_NAME_XML  = "category.xml"
  val FILE_NAME_JSON = "category.json"
}

sealed abstract class TechniqueCategoryId(val name: TechniqueCategoryName) {

  /**
   * Allows to build a subcategory with
   * catId / "subCatName"
   */
  def /(subCategoryName: String): SubTechniqueCategoryId =
    SubTechniqueCategoryId(TechniqueCategoryName(subCategoryName), this)

  /**
   * The toString of a SubTechniqueCategoryId is its path
   * from root with "/" as separator
   */
  override lazy val toString: String = getPathFromRoot.tail.map(_.value).mkString("/", "/", "")

  /**
   * The list of category from root to that one
   * (including that one)
   */
  lazy val getPathFromRoot:   List[TechniqueCategoryName] = TechniqueCategoryId.pathFrom(this).reverse
  lazy val getIdPathFromRoot: List[TechniqueCategoryId]   = TechniqueCategoryId.idPathFrom(this).reverse

  /**
   * The list of category from root to the
   * parent of that one (son excluding that one)
   */
  lazy val getParentPathFromRoot: List[TechniqueCategoryName] = this match {
    case RootTechniqueCategoryId => Nil
    case s: SubTechniqueCategoryId => TechniqueCategoryId.pathFrom(s)
  }

  lazy val getParentIdPathFromRoot: List[TechniqueCategoryId] = this match {
    case RootTechniqueCategoryId => Nil
    case s: SubTechniqueCategoryId => TechniqueCategoryId.idPathFrom(s)
  }
}

case object RootTechniqueCategoryId extends TechniqueCategoryId(TechniqueCategoryName("/"))

final case class SubTechniqueCategoryId(
    override val name: TechniqueCategoryName,
    parentId:          TechniqueCategoryId
) extends TechniqueCategoryId(name)

object TechniqueCategoryId {

  /**
   * Build the path from the given TechniqueCategory
   * up to the root
   */
  def pathFrom(id: TechniqueCategoryId): List[TechniqueCategoryName] = {
    id match {
      case RootTechniqueCategoryId           => id.name :: Nil
      case SubTechniqueCategoryId(name, pId) => id.name :: pathFrom(pId)
    }
  }

  def idPathFrom(id: TechniqueCategoryId): List[TechniqueCategoryId] = {
    id match {
      case RootTechniqueCategoryId           => id :: Nil
      case SubTechniqueCategoryId(name, pId) => id :: idPathFrom(pId)
    }
  }

  private val empty = """^[\s]*$""".r

  /**
   * Build a category id from a path.
   * The path must follow the unix syntaxe (a/b/c).
   * If it does not start with a "slash", it is assumed that it
   * is relative to root so that the only difference between relative and absolute
   * paths is the root.
   * Trailing slashes are removed (i.e /a/b == /a/b/ == /a/b//)
   * Each part is checck to be non-empty (i.e: /a/b == /a//b == //a///b)
   * No other verification are done on the last element.
   * A path must contains at least one non empty element to be a valid path,
   * root being considered as a valid non empty element, so that :
   * - "/" is valid
   * - "/    " is valid and == "/" == "    /   / " (because in the last case,
   *   root is appended to the relative path, and then all other element are empty
   * - "    " is valid and == "/"
   */
  def buildId(path: String): TechniqueCategoryId = {
    val absPath = "/" + path
    val parts   = absPath.split("/").filterNot(x => empty.findFirstIn(x).isDefined)
    parts.foldLeft((RootTechniqueCategoryId: TechniqueCategoryId)) { (id, name) =>
      SubTechniqueCategoryId(TechniqueCategoryName(name), id)
    }
  }
}

/**
 * That class define a node in the hierarchy of techniques.
 * It's a code representation of the file system  hierarchy.
 *
 */
sealed trait TechniqueCategory {
  type A <: TechniqueCategoryId
  def id: A
  val name:           String
  val description:    String
  val subCategoryIds: Set[SubTechniqueCategoryId]
  val techniqueIds:   SortedSet[TechniqueId]
  val isSystem:       Boolean

  require(
    subCategoryIds.forall(sc => sc.parentId == id),
    "Inconsistency in the Technique Category; one of the subcategories is not marked as having [%s] as parent. Subcategory ids: %s"
      .format(
        id.toString,
        subCategoryIds.map(_.toString).mkString("\n", "\n", "\n")
      )
  )
}

final case class RootTechniqueCategory(
    name:           String,
    description:    String,
    subCategoryIds: Set[SubTechniqueCategoryId] = Set(),
    techniqueIds:   SortedSet[TechniqueId] = SortedSet(),
    isSystem:       Boolean = false
) extends TechniqueCategory {
  type A = RootTechniqueCategoryId.type
  override lazy val id: A = RootTechniqueCategoryId
}

final case class SubTechniqueCategory(
    override val id: SubTechniqueCategoryId,
    name:            String,
    description:     String,
    subCategoryIds:  Set[SubTechniqueCategoryId] = Set(),
    techniqueIds:    SortedSet[TechniqueId] = SortedSet(),
    isSystem:        Boolean = false
) extends TechniqueCategory {
  type A = SubTechniqueCategoryId
}
