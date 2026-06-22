/*
 *************************************************************************************
 * Copyright 2018 Normation SAS
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

package com.normation.rudder.rest.lift

import com.normation.cfclerk.services.UpdateTechniqueLibrary
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.ldap.core.SoftwareService
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.batch.AsyncDeploymentAgent
import com.normation.rudder.batch.ManualStartDeployment
import com.normation.rudder.batch.UpdateDynamicGroups
import com.normation.rudder.domain.archives.ArchiveType
import com.normation.rudder.domain.logger.ApiLogger
import com.normation.rudder.git.GitArchiveId
import com.normation.rudder.git.GitCommitId
import com.normation.rudder.git.GitFindUtils
import com.normation.rudder.git.GitRepositoryProvider
import com.normation.rudder.metrics.SystemInfoService
import com.normation.rudder.repository.ItemArchiveManager
import com.normation.rudder.rest.ApiModuleProvider
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.EndpointSchema
import com.normation.rudder.rest.OneParam
import com.normation.rudder.rest.RudderJsonResponse
import com.normation.rudder.rest.RudderJsonResponse.ResponseSchema
import com.normation.rudder.rest.SystemApi as API
import com.normation.rudder.rest.data.*
import com.normation.rudder.rest.data.SystemInfoJson.*
import com.normation.rudder.rest.syntax.*
import com.normation.rudder.services.ClearCacheService
import com.normation.rudder.services.healthcheck.HealthcheckNotificationService
import com.normation.rudder.services.healthcheck.HealthcheckResult
import com.normation.rudder.services.healthcheck.HealthcheckResult.Critical
import com.normation.rudder.services.healthcheck.HealthcheckResult.Ok
import com.normation.rudder.services.healthcheck.HealthcheckResult.Warning
import com.normation.rudder.services.healthcheck.HealthcheckService
import com.normation.rudder.services.user.PersonIdentService
import com.normation.rudder.tenants.ChangeContext
import com.normation.rudder.tenants.QueryContext
import com.normation.utils.DateFormaterService
import com.normation.utils.StringUuidGenerator
import com.normation.zio.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.SignStyle
import java.time.temporal.ChronoField.DAY_OF_MONTH
import java.time.temporal.ChronoField.HOUR_OF_DAY
import java.time.temporal.ChronoField.MINUTE_OF_HOUR
import java.time.temporal.ChronoField.MONTH_OF_YEAR
import java.time.temporal.ChronoField.SECOND_OF_MINUTE
import java.time.temporal.ChronoField.YEAR
import net.liftweb.common.*
import net.liftweb.http.InMemoryResponse
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevWalk
import zio.*
import zio.json.*
import zio.json.ast.*
import zio.json.ast.Json.*

class SystemApi(
    apiv11service:     SystemApiService11,
    apiv13service:     SystemApiService13,
    systemInfoService: SystemInfoService
) extends LiftApiModuleProvider[API] {

  def schemas: ApiModuleProvider[API] = API

  override def getLiftEndpoints(): List[LiftApiModule] = {

    API.endpoints.map {
      case API.Info                           => Info
      case API.Status                         => Status
      case API.TechniquesReload               => TechniquesReload
      case API.DyngroupsReload                => DyngroupsReload
      case API.ReloadAll                      => ReloadAll
      case API.PoliciesUpdate                 => PoliciesUpdate
      case API.PoliciesRegenerate             => PoliciesRegenerate
      case API.ArchivesGroupsList             => ArchivesGroupsList
      case API.ArchivesDirectivesList         => ArchivesDirectivesList
      case API.ArchivesRulesList              => ArchivesRulesList
      case API.ArchivesParametersList         => ArchivesParametersList
      case API.ArchivesFullList               => ArchivesFullList
      case API.RestoreGroupsLatestArchive     => RestoreGroupsLatestArchive
      case API.RestoreDirectivesLatestArchive => RestoreDirectivesLatestArchive
      case API.RestoreRulesLatestArchive      => RestoreRulesLatestArchive
      case API.RestoreParametersLatestArchive => RestoreParametersLatestArchive
      case API.RestoreFullLatestArchive       => RestoreFullLatestArchive
      case API.RestoreGroupsLatestCommit      => RestoreGroupsLatestCommit
      case API.RestoreDirectivesLatestCommit  => RestoreDirectivesLatestCommit
      case API.RestoreRulesLatestCommit       => RestoreRulesLatestCommit
      case API.RestoreParametersLatestCommit  => RestoreParametersLatestCommit
      case API.RestoreFullLatestCommit        => RestoreFullLatestCommit
      case API.ArchiveGroups                  => ArchiveGroups
      case API.ArchiveDirectives              => ArchiveDirectives
      case API.ArchiveRules                   => ArchiveRules
      case API.ArchiveParameters              => ArchiveParameters
      case API.ArchiveFull                    => ArchiveAll
      case API.ArchiveGroupDateRestore        => ArchiveGroupDateRestore
      case API.ArchiveDirectiveDateRestore    => ArchiveDirectiveDateRestore
      case API.ArchiveRuleDateRestore         => ArchiveRuleDateRestore
      case API.ArchiveParameterDateRestore    => ArchiveParameterDateRestore
      case API.ArchiveFullDateRestore         => ArchiveFullDateRestore
      case API.GetGroupsZipArchive            => GetGroupsZipArchive
      case API.GetDirectivesZipArchive        => GetDirectivesZipArchive
      case API.GetRulesZipArchive             => GetRulesZipArchive
      case API.GetParametersZipArchive        => GetParametersZipArchive
      case API.GetAllZipArchive               => GetAllZipArchive
      case API.GetHealthcheckResult           => GetHealthcheckResult
      case API.PurgeSoftware                  => PurgeSoftware
    }
  }

  object Info extends LiftApiModule0 {
    override val schema: API.Info.type = API.Info

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {

      systemInfoService
        .getAll()
        .map(i => SystemInfoJson.transformSystemInfo.transform(i))
        .toLiftResponseOne(params, schema, None)
    }
  }

  object Status extends LiftApiModule0 {
    override val schema: API.Status.type = API.Status

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      given prettify: Boolean = false

      RudderJsonResponse.successOne(ResponseSchema("getStatus", None), Map("global" -> "OK"), None)
    }
  }

  // Reload [techniques / dynamics groups] endpoint implementation

  object TechniquesReload extends LiftApiModule0 {
    override val schema: API.TechniquesReload.type = API.TechniquesReload

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      apiv11service.reloadTechniques(req, schema, params)
    }
  }

  object DyngroupsReload extends LiftApiModule0 {
    val schema: API.DyngroupsReload.type = API.DyngroupsReload

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiv11service.reloadDyngroups(schema, params)
    }
  }

  object ReloadAll extends LiftApiModule0 {
    override val schema: API.ReloadAll.type = API.ReloadAll

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      apiv11service.reloadAll(req, schema, params)
    }
  }

  // Policies update and regenerate endpoint implementation

  object PoliciesUpdate extends LiftApiModule0 {
    override val schema: API.PoliciesUpdate.type = API.PoliciesUpdate

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      apiv11service.updatePolicies(req, schema, params)
    }
  }

  object PoliciesRegenerate extends LiftApiModule0 {
    override val schema: API.PoliciesRegenerate.type = API.PoliciesRegenerate

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      apiv11service.regeneratePolicies(req, schema, params)
    }
  }

  // Archives endpoint implementation

  object ArchivesGroupsList extends LiftApiModule0 {
    override val schema: API.ArchivesGroupsList.type = API.ArchivesGroupsList

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      apiv11service.listGroupsArchive(schema, params)
    }
  }

  object ArchivesDirectivesList extends LiftApiModule0 {
    override val schema: API.ArchivesDirectivesList.type = API.ArchivesDirectivesList

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      apiv11service.listDirectivesArchive(schema, params)
    }
  }

  object ArchivesRulesList extends LiftApiModule0 {
    override val schema: API.ArchivesRulesList.type = API.ArchivesRulesList

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      apiv11service.listRulesArchive(schema, params)
    }
  }

  object ArchivesParametersList extends LiftApiModule0 {
    override val schema: API.ArchivesParametersList.type = API.ArchivesParametersList

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      apiv11service.listParametersArchive(schema, params)
    }
  }

  object ArchivesFullList extends LiftApiModule0 {
    override val schema: API.ArchivesFullList.type = API.ArchivesFullList

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      apiv11service.listFullArchive(schema, params)
    }
  }

  object RestoreGroupsLatestArchive extends LiftApiModule0 {
    override val schema: API.RestoreGroupsLatestArchive.type = API.RestoreGroupsLatestArchive

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      apiv11service.restoreGroupsLatestArchive(req, schema, params)
    }
  }

  object RestoreDirectivesLatestArchive extends LiftApiModule0 {
    override val schema: API.RestoreDirectivesLatestArchive.type = API.RestoreDirectivesLatestArchive

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      apiv11service.restoreDirectivesLatestArchive(req, schema, params)
    }
  }

  object RestoreRulesLatestArchive extends LiftApiModule0 {
    override val schema: API.RestoreRulesLatestArchive.type = API.RestoreRulesLatestArchive

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      apiv11service.restoreRulesLatestArchive(req, schema, params)
    }
  }

  object RestoreParametersLatestArchive extends LiftApiModule0 {
    override val schema: API.RestoreParametersLatestArchive.type = API.RestoreParametersLatestArchive

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      apiv11service.restoreParametersLatestArchive(req, schema, params)
    }
  }

  object RestoreFullLatestArchive extends LiftApiModule0 {
    override val schema: API.RestoreFullLatestArchive.type = API.RestoreFullLatestArchive

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      apiv11service.restoreFullLatestArchive(req, schema, params)
    }
  }

  object RestoreGroupsLatestCommit extends LiftApiModule0 {
    override val schema: API.RestoreGroupsLatestCommit.type = API.RestoreGroupsLatestCommit

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      apiv11service.restoreGroupsLatestCommit(req, schema, params)
    }
  }

  object RestoreDirectivesLatestCommit extends LiftApiModule0 {
    override val schema: API.RestoreDirectivesLatestCommit.type = API.RestoreDirectivesLatestCommit

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      apiv11service.restoreDirectivesLatestCommit(req, schema, params)
    }
  }

  object RestoreRulesLatestCommit extends LiftApiModule0 {
    override val schema: API.RestoreRulesLatestCommit.type = API.RestoreRulesLatestCommit

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      apiv11service.restoreRulesLatestCommit(req, schema, params)
    }
  }

  object RestoreParametersLatestCommit extends LiftApiModule0 {
    override val schema: API.RestoreParametersLatestCommit.type = API.RestoreParametersLatestCommit

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      apiv11service.restoreParametersLatestCommit(req, schema, params)
    }
  }

  object RestoreFullLatestCommit extends LiftApiModule0 {
    override val schema: API.RestoreFullLatestCommit.type = API.RestoreFullLatestCommit

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      apiv11service.restoreFullLatestCommit(req, schema, params)
    }
  }

  object ArchiveGroups     extends LiftApiModule0 {
    override val schema: API.ArchiveGroups.type = API.ArchiveGroups

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      apiv11service.archiveGroups(req, schema, params)
    }
  }
  object ArchiveDirectives extends LiftApiModule0 {
    override val schema: API.ArchiveDirectives.type = API.ArchiveDirectives

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      apiv11service.archiveDirectives(req, schema, params)
    }
  }

  object ArchiveRules extends LiftApiModule0 {
    override val schema: API.ArchiveRules.type = API.ArchiveRules

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      apiv11service.archiveRules(req, schema, params)
    }
  }

  object ArchiveParameters extends LiftApiModule0 {
    override val schema: API.ArchiveParameters.type = API.ArchiveParameters

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      apiv11service.archiveParameters(req, schema, params)
    }
  }

  object ArchiveAll extends LiftApiModule0 {
    override val schema: API.ArchiveFull.type = API.ArchiveFull

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      apiv11service.archiveAll(req, schema, params)
    }
  }

  object ArchiveGroupDateRestore extends LiftApiModule {
    override val schema: OneParam = API.ArchiveGroupDateRestore

    override def process(
        version:    ApiVersion,
        path:       ApiPath,
        dateTime:   String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      apiv11service.archiveGroupDateRestore(req, schema, params, dateTime)
    }
  }

  object ArchiveDirectiveDateRestore extends LiftApiModule {
    override val schema: OneParam = API.ArchiveDirectiveDateRestore

    override def process(
        version:    ApiVersion,
        path:       ApiPath,
        dateTime:   String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      apiv11service.archiveDirectiveDateRestore(req, schema, params, dateTime)
    }
  }

  object ArchiveRuleDateRestore extends LiftApiModule {
    override val schema: OneParam = API.ArchiveRuleDateRestore

    override def process(
        version:    ApiVersion,
        path:       ApiPath,
        dateTime:   String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      apiv11service.archiveRuleDateRestore(req, schema, params, dateTime)
    }
  }

  object ArchiveParameterDateRestore extends LiftApiModule {
    val schema: OneParam = API.ArchiveParameterDateRestore

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        dateTime:   String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      apiv11service.archiveParameterDateRestore(req, schema, params, dateTime)
    }
  }

  object ArchiveFullDateRestore extends LiftApiModule {
    val schema: OneParam = API.ArchiveFullDateRestore

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        dateTime:   String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      apiv11service.archiveFullDateRestore(req, schema, params, dateTime)
    }
  }

  object GetGroupsZipArchive extends LiftApiModule {
    override val schema: OneParam = API.GetGroupsZipArchive

    override def process(
        version:    ApiVersion,
        path:       ApiPath,
        commitId:   String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      apiv11service.getZipResponse(schema, params, commitId, ArchiveType.Groups)
    }
  }

  object GetDirectivesZipArchive extends LiftApiModule {
    val schema: OneParam = API.GetDirectivesZipArchive

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        commitId:   String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      apiv11service.getZipResponse(schema, params, commitId, ArchiveType.Directives)
    }
  }

  object GetRulesZipArchive extends LiftApiModule {
    override val schema: OneParam = API.GetRulesZipArchive

    override def process(
        version:    ApiVersion,
        path:       ApiPath,
        commitId:   String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      apiv11service.getZipResponse(schema, params, commitId, ArchiveType.Rules)
    }
  }

  object GetParametersZipArchive extends LiftApiModule {
    override val schema: OneParam = API.GetParametersZipArchive

    override def process(
        version:    ApiVersion,
        path:       ApiPath,
        commitId:   String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      apiv11service.getZipResponse(schema, params, commitId, ArchiveType.Parameters)
    }
  }

  object GetAllZipArchive extends LiftApiModule {
    override val schema: OneParam = API.GetAllZipArchive

    override def process(
        version:    ApiVersion,
        path:       ApiPath,
        commitId:   String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      apiv11service.getZipResponse(schema, params, commitId, ArchiveType.All)
    }
  }

  object GetHealthcheckResult extends LiftApiModule0 {
    override val schema: API.GetHealthcheckResult.type = API.GetHealthcheckResult

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      apiv13service.getHealthcheck(schema, params)
    }
  }

  object PurgeSoftware extends LiftApiModule0 {
    override val schema: API.PurgeSoftware.type = API.PurgeSoftware

    override def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      apiv13service.purgeSoftware(schema, params)
    }
  }
}

class SystemApiService13(
    healthcheckService: HealthcheckService,
    hcNotifService:     HealthcheckNotificationService,
    softwareService:    SoftwareService
) extends Loggable {

  /*
   * This one is hard to test because it depends of the machine status.
   * The awaited format is:
   *
   *  "data" : [
   *    {
   *      "name" : "CPU cores",
   *      "msg" : "16 cores",
   *      "status" : "Ok"
   *    },
   *    {
   *      "name" : "Free disk space",
   *      "msg" : "Enough available free space:\n/var/tmp has 45% free space etc",
   *      "status" : "Ok"
   *    },
   *    {
   *      "name" : "File descriptor limit",
   *      "msg" : "Maximum number of file descriptors is 524288",
   *      "status" : "Ok"
   *    }
   *  ]
   */
  private case class JHealthcheck(name: String, msg: String, status: String) derives JsonEncoder
  def getHealthcheck(schema: EndpointSchema, params: DefaultParams): LiftResponse = {
    def serializeHealthcheckResult(check: HealthcheckResult): JHealthcheck = {
      val status = check match {
        case _: Critical => "Critical"
        case _: Ok       => "Ok"
        case _: Warning  => "Warning"
      }
      JHealthcheck(check.name.value, check.msg, status)
    }

    (for {
      checks <- healthcheckService.runAll
      _      <- hcNotifService.updateCacheFromExt(checks)
    } yield {
      checks.map(serializeHealthcheckResult)
    }).toLiftResponseList(params, schema)
  }

  def purgeSoftware(schema: EndpointSchema, params: DefaultParams): LiftResponse = {
    // create it an async daemon to execute and handle error
    softwareService.deleteUnreferencedSoftware().forkDaemon.runNow

    given prettify: Boolean = params.prettify
    val msg = "Purge of unreferenced software started. More information in /var/log/rudder/webapp/webapp.log"
    RudderJsonResponse.successOne(ResponseSchema.fromSchema(schema), msg, None)
  }
}

