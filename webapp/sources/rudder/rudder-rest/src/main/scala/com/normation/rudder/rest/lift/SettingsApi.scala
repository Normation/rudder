/*
 *************************************************************************************
 * Copyright 2016 Normation SAS
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

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.normation.appconfig.ReadConfigService
import com.normation.appconfig.UpdateConfigService
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.apidata.JsonResponseObjects.*
import com.normation.rudder.apidata.ZioJsonExtractor
import com.normation.rudder.apidata.implicits.*
import com.normation.rudder.batch.AsyncDeploymentActor
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.batch.PolicyGenerationTrigger
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.rudder.domain.nodes.NodeKind
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.facts.nodes.SelectNodeStatus
import com.normation.rudder.reports.*
import com.normation.rudder.rest.ApiModuleProvider
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.OneParam
import com.normation.rudder.rest.RudderJsonRequest.*
import com.normation.rudder.rest.SettingsApi as API
import com.normation.rudder.rest.syntax.*
import com.normation.rudder.services.policies.SendMetrics as SendMetricsEnum
import com.normation.rudder.services.servers.AllowedNetwork
import com.normation.rudder.services.servers.PolicyServerManagementService
import com.normation.rudder.services.servers.RelaySynchronizationMethod
import com.normation.utils.StringUuidGenerator
import enumeratum.*
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import scala.concurrent.duration.Duration
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.syntax.*

class SettingsApi(
    val configService:                 ReadConfigService & UpdateConfigService,
    val asyncDeploymentAgent:          AsyncDeploymentActor,
    val uuidGen:                       StringUuidGenerator,
    val policyServerManagementService: PolicyServerManagementService,
    val nodeFactRepo:                  NodeFactRepository,
    zioJsonExtractor:                  ZioJsonExtractor
) extends LiftApiModuleProvider[API] {
  import SettingsApi.*

  private val allSettings: List[RestSetting[?]] = {
    RudderReportProtocolDefault ::
    GlobalPolicyMode ::
    GlobalPolicyModeOverridable ::
    RunFrequency ::
    FirstRunHour ::
    FirstRunMinute ::
    SplayTime ::
    ModifiedFileTtl ::
    OutputFileTtl ::
    RequireTimeSynchronization ::
    RelayServerSynchronizationMethod ::
    RelayServerSynchronizePolicies ::
    RelayServerSynchronizeSharedFiles ::
    ReportingMode ::
    EnableChangeMessage ::
    MandatoryChangeMessage ::
    ChangeMessagePrompt ::
    EnableChangeRequest ::
    EnableSelfValidation ::
    EnableSelfDeployment ::
    EnableValidateAll ::
    DisplayRecentChangesGraphs ::
    EnableJavascriptDirectives ::
    SendMetrics ::
    NodeOnacceptDefaultState ::
    NodeOnacceptDefaultPolicyMode ::
    RudderComputeChanges ::
    RudderGenerationComputeDyngroups ::
    RudderGenerationMaxParallelism ::
    RudderGenerationJsTimeout ::
    RudderGenerationContinueOnError ::
    RudderGenerationDelay ::
    RudderGenerationPolicy ::
    NodeAcceptDuplicatedHostname ::
    RudderComputeDyngroupsMaxParallelism ::
    RudderSetupDone ::
    Nil
  }

  def schemas: ApiModuleProvider[API] = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map {
      case API.GetAllAllowedNetworks     => GetAllAllowedNetworks
      case API.GetAllowedNetworks        => GetAllowedNetworks
      case API.ModifyAllowedNetworks     => ModifyAllowedNetworks
      case API.ModifyDiffAllowedNetworks => ModifyDiffAllowedNetworks
      case API.GetAllSettings            => GetAllSettings
      case API.ModifySettings            => ModifySettings
      case API.GetSetting                => GetSetting
      case API.ModifySetting             => ModifySetting
    }
  }

  def startNewPolicyGeneration(actor: EventActor): Unit = {
    val modId = ModificationId(uuidGen.newUuid)
    asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
  }

  object GetAllSettings extends LiftApiModule0 {
    val schema:                                                                                                API.GetAllSettings.type = API.GetAllSettings
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse            = {
      import authzToken.qc
      val allSettingsJson = ZIO
        .foreach(allSettings)(setting =>
          setting.getJson.chainError(s"Could not get parameter '${setting.key}'").map(value => (setting.key, value))
        )
        .map(values => Json.Obj(values*))
      (for {
        nets           <- GetAllAllowedNetworks.getAllowedNetworks().chainError("Could not get parameter 'allowed_networks'")
        jsonAst        <- JRAllowedNetworksNode(nets).toJsonAST.toIO
        networkSetting <- jsonAst.asObject.notOptional("Allowed network is not a JSON object")
        settings       <- allSettingsJson
      } yield {
        JRSettings(networkSetting.merge(settings).mapObject(obj => Json.Obj(obj.fields.sortBy((name, _) => name))))
      }).chainError("Could not get all settings")
        .provideLayer(ZLayer.succeed[ReadConfigService](configService))
        .toLiftResponseOne(params, schema, None)

    }
  }

  object ModifySettings extends LiftApiModule0 {
    val schema:                                                                                                API.ModifySettings.type = API.ModifySettings
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse            = {
      (for {
        data    <- ZIO.foreach(allSettings)(setting => {
                     for {
                       value <- setting.setFromRequestOpt(req, authzToken.qc.actor)
                     } yield {
                       (setting.startPolicyGeneration, (setting.key, value))
                     }
                   })
        generate = data.forall { case (needsGeneration, (_, v)) => needsGeneration && v.isDefined }
        result   = JRSettings(Chunk.from(data.flatMap { case (_, (k, v)) => v.map(k -> _) }))
      } yield {
        if (generate) startNewPolicyGeneration(authzToken.qc.actor)
        result
      }).chainError(s"Could not modify settings")
        .provideLayer(ZLayer.succeed[UpdateConfigService](configService))
        .toLiftResponseOne(params, schema, None)
    }
  }

  object GetSetting extends LiftApiModule {
    val schema: OneParam = API.GetSetting
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        key:        String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      (for {
        setting <- settingFromKey(key, allSettings).toIO
        value   <- setting.getJson
      } yield {
        JRSettings(key -> value)
      }).chainError(s"Could not get parameter '${key}'")
        .provideLayer(ZLayer.succeed[ReadConfigService](configService))
        .toLiftResponseOne(params, schema, Some(key))
    }
  }

  object ModifySetting extends LiftApiModule {
    val schema: OneParam = API.ModifySetting
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        key:        String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      (for {
        setting <- settingFromKey(key, allSettings).toIO
        value   <- setting.setFromRequest(req, authzToken.qc.actor)
      } yield {
        if (setting.startPolicyGeneration) startNewPolicyGeneration(authzToken.qc.actor)
        JRSettings(key -> value)
      }).chainError(s"Could not modify parameter '${key}'")
        .provideLayer(ZLayer.succeed[UpdateConfigService](configService))
        .toLiftResponseOne(params, schema, Some(key))
    }
  }

  // if the directive is missing for policy server, it may be because it misses dedicated allowed networks.
  // We don't want to fail on that, so we need to special case Empty
  private def getAllowedNetworksForServer(nodeId: NodeId): IOResult[Chunk[AllowedNetwork]] = {
    policyServerManagementService.getAllowedNetworks(nodeId).map(Chunk.from(_))
  }

  object GetAllAllowedNetworks extends LiftApiModule0 {
    override val schema: API.GetAllAllowedNetworks.type = API.GetAllAllowedNetworks

    def getAllowedNetworks()(using qc: QueryContext): IOResult[Chunk[JRAllowedNetwork]] = {
      for {
        servers         <- nodeFactRepo
                             .getAll()(using qc, SelectNodeStatus.Accepted)
                             .map(_.collect { case (_, n) if (n.rudderSettings.kind != NodeKind.Node) => n.id })
        allowedNetworks <- policyServerManagementService.getAllAllowedNetworks()
      } yield {
        Chunk
          .from(servers)
          .map(id => JRAllowedNetwork(id.value, Chunk.from(allowedNetworks.getOrElse(id, Nil))))
          .sortWith {
            case (JRAllowedNetwork("root", _), _)                         => true
            case (_, JRAllowedNetwork("root", _))                         => false
            case (JRAllowedNetwork(a, _), JRAllowedNetwork(b: String, _)) => a < b
          }
      }
    }

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      getAllowedNetworks()(using authzToken.qc)
        .chainError(s"Could not get allowed networks")
        .map(JRAllowedNetworksNode(_))
        .toLiftResponseOne(params, schema, None)
    }
  }

  object GetAllowedNetworks extends LiftApiModule {
    override val schema: OneParam = API.GetAllowedNetworks
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      import authzToken.qc
      (for {
        nodeInfo <- nodeFactRepo.get(NodeId(id))
        isServer <- nodeInfo match {
                      case Some(info) if info.rudderSettings.isPolicyServer =>
                        ZIO.unit
                      case Some(_)                                          =>
                        Inconsistency(
                          s"Can get allowed networks of node with '${id}' because it is node a policy server (root or relay)"
                        ).fail
                      case None                                             =>
                        Inconsistency(s"Could not find node information for id '${id}', this node does not exist").fail
                    }
        networks <- getAllowedNetworksForServer(NodeId(id))
      } yield {
        JRAllowedNetworks(networks)
      })
        .chainError(s"Could not get allowed networks for policy server '${id}'")
        .toLiftResponseOne(params, schema, Some(id))
    }
  }

  object ModifyAllowedNetworks     extends LiftApiModule {
    override val schema: OneParam = API.ModifyAllowedNetworks
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      import authzToken.qc
      val actor          = authzToken.qc.actor
      val modificationId = new ModificationId(uuidGen.newUuid)
      val nodeId         = NodeId(id)

      (for {
        nodeInfo <- nodeFactRepo.get(nodeId)
        isServer <- nodeInfo match {
                      case Some(info) if info.rudderSettings.isPolicyServer =>
                        ZIO.unit
                      case Some(_)                                          =>
                        Inconsistency(
                          s"Can set allowed networks of node with '${id}' because it is node a policy server (root or relay)"
                        ).fail
                      case None                                             =>
                        Inconsistency(s"Could not find node information for id '${id}', this node does not exist").fail
                    }
        networks <- zioJsonExtractor.extractAllowedNetworks(req).toIO
        nets     <- networks.notOptional(s"You must specify an array of IP for 'allowed_networks' parameter")
        set      <- policyServerManagementService.setAllowedNetworks(nodeId, nets, modificationId, actor)
      } yield {
        asyncDeploymentAgent.launchDeployment(AutomaticStartDeployment(ModificationId(uuidGen.newUuid), actor))
        JRAllowedNetworks(nets)
      }).chainError(s"Error when trying to modify allowed networks for policy server '${id}'")
        .toLiftResponseOne(params, schema, Some(id))
    }
  }
  /*
   * Contrary to the previous, this one await a diff structure with networks to add or remove.
   * We ensure that the diff is atomically commited
   * Awaited json:
   * { "add": ["192.168.42.0/24"]
   * , "delete": ["192.168.2.0/24", "192.168.3.0/24"]
   * }
   *
   * Removed is a no-op if the network is already missing.
   */
  object ModifyDiffAllowedNetworks extends LiftApiModule {
    override val schema: OneParam = API.ModifyDiffAllowedNetworks
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      import authzToken.qc

      val actor          = authzToken.qc.actor
      val modificationId = new ModificationId(uuidGen.newUuid)
      val nodeId         = NodeId(id)

      (for {
        nodeInfo <- nodeFactRepo.get(nodeId)
        isServer <- nodeInfo match {
                      case Some(info) if info.rudderSettings.isPolicyServer =>
                        ZIO.unit
                      case Some(_)                                          =>
                        Inconsistency(
                          s"Can set allowed networks of node with '${id}' because it is node a policy server (root or relay)"
                        ).fail
                      case None                                             =>
                        Inconsistency(s"Could not find node information for id '${id}', this node does not exist").fail
                    }
        diff     <-
          zioJsonExtractor
            .extractAllowedNetworksDiff(req)
            .toIO
            .notOptional(
              s"""Impossible to parse allowed networks diff json. Expected {"allowed_networks": {"add":["192.168.2.0/24", ...]",""" +
              s""" "delete":["192.168.1.0/24", ...]"}}"""
            )
        res      <-
          policyServerManagementService.updateAllowedNetworks(nodeId, diff.add, diff.delete.map(_.inet), modificationId, actor)
      } yield {
        asyncDeploymentAgent.launchDeployment(AutomaticStartDeployment(ModificationId(uuidGen.newUuid), actor))
        JRAllowedNetworks(Chunk.from(res))
      })
        .chainError(s"Error when trying to modify allowed networks for policy server '${id}'")
        .toLiftResponseOne(params, schema, Some(id))
    }
  }
}

