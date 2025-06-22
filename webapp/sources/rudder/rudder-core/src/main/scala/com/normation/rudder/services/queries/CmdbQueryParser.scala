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
import com.normation.errors.*
import com.normation.rudder.apidata.implicits.queryDecoder
import com.normation.rudder.domain.queries.*
import net.liftweb.common.*
import zio.json.*

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

  /**
    * Use JSON parser for the query, using strict validation (of comparator values), with criterion objects.
    */
  def jsonStrictParser(objects: Map[String, ObjectCriterion]): CmdbQueryParser & StringQueryParser & JsonQueryLexer = {
    new CmdbQueryParser with DefaultStringQueryParser with JsonQueryLexer {
      override val criterionObjects: Map[String, ObjectCriterion] = objects
    }
  }

  /**
    * Use the specific JSON parser that bypass validation of criterion values.
    */
  def jsonRawParser(objects: Map[String, ObjectCriterion]): CmdbQueryParser & RawStringQueryParser & JsonQueryLexer = {
    new CmdbQueryParser with RawStringQueryParser with JsonQueryLexer {
      override val criterionObjects: Map[String, ObjectCriterion] = objects
    }
  }
}

trait QueryLexer {
  def lex(query: String): Box[StringQuery] = lexPure(query).toBox

  def lexPure(query: String): PureResult[StringQuery]
}

trait StringQueryParser {
  def parse(query:     StringQuery): Box[Query]
  def parsePure(query: StringQuery): PureResult[Query]
}

sealed trait CmdbQueryParser extends StringQueryParser with QueryLexer {
  def apply(query: String): Box[Query] = for {
    sq <- lex(query)
    q  <- parse(sq)
  } yield q

  def applyPure(query: String): PureResult[Query] = for {
    sq <- query.fromJson[StringQuery].leftMap(errMsg => Inconsistency(errMsg))
    q  <- parsePure(sq)
  } yield q
}

/**
 * Some default behaviour:
 * - validation of criterion value being empty or not is applied
 * - default composition is AND
 * - transformation is not inverted
 */
trait DefaultStringQueryParser extends StringQueryParser {

  protected def needValidation:        Boolean              = true
  protected def defaultComposition:    CriterionComposition = CriterionComposition.And
  protected def defaultTransformation: ResultTransformation = ResultTransformation.Identity

  def criterionObjects: Map[String, ObjectCriterion]

  override def parse(query: StringQuery): Box[Query] = {
    parsePure(query).toBox
  }

  override def parsePure(query: StringQuery): PureResult[Query] = {
    for {
      comp  <- query.composition match {
                 case None    => Right(defaultComposition)
                 case Some(s) => CriterionComposition.parse(s)
               }
      trans <- query.transform match {
                 case None    => Right(defaultTransformation)
                 case Some(x) => ResultTransformation.parse(x)
               }
      lines <- query.criteria.accumulatePure(parseLine andThen (_.leftMap(errMsg => Inconsistency(errMsg))))
    } yield {
      Query(query.returnType, comp, trans, lines)
    }
  }

  def parseLine(line: StringCriterionLine): Either[String, CriterionLine] = {

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
       * An empty String is allowed because "" could have a different meaning from being absent.
       * Providing an error when none is required is not an error.
       */
      value      <- line.value match {
                      case Some(x) => Right(x)
                      case None    =>
                        if (needValidation && comparator.hasValue)
                          Left("Missing required value for comparator '%s' in line '%s'".format(line.comparator, line))
                        else Right("")
                    }
    } yield CriterionLine(objectType, criterion, comparator, value))

  }

}

/**
  * The query parser that does not apply validation
  */
trait RawStringQueryParser extends DefaultStringQueryParser {
  final override val needValidation: Boolean = false
}

/**
 * This lexer read and valid syntax of JSON query
 *
 */
trait JsonQueryLexer extends QueryLexer {

  override def lexPure(query: String): PureResult[StringQuery] = {
    query.fromJson[StringQuery].left.map(Inconsistency.apply)
  }

}
