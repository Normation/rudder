/*
*************************************************************************************
* Copyright 2016 Normation SAS
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

package com.normation.rudder.services.quicksearch


import scala.util.parsing.combinator.RegexParsers
import net.liftweb.common.{ Failure => FailedBox, _ }
import com.normation.utils.Control.sequence

/**
 * This file contains an implementation of the query parser
 */

object QSRegexQueryParser extends RegexParsers {

  /*
   *  parse a string like:
   *
   *
   *  """/var/log/rudder/ object:directive key:attr.value"""
   *  """object:directive /var/log/rudder key:attr.value"""
   *  """object:group this is some groupe description I want key:description"""
   *
   *  But we don't want to allows a splitted "lookup value". So filters are
   *  going either at the start or at the end.
   *
   *  Space are not relevant between tokens (meaning that we will have a hard time
   *  have spaces at start or end of lookup value).
   *
   */


  /*
   * In our parser, whitespace are relevant, but only in the query string,
   * not as a separator of different tokens.
   */
  override val skipWhitespace = true

  /*
   * Our AST for interpolated variable:
   * A string to look for interpolation is a list of token.
   * A token can be a plain string with no variable, or something
   * to interpolate. For now, we can interpolate two kind of variables:
   * - node information (thanks to a pointed path to the intersting property)
   * - rudder parameters (only globals for now)
   */
  sealed trait Token

  //the user query string to look-up
  sealed trait      QueryString       extends Token
  final case class  CharSeq(s:String) extends QueryString
  final case object EmptyQuery        extends QueryString

  //filters like object:rule,directive
  sealed trait     Filter                                   extends Token
  final case class ObjectFilter   (objects:    Set[String]) extends Filter
  final case class AttributeFilter(attributes: Set[String]) extends Filter


  //// parsing language

  /*
   * We want to parse a string that:
   * - starts or/and ends with 0 or more filter
   * - a filter is one of an object or a key filter
   * - have a query string, which is in one piece, and is exactly parsed as it is (unicode, regex, etc)
   *   - in particular, regex must not be interpreted, because they are often use in groups
   */
  private[this] def all: Parser[(QueryString, List[Filter])] = ( filter.* ~ ( queryString | emptyQueryString ) ~ filter.* ) ^^ {
    case ~(~(f1, q), f2) => (q, f1 ::: f2)
  }


  //maches exaclty a name (case insensitive)
  //modifier: see http://docs.oracle.com/javase/7/docs/api/java/util/regex/Pattern.html
  //i: ignore case
  //u: considere unicode char for ignore case
  //s: match end of line in ".*" pattern
  //m: multi-lines mode
  //exactly quote s - no regex authorized in
  private[this] def exact(s: String) = ("""(?iu)\Q""" + s + """\E""").r

  // deal with filters, either objects or attributes
  private[this] def filter         : Parser[Filter] = ( objectFilter | attributeFilter )

  // a filter
  private[this] def objectFilter   : Parser[Filter] = exact("object")    ~> ":" ~> filterKey ^^ { x => ObjectFilter   (Set(x)) }
  private[this] def attributeFilter: Parser[Filter] = exact("attribute") ~> ":" ~> filterKey ^^ { x => AttributeFilter(Set(x)) }

  // the key part
  private[this] def filterKey      : Parser[String] = """[\-_a-zA-Z0-9]+""".r

  // and what the user is actually looking for.
  private[this] def queryString     : Parser[QueryString] = """(?iums)(.*\S.*)""".r ^^ { x => CharSeq(x.trim) }
  private[this] def emptyQueryString: Parser[QueryString] = """(?iums)(\s)*""" .r ^^^  { EmptyQuery }


  /*
   * just call the parser on a value, and in case of successful parsing, interprete
   * the resulting AST (seq of token)
   */
  def parse(value: String): Box[Query] = {
    parseAll(all, value) match {
      case NoSuccess(msg   , remaining) => FailedBox(s"""Error when parsing query "${value}", error message is: ${msg}""")
      case Success  (parsed, remaining) => interprete(parsed)
    }
  }


  /*
   * The funny part that for each token add the interpretation of the token
   * by composing interpretation function.
   */
  def interprete(parsed: (QueryString, List[Filter])): Box[Query] = {

    parsed match {
      case (EmptyQuery, _) =>
        FailedBox("No query string was found (the query is only composed of whitespaces and filters)")

      case (CharSeq(query), filters) =>
        val objects    = filters.collect { case ObjectFilter   (set) => set }.flatten.toSet
        val attributes = filters.collect { case AttributeFilter(set) => set }.flatten.toSet
        for {
          objs  <- getObjects(objects)
          attrs <- getAttributes(attributes)
        } yield {
          Query(query, objs, attrs)
        }
    }
  }


  /**
   * Mapping between a string and actual (objects, attribute).
   * We try to be kind with users: not case sensitive, not plural sensitive
   */
  private[this] def toNamesMapping[T](set: Set[T], name: T => String): Map[String, T] = {
    set.map { obj =>
      val n = name(obj).toLowerCase
      (n -> obj) :: ( n + "s" -> obj) :: Nil
    }.flatten.toMap
  }

  private[this] val objectNameMapping    = toNamesMapping[QSObject]   (QSObject.all   , _.name)
  private[this] val attributeNameMapping = toNamesMapping[QSAttribute](QSAttribute.all, _.name)
  private[this] def getMapping[T](names: Set[String], map: Map[String, T], possible: Seq[String]): Box[Set[T]] = {
    sequence(names.toSeq) { name =>
      map.get(name.toLowerCase) match {
        case Some(obj) => Full(obj)
        case None      => FailedBox(s"Requested object type '${name}' is not known. Please choose among '${possible.mkString("', '")}'")
      }
    }.map( _.toSet )
  }


  /**
   * Mapping between a string and actual object.
   * We try to be kind with users: not case sensitive, not plural sensitive
   */
  private[this] def getObjects(names: Set[String]): Box[Set[QSObject]] = {
    getMapping(names, objectNameMapping, QSObject.all.map( _.name).toSeq.sorted)
  }

  /**
   * Mapping between a string and actual attributes.
   * We try to be kind with users: not case sensitive, not plural sensitive
   */
  private[this] def getAttributes(names: Set[String]): Box[Set[QSAttribute]] = {
    getMapping(names, attributeNameMapping, QSAttribute.all.map( _.name).toSeq.sorted)
  }


}