object SettingsApi {

  def settingFromKey(key: String, modules: List[RestSetting[?]]): PureResult[RestSetting[?]] = {
    modules.find(_.key == key) match {
      case Some(setting) => Right(setting)
      case None          => Left(Unexpected(s"'$key' is not a valid settings key"))
    }
  }

  private type GetResult[T] = ZIO[ReadConfigService, RudderError, T]
  private type SetResult[T] = ZIO[UpdateConfigService, RudderError, T]

  sealed trait RestSetting[T](using codec: JsonCodec[T]) extends EnumEntry with EnumEntry.Snakecase {

    /**
     * Default key is the name that enforces enum entryName.
     * If key is specific, then the entryName should be overridden
     */
    final def key: String          = entryName
    def get:       GetResult[T]
    def getJson:   GetResult[Json] = get.flatMap(_.toJsonAST.toIO)

    def set: (T, EventActor, Option[String]) => SetResult[Unit]
    def parseParam(param: String): PureResult[T]

    private def extractData(req: Req): PureResult[T] = {
      if (req.json_?) {
        for {
          data  <- req.fromJson[Json]
          value <- data.asObject.flatMap(_.get("value")).notOptionalPure("No value defined in request")
          res   <- value.as[T].leftMap(Unexpected(_))
        } yield {
          res
        }
      } else {
        req.params.get("value") match {
          case Some(value :: Nil) => parseParam(value)
          case Some(_)            => Left(Unexpected("Too much values defined, only need one"))
          case None               => Left(Unexpected("No value defined in request"))
        }
      }
    }

