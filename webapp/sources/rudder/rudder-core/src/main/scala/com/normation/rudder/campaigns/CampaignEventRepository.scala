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

import com.normation.errors.IOResult
import com.normation.rudder.campaigns.CampaignEventState.Scheduled
import com.normation.rudder.db.Doobie
import doobie.Read
import doobie.Write
import doobie.implicits._
import doobie.implicits.javasql._
import doobie.implicits.toSqlInterpolator
import org.joda.time.DateTime
import zio.interop.catz._

import java.sql.Timestamp


trait CampaignEventRepository {
  def getAllActiveCampaignEvents() : IOResult[List[CampaignEvent]]
  def get(campaignEventId: CampaignEventId) : IOResult[CampaignEvent]
  def getEventsForCampaign(campaignId: CampaignId, state: Option[CampaignEventState]) : IOResult[List[CampaignEvent]]
  def saveCampaignEvent(c : CampaignEvent) : IOResult[CampaignEvent]
}

class CampaignEventRepositoryImpl(doobie: Doobie) extends CampaignEventRepository {

  import doobie._


  implicit  val stateWrite : Write[CampaignEventState] = Write[String].contramap(_.value)

  implicit val eventWrite: Write[CampaignEvent] =
    Write[(String,String,CampaignEventState,Timestamp,Timestamp)].contramap{
      case event => (event.id.value,event.campaignId.value, event.state , new java.sql.Timestamp(event.start.getMillis), new java.sql.Timestamp(event.end.getMillis))
    }

  implicit val eventRead : Read[CampaignEvent] =
    Read[(String,String,String,Timestamp,Timestamp)].map {
      d : (String,String,String,Timestamp,Timestamp) =>
        CampaignEvent(
            CampaignEventId(d._1)
          , CampaignId(d._2)
          , CampaignEventState.parse(d._3).getOrElse(Scheduled)
          , new DateTime(d._4.getTime())
          , new DateTime(d._5.getTime())
        )
    }

  def getAllActiveCampaignEvents(): IOResult[List[CampaignEvent]] = {
    val q = sql"select eventId, campaignId, state, startDate, endDate from  CampaignEvent where state != 'finished' and state != 'skipped'"
    transactIOResult(s"error when getting active campaign events")(xa => q.query[CampaignEvent].to[List].transact(xa))
  }


  def get(id : CampaignEventId): IOResult[CampaignEvent] = {
    val q = sql"select eventId, campaignId, state, startDate, endDate from  CampaignEvent where eventId = '${id.value}'"
    transactIOResult(s"error when getting campaign event with id ${id.value}")(xa => q.query[CampaignEvent].unique.transact(xa))
  }



  def saveCampaignEvent(c: CampaignEvent): IOResult[CampaignEvent] = {
    import doobie._
    val query =
      sql"""insert into campaignEvent  (eventId, campaignId, state, startDate, endDate) values (${c})
           |  ON CONFLICT (eventId) DO UPDATE
           |  SET state = ${c.state}; """.stripMargin

    transactIOResult(s"error when inserting event with id ${c.campaignId.value}")(xa => query.update.run.transact(xa)).map(_ => c)
  }

  def getEventsForCampaign(campaignId: CampaignId, state: Option[CampaignEventState]): IOResult[List[CampaignEvent]] = {
    val q = sql"select eventId, campaignId, state, startDate, endDate from CampaignEvent where campaignId = ${campaignId.value}"
    transactIOResult(s"error when getting campaign events for campaign ${campaignId.value}")(xa => q.query.to[List].transact(xa))
  }

}

