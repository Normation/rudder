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
import com.normation.rudder.services.policies.SendMetrics
import com.normation.rudder.services.servers.AllowedNetwork
import com.normation.rudder.services.servers.PolicyServerManagementService
import com.normation.rudder.services.servers.RelaySynchronizationMethod
import com.normation.utils.StringUuidGenerator
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

  val allSettings: List[RestSetting[?]] = {
    RestReportProtocolDefault ::
    RestPolicyMode ::
    RestPolicyModeOverridable ::
    RestRunFrequency ::
    RestRunHour ::
    RestRunMinute ::
    RestSplayTime ::
    RestModifiedFileTTL ::
    RestOutputFileTTL ::
    RestRequireTimeSynch ::
    RestRelaySyncMethod ::
    RestRelaySynchronizePolicies ::
    RestRelaySynchronizeSharedFiles ::
    RestReportingMode ::
    RestChangeMessageEnabled ::
    RestChangeMessageManadatory ::
    RestChangeMessagePrompt ::
    RestChangeRequestEnabled ::
    RestChangeRequestSelfValidation ::
    RestChangeRequestSelfDeployment ::
    RestChangeRequestValidateAll ::
    RestChangesGraphs ::
    RestJSEngine ::
    RestSendMetrics ::
    RestOnAcceptNodeState ::
    RestOnAcceptPolicyMode ::
    RestComputeChanges ::
    RestGenerationComputeDynGroups ::
    RestGenerationMaxParallelism ::
    RestGenerationJsTimeout ::
    RestContinueGenerationOnError ::
    RestGenerationDelay ::
    RestPolicyGenerationTrigger ::
    RestNodeAcceptDuplicatedHostname ::
    RestComputeDynGroupMaxParallelism ::
    RestSetupDone ::
    Nil
  }

  val kind = "settings"

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
        JRSettings(key -> value)
      }).chainError(s"Could not modify parameter '${key}'")
        .toLiftResponseOne(params, schema, Some(key))
    }
  }

  ///////////////
  /////////////// Utility function and definition for each setting
  ///////////////

  def settingFromKey(key: String, modules: List[RestSetting[?]]): PureResult[RestSetting[?]] = {
    modules.find(_.key == key) match {
      case Some(setting) => Right(setting)
      case None          => Left(Unexpected(s"'$key' is not a valid settings key"))
    }
  }

  sealed trait RestSetting[T](using codec: JsonCodec[T]) {
    def key:     String
    def get:     IOResult[T]
    def getJson: IOResult[Json] = get.flatMap(_.toJsonAST.toIO)

    def set: (T, EventActor, Option[String]) => IOResult[Unit]
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

    def setFromRequest(req: Req, actor: EventActor): IOResult[Json] = {
      for {
        value  <- extractData(req).toIO
        result <- set(value, actor, None)
        res    <- value.toJsonAST.toIO
      } yield {
        if (startPolicyGeneration) startNewPolicyGeneration(actor)
        res
      }
    }

    def setFromRequestOpt(req: Req, actor: EventActor): IOResult[Option[Json]] = {
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
  }

  case object RestPolicyMode extends RestSetting[PolicyMode] {
    val key                   = "global_policy_mode"
    val startPolicyGeneration = true
    def get: IOResult[PolicyMode]                                       = configService.rudder_policy_mode_name()
    def set: (PolicyMode, EventActor, Option[String]) => IOResult[Unit] = configService.set_rudder_policy_mode_name
    def parseParam(param: String): PureResult[PolicyMode] = PolicyMode.parse(param)
  }
  given JsonCodec[PolicyMode] = {
    JsonCodec(
      JsonEncoder[String].contramap(_.name),
      JsonDecoder[String].mapOrFail(PolicyMode.parse(_).leftMap(_.fullMsg))
    )
  }

  trait RestBooleanSetting extends RestSetting[Boolean] {
    def parseParam(param: String): PureResult[Boolean] = {
      Either
        .catchOnly[IllegalArgumentException](param.toBoolean)
        .leftMap(argErr => Inconsistency(s"value for boolean should be true or false : ${argErr.getMessage}"))
    }
  }

  trait RestStringSetting extends RestSetting[String] {
    def parseParam(param: String): PureResult[String] = Right(param)
  }

  trait RestIntSetting extends RestSetting[Int] {
    def parseParam(param: String): PureResult[Int] = {
      Either
        .catchOnly[java.lang.NumberFormatException](param.toInt)
        .leftMap(argErr => Inconsistency(s"value for integer should be an integer instead of ${param}"))
    }
  }

  case object RestPolicyModeOverridable extends RestBooleanSetting {
    val key                   = "global_policy_mode_overridable"
    val startPolicyGeneration = true
    def get: IOResult[Boolean]                                       = configService.rudder_policy_overridable()
    def set: (Boolean, EventActor, Option[String]) => IOResult[Unit] = configService.set_rudder_policy_overridable
  }

  case object RestRunFrequency                extends RestIntSetting                          {
    val key                   = "run_frequency"
    val startPolicyGeneration = true
    def get: IOResult[Int]                                       = configService.agent_run_interval()
    def set: (Int, EventActor, Option[String]) => IOResult[Unit] = configService.set_agent_run_interval
  }
  case object RestRunHour                     extends RestIntSetting                          {
    val key                   = "first_run_hour"
    val startPolicyGeneration = true
    def get: IOResult[Int]                                       = configService.agent_run_start_hour()
    def set: (Int, EventActor, Option[String]) => IOResult[Unit] = configService.set_agent_run_start_hour
  }
  case object RestRunMinute                   extends RestIntSetting                          {
    val key                   = "first_run_minute"
    val startPolicyGeneration = true
    def get: IOResult[Int]                                       = configService.agent_run_start_minute()
    def set: (Int, EventActor, Option[String]) => IOResult[Unit] = configService.set_agent_run_start_minute
  }
  case object RestSplayTime                   extends RestIntSetting                          {
    val key                   = "splay_time"
    val startPolicyGeneration = true
    def get: IOResult[Int]                                       = configService.agent_run_splaytime()
    def set: (Int, EventActor, Option[String]) => IOResult[Unit] = configService.set_agent_run_splaytime
  }
  case object RestModifiedFileTTL             extends RestIntSetting                          {
    val key                   = "modified_file_ttl"
    val startPolicyGeneration = true
    def get: IOResult[Int]                                       = configService.cfengine_modified_files_ttl()
    def set: (Int, EventActor, Option[String]) => IOResult[Unit] = (value: Int, _, _) =>
      configService.set_cfengine_modified_files_ttl(value)
  }
  case object RestOutputFileTTL               extends RestIntSetting                          {
    val key                   = "output_file_ttl"
    val startPolicyGeneration = true
    def get: IOResult[Int]                                       = configService.cfengine_outputs_ttl()
    def set: (Int, EventActor, Option[String]) => IOResult[Unit] = (value: Int, _, _) =>
      configService.set_cfengine_outputs_ttl(value)
  }
  case object RestReportingMode               extends RestSetting[ComplianceModeName]         {
    val key                   = "reporting_mode"
    val startPolicyGeneration = true
    def get: ZIO[Any, RudderError, ComplianceModeName]                          = for {
      name <- configService.rudder_compliance_mode_name()
      mode <- ComplianceModeName.parse(name).toIO
    } yield {
      mode
    }
    def set: (ComplianceModeName, EventActor, Option[String]) => IOResult[Unit] = {
      (value: ComplianceModeName, actor: EventActor, reason: Option[String]) =>
        configService.set_rudder_compliance_mode_name(value.name, actor, reason)
    }
    def parseParam(param: String): PureResult[ComplianceModeName] = ComplianceModeName.parse(param)
  }
  case object RestChangeMessageEnabled        extends RestBooleanSetting                      {
    val startPolicyGeneration = false
    val key                   = "enable_change_message"
    def get: IOResult[Boolean]                                       = configService.rudder_ui_changeMessage_enabled()
    def set: (Boolean, EventActor, Option[String]) => IOResult[Unit] = (value: Boolean, _, _) =>
      configService.set_rudder_ui_changeMessage_enabled(value)
  }
  case object RestChangeMessageManadatory     extends RestBooleanSetting                      {
    val startPolicyGeneration = false
    val key                   = "mandatory_change_message"
    def get: IOResult[Boolean]                                       = configService.rudder_ui_changeMessage_mandatory()
    def set: (Boolean, EventActor, Option[String]) => IOResult[Unit] = (value: Boolean, _, _) =>
      configService.set_rudder_ui_changeMessage_mandatory(value)
  }
  case object RestChangeMessagePrompt         extends RestStringSetting                       {
    val startPolicyGeneration = false
    val key                   = "change_message_prompt"
    def get: IOResult[String]                                       = configService.rudder_ui_changeMessage_explanation()
    def set: (String, EventActor, Option[String]) => IOResult[Unit] = (value: String, _, _) =>
      configService.set_rudder_ui_changeMessage_explanation(value)
  }
  case object RestChangeRequestEnabled        extends RestBooleanSetting                      {
    val startPolicyGeneration = false
    val key                   = "enable_change_request"
    def get: IOResult[Boolean]                                       = configService.rudder_workflow_enabled()
    def set: (Boolean, EventActor, Option[String]) => IOResult[Unit] = (value: Boolean, _, _) =>
      configService.set_rudder_workflow_enabled(value)
  }
  case object RestChangeRequestSelfDeployment extends RestBooleanSetting                      {
    val startPolicyGeneration = false
    val key                   = "enable_self_deployment"
    def get: IOResult[Boolean]                                       = configService.rudder_workflow_self_deployment()
    def set: (Boolean, EventActor, Option[String]) => IOResult[Unit] = (value: Boolean, _, _) =>
      configService.set_rudder_workflow_self_deployment(value)
  }
  case object RestChangeRequestSelfValidation extends RestBooleanSetting                      {
    val key                   = "enable_self_validation"
    val startPolicyGeneration = true
    def get: IOResult[Boolean]                                       = configService.rudder_workflow_self_validation()
    def set: (Boolean, EventActor, Option[String]) => IOResult[Unit] = (value: Boolean, _, _) =>
      configService.set_rudder_workflow_self_validation(value)
  }
  case object RestChangeRequestValidateAll    extends RestBooleanSetting                      {
    val key                   = "enable_validate_all"
    val startPolicyGeneration = true
    def get: IOResult[Boolean]                                       = configService.rudder_workflow_validate_all()
    def set: (Boolean, EventActor, Option[String]) => IOResult[Unit] = (value: Boolean, _, _) =>
      configService.set_rudder_workflow_validate_all(value)
  }
  case object RestRequireTimeSynch            extends RestBooleanSetting                      {
    val key                   = "require_time_synchronization"
    val startPolicyGeneration = true
    def get: IOResult[Boolean]                                       = configService.cfengine_server_denybadclocks()
    def set: (Boolean, EventActor, Option[String]) => IOResult[Unit] = (value: Boolean, _, _) =>
      configService.set_cfengine_server_denybadclocks(value)
  }
  case object RestRelaySyncMethod             extends RestSetting[RelaySynchronizationMethod] {
    val key                   = "relay_server_synchronization_method"
    val startPolicyGeneration = true
    def get: IOResult[RelaySynchronizationMethod]                                       = configService.relay_server_sync_method()
    def set: (RelaySynchronizationMethod, EventActor, Option[String]) => IOResult[Unit] =
      (value: RelaySynchronizationMethod, _, _) => configService.set_relay_server_sync_method(value)
    def parseParam(param: String): PureResult[RelaySynchronizationMethod] = RelaySynchronizationMethod.parse(param)
  }
  given JsonCodec[RelaySynchronizationMethod] = JsonCodec(
    JsonEncoder[String].contramap(_.value),
    JsonDecoder[String].mapOrFail(RelaySynchronizationMethod.parse(_).leftMap(_.fullMsg))
  )

  case object RestRelaySynchronizePolicies    extends RestBooleanSetting {
    val key                   = "relay_server_synchronize_policies"
    val startPolicyGeneration = true
    def get: IOResult[Boolean]                                       = configService.relay_server_syncpromises()
    def set: (Boolean, EventActor, Option[String]) => IOResult[Unit] = (value: Boolean, _, _) =>
      configService.set_relay_server_syncpromises(value)
  }
  case object RestRelaySynchronizeSharedFiles extends RestBooleanSetting {
    val key                   = "relay_server_synchronize_shared_files"
    val startPolicyGeneration = true
    def get: IOResult[Boolean]                                       = configService.relay_server_syncsharedfiles()
    def set: (Boolean, EventActor, Option[String]) => IOResult[Unit] = (value: Boolean, _, _) =>
      configService.set_relay_server_syncsharedfiles(value)
  }

  case object RestReportProtocolDefault extends RestSetting[AgentReportingProtocol] {
    var key                   = "rudder_report_protocol_default"
    val startPolicyGeneration = false
    def get: IOResult[AgentReportingProtocol]                                       = configService.rudder_report_protocol_default()
    def set: (AgentReportingProtocol, EventActor, Option[String]) => IOResult[Unit] = (value: AgentReportingProtocol, _, _) =>
      configService.set_rudder_report_protocol_default(value)
    def parseParam(param: String): PureResult[AgentReportingProtocol] = AgentReportingProtocol.parse(param).leftMap(Unexpected(_))
  }
  given JsonCodec[AgentReportingProtocol] = JsonCodec(
    JsonEncoder[String].contramap(_.value),
    JsonDecoder[String].mapOrFail(AgentReportingProtocol.parse)
  )

  case object RestChangesGraphs extends RestBooleanSetting {
    val startPolicyGeneration = false
    val key                   = "display_recent_changes_graphs"
    def get: IOResult[Boolean]                                       = configService.display_changes_graph()
    def set: (Boolean, EventActor, Option[String]) => IOResult[Unit] = (value: Boolean, _, _) =>
      configService.set_display_changes_graph(value)
  }

  case object RestSendMetrics extends RestSetting[Option[SendMetrics]](using codecMetrics) {
    val startPolicyGeneration = true
    val key                   = "send_metrics"
    def get: IOResult[Option[SendMetrics]]                                       = configService.send_server_metrics()
    def set: (Option[SendMetrics], EventActor, Option[String]) => IOResult[Unit] = configService.set_send_server_metrics
    def parseParam(param: String): PureResult[Option[SendMetrics]] = parseSendMetrics(param)
  }
  private def parseSendMetrics(value: String): PureResult[Option[SendMetrics]] = {
    value match {
      case "complete" | "true" => Right(Some(SendMetrics.CompleteMetrics))
      case "no" | "false"      => Right(Some(SendMetrics.NoMetrics))
      case "minimal"           => Right(Some(SendMetrics.MinimalMetrics))
      case "not_defined"       => Right(None)
      case _                   => Left(Unexpected(s"value for 'send metrics' settings should be 'no', 'minimal' or 'complete' instead of ${value}"))
    }
  }
  private def serializeSendMetrics(opt: Option[SendMetrics]): String = opt match {
    case None                              => "not_defined"
    case Some(SendMetrics.NoMetrics)       => "no"
    case Some(SendMetrics.MinimalMetrics)  => "minimal"
    case Some(SendMetrics.CompleteMetrics) => "complete"
  }
  given codecMetrics: JsonCodec[Option[SendMetrics]] = JsonCodec(
    JsonEncoder[String].contramap(serializeSendMetrics),
    JsonDecoder[String].mapOrFail(parseSendMetrics(_).leftMap(_.fullMsg))
  )

  case object RestJSEngine extends RestSetting[FeatureSwitch] {
    val startPolicyGeneration = true
    val key                   = "enable_javascript_directives"
    def get: IOResult[FeatureSwitch]                                       = configService.rudder_featureSwitch_directiveScriptEngine()
    def set: (FeatureSwitch, EventActor, Option[String]) => IOResult[Unit] = (value: FeatureSwitch, _, _) =>
      configService.set_rudder_featureSwitch_directiveScriptEngine(value)
    def parseParam(param: String): PureResult[FeatureSwitch] = FeatureSwitch.parse(param)
  }
  given JsonCodec[FeatureSwitch] = JsonCodec(
    JsonEncoder[String].contramap(_.name),
    JsonDecoder[String].mapOrFail(FeatureSwitch.parse(_).leftMap(_.fullMsg))
  )

  case object RestOnAcceptPolicyMode extends RestSetting[Option[PolicyMode]](using codecPolicyMode) {
    val startPolicyGeneration = false
    def parseParam(value: String): PureResult[Option[PolicyMode]] = {
      Right(PolicyMode.values.find(_.name == value))
    }
    val key = "node_onaccept_default_policyMode"
    def get: IOResult[Option[PolicyMode]]                                       = configService.rudder_node_onaccept_default_policy_mode()
    def set: (Option[PolicyMode], EventActor, Option[String]) => IOResult[Unit] = (value: Option[PolicyMode], _, _) =>
      configService.set_rudder_node_onaccept_default_policy_mode(value)
  }
  given codecPolicyMode: JsonCodec[Option[PolicyMode]] = JsonCodec(
    JsonEncoder[String].contramap(_.fold("default")(_.name)),
    JsonDecoder[String].mapOrFail(PolicyMode.withNameEither(_).bimap(_.getMessage, Some(_)))
  )

  case object RestOnAcceptNodeState extends RestSetting[NodeState] {
    val startPolicyGeneration = false
    def parseParam(value: String): PureResult[NodeState] = {
      Right(NodeState.values.find(_.name == value).getOrElse(NodeState.Enabled))
    }
    val key = "node_onaccept_default_state"
    def get: IOResult[NodeState]                                       = configService.rudder_node_onaccept_default_state()
    def set: (NodeState, EventActor, Option[String]) => IOResult[Unit] = (value: NodeState, _, _) =>
      configService.set_rudder_node_onaccept_default_state(value)
  }
  given JsonCodec[NodeState] = JsonCodec(
    JsonEncoder[String].contramap(_.name),
    JsonDecoder[String].map(NodeState.withNameOption(_).getOrElse(NodeState.Enabled))
  )

  def startNewPolicyGeneration(actor: EventActor): Unit = {
    val modId = ModificationId(uuidGen.newUuid)
    asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
  }

  case object RestComputeChanges extends RestBooleanSetting {
    val startPolicyGeneration = false
    val key                   = "rudder_compute_changes"
    def get: IOResult[Boolean]                                       = configService.rudder_compute_changes()
    def set: (Boolean, EventActor, Option[String]) => IOResult[Unit] = (value: Boolean, _, _) =>
      configService.set_rudder_compute_changes(value)
  }

  case object RestGenerationComputeDynGroups extends RestBooleanSetting {
    val startPolicyGeneration = false
    val key                   = "rudder_generation_compute_dyngroups"
    def get: IOResult[Boolean]                                       = configService.rudder_generation_compute_dyngroups()
    def set: (Boolean, EventActor, Option[String]) => IOResult[Unit] = (value: Boolean, _, _) =>
      configService.set_rudder_generation_compute_dyngroups(value)
  }

  case object RestGenerationMaxParallelism extends RestStringSetting {
    val startPolicyGeneration = false
    val key                   = "rudder_generation_max_parallelism"
    def get: IOResult[String]                                       = configService.rudder_generation_max_parallelism()
    def set: (String, EventActor, Option[String]) => IOResult[Unit] = (value: String, _, _) =>
      configService.set_rudder_generation_max_parallelism(value)
  }

  case object RestGenerationDelay extends RestSetting[Duration] {
    val startPolicyGeneration = false
    val key                   = "rudder_generation_delay"
    def get:                       IOResult[Duration]                                       = configService.rudder_generation_delay()
    def set:                       (Duration, EventActor, Option[String]) => IOResult[Unit] = (value: Duration, _, _) =>
      configService.set_rudder_generation_delay(value)
    def parseParam(param: String): PureResult[Duration]                                     = {
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

  case object RestPolicyGenerationTrigger extends RestSetting[PolicyGenerationTrigger] {
    val startPolicyGeneration = false
    val key                   = "rudder_generation_policy"
    def get:                       IOResult[PolicyGenerationTrigger]                                       = configService.rudder_generation_trigger()
    def set:                       (PolicyGenerationTrigger, EventActor, Option[String]) => IOResult[Unit] = (value: PolicyGenerationTrigger, _, _) =>
      configService.set_rudder_generation_trigger(value)
    def parseParam(value: String): PureResult[PolicyGenerationTrigger]                                     = {
      PolicyGenerationTrigger.parse(value).leftMap(Unexpected(_))
    }
  }
  given JsonCodec[PolicyGenerationTrigger] = JsonCodec(
    JsonEncoder[String].contramap(_.entryName),
    JsonDecoder[String].mapOrFail(PolicyGenerationTrigger.parse)
  )

  case object RestGenerationJsTimeout extends RestIntSetting {
    val startPolicyGeneration = false
    val key                   = "rudder_generation_js_timeout"
    def get: IOResult[Int]                                       = configService.rudder_generation_js_timeout()
    def set: (Int, EventActor, Option[String]) => IOResult[Unit] = (value: Int, _, _) =>
      configService.set_rudder_generation_js_timeout(value)
  }

  case object RestContinueGenerationOnError extends RestBooleanSetting {
    val startPolicyGeneration = false
    val key                   = "rudder_generation_continue_on_error"
    def get: IOResult[Boolean]                                       = configService.rudder_generation_continue_on_error()
    def set: (Boolean, EventActor, Option[String]) => IOResult[Unit] = (value: Boolean, _, _) =>
      configService.set_rudder_generation_continue_on_error(value)
  }

  case object RestNodeAcceptDuplicatedHostname extends RestBooleanSetting {
    val startPolicyGeneration = false
    val key                   = "node_accept_duplicated_hostname"
    def get: IOResult[Boolean]                                       = configService.node_accept_duplicated_hostname()
    def set: (Boolean, EventActor, Option[String]) => IOResult[Unit] = (value: Boolean, _, _) =>
      configService.set_node_accept_duplicated_hostname(value)
  }

  case object RestComputeDynGroupMaxParallelism extends RestStringSetting {
    val startPolicyGeneration = false
    val key                   = "rudder_compute_dyngroups_max_parallelism"
    def get: IOResult[String]                                       = configService.rudder_compute_dyngroups_max_parallelism()
    def set: (String, EventActor, Option[String]) => IOResult[Unit] = (value: String, _, _) =>
      configService.set_rudder_compute_dyngroups_max_parallelism(value)
  }

  case object RestSetupDone extends RestBooleanSetting {
    val startPolicyGeneration = false
    val key                   = "rudder_setup_done"
    def get: IOResult[Boolean]                                       = configService.rudder_setup_done()
    def set: (Boolean, EventActor, Option[String]) => IOResult[Unit] = (value: Boolean, _, _) =>
      configService.set_rudder_setup_done(value)
  }

  // if the directive is missing for policy server, it may be because it misses dedicated allowed networks.
  // We don't want to fail on that, so we need to special case Empty
  private def getAllowedNetworksForServer(nodeId: NodeId): IOResult[Chunk[AllowedNetwork]] = {
    policyServerManagementService.getAllowedNetworks(nodeId).map(Chunk.from(_))
  }

  def getRootNameForVersion(version: ApiVersion): String = {
    "allowed_networks"
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