    private def extractDataOpt(req: Req): PureResult[Option[T]] = {
      if (req.json_?) {
        for {
          data          <- req.fromJson[Json]
          optSettingData = data.asObject.flatMap(_.get(key))
          res           <- optSettingData.traverse(_.as[T].leftMap(Unexpected(_)))
        } yield {
          res
        }
      } else {
        req.params.get(key) match {
          case Some(value :: Nil) => parseParam(value).map(Some(_))
          case Some(Nil) | None   => Right(None)
          case Some(_)            => Left(Unexpected("Too much values defined, only need one"))
        }
      }
    }

    def startPolicyGeneration: Boolean

    def setFromRequest(req: Req, actor: EventActor): SetResult[Json] = {
      for {
        value  <- extractData(req).toIO
        result <- set(value, actor, None)
        res    <- value.toJsonAST.toIO
      } yield {
        res
      }
    }

    def setFromRequestOpt(req: Req, actor: EventActor): SetResult[Option[Json]] = {
      for {
        optValue <- extractDataOpt(req).toIO
        result   <-
          optValue match {
            case Some(value) =>
              set(value, actor, None) *> value.toJsonAST.map(Some(_)).toIO
            case None        =>
              None.succeed
          }
      } yield {
        result
      }
    }

    def readConfig[A](f: ReadConfigService => IOResult[A]):      GetResult[A]    = ZIO.service[ReadConfigService].flatMap(f)
    def updateConfig(f:  UpdateConfigService => IOResult[Unit]): SetResult[Unit] = ZIO.service[UpdateConfigService].flatMap(f)
    def updateConfig(
        f: UpdateConfigService => (T, EventActor, Option[String]) => IOResult[Unit]
    ): (T, EventActor, Option[String]) => SetResult[Unit] = (t, actor, reason) =>
      ZIO.service[UpdateConfigService].flatMap(configService => f(configService)(t, actor, reason))
  }

