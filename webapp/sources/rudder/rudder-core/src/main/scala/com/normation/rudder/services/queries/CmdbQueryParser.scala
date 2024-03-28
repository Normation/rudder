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

package com.normation.rudder.services.queries

import cats.implicits.*
import com.normation.box.*
import com.normation.rudder.domain.queries.*
import com.normation.rudder.services.queries.CmdbQueryParser.*
import com.normation.utils.Control.traverse
import net.liftweb.common.*
import net.liftweb.json.*
import net.liftweb.json.JsonParser.ParseException

/**
 * This trait is the general interface that
 * transform Query represented as string into
 * our internal Query object.
 * Most of the time, the query parsing will be in
 * two times (lexing and parsing)
 *
 */

//only string version of the query - no domain here
final case class StringCriterionLine(objectType: String, attribute: String, comparator: String, value: Option[String] = None)
final case class StringQuery(
    returnType:  QueryReturnType,
    composition: Option[String],
    transform:   Option[String],
    criteria:    List[StringCriterionLine]
)

object CmdbQueryParser {
  // query attribute
  val TARGET      = "select"
  val COMPOSITION = "composition"
  val CRITERIA    = "where"
  val TRANSFORM   = "transform"

  // criterion attribute
  val OBJECT     = "objectType"
  val ATTRIBUTE  = "attribute"
  val COMPARATOR = "comparator"
  val VALUE      = "value"
}

trait QueryLexer {
  def lex(query: String): Box[StringQuery]
  // We are awaiting for a Map(String,String) as json object,
  // with keys: objectType, attribute, comparator, value
}

trait StringQueryParser {
  def parse(query: StringQuery): Box[Query]
}

trait CmdbQueryParser extends StringQueryParser with QueryLexer {
  def apply(query: String): Box[Query] = for {
    sq <- lex(query)
    q  <- parse(sq)
  } yield q
}

/**
 * Some default behaviour:
 * - default composition is AND
 *
 */
trait DefaultStringQueryParser extends StringQueryParser {

  def criterionObjects: Map[String, ObjectCriterion]

  override def parse(query: StringQuery): Box[Query] = {

    for {
      comp  <- query.composition match {
                 case None    => Full(And)
                 case Some(s) =>
                   CriterionComposition.parse(s) match {
                     case Some(x) => Full(x)
                     case None    => Failure(s"The requested composition '${query.composition}' is not know")
                   }
               }
      trans <- query.transform match {
                 case None    => Full(ResultTransformation.Identity)
                 case Some(x) => ResultTransformation.parse(x).toBox
               }
      lines <- traverse(query.criteria)(parseLine)
    } yield {
      Query(query.returnType, comp, trans, lines.toList)
    }
  }

  def parseLine(line: StringCriterionLine): Box[CriterionLine] = {

    (for {
      objectType <-
        criterionObjects
          .get(line.objectType)
          .toRight(
            s"The object type '${line.objectType}' is unknown in line 'line'. Possible object types: [${criterionObjects.keySet.toList.sorted
                .mkString(",")}] ".format(line)
          )
      criterion  <- objectType.criterionForName(line.attribute).toRight {
                      s"The attribute '${line.attribute}' is unknown for type '${line.objectType}' in line '${line}'. Possible attributes: [${objectType.criteria.map(_.name).sorted.mkString(", ")}]"
                    }

      comparator <- criterion.cType.comparatorForString(line.comparator).toRight {
                      s"The comparator '${line.comparator}' is unknown for attribute '${line.attribute}' in line '${line}'. Possible comparators:: [${criterion.cType.comparators.map(_.id).sorted.mkString(", ")}]"
                    }

      /*
       * Only validate the fact that if the comparator requires a value, then a value is provided.
       * Providing an error when none is required is not an error
       */
      value      <- line.value match {
                      case Some(x) => Right(x)
                      case None    =>
                        if (comparator.hasValue)
                          Left("Missing required value for comparator '%s' in line '%s'".format(line.comparator, line))
                        else Right("")
                    }
    } yield CriterionLine(objectType, criterion, comparator, value)).toBox

  }

}

/**
 * This lexer read and valid syntax of JSON query
 *
 */
trait JsonQueryLexer extends QueryLexer {

  override def lex(query: String): Box[StringQuery] = for {
    json <- jsonLex(query)
    q    <- jsonParse(json)
  } yield q

  def jsonLex(s: String): Box[JValue] = try {
    Full(JsonParser.parse(s))
  } catch {
    case e: ParseException =>
      Failure("Parsing failed when processing query: " + s, Full(e), Empty)
  }

