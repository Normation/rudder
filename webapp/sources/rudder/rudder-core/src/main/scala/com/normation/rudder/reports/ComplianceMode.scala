/*
 *************************************************************************************
 * Copyright 2014 Normation SAS
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

package com.normation.rudder.reports

import com.normation.errors.*
import zio.json.DeriveJsonCodec
import zio.json.JsonCodec
import zio.json.JsonDecoder
import zio.json.JsonEncoder

/**
 * Define level of compliance:
 *
 * - compliance: full compliance, check success report (historical Rudder way)
 * - error_only: only report for repaired and error reports.
 */

sealed trait ComplianceModeName(val name: String)

object ComplianceModeName {
  case object FullCompliance  extends ComplianceModeName("full-compliance")
  case object ChangesOnly     extends ComplianceModeName("changes-only")
  case object ReportsDisabled extends ComplianceModeName("reports-disabled")

  implicit val codecComplianceModeName: JsonCodec[ComplianceModeName] = {
    implicit val encoderComplianceModeName: JsonEncoder[ComplianceModeName] =
      JsonEncoder[String].contramap[ComplianceModeName](_.name)
    implicit val decoderComplianceModeName: JsonDecoder[ComplianceModeName] =
      JsonDecoder[String].mapOrFail(s => ComplianceModeName.parse(s).left.map(_.fullMsg))
    JsonCodec(encoderComplianceModeName, decoderComplianceModeName)
  }

  val allModes: List[ComplianceModeName] = FullCompliance :: ChangesOnly :: ReportsDisabled :: Nil

  def parse(value: String): PureResult[ComplianceModeName] = {
    allModes.find(_.name == value) match {
      case None       =>
        Left(
          Unexpected(
            s"Unable to parse the compliance mode name '${value}'. was expecting ${allModes.map(_.name).mkString("'", "' or '", "'")}."
          )
        )
      case Some(mode) =>
        Right(mode)
    }
  }
}

sealed trait ComplianceMode {
  def mode: ComplianceModeName
  val name: String = mode.name
}

final case class GlobalComplianceMode(
    override val mode: ComplianceModeName
) extends ComplianceMode

object GlobalComplianceMode {
  implicit val codecGlobalComplianceMode: JsonCodec[GlobalComplianceMode] = DeriveJsonCodec.gen
}

final case class NodeComplianceMode(
    override val mode: ComplianceModeName,
    overrides:         Boolean
) extends ComplianceMode

object NodeComplianceMode {
  implicit val codecNodeComplianceMode: JsonCodec[NodeComplianceMode] = DeriveJsonCodec.gen
}

trait ComplianceModeService {
  def getGlobalComplianceMode: IOResult[GlobalComplianceMode]
}

class ComplianceModeServiceImpl(
    readComplianceMode: () => IOResult[String]
) extends ComplianceModeService {

  override def getGlobalComplianceMode: IOResult[GlobalComplianceMode] = {
    for {
      modeName <- readComplianceMode()
      mode     <- ComplianceModeName.parse(modeName).toIO
    } yield {
      GlobalComplianceMode(mode)
    }
  }
}