  case object GlobalPolicyMode extends RestSetting[PolicyMode] {
    val startPolicyGeneration = true
    def get: GetResult[PolicyMode]                                       = readConfig(_.rudder_policy_mode_name())
    def set: (PolicyMode, EventActor, Option[String]) => SetResult[Unit] = updateConfig(_.set_rudder_policy_mode_name)
    def parseParam(param: String): PureResult[PolicyMode] = PolicyMode.parse(param)
  }
  given JsonCodec[PolicyMode] = {
    JsonCodec(
      JsonEncoder[String].contramap(_.name),
      JsonDecoder[String].mapOrFail(PolicyMode.parse(_).leftMap(_.fullMsg))
    )
  }

  sealed trait RestBooleanSetting extends RestSetting[Boolean] {
    def parseParam(param: String): PureResult[Boolean] = {
      Either
        .catchOnly[IllegalArgumentException](param.toBoolean)
        .leftMap(argErr => Inconsistency(s"value for boolean should be true or false : ${argErr.getMessage}"))
    }
  }

  sealed trait RestStringSetting extends RestSetting[String] {
    def parseParam(param: String): PureResult[String] = Right(param)
  }

  sealed trait RestIntSetting extends RestSetting[Int] {
    def parseParam(param: String): PureResult[Int] = {
      Either
        .catchOnly[java.lang.NumberFormatException](param.toInt)
        .leftMap(argErr => Inconsistency(s"value for integer should be an integer instead of ${param}"))
    }
  }

  case object GlobalPolicyModeOverridable extends RestBooleanSetting {
    val startPolicyGeneration = true
    def get: GetResult[Boolean]                                       = readConfig(_.rudder_policy_overridable())
    def set: (Boolean, EventActor, Option[String]) => SetResult[Unit] = updateConfig(_.set_rudder_policy_overridable)
  }