// used in SystemApiService11 and tests, so need to be accessible
final case class JArchiveInfo(id: String, date: String, committer: String, gitCommit: String) derives JsonEncoder
final case class JArchiveLog(committer: String, gitCommit: String, id: String) derives JsonEncoder
final case class JReloadStatus(groups: Option[String], techniques: Option[String]) derives JsonEncoder

class SystemApiService11(
    updatePTLibService:   UpdateTechniqueLibrary,
    clearCacheService:    ClearCacheService,
    asyncDeploymentAgent: AsyncDeploymentAgent,
    uuidGen:              StringUuidGenerator,
    updateDynamicGroups:  UpdateDynamicGroups,
    itemArchiveManager:   ItemArchiveManager,
    personIdentService:   PersonIdentService,
    repo:                 GitRepositoryProvider
) extends Loggable {
  import com.normation.rudder.rest.lift.SystemApi.*

  // The private methods are the internal behavior of the API.
  // They are helper functions called by the public method (implemented lower) to avoid code repetition.

  private def reloadTechniquesWrapper(req: Req)(implicit qc: QueryContext): Either[String, String] = {
    ApiLogger.info(s"Trigger technique reload")
    updatePTLibService.update()(using qc.newCC(Some("Technique library reloaded from REST API"))) match {
      case Full(_) => Right("Started")
      case eb: EmptyBox =>
        val e = eb ?~! "An error occurred when updating the Technique library from file system"
        logger.error(e.messageChain)
        e.rootExceptionCause.foreach(ex => logger.error("Root exception cause was:", ex))
        Left(e.msg)
    }
  }

  // For now we are not able to give information about the group reload process.
  // We still send OK instead to inform the endpoint has correctly triggered.
  private def reloadDyngroupsWrapper(): Either[String, String] = {
    ApiLogger.info(s"Trigger dynamic group reload")
    updateDynamicGroups.forceStartUpdate
    Right("Started")
  }

  /**
    * that produce a JSON file:
    * (the most recent is the first in the array)
    * { "archiveType" : [ { "id" : "datetimeID", "date": "human readable date" , "commiter": "name", "gitPath": "path" }, ... ]
    */
  private def formatList(archiveType: String, availableArchives: Map[Instant, GitArchiveId]): (String, List[JArchiveInfo]) = {
    val ordered = availableArchives.toList.sortWith { case ((d1, _), (d2, _)) => d1.isAfter(d2) }.map {
      case (date, tag) =>
        // archiveId is contained in gitPath, archive is archives/<kind>/archiveId
        // splitting on last an getting back last part, falling back to full Id
        val id = tag.path.value.split("/").lastOption.getOrElse(tag.path.value)

        val datetime = dateAsArchiveFormat(date)
        // json
        JArchiveInfo(id, datetime, tag.commiter.getName, tag.commit.value)
    }
    (archiveType, ordered)
  }

  private def listTags(
      list:        () => IOResult[Map[Instant, GitArchiveId]],
      archiveType: String
  ): Either[String, (String, List[JArchiveInfo])] = {
    list().either.runNow.fold(
      err => Left(s"Error when trying to list available archives for ${archiveType}. Error was: ${err.fullMsg}"),
      map => Right(formatList(archiveType, map))
    )
  }

  private def newModId = ModificationId(uuidGen.newUuid)

  private def restoreLatestArchive(
      req:         Req,
      list:        () => IOResult[Map[Instant, GitArchiveId]],
      restore:     (GitCommitId, PersonIdent) => IOResult[GitCommitId],
      archiveType: String
  )(implicit qc: QueryContext): Either[String, (String, String)] = {
    (for {
      archives   <- list()
      commiter   <- personIdentService.getPersonIdentOrDefault(qc.actor.name)
      x          <-
        archives.toList.sortWith { case ((d1, _), (d2, _)) => d1.isAfter(d2) }.headOption.notOptional("No archive is available")
      (date, tag) = x
      restored   <- restore(
                      tag.commit,
                      commiter
                    )
    } yield {
      restored
    }).either.runNow.fold(
      err => Left(s"Error when trying to restore the latest archive for ${archiveType}. Error was: ${err.fullMsg}"),
      x => Right((archiveType, "Started"))
    )
  }

  private def restoreLatestCommit(
      req:         Req,
      restore:     PersonIdent => IOResult[GitCommitId],
      archiveType: String
  )(implicit qc: QueryContext): Either[String, (String, String)] = {
    (for {
      commiter <- personIdentService.getPersonIdentOrDefault(qc.actor.name)
      restored <- restore(
                    commiter
                  )
    } yield {
      restored
    }).either.runNow.fold(
      err => Left(s"Error when trying to restore the latest archive for ${archiveType}. Error was: ${err.fullMsg}"),
      x => Right((archiveType, "Started"))
    )
  }

  private def restoreByDatetime(
      req:         Req,
      list:        () => IOResult[Map[Instant, GitArchiveId]],
      restore:     (GitCommitId, PersonIdent) => IOResult[GitCommitId],
      datetime:    String,
      archiveType: String
  )(implicit qc: QueryContext): Either[String, (String, String)] = {
    (for {
      valideDate <- IOResult.attempt(s"The given archive id is not a valid archive tag: ${datetime}")(
                      DateFormaterService.parseAsGitTag(datetime)
                    )
      archives   <- list()
      commiter   <- personIdentService.getPersonIdentOrDefault(qc.actor.name)
      tag        <-
        archives
          .get(valideDate)
          .notOptional(
            s"The archive with tag '${datetime}' is not available. Available archives: ${archives.keySet.map(DateFormaterService.formatAsGitTag).mkString(", ")}"
          )
      restored   <- restore(
                      tag.commit,
                      commiter
                    )
    } yield {
      restored
    }).either.runNow.fold(
      err => Left(s"Error when trying to restore archive '${datetime}' for ${archiveType}. Error was: ${err.fullMsg}"),
      x => Right((archiveType, "Started"))
    )
  }

  private def archive(
      req:         Req,
      archive:     (PersonIdent, ModificationId, EventActor, Option[String]) => IOResult[GitArchiveId],
      archiveType: String
  )(implicit qc: QueryContext): Either[String, (String, JArchiveLog)] = {
    (for {
      committer <- personIdentService.getPersonIdentOrDefault(qc.actor.name)
      archiveId <-
        archive(committer, newModId, qc.actor, Some("Create new archive requested from REST API"))
    } yield {
      archiveId
    }).either.runNow.fold(
      err => Left(s"Error when trying to archive '${archiveType}'. Error: ${err.fullMsg}"),
      x => {
        // archiveId is contained in gitPath, archive is archives/<kind>/archiveId
        // splitting on last an getting back last part, falling back to full Id
        val archiveId = x.path.value.split("/").lastOption.getOrElse(x.path.value)
        val res       = JArchiveLog(x.commiter.getName, x.commit.value, archiveId)
        Right((archiveType, res))
      }
    )
  }

  /**
    * Return the file content and a filename of the ZIP file that is created
    */
  def getZip(
      commitId:    String,
      archiveType: ArchiveType
  ): Either[String, (Array[Byte], String)] = {
    (ZIO
      .acquireReleaseWith(IOResult.attempt(new RevWalk(repo.db)))(rw => effectUioUnit(rw.dispose)) { rw =>
        for {
          revCommit <- IOResult.attempt(s"Error when retrieving commit revision for '${commitId}'") {
                         val id = repo.db.resolve(commitId)
                         rw.parseCommit(id)
                       }
          treeId    <- IOResult.attempt(revCommit.getTree.getId)
          bytes     <- GitFindUtils.getZip(repo.db, treeId, archiveType.directories)
          date       = Instant.ofEpochSecond(revCommit.getCommitTime)
        } yield {
          (bytes, date)
        }
      })
      .either
      .map {
        case Left(err)            => Left(s"Error when trying to get archive as a Zip: ${err.fullMsg}")
        case Right((bytes, date)) => Right(bytes -> getArchiveName(archiveType, date))
      }
      .runNow
  }

  def getZipResponse(
      schema:      EndpointSchema,
      params:      DefaultParams,
      commitId:    String,
      archiveType: ArchiveType
  ): LiftResponse = {
    getZip(commitId, archiveType) match {
      case Right((bytes, filename)) =>
        val headers = {
          "Content-Type"        -> "application/zip" ::
          "Content-Disposition" -> s"""attachment;filename="${filename}"""" ::
          Nil
        }
        InMemoryResponse(bytes, headers, Nil, 200)
      case Left(err)                =>
        given prettify: Boolean = params.prettify
        RudderJsonResponse.internalError(None, ResponseSchema.fromSchema(schema), err)
    }
  }

  // Archive RESTORE based on a date given as the last part of the URL

  extension [A: JsonEncoder](res: Either[String, (String, A)]) {
    private def toLiftResponse(schema: EndpointSchema)(using prettify: Boolean): LiftResponse = {
      val s = ResponseSchema.fromSchema(schema)
      res match {
        case Left(error)   => RudderJsonResponse.internalError(None, s, error)
        case Right((k, v)) => RudderJsonResponse.successOne(s, Map((k, v)), None)
      }
    }
  }

  def archiveGroupDateRestore(req: Req, schema: EndpointSchema, params: DefaultParams, dateTime: String)(implicit
      qc: QueryContext
  ): LiftResponse = {
    given prettify:  Boolean       = params.prettify
    implicit val cc: ChangeContext = qc.newCC(Some(s"Restore archive for date time ${dateTime} requested from REST API"))

    restoreByDatetime(
      req,
      () => itemArchiveManager.getGroupLibraryTags,
      itemArchiveManager.importGroupLibrary,
      dateTime,
      "group"
    ).toLiftResponse(schema)
  }

  def archiveDirectiveDateRestore(req: Req, schema: EndpointSchema, params: DefaultParams, dateTime: String)(implicit
      qc: QueryContext
  ): LiftResponse = {
    given prettify:  Boolean       = params.prettify
    implicit val cc: ChangeContext = qc.newCC(Some(s"Restore archive for date time ${dateTime} requested from REST API"))

    restoreByDatetime(
      req,
      () => itemArchiveManager.getTechniqueLibraryTags,
      itemArchiveManager.importTechniqueLibrary,
      dateTime,
      "directive"
    ).toLiftResponse(schema)
  }

  def archiveRuleDateRestore(req: Req, schema: EndpointSchema, params: DefaultParams, dateTime: String)(implicit
      qc: QueryContext
  ): LiftResponse = {
    given prettify:  Boolean       = params.prettify
    implicit val cc: ChangeContext = qc.newCC(Some(s"Restore archive for date time ${dateTime} requested from REST API"))

    restoreByDatetime(req, () => itemArchiveManager.getRulesTags, itemArchiveManager.importRules, dateTime, "rule")
      .toLiftResponse(schema)
  }

  def archiveParameterDateRestore(req: Req, schema: EndpointSchema, params: DefaultParams, dateTime: String)(implicit
      qc: QueryContext
  ): LiftResponse = {
    given prettify:  Boolean       = params.prettify
    implicit val cc: ChangeContext = qc.newCC(Some(s"Restore archive for date time ${dateTime} requested from REST API"))

    restoreByDatetime(
      req,
      () => itemArchiveManager.getParametersTags,
      itemArchiveManager.importParameters,
      dateTime,
      "parameter"
    ).toLiftResponse(schema)
  }

  def archiveFullDateRestore(req: Req, schema: EndpointSchema, params: DefaultParams, dateTime: String)(implicit
      qc: QueryContext
  ): LiftResponse = {
    given prettify:  Boolean       = params.prettify
    implicit val cc: ChangeContext = qc.newCC(Some(s"Restore archive for date time ${dateTime} requested from REST API"))

    restoreByDatetime(req, () => itemArchiveManager.getFullArchiveTags, itemArchiveManager.importAll, dateTime, "full")
      .toLiftResponse(schema)
  }

  // Archiving endpoints

  def archiveGroups(req: Req, schema: EndpointSchema, params: DefaultParams)(implicit qc: QueryContext): LiftResponse = {
    given prettify: Boolean = params.prettify

    archive(req, itemArchiveManager.exportGroupLibrary, "groups").toLiftResponse(schema)
  }

  def archiveDirectives(req: Req, schema: EndpointSchema, params: DefaultParams)(implicit qc: QueryContext): LiftResponse = {
    given prettify: Boolean = params.prettify

    archive(req, ((a, b, c, d) => itemArchiveManager.exportTechniqueLibrary(a, b, c, d).map(_._1)), "directives")
      .toLiftResponse(schema)
  }
  def archiveRules(req: Req, schema: EndpointSchema, params: DefaultParams)(implicit qc: QueryContext):      LiftResponse = {
    given prettify: Boolean = params.prettify

    archive(req, itemArchiveManager.exportRules, "rules").toLiftResponse(schema)
  }
  def archiveParameters(req: Req, schema: EndpointSchema, params: DefaultParams)(implicit qc: QueryContext): LiftResponse = {
    given prettify: Boolean = params.prettify

    archive(req, itemArchiveManager.exportParameters, "parameters").toLiftResponse(schema)
  }

  def archiveAll(req: Req, schema: EndpointSchema, params: DefaultParams)(implicit qc: QueryContext): LiftResponse = {
    given prettify: Boolean = params.prettify

    archive(req, ((a, b, c, d) => itemArchiveManager.exportAll(a, b, c, d).map(_._1)), "full").toLiftResponse(schema)
  }

  // All archives RESTORE functions implemented by the API (latest archive or latest commit)

  def restoreGroupsLatestArchive(req: Req, schema: EndpointSchema, params: DefaultParams)(implicit
      qc: QueryContext
  ): LiftResponse = {
    given prettify:  Boolean       = params.prettify
    implicit val cc: ChangeContext = qc.newCC(Some("Restore latest archive required from REST API"))
    restoreLatestArchive(
      req,
      () => itemArchiveManager.getGroupLibraryTags,
      itemArchiveManager.importGroupLibrary,
      "groups"
    ).toLiftResponse(schema)
  }

  def restoreDirectivesLatestArchive(req: Req, schema: EndpointSchema, params: DefaultParams)(implicit
      qc: QueryContext
  ): LiftResponse = {
    given prettify:  Boolean       = params.prettify
    implicit val cc: ChangeContext = qc.newCC(Some("Restore latest archive required from REST API"))

    restoreLatestArchive(
      req,
      () => itemArchiveManager.getTechniqueLibraryTags,
      itemArchiveManager.importTechniqueLibrary,
      "directives"
    ).toLiftResponse(schema)
  }

  def restoreRulesLatestArchive(req: Req, schema: EndpointSchema, params: DefaultParams)(implicit
      qc: QueryContext
  ): LiftResponse = {
    given prettify:  Boolean       = params.prettify
    implicit val cc: ChangeContext = qc.newCC(Some("Restore latest archive required from REST API"))

    restoreLatestArchive(req, () => itemArchiveManager.getRulesTags, itemArchiveManager.importRules, "rules")
      .toLiftResponse(schema)
  }

  def restoreParametersLatestArchive(req: Req, schema: EndpointSchema, params: DefaultParams)(implicit
      qc: QueryContext
  ): LiftResponse = {
    given prettify:  Boolean       = params.prettify
    implicit val cc: ChangeContext = qc.newCC(Some("Restore latest archive required from REST API"))

    restoreLatestArchive(
      req,
      () => itemArchiveManager.getParametersTags,
      itemArchiveManager.importParameters,
      "parameters"
    ).toLiftResponse(schema)
  }

  def restoreFullLatestArchive(req: Req, schema: EndpointSchema, params: DefaultParams)(implicit
      qc: QueryContext
  ): LiftResponse = {
    given prettify:  Boolean       = params.prettify
    implicit val cc: ChangeContext = qc.newCC(Some("Restore latest archive required from REST API"))

    restoreLatestArchive(req, () => itemArchiveManager.getFullArchiveTags, itemArchiveManager.importAll, "full")
      .toLiftResponse(schema)
  }

  def restoreGroupsLatestCommit(req: Req, schema: EndpointSchema, params: DefaultParams)(implicit
      qc: QueryContext
  ): LiftResponse = {
    given prettify:  Boolean       = params.prettify
    implicit val cc: ChangeContext = qc.newCC(Some("Restore archive from latest commit on HEAD required from REST API"))

    restoreLatestCommit(req, itemArchiveManager.importHeadGroupLibrary, "groups").toLiftResponse(schema)
  }

  def restoreDirectivesLatestCommit(req: Req, schema: EndpointSchema, params: DefaultParams)(implicit
      qc: QueryContext
  ): LiftResponse = {
    given prettify:  Boolean       = params.prettify
    implicit val cc: ChangeContext = qc.newCC(Some("Restore archive from latest commit on HEAD required from REST API"))

    restoreLatestCommit(req, itemArchiveManager.importHeadTechniqueLibrary, "directives").toLiftResponse(schema)
  }

  def restoreRulesLatestCommit(req: Req, schema: EndpointSchema, params: DefaultParams)(implicit
      qc: QueryContext
  ): LiftResponse = {
    given prettify:  Boolean       = params.prettify
    implicit val cc: ChangeContext = qc.newCC(Some("Restore latest archive required from REST API"))

    restoreLatestCommit(req, itemArchiveManager.importHeadRules, "rules").toLiftResponse(schema)
  }

  def restoreParametersLatestCommit(req: Req, schema: EndpointSchema, params: DefaultParams)(implicit
      qc: QueryContext
  ): LiftResponse = {
    given prettify:  Boolean       = params.prettify
    implicit val cc: ChangeContext = qc.newCC(Some("Restore latest archive required from REST API"))

    restoreLatestCommit(req, itemArchiveManager.importHeadParameters, "parameters").toLiftResponse(schema)
  }
  def restoreFullLatestCommit(req: Req, schema: EndpointSchema, params: DefaultParams)(implicit
      qc: QueryContext
  ): LiftResponse = {
    given prettify:  Boolean       = params.prettify
    implicit val cc: ChangeContext = qc.newCC(Some("Restore latest archive required from REST API"))

    restoreLatestCommit(req, itemArchiveManager.importHeadAll, "full").toLiftResponse(schema)
  }

  // All list action implemented by the API

  def listGroupsArchive(schema: EndpointSchema, params: DefaultParams): LiftResponse = {
    given prettify: Boolean = params.prettify

    listTags(() => itemArchiveManager.getGroupLibraryTags, "groups").toLiftResponse(schema)
  }

  def listDirectivesArchive(schema: EndpointSchema, params: DefaultParams): LiftResponse = {
    given prettify: Boolean = params.prettify

    listTags(() => itemArchiveManager.getTechniqueLibraryTags, "directives").toLiftResponse(schema)
  }

  def listRulesArchive(schema: EndpointSchema, params: DefaultParams): LiftResponse = {
    given prettify: Boolean = params.prettify

    listTags(() => itemArchiveManager.getRulesTags, "rules").toLiftResponse(schema)
  }

  def listParametersArchive(schema: EndpointSchema, params: DefaultParams): LiftResponse = {
    given prettify: Boolean = params.prettify

    listTags(() => itemArchiveManager.getParametersTags, "parameters").toLiftResponse(schema)
  }

  def listFullArchive(schema: EndpointSchema, params: DefaultParams): LiftResponse = {
    given prettify: Boolean = params.prettify

    listTags(() => itemArchiveManager.getFullArchiveTags, "full").toLiftResponse(schema)
  }

  // Reload Function

  def reloadDyngroups(schema: EndpointSchema, params: DefaultParams): LiftResponse = {
    reloadDyngroupsWrapper().map(s => JReloadStatus(Some(s), None)).toIO.toLiftResponseOne(params, schema, None)
  }

  def reloadTechniques(req: Req, schema: EndpointSchema, params: DefaultParams)(implicit qc: QueryContext): LiftResponse = {
    reloadTechniquesWrapper(req).map(s => JReloadStatus(None, Some(s))).toIO.toLiftResponseOne(params, schema, None)
  }

  def reloadAll(req: Req, schema: EndpointSchema, params: DefaultParams)(implicit qc: QueryContext): LiftResponse = {
    (for {
      groups     <- reloadDyngroupsWrapper()
      techniques <- reloadTechniquesWrapper(req)
    } yield {
      JReloadStatus(Some(groups), Some(techniques))
    }).toIO.toLiftResponseOne(params, schema, None)
  }

  // Update and Regenerate(update + clear Cache) policies

  def updatePolicies(req: Req, schema: EndpointSchema, params: DefaultParams)(implicit qc: QueryContext): LiftResponse = {
    val dest = ManualStartDeployment(newModId, qc.actor, "Policy update asked by REST request")
    asyncDeploymentAgent.launchDeployment(dest)

    given prettify: Boolean = params.prettify
    RudderJsonResponse.successOne(ResponseSchema.fromSchema(schema), Obj(("policies" -> Str("Started"))), None)
  }

  def regeneratePolicies(req: Req, schema: EndpointSchema, params: DefaultParams)(implicit qc: QueryContext): LiftResponse = {

    val dest = ManualStartDeployment(newModId, qc.actor, "Policy regenerate asked by REST request")
    clearCacheService.action(qc.actor).runNow
    asyncDeploymentAgent.launchDeployment(dest)

    given prettify: Boolean = params.prettify
    RudderJsonResponse.successOne(ResponseSchema.fromSchema(schema), Obj("policies" -> Str("Started")), None)
  }
}

private[rest] object SystemApi {

  private val archiveFormat = {
    new java.time.format.DateTimeFormatterBuilder()
      .appendValue(YEAR, 4, 10, SignStyle.NEVER)
      .appendLiteral('-')
      .appendValue(MONTH_OF_YEAR, 2)
      .appendLiteral('-')
      .appendValue(DAY_OF_MONTH, 2)
      .appendLiteral('T')
      .appendValue(HOUR_OF_DAY, 2)
      .appendValue(MINUTE_OF_HOUR, 2)
      .appendValue(SECOND_OF_MINUTE, 2)
      .appendOffsetId()
      .toFormatter()
  }

  /**
   * Public format to display archive tagged at date
   */
  def dateAsArchiveFormat(instant: Instant): String =
    archiveFormat.format(instant.atOffset(ZoneOffset.UTC))

  def getArchiveName(archiveType: ArchiveType, date: Instant): String =
    s"rudder-conf-${archiveType.entryName}-${dateAsArchiveFormat(date)}.zip"

}
