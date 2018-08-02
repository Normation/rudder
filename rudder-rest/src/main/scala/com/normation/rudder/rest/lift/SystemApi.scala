package com.normation.rudder.rest.lift
import com.normation.rudder.rest.{ApiPath, ApiVersion, AuthzToken, RestExtractorService, RestUtils, SystemApi => API}
import net.liftweb.http.{InMemoryResponse, LiftResponse, Req}
import com.normation.rudder.rest.RestUtils.{getActor, toJsonError, toJsonResponse}
import net.liftweb.json.JsonDSL._
import com.normation.eventlog.{EventActor, ModificationId}
import com.normation.cfclerk.services.{GitRepositoryProvider, UpdateTechniqueLibrary}
import com.normation.rudder.batch.{AsyncDeploymentAgent, ManualStartDeployment, UpdateDynamicGroups}
import com.normation.rudder.UserService
import com.normation.rudder.repository.xml.{GitFindUtils, GitTagDateTimeFormatter}
import com.normation.rudder.repository.{GitArchiveId, GitCommitId, ItemArchiveManager}
import com.normation.rudder.services.{ClearCacheService, SupportScriptService}
import com.normation.rudder.services.user.PersonIdentService
import com.normation.utils.StringUuidGenerator
import net.liftweb.common._
import net.liftweb.json.JsonAST.{JField, JObject}
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers.tryo
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevWalk
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormat, DateTimeFormatterBuilder}