  case object RunFrequency                     extends RestIntSetting                          {
    val startPolicyGeneration = true
    def get: GetResult[Int]                                       = readConfig(_.agent_run_interval())
    def set: (Int, EventActor, Option[String]) => SetResult[Unit] = updateConfig(_.set_agent_run_interval)
  }
  case object FirstRunHour                     extends RestIntSetting                          {
    val startPolicyGeneration = true
    def get: GetResult[Int]                                       = readConfig(_.agent_run_start_hour())
    def set: (Int, EventActor, Option[String]) => SetResult[Unit] = updateConfig(_.set_agent_run_start_hour)
  }
  case object FirstRunMinute                   extends RestIntSetting                          {
    val startPolicyGeneration = true
    def get: GetResult[Int]                                       = readConfig(_.agent_run_start_minute())
    def set: (Int, EventActor, Option[String]) => SetResult[Unit] = updateConfig(_.set_agent_run_start_minute)
  }
  case object SplayTime                        extends RestIntSetting                          {
    val startPolicyGeneration = true
    def get: GetResult[Int]                                       = readConfig(_.agent_run_splaytime())
    def set: (Int, EventActor, Option[String]) => SetResult[Unit] = updateConfig(_.set_agent_run_splaytime)
  }
  case object ModifiedFileTtl                  extends RestIntSetting                          {
    val startPolicyGeneration = true
    def get: GetResult[Int]                                       = readConfig(_.cfengine_modified_files_ttl())
    def set: (Int, EventActor, Option[String]) => SetResult[Unit] = (value: Int, _, _) =>
      updateConfig(_.set_cfengine_modified_files_ttl(value))
  }
  case object OutputFileTtl                    extends RestIntSetting                          {
    val startPolicyGeneration = true
    def get: GetResult[Int]                                       = readConfig(_.cfengine_outputs_ttl())
    def set: (Int, EventActor, Option[String]) => SetResult[Unit] = (value: Int, _, _) =>
      updateConfig(_.set_cfengine_outputs_ttl(value))
  }
  case object ReportingMode                    extends RestSetting[ComplianceModeName]         {
    val startPolicyGeneration = true
    def get: GetResult[ComplianceModeName]                                       = for {
      name <- readConfig(_.rudder_compliance_mode_name())
      mode <- ComplianceModeName.parse(name).toIO
    } yield {
      mode
    }
    def set: (ComplianceModeName, EventActor, Option[String]) => SetResult[Unit] = {
      (value: ComplianceModeName, actor: EventActor, reason: Option[String]) =>
        updateConfig(_.set_rudder_compliance_mode_name(value.name, actor, reason))
    }
    def parseParam(param: String): PureResult[ComplianceModeName] = ComplianceModeName.parse(param)
  }
  case object EnableChangeMessage              extends RestBooleanSetting                      {
    val startPolicyGeneration = false
    def get: GetResult[Boolean]                                       = readConfig(_.rudder_ui_changeMessage_enabled())
    def set: (Boolean, EventActor, Option[String]) => SetResult[Unit] = (value: Boolean, _, _) =>
      updateConfig(_.set_rudder_ui_changeMessage_enabled(value))
  }
  case object MandatoryChangeMessage           extends RestBooleanSetting                      {
    val startPolicyGeneration = false
    def get: GetResult[Boolean]                                       = readConfig(_.rudder_ui_changeMessage_mandatory())
    def set: (Boolean, EventActor, Option[String]) => SetResult[Unit] = (value: Boolean, _, _) =>
      updateConfig(_.set_rudder_ui_changeMessage_mandatory(value))
  }
  case object ChangeMessagePrompt              extends RestStringSetting                       {
    val startPolicyGeneration = false
    def get: GetResult[String]                                       = readConfig(_.rudder_ui_changeMessage_explanation())
    def set: (String, EventActor, Option[String]) => SetResult[Unit] = (value: String, _, _) =>
      updateConfig(_.set_rudder_ui_changeMessage_explanation(value))
  }
  case object EnableChangeRequest              extends RestBooleanSetting                      {
    val startPolicyGeneration = false
    def get: GetResult[Boolean]                                       = readConfig(_.rudder_workflow_enabled())
    def set: (Boolean, EventActor, Option[String]) => SetResult[Unit] = (value: Boolean, _, _) =>
      updateConfig(_.set_rudder_workflow_enabled(value))
  }
  case object EnableSelfDeployment             extends RestBooleanSetting                      {
    val startPolicyGeneration = false
    def get: GetResult[Boolean]                                       = readConfig(_.rudder_workflow_self_deployment())
    def set: (Boolean, EventActor, Option[String]) => SetResult[Unit] = (value: Boolean, _, _) =>
      updateConfig(_.set_rudder_workflow_self_deployment(value))
  }
  case object EnableSelfValidation             extends RestBooleanSetting                      {
    val startPolicyGeneration = true
    def get: GetResult[Boolean]                                       = readConfig(_.rudder_workflow_self_validation())
    def set: (Boolean, EventActor, Option[String]) => SetResult[Unit] = (value: Boolean, _, _) =>
      updateConfig(_.set_rudder_workflow_self_validation(value))
  }
  case object EnableValidateAll                extends RestBooleanSetting                      {
    val startPolicyGeneration = true
    def get: GetResult[Boolean]                                       = readConfig(_.rudder_workflow_validate_all())
    def set: (Boolean, EventActor, Option[String]) => SetResult[Unit] = (value: Boolean, _, _) =>
      updateConfig(_.set_rudder_workflow_validate_all(value))
  }
  case object RequireTimeSynchronization       extends RestBooleanSetting                      {
    val startPolicyGeneration = true
    def get: GetResult[Boolean]                                       = readConfig(_.cfengine_server_denybadclocks())
    def set: (Boolean, EventActor, Option[String]) => SetResult[Unit] = (value: Boolean, _, _) =>
      updateConfig(_.set_cfengine_server_denybadclocks(value))
  }
  case object RelayServerSynchronizationMethod extends RestSetting[RelaySynchronizationMethod] {
    val startPolicyGeneration = true
    def get:                       GetResult[RelaySynchronizationMethod]                                       = readConfig(_.relay_server_sync_method())
    def set:                       (RelaySynchronizationMethod, EventActor, Option[String]) => SetResult[Unit] =
      (value: RelaySynchronizationMethod, _, _) => updateConfig(_.set_relay_server_sync_method(value))
    def parseParam(param: String): PureResult[RelaySynchronizationMethod]                                      =
      RelaySynchronizationMethod.parse(param).leftMap(Unexpected(_))
  }
  given JsonCodec[RelaySynchronizationMethod] = JsonCodec(
    JsonEncoder[String].contramap(_.value),
    JsonDecoder[String].mapOrFail(RelaySynchronizationMethod.parse)
  )

