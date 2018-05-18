package com.normation.rudder.rest.lift
import com.normation.rudder.rest.{ApiPath, ApiVersion, AuthzToken, RestExtractorService, SystemApi => API}
import net.liftweb.http.{LiftResponse, Req}
import com.normation.rudder.rest.RestUtils.{getActor, toJsonError, toJsonResponse}
import net.liftweb.json.JsonDSL._
import com.normation.eventlog.ModificationId
import com.normation.cfclerk.services.UpdateTechniqueLibrary
import com.normation.rudder.UserService
import com.normation.rudder.batch.UpdateDynamicGroups
import com.normation.utils.StringUuidGenerator
import net.liftweb.common.{EmptyBox, Full, Loggable}
import net.liftweb.json.JsonAST.{JField, JObject}

class SystemApi(
    restExtractorService : RestExtractorService
  , apiv11service        : SystemApiService11
) extends LiftApiModuleProvider[API] {

  def schemas = API

  override def getLiftEndpoints(): List[LiftApiModule] = {

    API.endpoints.map(e => e match {
      case API.Status           => Status
      case API.TechniquesReload => TechniquesReload
      case API.DyngroupsReload  => DyngroupsReload
      case API.ReloadAll        => ReloadAll
    })
  }

  object Status extends LiftApiModule0 {
    val schema = API.Status
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val prettify = params.prettify
      implicit val action = "getStatus"
      toJsonResponse(None,("global" -> "ok"))
    }
  }

  object TechniquesReload extends LiftApiModule0 {
    val schema = API.TechniquesReload
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.reloadTechniques(req, params)
    }
  }

  object DyngroupsReload extends LiftApiModule0 {
    val schema = API.DyngroupsReload
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.reloadDyngroups(params)
    }
  }

  object ReloadAll extends LiftApiModule0 {
    val schema = API.ReloadAll
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.reloadAll(req, params)

    }
  }
}

class SystemApiService11(
    updatePTLibService      : UpdateTechniqueLibrary
  , uuidGen                 : StringUuidGenerator
  , updateDynamicGroups     : UpdateDynamicGroups
) (implicit userService: UserService) extends Loggable {

  private[this] def reloadTechniquesWrapper(req: Req) : Either[String, JField] = {

    updatePTLibService.update(ModificationId(uuidGen.newUuid), getActor(req), Some("Technique library reloaded from REST API")) match {
      case Full(x) => Right(JField("techniques", "Started"))
      case eb:EmptyBox =>
        val e = eb ?~! "An error occured when updating the Technique library from file system"
        logger.error(e.messageChain)
        e.rootExceptionCause.foreach { ex =>
          logger.error("Root exception cause was:", ex)
        }
        Left(e.msg)
    }
  }

  //For now we are not able to give information about the group reload process.
  //We still send OK instead to inform the endpoint has correctly triggered.
  private[this] def reloadDyngroupsWrapper() : Either[String, JField] = {
    updateDynamicGroups.startManualUpdate
    Right(JField("dynamicGroups", "Started"))
  }

  def reloadDyngroups(params: DefaultParams) : LiftResponse = {

    implicit val action = "reloadDynGroups"
    implicit val prettify = params.prettify

    reloadDyngroupsWrapper() match {
      case Left(error) => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }

  def reloadTechniques(req: Req, params: DefaultParams) = {

    implicit val action = "reloadTechniques"
    implicit val prettify = params.prettify

    reloadTechniquesWrapper(req) match {
      case Left(error) => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }

  def reloadAll(req: Req, params: DefaultParams) = {

    implicit val action = "reloadAll"
    implicit val prettify = params.prettify

    (for {
      groups     <- reloadDyngroupsWrapper()
      techniques <- reloadTechniquesWrapper(req)
    } yield {
      List(groups, techniques)
    }) match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }
}