class SystemApi(
    restExtractorService : RestExtractorService
  , apiv11service        : SystemApiService11
) extends LiftApiModuleProvider[API] {

  def schemas = API

  override def getLiftEndpoints(): List[LiftApiModule] = {

    API.endpoints.map(e => e match {
      case API.Status                         => Status
      case API.SupportInfos                   => SupportInfos
      case API.TechniquesReload               => TechniquesReload
      case API.DyngroupsReload                => DyngroupsReload
      case API.ReloadAll                      => ReloadAll
      case API.PoliciesUpdate                 => PoliciesUpdate
      case API.PoliciesRegenerate             => PoliciesRegenerate
      case API.ArchivesGroupsList             => ArchivesGroupsList
      case API.ArchivesDirectivesList         => ArchivesDirectivesList
      case API.ArchivesRulesList              => ArchivesRulesList
      case API.ArchivesFullList               => ArchivesFullList
      case API.RestoreGroupsLatestArchive     => RestoreGroupsLatestArchive
      case API.RestoreDirectivesLatestArchive => RestoreDirectivesLatestArchive
      case API.RestoreRulesLatestArchive      => RestoreRulesLatestArchive
      case API.RestoreFullLatestArchive       => RestoreFullLatestArchive
      case API.RestoreGroupsLatestCommit      => RestoreGroupsLatestCommit
      case API.RestoreDirectivesLatestCommit  => RestoreDirectivesLatestCommit
      case API.RestoreRulesLatestCommit       => RestoreRulesLatestCommit
      case API.RestoreFullLatestCommit        => RestoreFullLatestCommit
      case API.ArchiveGroups                  => ArchiveGroups
      case API.ArchiveDirectives              => ArchiveDirectives
      case API.ArchiveRules                   => ArchiveRules
      case API.ArchiveFull                    => ArchiveAll
      case API.ArchiveGroupDateRestore        => ArchiveGroupDateRestore
      case API.ArchiveDirectiveDateRestore    => ArchiveDirectiveDateRestore
      case API.ArchiveRuleDateRestore         => ArchiveRuleDateRestore
      case API.ArchiveFullDateRestore         => ArchiveFullDateRestore
      case API.GetGroupsZipArchive            => GetGroupsZipArchive
      case API.GetDirectivesZipArchive        => GetDirectivesZipArchive
      case API.GetRulesZipArchive             => GetRulesZipArchive
      case API.GetAllZipArchive               => GetAllZipArchive
    })
  }

  object Status extends LiftApiModule0 {
    val schema = API.Status
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val prettify = params.prettify
      implicit val action = "getStatus"

      toJsonResponse(None, ("global" -> "OK"))
    }
  }

  object SupportInfos extends LiftApiModule0 {

    val schema = API.SupportInfos
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {

      apiv11service.supportInfo(params)
    }

  }

  //Reload [techniques / dynamics groups] endpoint implementation

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

  //Policies update and regenerate endpoint implementation

  object PoliciesUpdate extends LiftApiModule0 {
    val schema = API.PoliciesUpdate
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.updatePolicies(req, params)
    }
  }

  object PoliciesRegenerate extends LiftApiModule0 {
    val schema = API.PoliciesRegenerate
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.regeneratePolicies(req, params)
    }
  }

  //Archives endpoint implementation

  object ArchivesGroupsList extends LiftApiModule0 {
    val schema = API.ArchivesGroupsList
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.listGroupsArchive(params)
    }
  }

  object ArchivesDirectivesList extends LiftApiModule0 {
    val schema = API.ArchivesDirectivesList
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.listDirectivesArchive(params)
    }
  }

  object ArchivesRulesList extends LiftApiModule0 {
    val schema = API.ArchivesRulesList
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.listRulesArchive(params)
    }
  }

  object ArchivesFullList extends LiftApiModule0 {
    val schema = API.ArchivesFullList
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.listFullArchive(params)
    }
  }

  object RestoreGroupsLatestArchive extends LiftApiModule0 {
    val schema = API.RestoreGroupsLatestArchive
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.restoreGroupsLatestArchive(req, params)
    }
  }

  object RestoreDirectivesLatestArchive extends LiftApiModule0 {
    val schema = API.RestoreDirectivesLatestArchive
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.restoreDirectivesLatestArchive(req, params)
    }
  }

  object RestoreRulesLatestArchive extends LiftApiModule0 {
    val schema = API.RestoreRulesLatestArchive
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.restoreRulesLatestArchive(req, params)
    }
  }

  object RestoreFullLatestArchive extends LiftApiModule0 {
    val schema = API.RestoreFullLatestArchive
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.restoreFullLatestArchive(req, params)
    }
  }

  object RestoreGroupsLatestCommit extends LiftApiModule0 {
    val schema = API.RestoreGroupsLatestCommit
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.restoreGroupsLatestCommit(req, params)
    }
  }

  object RestoreDirectivesLatestCommit extends LiftApiModule0 {
    val schema = API.RestoreDirectivesLatestCommit
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.restoreDirectivesLatestCommit(req, params)
    }
  }

  object RestoreRulesLatestCommit extends LiftApiModule0 {
    val schema = API.RestoreRulesLatestCommit
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.restoreRulesLatestCommit(req, params)
    }
  }

  object RestoreFullLatestCommit extends LiftApiModule0 {
    val schema = API.RestoreFullLatestCommit
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.restoreFullLatestCommit(req, params)
    }
  }

  object ArchiveGroups extends LiftApiModule0 {
    val schema = API.ArchiveGroups
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.archiveGroups(req, params)
    }
  }
  object ArchiveDirectives extends LiftApiModule0 {
    val schema = API.ArchiveDirectives
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.archiveDirectives(req, params)
    }
  }

  object ArchiveRules extends LiftApiModule0 {
    val schema = API.ArchiveRules
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.archiveRules(req, params)
    }
  }

  object ArchiveAll extends LiftApiModule0 {
    val schema = API.ArchiveFull
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.archiveAll(req, params)
    }
  }

  object ArchiveGroupDateRestore extends LiftApiModule {
    val schema = API.ArchiveGroupDateRestore
    val restExtractor = restExtractorService

    def process(version: ApiVersion, path: ApiPath, dateTime: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.archiveGroupDateRestore(req, params, dateTime)
    }
  }

  object ArchiveDirectiveDateRestore extends LiftApiModule {
    val schema = API.ArchiveDirectiveDateRestore
    val restExtractor = restExtractorService

    def process(version: ApiVersion, path: ApiPath, dateTime: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.archiveDirectiveDateRestore(req, params, dateTime)
    }
  }

  object ArchiveRuleDateRestore extends LiftApiModule {
    val schema = API.ArchiveRuleDateRestore
    val restExtractor = restExtractorService

    def process(version: ApiVersion, path: ApiPath, dateTime: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.archiveRuleDateRestore(req, params, dateTime)
    }
  }

  object ArchiveFullDateRestore extends LiftApiModule {
    val schema = API.ArchiveFullDateRestore
    val restExtractor = restExtractorService

    def process(version: ApiVersion, path: ApiPath, dateTime: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.archiveFullDateRestore(req, params, dateTime)
    }
  }

  object GetGroupsZipArchive extends LiftApiModule {
    val schema = API.GetGroupsZipArchive
    val restExtractor = restExtractorService

    def process(version: ApiVersion, path: ApiPath, commitId: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.getGroupsZipArchive(params, commitId)
    }
  }

  object GetDirectivesZipArchive extends LiftApiModule {
    val schema = API.GetDirectivesZipArchive
    val restExtractor = restExtractorService

    def process(version: ApiVersion, path: ApiPath, commitId: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.getDirectivesZipArchive(params, commitId)
    }
  }

  object GetRulesZipArchive extends LiftApiModule {
    val schema = API.GetRulesZipArchive
    val restExtractor = restExtractorService

    def process(version: ApiVersion, path: ApiPath, commitId: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.getRulesZipArchive(params, commitId)
    }
  }

  object GetAllZipArchive extends LiftApiModule {
    val schema = API.GetAllZipArchive
    val restExtractor = restExtractorService

    def process(version: ApiVersion, path: ApiPath, commitId: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.getAllZipArchive(params, commitId)
    }
  }


}