  case object RelayServerSynchronizePolicies    extends RestBooleanSetting {
    val startPolicyGeneration = true
    def get: GetResult[Boolean]                                       = readConfig(_.relay_server_syncpromises())
    def set: (Boolean, EventActor, Option[String]) => SetResult[Unit] = (value: Boolean, _, _) =>
      updateConfig(_.set_relay_server_syncpromises(value))
  }
  case object RelayServerSynchronizeSharedFiles extends RestBooleanSetting {
    val startPolicyGeneration = true
    def get: GetResult[Boolean]                                       = readConfig(_.relay_server_syncsharedfiles())
    def set: (Boolean, EventActor, Option[String]) => SetResult[Unit] = (value: Boolean, _, _) =>
      updateConfig(_.set_relay_server_syncsharedfiles(value))
  }

  case object RudderReportProtocolDefault extends RestSetting[AgentReportingProtocol] {
    val startPolicyGeneration = false
    def get: GetResult[AgentReportingProtocol]                                       = readConfig(_.rudder_report_protocol_default())
    def set: (AgentReportingProtocol, EventActor, Option[String]) => SetResult[Unit] = (value: AgentReportingProtocol, _, _) =>
      updateConfig(_.set_rudder_report_protocol_default(value))
    def parseParam(param: String): PureResult[AgentReportingProtocol] = AgentReportingProtocol.parse(param).leftMap(Unexpected(_))
  }
  given JsonCodec[AgentReportingProtocol] = JsonCodec(
    JsonEncoder[String].contramap(_.value),
    JsonDecoder[String].mapOrFail(AgentReportingProtocol.parse)
  )

  case object DisplayRecentChangesGraphs extends RestBooleanSetting {
    val startPolicyGeneration = false
    def get: GetResult[Boolean]                                       = readConfig(_.display_changes_graph())
    def set: (Boolean, EventActor, Option[String]) => SetResult[Unit] = (value: Boolean, _, _) =>
      updateConfig(_.set_display_changes_graph(value))
  }

