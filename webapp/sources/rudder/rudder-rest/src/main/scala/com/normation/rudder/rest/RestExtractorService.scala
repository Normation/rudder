/*
 *************************************************************************************
 * Copyright 2013 Normation SAS
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

package com.normation.rudder.rest

import com.normation.box.*
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.rudder.config.UserPropertyService
import com.normation.rudder.domain.reports.CompliancePrecision
import com.normation.rudder.ncf.ParameterType.ParameterTypeService
import com.normation.rudder.repository.*
import com.normation.rudder.rest.data.*
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.rudder.services.queries.JsonQueryLexer
import com.normation.rudder.services.workflows.WorkflowLevelService
import com.normation.utils.StringUuidGenerator
import net.liftweb.common.*
import net.liftweb.http.Req
import net.liftweb.json.*

final case class RestExtractorService(
    readRule:             RoRuleRepository,
    readDirective:        RoDirectiveRepository,
    readGroup:            RoNodeGroupRepository,
    techniqueRepository:  TechniqueRepository,
    queryParser:          CmdbQueryParser with JsonQueryLexer,
    userPropertyService:  UserPropertyService,
    workflowLevelService: WorkflowLevelService,
    uuidGenerator:        StringUuidGenerator,
    parameterTypeService: ParameterTypeService
) extends Loggable {

  /*
   * Looking for parameter: "level=2"
   * Still used in compliance APIs
   */
  def extractComplianceLevel(params: Map[String, List[String]]): Box[Option[Int]] = {
    params.get("level") match {
      case None | Some(Nil) => Full(None)
      case Some(h :: tail)  => // only take into account the first level param is several are passed
        try { Full(Some(h.toInt)) }
        catch {
          case ex: NumberFormatException =>
            Failure(s"level (displayed level of compliance details) must be an integer, was: '${h}'")
        }
    }
  }

  def extractPercentPrecision(params: Map[String, List[String]]): Box[Option[CompliancePrecision]] = {
    params.get("precision") match {
      case None | Some(Nil) => Full(None)
      case Some(h :: tail)  => // only take into account the first level param is several are passed
        for {
          extracted <- try { Full(h.toInt) }
                       catch {
                         case ex: NumberFormatException => Failure(s"percent precison must be an integer, was: '${h}'")
                       }
          level     <- CompliancePrecision.fromPrecision(extracted)
        } yield {
          Some(level)
        }

    }
  }

  /*
   * Still used in technique API
   */
  def extractString[T](key: String)(req: Req)(fun: String => Box[T]): Box[Option[T]] = {
    req.json match {
      case Full(json) =>
        json \ key match {
          case JString(value) => fun(value).map(Some(_))
          case JNothing       => Full(None)
          case x              => Failure(s"Not a valid value for '${key}' parameter, current value is : ${x}")
        }
      case _          =>
        req.params.get(key) match {
          case None              => Full(None)
          case Some(head :: Nil) => fun(head).map(Some(_))
          case Some(list)        => Failure(s"${list.size} values defined for '${key}' parameter, only one needs to be defined")
        }
    }
  }

  /*
   * Still used in technique/apiaccounts API
   */
  def extractBoolean[T](key: String)(req: Req)(fun: Boolean => T): Box[Option[T]] = {
    req.json match {
      case Full(json) =>
        json \ key match {
          case JBool(value) => Full(Some(fun(value)))
          case JNothing     => Full(None)
          case x            => Failure(s"Not a valid value for '${key}' parameter, current value is : ${x}")
        }
      case _          =>
        req.params.get(key) match {
          case None              => Full(None)
          case Some(head :: Nil) =>
            try {
              Full(Some(fun(head.toBoolean)))
            } catch {
              case e: Throwable =>
                Failure(
                  s"Parsing request parameter '${key}' as a boolean failed, current value is '${head}'. Error message is: '${e.getMessage}'."
                )
            }
          case Some(list)        => Failure(s"${list.size} values defined for 'id' parameter, only one needs to be defined")
        }
    }
  }

  def extractComplianceFormat(params: Map[String, List[String]]): Box[ComplianceFormat] = {
    params.get("format") match {
      case None | Some(Nil) | Some("" :: Nil) =>
        Full(ComplianceFormat.JSON) // by default if no there is no format, should I choose the only one available ?
      case Some(format :: _)                  =>
        ComplianceFormat.fromValue(format).toBox
    }
  }
}
