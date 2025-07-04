package com.normation.rudder.rest.lift

import com.normation.errors.Unexpected
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.apidata.ZioJsonExtractor
import com.normation.rudder.campaigns.*
import com.normation.rudder.campaigns.CampaignSerializer.*
import com.normation.rudder.rest.ApiModuleProvider
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.CampaignApi as API
import com.normation.rudder.rest.OneParam
import com.normation.rudder.rest.implicits.*
import com.normation.utils.DateFormaterService
import com.normation.utils.StringUuidGenerator
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import org.joda.time.DateTime
import zio.ZIO
import zio.syntax.*

class CampaignApi(
    campaignRepository:      CampaignRepository,
    campaignSerializer:      CampaignSerializer,
    campaignEventRepository: CampaignEventRepository,
    mainCampaignService:     MainCampaignService,
    stringUuidGenerator:     StringUuidGenerator
) extends LiftApiModuleProvider[API] {

  def schemas: ApiModuleProvider[API] = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map {
      case API.SaveCampaign              => SaveCampaign
      case API.ScheduleCampaign          => ScheduleCampaign
      case API.SaveCampaignEvent         => SaveCampaignEvent
      case API.GetCampaignEventDetails   => GetCampaignEventDetails
      case API.GetCampaignEvents         => GetCampaignEvents
      case API.GetCampaignDetails        => GetCampaignDetails
      case API.GetCampaignEventsForModel => GetAllEventsForCampaign
      case API.GetCampaigns              => GetCampaigns
      case API.DeleteCampaign            => DeleteCampaign
      case API.DeleteCampaignEvent       => DeleteCampaignEvent
    }
  }

  object GetCampaigns extends LiftApiModule0 {
    val schema: API.GetCampaigns.type = API.GetCampaigns

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val campaignType     = req.params.get("campaignType").getOrElse(Nil).map(campaignSerializer.campaignType)
      val (errors, status) =
        req.params.get("status").getOrElse(Nil).map(v => CampaignStatusValue.getValue(v)).partitionMap(identity)
      errors.foreach(e => LiftApiProcessingLogger.error(s"Error while extracting campaign status from request, details:  ${e}"))
      val res              = (for {

        campaigns <- campaignRepository.getAll(campaignType, status)

        serialized <- ZIO.foreach(campaigns)(campaignSerializer.getJson)
      } yield {
        serialized
      }).map(_.toSeq)

      res.toLiftResponseList(params, schema)

    }
  }

  object GetCampaignDetails extends LiftApiModule {
    val schema: OneParam = API.GetCampaignDetails

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        resources:  String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      val res = {
        for {
          campaign   <- campaignRepository.get(CampaignId(resources))
          serialized <- ZIO.foreach(campaign)(campaignSerializer.getJson _)
        } yield {
          serialized
        }
      }

      res.toLiftResponseOne(params, schema, _ => Some(resources))

    }
  }

  object DeleteCampaign extends LiftApiModule {
    val schema: OneParam = API.DeleteCampaign

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        resources:  String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      val res = {
        for {
          campaign <- mainCampaignService.deleteCampaign(CampaignId(resources))
        } yield {
          resources
        }
      }

      res.toLiftResponseOne(params, schema, _ => Some(resources))

    }
  }

  object ScheduleCampaign extends LiftApiModule {
    val schema: OneParam = API.ScheduleCampaign

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        resources:  String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      val res = {
        for {
          campaign <- campaignRepository.get(CampaignId(resources)).notOptional(s"Campaign with id ${resources} not found")
          newEvent <- mainCampaignService.scheduleCampaignEvent(campaign, DateTime.now())
        } yield {
          newEvent
        }
      }

      res.toLiftResponseOne(params, schema, _ => Some(resources))

    }
  }

  object SaveCampaignEvent extends LiftApiModule {
    val schema: OneParam = API.SaveCampaignEvent
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        resources:  String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {

      (for {
        campaignEvent <- ZioJsonExtractor.parseJson[CampaignEvent](req).toIO
        saved         <- campaignEventRepository.saveCampaignEvent(campaignEvent)
      } yield {
        campaignEvent
      }).toLiftResponseOne(params, schema, _ => None)

    }
  }

  object DeleteCampaignEvent extends LiftApiModule {
    val schema: OneParam = API.DeleteCampaignEvent

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        resources:  String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      val res = {
        for {
          campaign <- mainCampaignService.deleteCampaignEvent(CampaignEventId(resources))
        } yield {
          resources
        }
      }

      res.toLiftResponseOne(params, schema, _ => Some(resources))

    }
  }

  object SaveCampaign extends LiftApiModule0 {
    val schema:                                                                                                API.SaveCampaign.type = API.SaveCampaign
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse          = {

      // copied from `Req.forcedBodyAsJson`
      def r  = """; *charset=(.*)""".r
      def r2 = """[^=]*$""".r
      def charset: String = req.contentType.flatMap(ct => r.findFirstIn(ct).flatMap(r2.findFirstIn)).getOrElse("UTF-8")
      // end copy

      (for {
        campaign   <-
          req.body match {
            case eb: EmptyBox => Unexpected((eb ?~! "error when accessing request body").messageChain).fail
            case Full(bytes) => campaignSerializer.parse(new String(bytes, charset))
          }
        // campaign needs to be configured with a timezone (with the current one as fallback)
        c           = if (campaign.info.schedule.tz.isDefined) campaign else campaign.setScheduleTimeZone(ScheduleTimeZone.now())
        withId      = if (campaign.info.id.value.isEmpty) c.copyWithId(CampaignId(stringUuidGenerator.newUuid)) else c
        saved      <- mainCampaignService.saveCampaign(withId)
        serialized <- campaignSerializer.getJson(saved)
      } yield {
        serialized
      }).tapError(err => CampaignLogger.error(s"Error when saving campaign: " + err.fullMsg))
        .toLiftResponseOne(params, schema, _ => None)

    }
  }

  object GetCampaignEvents extends LiftApiModule0 {
    val schema: API.GetCampaignEvents.type = API.GetCampaignEvents

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val states       = req.params.getOrElse("state", Nil).flatMap(s => CampaignEventState.parse(s).toOption)
      val campaignType = req.params.getOrElse("campaignType", Nil).map(campaignSerializer.campaignType)
      val campaignId   = req.params.get("campaignId").flatMap(_.headOption).map(i => CampaignId(i))
      val limit        = req.params.get("limit").flatMap(_.headOption).flatMap(i => i.toIntOption)
      val offset       = req.params.get("offset").flatMap(_.headOption).flatMap(i => i.toIntOption)
      val beforeDate   = req.params.get("before").flatMap(_.headOption).flatMap(i => DateFormaterService.parseDate(i).toOption)
      val afterDate    = req.params.get("after").flatMap(_.headOption).flatMap(i => DateFormaterService.parseDate(i).toOption)
      val order        = req.params.get("order").flatMap(l => l.headOption.flatMap(CampaignSortOrder.withNameInsensitiveOption))
      val asc          = req.params.get("asc").flatMap(l => l.headOption.flatMap(CampaignSortDirection.withNameInsensitiveOption))
      campaignEventRepository
        .getWithCriteria(states, campaignType, campaignId, limit, offset, afterDate, beforeDate, order, asc)
        .toLiftResponseList(params, schema)
    }
  }

  object GetCampaignEventDetails extends LiftApiModule {
    val schema: OneParam = API.GetCampaignEventDetails

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        resources:  String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {

      campaignEventRepository.get(CampaignEventId(resources)).toLiftResponseOne(params, schema, _ => Some(resources))

    }
  }

  object GetAllEventsForCampaign extends LiftApiModule {
    val schema: OneParam = API.GetCampaignEventsForModel
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        resources:  String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      val states       = req.params.getOrElse("state", Nil).flatMap(s => CampaignEventState.parse(s).toOption)
      val campaignType = req.params.getOrElse("campaignType", Nil).map(campaignSerializer.campaignType)
      val limit        = req.params.get("limit").flatMap(_.headOption).flatMap(i => i.toIntOption)
      val offset       = req.params.get("offset").flatMap(_.headOption).flatMap(i => i.toIntOption)
      val beforeDate   = req.params.get("before").flatMap(_.headOption).flatMap(i => DateFormaterService.parseDate(i).toOption)
      val afterDate    = req.params.get("after").flatMap(_.headOption).flatMap(i => DateFormaterService.parseDate(i).toOption)
      val order        = req.params.get("order").flatMap(l => l.headOption.flatMap(CampaignSortOrder.withNameInsensitiveOption))
      val asc          = req.params.get("asc").flatMap(l => l.headOption.flatMap(CampaignSortDirection.withNameInsensitiveOption))
      campaignEventRepository
        .getWithCriteria(states, campaignType, Some(CampaignId(resources)), limit, offset, afterDate, beforeDate, order, asc)
        .toLiftResponseList(params, schema)
    }
  }
}