  case object SendMetrics extends RestSetting[Option[SendMetricsEnum]](using codecMetrics) {
    val startPolicyGeneration = true
    def get: GetResult[Option[SendMetricsEnum]]                                       = readConfig(_.send_server_metrics())
    def set: (Option[SendMetricsEnum], EventActor, Option[String]) => SetResult[Unit] = updateConfig(_.set_send_server_metrics)
    def parseParam(param: String): PureResult[Option[SendMetricsEnum]] = parseSendMetrics(param)
  }
  private def parseSendMetrics(value: String): PureResult[Option[SendMetricsEnum]] = {
    import SendMetricsEnum.*
    value match {
      case "complete" | "true" => Right(Some(CompleteMetrics))
      case "no" | "false"      => Right(Some(NoMetrics))
      case "minimal"           => Right(Some(MinimalMetrics))
      case "not_defined"       => Right(None)
      case _                   => Left(Unexpected(s"value for 'send metrics' settings should be 'no', 'minimal' or 'complete' instead of ${value}"))
    }
  }
  private def serializeSendMetrics(opt: Option[SendMetricsEnum]): String = {
    import SendMetricsEnum.*
    opt match {
      case None                  => "not_defined"
      case Some(NoMetrics)       => "no"
      case Some(MinimalMetrics)  => "minimal"
      case Some(CompleteMetrics) => "complete"
    }
  }
  given codecMetrics: JsonCodec[Option[SendMetricsEnum]] = JsonCodec(
    JsonEncoder[String].contramap(serializeSendMetrics),
    JsonDecoder[String].mapOrFail(parseSendMetrics(_).leftMap(_.fullMsg))
  )

  case object EnableJavascriptDirectives extends RestSetting[FeatureSwitch] {
    val startPolicyGeneration = true
    def get: GetResult[FeatureSwitch]                                       = readConfig(_.rudder_featureSwitch_directiveScriptEngine())
    def set: (FeatureSwitch, EventActor, Option[String]) => SetResult[Unit] = (value: FeatureSwitch, _, _) =>
      updateConfig(_.set_rudder_featureSwitch_directiveScriptEngine(value))
    def parseParam(param: String): PureResult[FeatureSwitch] = FeatureSwitch.parse(param)
  }
  given JsonCodec[FeatureSwitch] = JsonCodec(
    JsonEncoder[String].contramap(_.name),
    JsonDecoder[String].mapOrFail(FeatureSwitch.parse(_).leftMap(_.fullMsg))
  )

  case object NodeOnacceptDefaultPolicyMode extends RestSetting[Option[PolicyMode]](using codecPolicyMode) {
    val startPolicyGeneration = false
    def parseParam(value: String): PureResult[Option[PolicyMode]] = {
      Right(PolicyMode.values.find(_.name == value))
    }
    override val entryName = "node_onaccept_default_policyMode"
    def get: GetResult[Option[PolicyMode]]                                       = readConfig(_.rudder_node_onaccept_default_policy_mode())
    def set: (Option[PolicyMode], EventActor, Option[String]) => SetResult[Unit] = (value: Option[PolicyMode], _, _) =>
      updateConfig(_.set_rudder_node_onaccept_default_policy_mode(value))
  }
  given codecPolicyMode: JsonCodec[Option[PolicyMode]] = JsonCodec(
    JsonEncoder[String].contramap(_.fold("default")(_.name)),
    JsonDecoder[String].mapOrFail(PolicyMode.withNameEither(_).bimap(_.getMessage, Some(_)))
  )

  case object NodeOnacceptDefaultState extends RestSetting[NodeState] {
    val startPolicyGeneration = false
    def parseParam(value: String): PureResult[NodeState]                                      = {
      Right(NodeState.values.find(_.name == value).getOrElse(NodeState.Enabled))
    }
    def get:                       GetResult[NodeState]                                       = readConfig(_.rudder_node_onaccept_default_state())
    def set:                       (NodeState, EventActor, Option[String]) => SetResult[Unit] = (value: NodeState, _, _) =>
      updateConfig(_.set_rudder_node_onaccept_default_state(value))
  }
  given JsonCodec[NodeState] = JsonCodec(
    JsonEncoder[String].contramap(_.name),
    JsonDecoder[String].map(NodeState.withNameOption(_).getOrElse(NodeState.Enabled))
  )

  case object RudderComputeChanges extends RestBooleanSetting {
    val startPolicyGeneration = false
    def get: GetResult[Boolean]                                       = readConfig(_.rudder_compute_changes())
    def set: (Boolean, EventActor, Option[String]) => SetResult[Unit] = (value: Boolean, _, _) =>
      updateConfig(_.set_rudder_compute_changes(value))
  }

