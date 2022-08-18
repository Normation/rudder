package com.normation.rudder.rest.lift
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.apidata.ZioJsonExtractor
import com.normation.rudder.campaigns.CampaignEvent
import com.normation.rudder.campaigns.CampaignEventId
import com.normation.rudder.campaigns.CampaignEventRepository
import com.normation.rudder.campaigns.CampaignEventState
import com.normation.rudder.campaigns.CampaignId
import com.normation.rudder.campaigns.CampaignLogger
import com.normation.rudder.campaigns.CampaignRepository
import com.normation.rudder.campaigns.CampaignSerializer
import com.normation.rudder.campaigns.CampaignSerializer.*
import com.normation.rudder.campaigns.MainCampaignService
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.implicits.*
import com.normation.rudder.rest.CampaignApi as API
import com.normation.utils.StringUuidGenerator
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import zio.ZIO
import zio.syntax.*
import com.normation.errors.Unexpected
import com.normation.utils.DateFormaterService

class CampaignApi (
    campaignRepository: CampaignRepository
  , campaignSerializer: CampaignSerializer
  , campaignEventRepository: CampaignEventRepository
  , mainCampaignService: MainCampaignService
  , restExtractorService: RestExtractorService
  , stringUuidGenerator: StringUuidGenerator
)  extends LiftApiModuleProvider[API] {

  def schemas = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => e match {
      case API.SaveCampaign => SaveCampaign
      case API.ScheduleCampaign => ScheduleCampaign
      case API.SaveCampaignEvent => SaveCampaignEvent
      case API.GetCampaignEventDetails => GetCampaignEventDetails
      case API.GetCampaignEvents => GetCampaignEvents
      case API.GetCampaignDetails => GetCampaignDetails
      case API.GetCampaignEventsForModel => GetAllEventsForCampaign
      case API.GetCampaigns => GetCampaigns
    })
  }
  object GetCampaigns extends LiftApiModule0 {
    val schema = API.GetCampaigns

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val res = (for {
        campaigns <- campaignRepository.getAll()
        serialized <- ZIO.foreach(campaigns)(campaignSerializer.getJson)
      } yield {
        serialized
      }).map(_.toSeq)

      res.toLiftResponseList(params,schema)

    }
  }
  object GetCampaignDetails extends LiftApiModule {
    val schema = API.GetCampaignDetails

    def process(version: ApiVersion, path: ApiPath, resources: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val res =
        for {
          campaign <- campaignRepository.get(CampaignId(resources))
          serialized <- campaignSerializer.getJson(campaign)
        } yield {
          serialized
        }

      res.toLiftResponseOne(params,schema, _ => Some(resources))

    }
  }


  object ScheduleCampaign extends LiftApiModule {
    val schema = API.ScheduleCampaign

    def process(version: ApiVersion, path: ApiPath, resources: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val res =
        for {
          campaign <- campaignRepository.get(CampaignId(resources))
          newEvent <- mainCampaignService.scheduleCampaignEvent(campaign)
        } yield {
          newEvent
        }

      res.toLiftResponseOne(params,schema, _ => Some(resources))

    }
  }

  object SaveCampaignEvent extends LiftApiModule0 {
    val schema = API.SaveCampaignEvent
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {

      (for {
        campaignEvent <- ZioJsonExtractor.parseJson[CampaignEvent](req).toIO
        saved <- campaignEventRepository.saveCampaignEvent(campaignEvent)
      } yield {
        campaignEvent
      }).toLiftResponseOne(params,schema, _ => None)

    }
  }

  object SaveCampaign extends LiftApiModule0 {
    val schema = API.SaveCampaign
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {

      // copied from `Req.forcedBodyAsJson`
      def r = """; *charset=(.*)""".r
      def r2 = """[^=]*$""".r
      def charset: String = req.contentType.flatMap(ct => r.findFirstIn(ct).flatMap(r2.findFirstIn)).getOrElse("UTF-8")
      // end copy

      (for {
        campaign <-
          req.body match {
            case eb: EmptyBox => Unexpected((eb ?~! "error when accessing request body").messageChain).fail
            case Full(bytes)  => campaignSerializer.parse(new String(bytes, charset))
          }
        withId = if (campaign.info.id.value.isEmpty) campaign.copyWithId(CampaignId(stringUuidGenerator.newUuid)) else campaign
        saved <- mainCampaignService.saveCampaign(withId)
        serialized <-  campaignSerializer.getJson(saved)
      } yield {
        serialized
      }).tapError(err => CampaignLogger.error(s"Error when saving campaign: " + err.fullMsg)).toLiftResponseOne(params,schema, _ => None)

    }
  }


  object GetCampaignEvents extends LiftApiModule0 {
    val schema = API.GetCampaignEvents

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val (errors,states) = req.params.get("state").map(_.map(CampaignEventState.parse)).getOrElse(Nil).partitionMap(identity)
      val campaignType = req.params.get("campaignType").flatMap(_.headOption).map(campaignSerializer.campaignType)
      val campaignId = req.params.get("campaignId").flatMap(_.headOption).map(i => CampaignId(i))
      val limit = req.params.get("limit").flatMap(_.headOption).flatMap(i => i.toIntOption)
      val offset = req.params.get("offset").flatMap(_.headOption).flatMap(i => i.toIntOption)
      val beforeDate = req.params.get("before").flatMap(_.headOption).flatMap(i => DateFormaterService.parseDate(i).toOption)
      val afterDate = req.params.get("after").flatMap(_.headOption).flatMap(i => DateFormaterService.parseDate(i).toOption)
      campaignEventRepository.getWithCriteria(states,campaignType,campaignId, limit, offset, afterDate, beforeDate).toLiftResponseList(params,schema )
    }
  }


  object GetCampaignEventDetails extends LiftApiModule {
    val schema = API.GetCampaignEventDetails

    def process(version: ApiVersion, path: ApiPath, resources: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {

      campaignEventRepository.get(CampaignEventId(resources)).toLiftResponseOne(params,schema, _ => Some(resources))

    }
  }

  object GetAllEventsForCampaign extends LiftApiModule {
    val schema = API.GetCampaignEventsForModel
    def process(version: ApiVersion, path: ApiPath, resources: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val (errors,states) = req.params.get("state").map(_.map(CampaignEventState.parse)).getOrElse(Nil).partitionMap(identity)
      val campaignType = req.params.get("campaignType").flatMap(_.headOption).map(campaignSerializer.campaignType)
      val limit = req.params.get("limit").flatMap(_.headOption).flatMap(i => i.toIntOption)
      val offset = req.params.get("offset").flatMap(_.headOption).flatMap(i => i.toIntOption)
      val beforeDate = req.params.get("before").flatMap(_.headOption).flatMap(i => DateFormaterService.parseDate(i).toOption)
      val afterDate = req.params.get("after").flatMap(_.headOption).flatMap(i => DateFormaterService.parseDate(i).toOption)
      campaignEventRepository.getWithCriteria(states, campaignType, Some(CampaignId(resources)), limit, offset, afterDate, beforeDate).toLiftResponseList(params,schema)

    }
  }
}