class SystemApiService11(
    updatePTLibService      : UpdateTechniqueLibrary
  , supportScriptService    : SupportScriptService
  , clearCacheService       : ClearCacheService
  , asyncDeploymentAgent    : AsyncDeploymentAgent
  , uuidGen                 : StringUuidGenerator
  , updateDynamicGroups     : UpdateDynamicGroups
  , itemArchiveManager      : ItemArchiveManager
  , personIdentService      : PersonIdentService
  , repo                    : GitRepositoryProvider
) (implicit userService: UserService) extends Loggable {


  // The private methods are the internal comportment of the API.
  // They are helper functions called by the public method (implemented lower) to avoid code repetition.

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

  /**
    * that produce a JSON file:
    * (the most recent is the first in the array)
    * { "archiveType" : [ { "id" : "datetimeID", "date": "human readable date" , "commiter": "name", "gitPath": "path" }, ... ]
    */
  private[this] def formatList(archiveType:String, availableArchives: Map[DateTime, GitArchiveId]) : JField = {
    case class JsonArchive(id:String, date:String, commiter:String, gitPath:String)
    val ordered = availableArchives.toList.sortWith {
      case ( (d1,_), (d2,_) ) => d1.isAfter(d2)
    }.map {
      case (date,tag) =>
        val id = date.toString(GitTagDateTimeFormatter)
        val datetime = "%s at %s".format(date.toString("YYYY-MM-dd"), date.toString("HH:mm:ss"))
        //json
        ("id" -> id) ~ ("date" -> datetime) ~ ("commiter" -> tag.commiter.getName) ~ ("gitCommit" -> tag.commit.value)
    }
    JField(archiveType,  ordered)
  }

  private[this] def listTags(list:() => Box[Map[DateTime, GitArchiveId]], archiveType:String) : Either[String, JField] = {
    list() match {
      case Full(map) => Right(formatList(archiveType, map))
      case eb : EmptyBox =>
        val e = eb ?~! "Error when trying to list available archives for %s".format(archiveType)
        Left(e.msg)
    }
  }

  private[this] def newModId = ModificationId(uuidGen.newUuid)

  private[this] def restoreLatestArchive(req:Req, list:() => Box[Map[DateTime, GitArchiveId]], restore:(GitCommitId,PersonIdent,ModificationId,EventActor,Option[String],Boolean) => Box[GitCommitId], archiveType:String) : Either[String, JField] = {
    (for {
      archives   <- list()
      commiter   <- personIdentService.getPersonIdentOrDefault(getActor(req).name)
      (date,tag) <- Box(archives.toList.sortWith { case ( (d1,_), (d2,_) ) => d1.isAfter(d2) }.headOption) ?~! "No archive is available"
      restored   <- restore(tag.commit,commiter,newModId,RestUtils.getActor(req),Some("Restore latest archive required from REST API"),false)
    } yield {
      restored
    }) match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error when trying to restore the latest archive for %s.".format(archiveType)
        Left(e.msg)
      case Full(x) =>
        Right(JField(archiveType, "Started"))
    }
  }

  private[this] def restoreLatestCommit(req:Req, restore: (PersonIdent,ModificationId,EventActor,Option[String],Boolean) => Box[GitCommitId], archiveType:String) : Either[String, JField] = {
    (for {
      commiter   <- personIdentService.getPersonIdentOrDefault(RestUtils.getActor(req).name)
      restored   <- restore(commiter,newModId,RestUtils.getActor(req),Some("Restore archive from latest commit on HEAD required from REST API"), false)
    } yield {
      restored
    }) match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error when trying to restore the latest archive for %s.".format(archiveType)
        Left(e.msg)
      case Full(x) =>
        Right(JField(archiveType, "Started"))
    }
  }


  private[this] def restoreByDatetime(req:Req, list:() => Box[Map[DateTime, GitArchiveId]], restore:(GitCommitId,PersonIdent,ModificationId,EventActor,Option[String],Boolean) => Box[GitCommitId], datetime:String, archiveType:String) : Either[String, JField] = {
    (for {
      valideDate <- tryo { GitTagDateTimeFormatter.parseDateTime(datetime) } ?~! "The given archive id is not a valid archive tag: %s".format(datetime)
      archives   <- list()
      commiter   <- personIdentService.getPersonIdentOrDefault(RestUtils.getActor(req).name)
      tag        <- Box(archives.get(valideDate)) ?~! "The archive with tag '%s' is not available. Available archives: %s".format(datetime,archives.keySet.map( _.toString(GitTagDateTimeFormatter)).mkString(", "))
      restored   <- restore(tag.commit,commiter,newModId,RestUtils.getActor(req),Some("Restore archive for date time %s requested from REST API".format(datetime)),false)
    } yield {
      restored
    }) match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error when trying to restore archive '%s' for %s.".format(datetime, archiveType)
        Left(e.msg)
      case Full(x) =>
        Right(JField(archiveType, "Started"))
    }
  }

  private[this] def archive(req:Req, archive:(PersonIdent,ModificationId,EventActor,Option[String],Boolean) => Box[GitArchiveId], archiveType:String) : Either[String, JField] = {
    (for {
      commiter  <- personIdentService.getPersonIdentOrDefault(RestUtils.getActor(req).name)
      archiveId <- archive(commiter,newModId,RestUtils.getActor(req),Some("Create new archive requested from REST API"),false)
    } yield {
      archiveId
    }) match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error when trying to archive %s.".format(archiveType)
        Left(e.msg)
      case Full(x) =>
        Right(JField(archiveType, "OK"))
    }
  }

  private[this] val format = new DateTimeFormatterBuilder()
    .append(DateTimeFormat.forPattern("YYYY-MM-dd"))
    .appendLiteral('T')
    .append(DateTimeFormat.forPattern("hhmmss")).toFormatter

  private[this] val directiveFiles = List("directives","techniques", "parameters", "ncf")
  private[this] val ruleFiles = List("rules","ruleCategories")
  private[this] val allFiles = "groups" :: ruleFiles ::: directiveFiles

  private[this] def getZip(commitId:String, paths:List[String], archiveType: String) : Either[String, (Array[Byte], List[(String, String)])] = {
    val rw = new RevWalk(repo.db)
    (for {
      revCommit <- try {
        val id = repo.db.resolve(commitId)
        Full(rw.parseCommit(id))
      } catch {
        case e:Exception => Failure("Error when retrieving commit revision for " + commitId, Full(e), Empty)
      } finally {
        rw.dispose
      }
      treeId    <- Helpers.tryo { revCommit.getTree.getId }
      bytes     <- GitFindUtils.getZip(repo.db, treeId, paths)
    } yield {
      (bytes, format.print(new DateTime(revCommit.getCommitTime.toLong * 1000)))
    }) match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error when trying to get archive as a Zip"
        e.rootExceptionCause.foreach { ex =>
          logger.debug("Root exception was:", ex)
        }
        Left(e.msg)
      case Full((bytes, date)) =>
        Right(
          (bytes
        ,   "Content-Type" -> "application/zip" ::
            "Content-Disposition" -> """attachment;filename="rudder-conf-%s-%s.zip"""".format(archiveType, date) ::
            Nil)
        )
    }
  }

  def supportInfo(params: DefaultParams) : LiftResponse = {
    implicit val action = "getSupportInfos"
    implicit val prettify = params.prettify

    supportScriptService.launch() match {
      case Full(bytes) =>
        InMemoryResponse(bytes
          , "content-Type" -> "application/gzip" ::
            "Content-Disposition" -> "attachment;filename=support-info-server.tar.gz" :: Nil
          , Nil
          , 200)
      case eb: EmptyBox =>
        val fail = eb ?~! "Error has occured while getting support script result"
        toJsonError(None, "script support" -> s"An Error has occured : ${fail.msg}" )
    }
  }

  def getGroupsZipArchive(params: DefaultParams,  commitId: String): LiftResponse = {
    implicit val action = "getGroupsZipArchive"
    implicit val prettify = params.prettify

    getZip(commitId, List("groups"), "groups") match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => InMemoryResponse(field._1, field._2, Nil, 200)
    }
  }

  def getDirectivesZipArchive(params: DefaultParams, commitId: String): LiftResponse = {
    implicit val action = "getDirectivesZipArchive"
    implicit val prettify = params.prettify

    getZip(commitId, directiveFiles, "directives") match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => InMemoryResponse(field._1, field._2, Nil, 200)
    }
  }

  def getRulesZipArchive(params: DefaultParams, commitId: String): LiftResponse = {
    implicit val action = "getRulesZipArchive"
    implicit val prettify = params.prettify

    getZip(commitId, ruleFiles, "rules") match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => InMemoryResponse(field._1, field._2, Nil, 200)
    }
  }

  def getAllZipArchive(params: DefaultParams, commitId: String): LiftResponse = {
    implicit val action = "getAllZipArchive"
    implicit val prettify = params.prettify

    getZip(commitId, allFiles, "all") match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => InMemoryResponse(field._1, field._2, Nil, 200)
    }
  }

  //Archive RESTORE based on a date given as the last part of the URL

  def archiveGroupDateRestore(req: Req, params: DefaultParams, dateTime: String) : LiftResponse = {
    implicit val action = "archiveGroupDateRestore"
    implicit val prettify = params.prettify

    restoreByDatetime(req, itemArchiveManager.getGroupLibraryTags _, itemArchiveManager.importGroupLibrary, dateTime, "group") match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }

  def archiveDirectiveDateRestore(req: Req, params: DefaultParams, dateTime: String) : LiftResponse = {
    implicit val action = "archiveDirectiveDateRestore"
    implicit val prettify = params.prettify

    restoreByDatetime(req, itemArchiveManager.getTechniqueLibraryTags _, itemArchiveManager.importTechniqueLibrary, dateTime, "directive") match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }

  def archiveRuleDateRestore(req: Req, params: DefaultParams, dateTime: String) : LiftResponse = {
    implicit val action = "archiveRuleDateRestore"
    implicit val prettify = params.prettify

    restoreByDatetime(req, itemArchiveManager.getRulesTags _, itemArchiveManager.importRules, dateTime, "rule") match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }

  def archiveFullDateRestore(req: Req, params: DefaultParams, dateTime: String) : LiftResponse = {
    implicit val action = "archiveFullDateRestore"
    implicit val prettify = params.prettify

    restoreByDatetime(req, itemArchiveManager.getFullArchiveTags _, itemArchiveManager.importAll, dateTime, "full") match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }

  // Archiving endpoints

  def archiveGroups(req: Req, params: DefaultParams) : LiftResponse = {
    implicit val action = "archiveGroups"
    implicit val prettify = params.prettify

    archive(req, itemArchiveManager.exportGroupLibrary _, "groups") match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }

  def archiveDirectives(req: Req, params: DefaultParams) : LiftResponse = {
    implicit val action = "archiveDirectives"
    implicit val prettify = params.prettify

    archive(req, ((a,b,c,d,e) => itemArchiveManager.exportTechniqueLibrary(a,b,c,d,e).map( _._1)), "directives")match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }
  def archiveRules(req: Req, params: DefaultParams) : LiftResponse = {
    implicit val action = "archiveRules"
    implicit val prettify = params.prettify

    archive(req, itemArchiveManager.exportGroupLibrary _, "rules") match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }

  def archiveAll(req: Req, params: DefaultParams) : LiftResponse = {
    implicit val action = "archiveAll"
    implicit val prettify = params.prettify

    archive(req,  ((a,b,c,d,e) => itemArchiveManager.exportAll(a,b,c,d,e).map( _._1)), "full archive") match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }

  // All archives RESTORE functions implemented by the API (latest archive or latest commit)

  def restoreGroupsLatestArchive(req: Req, params: DefaultParams) : LiftResponse = {

    implicit val action = "restoreGroupsLatestArchive"
    implicit val prettify = params.prettify

    restoreLatestArchive(req, itemArchiveManager.getGroupLibraryTags _, itemArchiveManager.importGroupLibrary, "groups") match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }

  def restoreDirectivesLatestArchive(req: Req, params: DefaultParams) : LiftResponse = {

    implicit val action = "restoreDirectivesLatestArchive"
    implicit val prettify = params.prettify

    restoreLatestArchive(req, itemArchiveManager.getTechniqueLibraryTags _, itemArchiveManager.importTechniqueLibrary, "directives") match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }


  def restoreRulesLatestArchive(req: Req, params: DefaultParams) : LiftResponse = {

    implicit val action = "restoreRulesLatestArchive"
    implicit val prettify = params.prettify

    restoreLatestArchive(req, itemArchiveManager.getRulesTags _, itemArchiveManager.importRules, "rules") match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }

  def restoreFullLatestArchive(req: Req, params: DefaultParams) : LiftResponse = {

    implicit val action = "restoreFullLatestArchive"
    implicit val prettify = params.prettify

    restoreLatestArchive(req, itemArchiveManager.getFullArchiveTags _, itemArchiveManager.importAll, "full") match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }

  def restoreGroupsLatestCommit(req: Req, params: DefaultParams) : LiftResponse = {

    implicit val action = "restoreGroupsLatestCommit"
    implicit val prettify = params.prettify

    restoreLatestCommit(req, itemArchiveManager.importHeadGroupLibrary, "groups") match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }

  def restoreDirectivesLatestCommit(req: Req, params: DefaultParams) : LiftResponse = {

    implicit val action = "restoreDirectivesLatestCommit"
    implicit val prettify = params.prettify

    restoreLatestCommit(req, itemArchiveManager.importHeadTechniqueLibrary, "directives") match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }

  def restoreRulesLatestCommit(req: Req, params: DefaultParams) : LiftResponse = {

    implicit val action = "restoreRulesLatestCommit"
    implicit val prettify = params.prettify

    restoreLatestCommit(req, itemArchiveManager.importHeadRules, "rules") match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }

  def restoreFullLatestCommit(req: Req, params: DefaultParams) : LiftResponse = {

    implicit val action = "restoreFullLatestCommit"
    implicit val prettify = params.prettify

    restoreLatestCommit(req, itemArchiveManager.importHeadAll, "full") match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }

   //All list action implemented by the API

  def listGroupsArchive(params: DefaultParams) : LiftResponse = {

    implicit val action = "listGroupsArchive"
    implicit val prettify = params.prettify

    listTags(itemArchiveManager.getGroupLibraryTags _, "groups") match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }

  def listDirectivesArchive(params: DefaultParams) : LiftResponse = {

    implicit val action = "listDirectivesArchive"
    implicit val prettify = params.prettify

    listTags(itemArchiveManager.getTechniqueLibraryTags _, "directives") match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }

  def listRulesArchive(params: DefaultParams) : LiftResponse = {

    implicit val action = "listRulesArchive"
    implicit val prettify = params.prettify

    listTags(itemArchiveManager.getRulesTags _, "rules") match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }

  def listFullArchive(params: DefaultParams) : LiftResponse = {

    implicit val action = "listFullArchive"
    implicit val prettify = params.prettify

    listTags(itemArchiveManager.getFullArchiveTags _, "full") match {
      case Left(error)  => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }

  // Reload Function

  def reloadDyngroups(params: DefaultParams): LiftResponse = {

    implicit val action = "reloadDynGroups"
    implicit val prettify = params.prettify

    reloadDyngroupsWrapper() match {
      case Left(error) => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }

  def reloadTechniques(req: Req, params: DefaultParams) : LiftResponse = {

    implicit val action = "reloadTechniques"
    implicit val prettify = params.prettify

    reloadTechniquesWrapper(req) match {
      case Left(error) => toJsonError(None, error)
      case Right(field) => toJsonResponse(None, JObject(field))
    }
  }

  def reloadAll(req: Req, params: DefaultParams) : LiftResponse = {

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

  // Update and Regenerate(update + clear Cache) policies

  def updatePolicies(req: Req, params: DefaultParams) : LiftResponse = {

    implicit val action = "updatePolicies"
    implicit val prettify = params.prettify
    val dest = ManualStartDeployment(newModId, getActor(req), "Policy update asked by REST request")

    asyncDeploymentAgent.launchDeployment(dest)
    toJsonResponse(None, "policies" -> "Started")
  }

  def regeneratePolicies(req: Req, params: DefaultParams) : LiftResponse = {

    implicit val action = "regeneratePolicies"
    implicit val prettify = params.prettify
    val dest = ManualStartDeployment(newModId, getActor(req), "Policy regenerate asked by REST request")

    clearCacheService.action(getActor(req))
    asyncDeploymentAgent.launchDeployment(dest)
    toJsonResponse(None, "policies" -> "Started")
  }
}