  case object RudderGenerationComputeDyngroups extends RestBooleanSetting {
    val startPolicyGeneration = false
    def get: GetResult[Boolean]                                       = readConfig(_.rudder_generation_compute_dyngroups())
    def set: (Boolean, EventActor, Option[String]) => SetResult[Unit] = (value: Boolean, _, _) =>
      updateConfig(_.set_rudder_generation_compute_dyngroups(value))
  }

  case object RudderGenerationMaxParallelism extends RestStringSetting {
    val startPolicyGeneration = false
    def get: GetResult[String]                                       = readConfig(_.rudder_generation_max_parallelism())
    def set: (String, EventActor, Option[String]) => SetResult[Unit] = (value: String, _, _) =>
      updateConfig(_.set_rudder_generation_max_parallelism(value))
  }

  case object RudderGenerationDelay extends RestSetting[Duration] {
    val startPolicyGeneration = false
    def get:                       GetResult[Duration]                                       = readConfig(_.rudder_generation_delay())
    def set:                       (Duration, EventActor, Option[String]) => SetResult[Unit] = (value: Duration, _, _) =>
      updateConfig(_.set_rudder_generation_delay(value))
    def parseParam(param: String): PureResult[Duration]                                      = {
      Either
        .catchOnly[NumberFormatException](Duration(param))
        .leftMap(err => Unexpected(err.getMessage))
        .chainError(s"Could not extract a valid duration from ${param}")
    }
  }
  given JsonCodec[Duration] = JsonCodec(
    JsonEncoder[String].contramap(_.toString),
    JsonDecoder[String].mapOrFail(s => Either.catchOnly[NumberFormatException](Duration(s)).leftMap(_.getMessage))
  )

  case object RudderGenerationPolicy extends RestSetting[PolicyGenerationTrigger] {
    val startPolicyGeneration = false
    def get:                       GetResult[PolicyGenerationTrigger]                                       = readConfig(_.rudder_generation_trigger())
    def set:                       (PolicyGenerationTrigger, EventActor, Option[String]) => SetResult[Unit] = (value: PolicyGenerationTrigger, _, _) =>
      updateConfig(_.set_rudder_generation_trigger(value))
    def parseParam(value: String): PureResult[PolicyGenerationTrigger]                                      = {
      PolicyGenerationTrigger.parse(value).leftMap(Unexpected(_))
    }
  }
  given JsonCodec[PolicyGenerationTrigger] = JsonCodec(
    JsonEncoder[String].contramap(_.entryName),
    JsonDecoder[String].mapOrFail(PolicyGenerationTrigger.parse)
  )

  case object RudderGenerationJsTimeout extends RestIntSetting {
    val startPolicyGeneration = false
    def get: GetResult[Int]                                       = readConfig(_.rudder_generation_js_timeout())
    def set: (Int, EventActor, Option[String]) => SetResult[Unit] = (value: Int, _, _) =>
      updateConfig(_.set_rudder_generation_js_timeout(value))
  }

  case object RudderGenerationContinueOnError extends RestBooleanSetting {
    val startPolicyGeneration = false
    def get: GetResult[Boolean]                                       = readConfig(_.rudder_generation_continue_on_error())
    def set: (Boolean, EventActor, Option[String]) => SetResult[Unit] = (value: Boolean, _, _) =>
      updateConfig(_.set_rudder_generation_continue_on_error(value))
  }

  case object NodeAcceptDuplicatedHostname extends RestBooleanSetting {
    val startPolicyGeneration = false
    def get: GetResult[Boolean]                                       = readConfig(_.node_accept_duplicated_hostname())
    def set: (Boolean, EventActor, Option[String]) => SetResult[Unit] = (value: Boolean, _, _) =>
      updateConfig(_.set_node_accept_duplicated_hostname(value))
  }

  case object RudderComputeDyngroupsMaxParallelism extends RestStringSetting {
    val startPolicyGeneration = false
    def get: GetResult[String]                                       = readConfig(_.rudder_compute_dyngroups_max_parallelism())
    def set: (String, EventActor, Option[String]) => SetResult[Unit] = (value: String, _, _) =>
      updateConfig(_.set_rudder_compute_dyngroups_max_parallelism(value))
  }

  case object RudderSetupDone extends RestBooleanSetting {
    val startPolicyGeneration = false
    def get: GetResult[Boolean]                                       = readConfig(_.rudder_setup_done())
    def set: (Boolean, EventActor, Option[String]) => SetResult[Unit] = (value: Boolean, _, _) =>
      updateConfig(_.set_rudder_setup_done(value))
  }

}
