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

import com.normation.errors._

/**
 * This file contains an implementation of the query parser
 */

object QSRegexQueryParser {


  /*
   * In our parser, whitespace are relevant, but only in the query string,
   * not as a separator of different tokens.
   */
  import fastparse._, SingleLineWhitespace._

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
   * just call the parser on a value, and in case of successful parsing, interprete
   * the resulting AST (seq of token)
   */
  def parse(value: String): PureResult[Query] = {
    if(value.trim.isEmpty()) {
      Left(Unexpected("You can't search with an empty or whitespace only query"))
    } else {
      fastparse.parse(value, all(_)) match {
        case Parsed.Success(parsed, index)   => interprete(parsed)
        case Parsed.Failure(label, i, extra) => Left(Unexpected(s"""Error when parsing query "${value}", error message is: ${label}"""))
      }
    }
  }

  /*
   * The funny part that for each token add the interpretation of the token
   * by composing interpretation function.
   */
  def interprete(parsed: (QueryString, List[Filter])): PureResult[Query] = {

    parsed match {
      case (EmptyQuery, _) =>
        Left(Unexpected("No query string was found (the query is only composed of whitespaces and filters)"))
      case (CharSeq(query), filters) =>
        val is = filters.collect { case FilterType(set) => set }.flatten
        val in = filters.collect { case FilterAttr(set) => set }.flatten

        (for {
          o <- getObjects(is.toSet)    chainError("Check 'is' filters")
          a <- getAttributes(in.toSet) chainError("Check 'in' filters")
        } yield {
          val (objs , oKeys) = o
          val (attrs, aKeys) = a

          /*
           * we need to add all attributes and objects if
           * sets are empty - the user just didn't provided any filters.
           */
          Query(
              query
            , if( objs.isEmpty) { QSObject.all    } else { objs  }
            , if(attrs.isEmpty) { QSAttribute.all } else { attrs }
          )
        }) chainError {
          val allNames = (
               QSMapping.objectNameMapping.keys.map( _.capitalize)
            ++ QSMapping.attributeNameMapping.keys
          ).toSeq.sorted.mkString("', '")
          s"Query containts unknown filter. Please choose among '${allNames}'"
        }
    }
  }

  /*
   * Our AST for interpolated variable:
   * A string to look for interpolation is a list of token.
   * A token can be a plain string with no variable, or something
   * to interpolate. For now, we can interpolate two kind of variables:
   * - node information (thanks to a pointed path to the interesting property)
   * - rudder parameters (only globals for now)
   */
  sealed trait Token

  //the user query string to look-up
  sealed trait      QueryString       extends Token
  final case class  CharSeq(s:String) extends QueryString
  final case object EmptyQuery        extends QueryString

  //filters like in:rule,directive,names,descriptions
  sealed trait Filter extends Token { def keys: Set[String] }
  final case class FilterAttr(keys: Set[String]) extends Filter
  final case class FilterType(keys: Set[String]) extends Filter

  //// parsing language

  //// does not work on empty/whitespace only string, forbid them before ////

  /*
   * We want to parse a string that:
   * - starts or/and ends with 0 or more filter
   * - a filter is one of an object or a key filter
   * - have a query string, which is in one piece, and is exactly parsed as it is (unicode, regex, etc)
   *   - in particular, regex must not be interpreted, because they are often use in groups
   */
  type QF = (QueryString, List[Filter])

  /////
  ///// this is the entry point /////
  /////

  private[this] def all[_:P]          : P[QF] = P( Start ~ ( nominal | onlyFilters ) ~ End )

  /////
  ///// different structure of queries
  /////

  //degenerated case with only filters, no query string
  private[this] def onlyFilters[_:P]  : P[QF] = P( filter.rep(1) )                      map { case f             => (EmptyQuery, f.toList) }

  //nonimal case: zero of more filter, a query string, zero or more filter
  private[this] def nominal[_:P]      : P[QF] = P( filter.rep(0) ~/ ( case0 | case1 ) ) map { case (f1, (q, f2)) => (check(q), f1.toList ::: f2.toList) }

  //need the two following rules so that so the parsing is correctly done for filter in the end
  private[this] def case0[_:P]        : P[QF] = P( queryInMiddle ~ filter.rep(1) )      map { case (q, f)        => (check(q)  , f.toList) }
  private[this] def case1[_:P]        : P[QF] = P( queryAtEnd                    )      map { case q             => (check(q)  , Nil     ) }

  /////
  ///// simple elements: filters
  /////

  // deal with filters: they all start with "in:"
  private[this] def filter[_:P]       : P[Filter] = P( filterAttr | filterType )
  private[this] def filterType[_:P]   : P[Filter] = P( IgnoreCase("is:") ~ filterKeys )  map { FilterType }
  private[this] def filterAttr[_:P]   : P[Filter] = P( IgnoreCase("in:") ~ filterKeys )  map { FilterAttr }

  // the keys part
  private[this] def filterKeys[_:P]   : P[Set[String]] = P( filterKey.rep(sep = ",") )   map { l => l.toSet }
  private[this] def filterKey[_:P]    : P[String]      = P( CharsWhileIn("""\\-._a-zA-Z0-9""").! )

  /////
  ///// simple elements: query string
  /////

  // we need to case, because regex are bad to look-ahead and see if there is still filter after. .+? necessary to stop at first filter
  private[this] def queryInMiddle[_:P]: P[QueryString] = P( (!("in:"|"is:") ~ AnyChar).rep(1).! ) map { x => CharSeq(x.trim) }
  private[this] def queryAtEnd[_:P]   : P[QueryString] = P( AnyChar.rep(1).!                    ) map { x => CharSeq(x.trim) }

  /////
  ///// utility methods
  /////

  /*
   * Check that the query is not empty (else, say it is the EmptyQuery),
   * and trimed it.
   */
  private[this] def check(qs: QueryString) = qs match {
    case EmptyQuery => EmptyQuery
    case CharSeq(s) =>
      val trimed = s.trim
      if(trimed.isEmpty) {
        EmptyQuery
      } else {
        CharSeq(trimed)
      }
  }

  private[this] def getMapping[T](names: Set[String], map: Map[String, T]): PureResult[(Set[T], Set[String])] = {
    val pairs = names.flatMap { name =>
      map.get(name.toLowerCase) match {
        case Some(obj) => Some((obj, name))
        case None      => None
      }
    }
    val keys   = pairs.map( _._2).toSet
    val values = pairs.map( _._1).toSet

    if(keys == names) {
      Right((values, keys))
    } else {
      Left(Unexpected(s"Some filters are not know: ${names -- keys}"))
    }
  }

  /**
   * Mapping between a string and actual object.
   * We try to be kind with users: not case sensitive, not plural sensitive
   *
   * Returned the set of matching attribute, and set of matching names for the
   * inputs
   */
  private[this] def getObjects(names: Set[String]): PureResult[(Set[QSObject], Set[String])] = {
    import QSMapping._
    getMapping(names, objectNameMapping)
  }

  /**
   * Mapping between a string and actual attributes.
   * We try to be kind with users: not case sensitive, not plural sensitive
   */
  private[this] def getAttributes(names: Set[String]): PureResult[(Set[QSAttribute], Set[String])] = {
    import QSMapping._
    getMapping(names, attributeNameMapping).map { case (attrs, keys) =>
      (attrs.flatten, keys)
    }
  }
}
