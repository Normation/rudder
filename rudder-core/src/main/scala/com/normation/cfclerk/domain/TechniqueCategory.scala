/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.cfclerk.domain

import com.normation.utils.HashcodeCaching
import scala.collection.SortedSet
import net.liftweb.common._

/**
 * A policy category name.
 * It must be unique only in the context of
 * a parent category (i.e: all subcategories of
 * a given categories must have different names)
 */
case class TechniqueCategoryName(value:String) extends HashcodeCaching {
  require(null != value && value.length > 0, "Id of a category can not be null nor empty")
}

object RootTechniqueCategoryName extends TechniqueCategoryName("/")


sealed abstract class TechniqueCategoryId(val name:TechniqueCategoryName) {

  /**
   * Allows to build a subcategory with
   * catId / "subCatName"
   */
  def /(subCategoryName:String) : SubTechniqueCategoryId =
    SubTechniqueCategoryId(TechniqueCategoryName(subCategoryName),this)

  /**
   * The toString of a SubTechniqueCategoryId is its path
   * from root with "/" as separator
   */
  override lazy val toString = getPathFromRoot.tail.map( _.value ).mkString("/", "/", "")

  /**
   * The list of category from root to that one
   * (including that one)
   */
  lazy val getPathFromRoot = TechniqueCategoryId.pathFrom(this).reverse
  lazy val getIdPathFromRoot = TechniqueCategoryId.idPathFrom(this).reverse

  /**
   * The list of category from root to the
   * parent of that one (son excluding that one)
   */
  lazy val getParentPathFromRoot = this match {
    case RootTechniqueCategoryId => Nil
    case s:SubTechniqueCategoryId => TechniqueCategoryId.pathFrom(s)
  }

  lazy val getParentIdPathFromRoot = this match {
    case RootTechniqueCategoryId => Nil
    case s:SubTechniqueCategoryId => TechniqueCategoryId.idPathFrom(s)
  }
}

case object RootTechniqueCategoryId extends TechniqueCategoryId(RootTechniqueCategoryName)

case class SubTechniqueCategoryId(
    override val name: TechniqueCategoryName,
    parentId : TechniqueCategoryId
) extends TechniqueCategoryId(name) with HashcodeCaching {


}

object TechniqueCategoryId {

  /**
   * Build the path from the given TechniqueCategory
   * up to the root
   */
  def pathFrom(id:TechniqueCategoryId) : List[TechniqueCategoryName] =
    id match {
      case RootTechniqueCategoryId => id.name :: Nil
      case SubTechniqueCategoryId(name, pId) => id.name :: pathFrom(pId)
    }

  def idPathFrom(id:TechniqueCategoryId) : List[TechniqueCategoryId] =
    id match {
      case RootTechniqueCategoryId => id :: Nil
      case SubTechniqueCategoryId(name, pId) => id :: idPathFrom(pId)
    }


  private[this] val empty = """^[\s]*$""".r

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
  def buildId(path:String) : TechniqueCategoryId = {
    val absPath = "/" + path
    val parts = absPath.split("/").filterNot(x => empty.findFirstIn(x).isDefined)
    ( (RootTechniqueCategoryId:TechniqueCategoryId) /: parts) { (id,name) =>
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
  def id : A
  val name : String
  val description : String
  val subCategoryIds: Set[SubTechniqueCategoryId]
  val packageIds : SortedSet[TechniqueId]
  val isSystem : Boolean

  require(subCategoryIds.forall(sc => sc.parentId == id),
      "Unconsistancy in the Technique Category; one of the subcategories is not marked as having [%s] as parent. Subcategory ids: %s".format(
          id.toString,
          subCategoryIds.map( _.toString).mkString("\n", "\n", "\n")
      ) )
}

case class RootTechniqueCategory(
    name          : String
  , description   : String
  , subCategoryIds: Set[SubTechniqueCategoryId] = Set()
  , packageIds    : SortedSet[TechniqueId] = SortedSet()
  , isSystem      : Boolean = false
) extends TechniqueCategory with HashcodeCaching {
  type A = RootTechniqueCategoryId.type
  override lazy val id : A = RootTechniqueCategoryId
}

case class SubTechniqueCategory(
    override val id : SubTechniqueCategoryId
  , name            : String
  , description     : String
  , subCategoryIds  : Set[SubTechniqueCategoryId] = Set()
  , packageIds      : SortedSet[TechniqueId] = SortedSet()
  , isSystem        : Boolean = false
) extends TechniqueCategory with HashcodeCaching {
  type A = SubTechniqueCategoryId
}

