/*
 *************************************************************************************
 * Copyright 2022 Normation SAS
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

package com.normation.rudder.campaigns

import com.normation.errors._
import com.normation.errors.IOResult
import com.normation.utils.DateFormaterService
import org.joda.time.DateTime
import zio.json.DecoderOps
import zio.json.DeriveJsonDecoder
import zio.json.DeriveJsonEncoder
import zio.json.EncoderOps
import zio.json.JsonDecoder
import zio.json.JsonEncoder
import zio.json.ast.Json

trait JSONTranslateCampaign {

  def getRawJson(campaign: Campaign): IOResult[Json]

  // serialize the campaign based on its campaignType
  def handle(pretty: Boolean, campaign: Campaign) =
    getRawJson(campaign).map(json => if (pretty) json.toJsonPretty else json.toJson)

  def read(json: String, parsingInfo: CampaignParsingInfo): IOResult[Campaign]

}

class CampaignSerializer {

  private[this] var tranlaters: Map[String, JSONTranslateCampaign] = Map.empty
  import CampaignSerializer._
  def getJson(campaign: Campaign) = {
    getTranslater(campaign.campaignType)
      .flatMap(_.getRawJson(campaign))
      .flatMap(json => CampaignParsingInfo(campaign.campaignType, campaign.version).toJsonAST.toIO.map(json.merge))
  }

  def addJsonTranslater(campaignType: String, c: JSONTranslateCampaign) = tranlaters = tranlaters + ((campaignType, c))

  def getTranslater(campaignType: String): IOResult[JSONTranslateCampaign] =
    tranlaters.get(campaignType).notOptional(s"No Json translator found for campaign type ${campaignType}")
  def serialize(campaign: Campaign):       IOResult[String]                = getJson(campaign).map(_.toJsonPretty)
  def parse(string: String):               IOResult[Campaign]              = {
    for {
      baseInfo   <- string.fromJson[CampaignParsingInfo].toIO
      translater <- getTranslater(baseInfo.campaignType)
      res        <- translater.read(string, baseInfo)
    } yield {
      res
    }
  }

}

object CampaignSerializer {
  implicit val idEncoder:               JsonEncoder[CampaignId]              = JsonEncoder[String].contramap(_.value)
  implicit val dateTime:                JsonEncoder[DateTime]                = JsonEncoder[String].contramap(DateFormaterService.serialize)
  implicit val dayOfWeek:               JsonEncoder[DayOfWeek]               = JsonEncoder[Int].contramap(_.value)
  implicit val monthlySchedulePosition: JsonEncoder[MonthlySchedulePosition] = JsonEncoder[Int].contramap(s => {
    s match {
      case First      => 1
      case Second     => 2
      case Third      => 3
      case Last       => -1
      case SecondLast => -2
    }
  })
  import scala.concurrent.duration._
  implicit val durationEncoder:         JsonEncoder[Duration]                = JsonEncoder[Long].contramap(_.toMillis)
  implicit val statusInfoEncoder:       JsonEncoder[CampaignStatus]          = DeriveJsonEncoder.gen
  implicit val dayTime:                 JsonEncoder[DayTime]                 = DeriveJsonEncoder.gen
  implicit val scheduleEncoder:         JsonEncoder[CampaignSchedule]        = DeriveJsonEncoder.gen
  implicit val campaignInfoEncoder:     JsonEncoder[CampaignInfo]            = DeriveJsonEncoder.gen
  implicit val idDecoder:               JsonDecoder[CampaignId]              = JsonDecoder[String].map(s => CampaignId(s))
  implicit val decodeIsoDate:           JsonDecoder[DateTime]                = JsonDecoder[String].mapOrFail(s => {
    try {
      Right(DateFormaterService.rfcDateformat.parseDateTime(s))
    } catch {
      case e: Exception => Left(e.getMessage)
    }
  })
  implicit val decodeDay:               JsonDecoder[DayOfWeek]               = JsonDecoder[Int].mapOrFail(v => {
    v match {
      case 1 => Right(Monday)
      case 2 => Right(Tuesday)
      case 3 => Right(Wednesday)
      case 4 => Right(Thursday)
      case 5 => Right(Friday)
      case 6 => Right(Saturday)
      case 7 => Right(Sunday)
      case _ => Left(s"value should be between 1-7, received ${v}")
    }
  })
  implicit val decodeWeekMonth:         JsonDecoder[MonthlySchedulePosition] = JsonDecoder[Int].mapOrFail(v => {
    v match {
      case 1  => Right(First)
      case 2  => Right(Second)
      case 3  => Right(Third)
      case -1 => Right(Last)
      case -2 => Right(SecondLast)
      case _  => Left(s"value should be one of [ -2, -1, 1, 2, 3], received ${v}")
    }
  })

  implicit val durationDecoder:     JsonDecoder[Duration]         = JsonDecoder[Long].map(_.millis)
  implicit val statusInfoDecoder:   JsonDecoder[CampaignStatus]   = DeriveJsonDecoder.gen
  implicit val dayTimeDecoder:      JsonDecoder[DayTime]          = DeriveJsonDecoder.gen
  implicit val scheduleDecoder:     JsonDecoder[CampaignSchedule] = DeriveJsonDecoder.gen
  implicit val campaignInfoDecoder: JsonDecoder[CampaignInfo]     = DeriveJsonDecoder.gen

  implicit val campaignEventIdDecoder:    JsonDecoder[CampaignEventId]    = JsonDecoder[String].map(CampaignEventId)
  implicit val campaignEventStateDecoder: JsonDecoder[CampaignEventState] = DeriveJsonDecoder.gen
  implicit val campaignEventDecoder:      JsonDecoder[CampaignEvent]      = DeriveJsonDecoder.gen

  implicit val campaignEventIdEncoder:    JsonEncoder[CampaignEventId]    = JsonEncoder[String].contramap(_.value)
  implicit val campaignEventStateEncoder: JsonEncoder[CampaignEventState] = DeriveJsonEncoder.gen
  implicit val campaignEventEncoder:      JsonEncoder[CampaignEvent]      = DeriveJsonEncoder.gen

  implicit val parsingInfoDecoder: JsonDecoder[CampaignParsingInfo] = DeriveJsonDecoder.gen
  implicit val parsingInfoEncoder: JsonEncoder[CampaignParsingInfo] = DeriveJsonEncoder.gen

}