  def failureMissing(s:   String): Failure = Failure("Missing expected '%s' query parameter".format(s))
  def failureEmpty(param: String): Failure = Failure("Parameter '%s' must be non empty in query".format(OBJECT))
  def failureBadFormat(obj: String, f: Any): Failure = Failure(
    "Bad query format for '%s' parameter. Expecting a string, found '%s'".format(obj, f)
  )

  def parseTarget(json: JObject): Box[QueryReturnType] = {
    json.values.get(TARGET) match {
      case None                                    => failureMissing(TARGET)
      case Some(NodeReturnType.value)              => Full(NodeReturnType)
      case Some(NodeAndRootServerReturnType.value) => Full(NodeAndRootServerReturnType)
      case Some(x)                                 => failureBadFormat(TARGET, x)
    }
  }

  def parseComposition(json: JObject): Box[Option[String]] = {
    json.values.get(COMPOSITION) match {
      case None            => Full(None)
      case Some(x: String) => Full(if (x.nonEmpty) Some(x) else None)
      case Some(x)         => failureBadFormat(COMPOSITION, x)
    }
  }

  def parseTransform(json: JObject): Box[Option[String]] = {
    json.values.get(TRANSFORM) match {
      case None            => Full(None)
      case Some(x: String) => Full(if (x.nonEmpty) Some(x) else None)
      case Some(x)         => failureBadFormat(TRANSFORM, x)
    }
  }

  def parseCriterionLine(json: JObject): Box[List[StringCriterionLine]] = {
    json.values.get(CRITERIA) match {
      case None               => Full(List[StringCriterionLine]())
      case Some(arr: List[?]) =>
        // try to parse all lines. On the first parsing error (parseCrtierion returns Failure),
        // stop and return a Failure
        // if all parsing are OK, return a Full(list(criterionLine)
        arr.foldLeft(Full(List[StringCriterionLine]()): Box[List[StringCriterionLine]]) { (opt, x) =>
          opt.flatMap(l => parseCriterion(x).map(_ :: l))
        } match {
          case Full(l) => Full(l.reverse)
          case eb: EmptyBox =>
            val fail = eb ?~! "Parsing criteria yields an empty result, abort"
            fail
        }
      case Some(x)            => failureBadFormat(COMPOSITION, x)
    }
  }
  def jsonParse(json: JValue):           Box[StringQuery]               = {
    /*
     * Structure of the Query:
     * var query = {
     *   'select' : 'server' ,  //what we are looking for at the end (servers, software...)
     *   'composition' : 'and' ,  // or 'or'
     *   'where': [
     *     { 'objectType' : '....' , 'attribute': '....' , 'comparator': '.....' , 'value': '....' } ,  //value is optionnal, other are mandatory
     *     { 'objectType' : '....' , 'attribute': '....' , 'comparator': '.....' , 'value': '....' } ,
     *     ...
     *     { 'objectType' : '....' , 'attribute': '....' , 'comparator': '.....' , 'value': '....' }
     *   ]
     * }
     */

    json match {
      case q @ JObject(attrs) =>
        // target returned object
        for {
          target      <- parseTarget(q)
          composition <- parseComposition(q)
          transform   <- parseTransform(q)
          criteria    <- parseCriterionLine(q)
        } yield {
          StringQuery(target, composition, transform, criteria.toList)
        }
      case x                  => Failure("Failed to parse the query, bad structure. Expected a JSON object, found: '%s'".format(x))
    }
  }

  def parseCriterion(json: Any): Box[StringCriterionLine] = {

    json match {
      case l: Map[?, ?] =>
        l.head match {
          case (_: String, _: String) =>
            val line = l.asInstanceOf[Map[String, String]] // is map always homogenous ?
            // First, parse the line. Then, try to bind name with object

            def getMandatoryAttribute(attribute: String) = {
              line.get(attribute) match {
                case None                  => Failure("Missing expected '%s' query parameter in criterion '%s'".format(attribute, line))
                case Some(x) if x.nonEmpty => Full(x)
                case Some(x)               => Failure("Parameter '%s' must be non empty in criterion '%s'".format(attribute, line))
              }
            }

            def getOptionalAttribute(attribute: String) = line.get(attribute).filter(_.nonEmpty)

            for {
              objectType <- getMandatoryAttribute(OBJECT)
              attribute  <- getMandatoryAttribute(ATTRIBUTE)
              comparator <- getMandatoryAttribute(COMPARATOR)
              value       = getOptionalAttribute(VALUE)
            } yield StringCriterionLine(objectType, attribute, comparator, value)

          case _ => Failure("Bad query format for criterion line. Expecting an (string,string), found '%s'".format(l.head))
        }
      case x => Failure("Bad query format for criterion line. Expecting an object, found '%s'".format(x))
    }

  }
}
