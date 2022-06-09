package com.normation.rudder.rest.lift
import com.normation.errors.Unexpected
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.apidata.ZioJsonExtractor
import com.normation.rudder.campaigns.CampaignEvent
import com.normation.rudder.campaigns.CampaignEventId
import com.normation.rudder.campaigns.CampaignEventRepository
import com.normation.rudder.campaigns.CampaignId
import com.normation.rudder.campaigns.CampaignRepository
import com.normation.rudder.campaigns.CampaignSerializer
import com.normation.rudder.campaigns.JSONTranslateCampaign._
import com.normation.rudder.campaigns.MainCampaignService
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.implicits._
import com.normation.rudder.rest.{CampaignApi => API}
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import zio.ZIO
import zio.syntax._

class CampaignApi (
    campaignRepository: CampaignRepository
  , campaignSerializer: CampaignSerializer
  , campaignEventRepository: CampaignEventRepository
  , mainCampaignService: MainCampaignService
  , restExtractorService: RestExtractorService
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
    val schema = API.GetCampaignDetails

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
    val schema = API.SaveCampaign
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

        saved <- campaignRepository.save(campaign)
        serialized <-  campaignSerializer.getJson(saved)
      } yield {
        serialized
      }).toLiftResponseOne(params,schema, _ => None)

    }
  }


  object GetCampaignEvents extends LiftApiModule0 {
    val schema = API.GetCampaignEvents

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {

      campaignEventRepository.getAllActiveCampaignEvents().toLiftResponseList(params,schema )

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
      campaignEventRepository.getEventsForCampaign(CampaignId(resources),None).toLiftResponseList(params,schema)

    }
  }
}