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

import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.appconfig.ReadConfigService
import com.normation.appconfig.UpdateConfigService
import com.normation.inventory.domain.NodeId
import com.normation.rudder.batch.AsyncDeploymentActor
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.batch.PolicyGenerationTrigger
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.reports._
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.RestUtils
import com.normation.rudder.rest.{SettingsApi => API}
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.reports.UnexpectedReportBehavior
import com.normation.rudder.services.servers.PolicyServerManagementService
import com.normation.rudder.services.servers.RelaySynchronizationMethod
import com.normation.rudder.services.servers.RelaySynchronizationMethod._
import com.normation.utils.Control.bestEffort
import com.normation.utils.Control.sequence
import com.normation.utils.StringUuidGenerator

import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.json._
import net.liftweb.json.JsonDSL._

import com.normation.box._
import com.normation.errors._
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.services.policies.SendMetrics
import com.normation.rudder.services.servers.AllowedNetwork

import com.normation.zio._
import zio._
import zio.syntax._
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

class SettingsApi(
    val restExtractorService         : RestExtractorService
  , val configService                : ReadConfigService with UpdateConfigService
  , val asyncDeploymentAgent         : AsyncDeploymentActor
  , val uuidGen                      : StringUuidGenerator
  , val policyServerManagementService: PolicyServerManagementService
  , val nodeInfoService              : NodeInfoService
) extends LiftApiModuleProvider[API] {


  val allSettings_v10: List[RestSetting[_]] =
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
      RestHeartbeat ::
      RestChangeMessageEnabled ::
      RestChangeMessageManadatory ::
      RestChangeMessagePrompt ::
      RestChangeRequestEnabled ::
      RestChangeRequestSelfValidation ::
      RestChangeRequestSelfDeployment ::
      RestChangesGraphs ::
      RestJSEngine ::
      RestSendMetrics ::
      RestOnAcceptNodeState ::
      RestOnAcceptPolicyMode ::
      RestChangeRequestUnexpectedUnboundVarValues ::
      RestComputeChanges ::
      RestGenerationComputeDynGroups ::
      RestPersistComplianceLevels ::
      RestPersistComplianceDetails ::
      RestGenerationMaxParallelism ::
      RestGenerationJsTimeout ::
      RestContinueGenerationOnError ::
      RestGenerationDelay ::
      RestPolicyGenerationTrigger ::
      RestNodeAcceptDuplicatedHostname ::
      RestComputeDynGroupMaxParallelism ::
      RestSetupDone ::
      ArchiveApiFeatureSwitch ::
      Nil

  val allSettings_v12 =   RestReportProtocolDefault :: allSettings_v10

  def allSettings(version: ApiVersion) = {
    if (version.value <= 10) {
      allSettings_v10
    } else {
      allSettings_v12
    }
  }

  val kind = "settings"


  def schemas = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => e match {
      case API.GetAllAllowedNetworks     => GetAllAllowedNetworks
      case API.GetAllowedNetworks        => GetAllowedNetworks
      case API.ModifyAllowedNetworks     => ModifyAllowedNetworks
      case API.ModifyDiffAllowedNetworks => ModifyDiffAllowedNetworks
      case API.GetAllSettings            => GetAllSettings
      case API.ModifySettings            => ModifySettings
      case API.GetSetting                => GetSetting
      case API.ModifySetting             => ModifySetting
    })
  }


  object GetAllSettings extends LiftApiModule0 {
    val schema = API.GetAllSettings
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val settings = for {
        setting <- allSettings(version)
      } yield {
        val result = setting.getJson match {
          case Full(res) => res
          case eb : EmptyBox =>
            val fail = eb ?~! s"Could not get parameter '${setting.key}'"
            JString(fail.messageChain)
        }
        JField(setting.key, result)
      }
      val data = if (version.value >= 11) {
        val networks = GetAllAllowedNetworks.getAllowedNetworks().either.runNow match {
            case Right(nets) =>
              nets
            case Left(err) =>
              val fail = s"Could not get parameter 'allowed_networks': ${err.fullMsg}"
              JString(fail)
          }
        JField("allowed_networks",networks) :: settings
      } else {
        settings
      }

      //sort settings alphanum
      RestUtils.response(restExtractorService, "settings", None)(Full(data.sortBy(_.name)), req, s"Could not settings")("getAllSettings")
    }
  }

  object ModifySettings extends LiftApiModule0 {
    val schema = API.ModifySettings
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      var generate = false
      val data = for {
        setting <- allSettings(version)
        value   <- setting.setFromRequestOpt(req, authzToken.actor)
      } yield {
        if (value.isDefined) generate = generate || setting.startPolicyGeneration
        JField(setting.key , value)
      }
      startNewPolicyGeneration(authzToken.actor)
      RestUtils.response(restExtractorService, "settings", None)(Full(data), req, s"Could not modfiy settings")("modifySettings")
    }
  }

  object GetSetting extends LiftApiModule {
    val schema = API.GetSetting
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, key: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val data : Box[JValue] = for {
        setting <- settingFromKey(key, allSettings(version))
        value   <- setting.getJson
      } yield {
        (key -> value)
      }
      RestUtils.response(restExtractorService, "settings", Some(key))(data, req, s"Could not get parameter '${key}'")("getSetting")
    }
  }

  object ModifySetting extends LiftApiModule {
    val schema = API.ModifySetting
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, key: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val data : Box[JValue] = for {
        setting <- settingFromKey(key, allSettings(version))
        value   <- setting.setFromRequest(req, authzToken.actor)
      } yield {
        (key -> value)
      }
      RestUtils.response(restExtractorService, "settings", Some(key))(data, req, s"Could not modify parameter '${key}'")("modifySetting")
    }
  }



  ///////////////
  /////////////// Utility function and definition for each setting
  ///////////////



  def settingFromKey(key : String, modules: List[RestSetting[_]]) : Box[RestSetting[_]] = {
      modules.find { _.key == key } match {
        case Some(setting) => Full(setting)
        case None          => Failure(s"'$key' is not a valid settings key")
      }
  }

  sealed trait RestSetting[T] {
    def key : String
    def get : IOResult[T]
    def toJson(value : T) : JValue
    def getJson : Box[JValue] = {
      for {
        value <- get
      } yield {
        toJson(value)
      }
    }.toBox
    def set : ( T, EventActor, Option[String]) => IOResult[Unit]
    def parseJson(json : JValue) : Box[T]
    def parseParam(param : String) : Box[T]
    type t = T

    def extractData(req : Req) = {
      req.json match {
        case Full(json) => parseJson(json \ "value")
        case _ => req.params.get("value") match {
          case Some(value :: Nil) => parseParam(value)
          case Some(values) => Failure("Too much values defined, only need one")
          case None => Failure("No value defined in request")
        }
      }
    }

   def extractDataOpt(req : Req) : Box[Option[T]] = {
      req.json match {
        case Full(json) => json \ key match {
          case JNothing => Full(None)
          case value => parseJson(value).map(Some(_))
        }
        case _ => req.params.get(key) match {
          case Some(value :: Nil) => parseParam(value).map(Some(_))
          // Not sure it can happen, but still treat it as empty value
          case Some(Nil) => Full(None)
          case Some(values) => Failure("Too much values defined, only need one")
          case None => Full(None)
        }
      }
   }

    def startPolicyGeneration : Boolean

    def setFromRequest(req: Req, actor: EventActor) = {
      for {
        value   <- extractData(req)
        result  <- set(value, actor, None).toBox
      } yield {
        if (startPolicyGeneration) startNewPolicyGeneration(actor)
        toJson(value)
      }
    }

    def setFromRequestOpt(req: Req, actor: EventActor) : Box[Option[JValue]]= {
      for {
        optValue   <- extractDataOpt(req)
        result     <-
          optValue match {
            case Some(value) =>
              for {
                result  <- set(value, actor, None).toBox
              } yield {
                Some(toJson(value))
              }
            case None => Full(None)
            }
      } yield {
        result
      }
    }
  }

final case object RestPolicyMode extends RestSetting[PolicyMode] {
    val key = "global_policy_mode"
    val startPolicyGeneration = true
    def get = configService.rudder_policy_mode_name()
    def set = configService.set_rudder_policy_mode_name _
    def toJson(value : PolicyMode) : JValue = value.name
    def parseJson(json: JValue) = {
      json match {
        case JString(value) => PolicyMode.parse(value).toBox
        case x => Failure("Invalid value "+x)
      }
    }
    def parseParam(param : String) = {
      PolicyMode.parse(param)
    }.toBox
  }

  trait RestBooleanSetting extends RestSetting[Boolean] {
    def toJson(value : Boolean) : JValue = value
    def parseJson(json: JValue) = {
      json match {
        case JBool(value) => Full(value)
        case x => Failure("Invalid value "+x)
      }
    }
    def parseParam(param : String) = {
      param match {
        case "true"  => Full(true)
        case "false" => Full(false)
        case _       => Failure(s"value for boolean should be true or false instead of ${param}")
      }
    }
  }

  trait RestStringSetting extends RestSetting[String] {
    def toJson(value : String) : JValue = value
    def parseJson(json: JValue) = {
      json match {
        case JString(value) => Full(value)
        case x => Failure("Invalid value "+x)
      }
    }
    def parseParam(param : String) = {
      Full(param)
    }
  }

  trait RestIntSetting extends RestSetting[Int] {
    def toJson(value : Int) : JValue = value
    def parseJson(json: JValue) = {
      json match {
        case JInt(value) => Full(value.toInt)
        case x => Failure("Invalid value "+x)
      }
    }
    def parseParam(param : String) = {
      try {
        Full(param.toInt)
      } catch  {
        case _ : java.lang.NumberFormatException => Failure(s"value for integer should be an integer instead of ${param}")
      }
    }
  }

final case object RestPolicyModeOverridable extends RestBooleanSetting {
    val key = "global_policy_mode_overridable"
    val startPolicyGeneration = true
    def get = configService.rudder_policy_overridable()
    def set =  configService.set_rudder_policy_overridable _
  }

final case object RestRunFrequency extends RestIntSetting {
    val key = "run_frequency"
    val startPolicyGeneration = true
    def get = configService.agent_run_interval()
    def set = configService.set_agent_run_interval _
  }
final case object RestRunHour extends RestIntSetting {
    val key = "first_run_hour"
    val startPolicyGeneration = true
    def get = configService.agent_run_start_hour()
    def set = configService.set_agent_run_start_hour _
  }
final case object RestRunMinute extends RestIntSetting {
    val key = "first_run_minute"
    val startPolicyGeneration = true
    def get = configService.agent_run_start_minute()
    def set = configService.set_agent_run_start_minute _
  }
final case object RestSplayTime extends RestIntSetting {
    val key = "splay_time"
    val startPolicyGeneration = true
    def get = configService.agent_run_splaytime()
    def set = configService.set_agent_run_splaytime _
  }
final case object RestModifiedFileTTL extends RestIntSetting {
    val key = "modified_file_ttl"
    val startPolicyGeneration = true
    def get = configService.cfengine_modified_files_ttl()
    def set = (value : Int, _, _) => configService.set_cfengine_modified_files_ttl(value)
  }
final case object RestOutputFileTTL extends RestIntSetting {
    val key = "output_file_ttl"
    val startPolicyGeneration = true
    def get = configService.cfengine_outputs_ttl()
    def set = (value : Int, _, _) => configService.set_cfengine_outputs_ttl(value)
  }
final case object RestReportingMode extends RestSetting[ComplianceModeName] {
    val key = "reporting_mode"
    val startPolicyGeneration = true
    def get = for {
      name <- configService.rudder_compliance_mode_name()
      mode <- ComplianceModeName.parse(name).toIO
    } yield {
      mode
    }
    def set =  (value : ComplianceModeName, actor : EventActor, reason : Option[String]) => configService.set_rudder_compliance_mode_name(value.name,actor,reason)
    def parseJson(json: JValue) = {
      json match {
        case JString(value) => ComplianceModeName.parse(value)
        case x => Failure("Invalid value "+x)
      }
    }
    def parseParam(param : String) = {
      ComplianceModeName.parse(param)
    }
    def toJson(value : ComplianceModeName) : JValue = value.name
  }
final case object RestHeartbeat extends RestIntSetting {
    val startPolicyGeneration = true
    val key = "heartbeat_frequency"
    def get = configService.rudder_compliance_heartbeatPeriod()
    def set =  configService.set_rudder_compliance_heartbeatPeriod _
  }
final case object RestChangeMessageEnabled extends RestBooleanSetting {
    val startPolicyGeneration = false
    val key = "enable_change_message"
    def get = configService.rudder_ui_changeMessage_enabled()
    def set = (value : Boolean, _, _) => configService.set_rudder_ui_changeMessage_enabled(value)
  }
final case object RestChangeMessageManadatory extends RestBooleanSetting {
    val startPolicyGeneration = false
    val key = "mandatory_change_message"
    def get = configService.rudder_ui_changeMessage_mandatory()
    def set = (value : Boolean, _, _) => configService.set_rudder_ui_changeMessage_mandatory(value)
  }
final case object RestChangeMessagePrompt extends RestStringSetting {
    val startPolicyGeneration = false
    val key = "change_message_prompt"
    def get = configService.rudder_ui_changeMessage_explanation()
    def set = (value : String, _, _) => configService.set_rudder_ui_changeMessage_explanation(value)
  }
final case object RestChangeRequestEnabled extends RestBooleanSetting {
    val startPolicyGeneration = false
    val key = "enable_change_request"
    def get = configService.rudder_workflow_enabled()
    def set = (value : Boolean, _, _) => configService.set_rudder_workflow_enabled(value)
  }
final case object RestChangeRequestSelfDeployment extends RestBooleanSetting {
    val startPolicyGeneration = false
    val key = "enable_self_deployment"
    def get = configService.rudder_workflow_self_deployment()
    def set = (value : Boolean, _, _) => configService.set_rudder_workflow_self_deployment(value)
  }
final case object RestChangeRequestSelfValidation extends RestBooleanSetting {
    val key = "enable_self_validation"
    val startPolicyGeneration = true
    def get = configService.rudder_workflow_self_validation()
    def set = (value : Boolean, _, _) => configService.set_rudder_workflow_self_validation(value)
  }
final case object RestRequireTimeSynch extends RestBooleanSetting {
    val key = "require_time_synchronization"
    val startPolicyGeneration = true
    def get = configService.cfengine_server_denybadclocks()
    def set = (value : Boolean, _, _) => configService.set_cfengine_server_denybadclocks(value)
  }
final case object RestRelaySyncMethod extends RestSetting[RelaySynchronizationMethod] {
    val key = "relay_server_synchronization_method"
    val startPolicyGeneration = true
    def get = configService.relay_server_sync_method()
    def set = (value : RelaySynchronizationMethod, _, _) => configService.set_relay_server_sync_method(value)
    def toJson(value : RelaySynchronizationMethod) : JValue = value.value
    def parseJson(json: JValue) = {
      json match {
        case JString(value) => parseParam(value.toLowerCase())
        case x => Failure("Invalid value "+x)
      }
    }
    def parseParam(param : String) = {
      param.toLowerCase() match {
        case Classic.value  => Full(Classic)
        case Rsync.value    => Full(Rsync)
        case Disabled.value => Full(Disabled)
        case _ => Failure(s"Invalid value '${param}' for relay server synchronization method")
      }
    }
  }
final case object RestRelaySynchronizePolicies extends RestBooleanSetting {
    val key = "relay_server_synchronize_policies"
    val startPolicyGeneration = true
    def get = configService.relay_server_syncpromises()
    def set = (value : Boolean, _, _) => configService.set_relay_server_syncpromises(value)
  }
final case object RestRelaySynchronizeSharedFiles extends RestBooleanSetting {
    val key = "relay_server_synchronize_shared_files"
    val startPolicyGeneration = true
    def get = configService.relay_server_syncsharedfiles()
    def set = (value : Boolean, _, _) => configService.set_relay_server_syncsharedfiles(value)
  }

final case object RestReportProtocolDefault extends RestSetting[AgentReportingProtocol] {
  var key = "rudder_report_protocol_default"
  val startPolicyGeneration = false
  def get = configService.rudder_report_protocol_default()
  def set = (value : AgentReportingProtocol, _, _)  => configService.set_rudder_report_protocol_default(value)
  def toJson(value : AgentReportingProtocol) : JValue = value.value
  def parseJson(json: JValue) = {
    json match {
      case JString(value) => parseParam(value.toUpperCase())
      case x => Failure("Invalid value "+x)
    }
  }
  def parseParam(param : String) = {
    param.toUpperCase() match {
      case AgentReportingHTTPS.value  => Full(AgentReportingHTTPS)
      case _ => Failure(s"Invalid value '${param}' for default reporting method")
    }
  }
}

final case object RestChangesGraphs extends RestBooleanSetting {
  val startPolicyGeneration = false
  val key = "display_recent_changes_graphs"
  def get = configService.display_changes_graph()
  def set = (value : Boolean, _, _) => configService.set_display_changes_graph(value)
}

final case object RestSendMetrics extends RestSetting[Option[SendMetrics]] {
  val startPolicyGeneration = true
  def toJson(value : Option[SendMetrics]) : JValue = JString ( value match {
    case None => "not_defined"
    case Some(SendMetrics.NoMetrics) => "no"
    case Some(SendMetrics.MinimalMetrics) => "minimal"
    case Some(SendMetrics.CompleteMetrics) => "complete"
  })
  def parseJson(json: JValue) = {
    json match {
      case JString(s)    => parseParam(s)
      case x             => Failure(s"Invalid value for 'send metrics' settings, should be a json string 'no'/'minimal'/'complete', but is instead: ${net.liftweb.json.compactRender(x)}")
    }
  }
  def parseParam(param : String): Box[Option[SendMetrics]] = {
    param match {
      case "complete"|"true"        => Full(Some(SendMetrics.CompleteMetrics))
      case "no"|"false"       => Full(Some(SendMetrics.NoMetrics))
      case "minimal"       => Full(Some(SendMetrics.MinimalMetrics))
      case "not_defined" => Full(None)
      case _             => Failure(s"value for 'send metrics' settings should be 'no', 'minimal' or 'complete' instead of ${param}")
    }
  }
  val key = "send_metrics"
  def get = configService.send_server_metrics()
  def set = configService.set_send_server_metrics _
}

  final case object RestJSEngine extends RestSetting[FeatureSwitch] {
    val startPolicyGeneration = true
    def toJson(value : FeatureSwitch) : JValue = value.name
    def parseJson(json: JValue) = {
      json match {
        case JString(value) => FeatureSwitch.parse(value)
        case x              => Failure("Invalid value "+x)
      }
    }
    def parseParam(param : String) = {
      FeatureSwitch.parse(param)
    }
    val key = "enable_javascript_directives"
    def get = configService.rudder_featureSwitch_directiveScriptEngine()
    def set = (value : FeatureSwitch, _, _) => configService.set_rudder_featureSwitch_directiveScriptEngine(value)
  }

  final case object ArchiveApiFeatureSwitch extends RestSetting[FeatureSwitch] {
    val startPolicyGeneration = false
    def toJson(value : FeatureSwitch) : JValue = value.name
    def parseJson(json: JValue) = {
      json match {
        case JString(value) => FeatureSwitch.parse(value)
        case x              => Failure("Invalid value "+x)
      }
    }
    def parseParam(param : String) = {
      FeatureSwitch.parse(param)
    }
    val key = "rudder_featureSwitch_archiveApi"
    def get = configService.rudder_featureSwitch_archiveApi()
    def set = (value : FeatureSwitch, _, _) => configService.set_rudder_featureSwitch_archiveApi(value)
  }

  final case object RestOnAcceptPolicyMode extends RestSetting[Option[PolicyMode]] {
    val startPolicyGeneration = false
    def parseParam(value: String): Box[Option[PolicyMode]] = {
      Full(PolicyMode.allModes.find( _.name == value))
    }
    def toJson(value: Option[PolicyMode]) : JValue = JString(value.map( _.name ).getOrElse("default"))
    def parseJson(json: JValue) = {
      json match {
        case JString(value) => parseParam(value)
        case x              => Failure("Invalid value "+x)
      }
    }
    val key = "node_onaccept_default_policyMode"
    def get = configService.rudder_node_onaccept_default_policy_mode()
    def set = (value : Option[PolicyMode], _, _) => configService.set_rudder_node_onaccept_default_policy_mode(value)
  }

  final case object RestOnAcceptNodeState extends RestSetting[NodeState] {
    val startPolicyGeneration = false
    def parseParam(value: String): Box[NodeState] = {
      Full(NodeState.values.find( _.name == value).getOrElse(NodeState.Enabled))
    }
    def toJson(value: NodeState) : JValue = value.name
    def parseJson(json: JValue) = {
      json match {
        case JString(value) => parseParam(value)
        case x              => Failure("Invalid value "+x)
      }
    }
    val key = "node_onaccept_default_state"
    def get = configService.rudder_node_onaccept_default_state()
    def set = (value : NodeState, _, _) => configService.set_rudder_node_onaccept_default_state(value)
  }

  trait RestChangeUnexpectedReportInterpretation extends RestBooleanSetting {
    def prop: UnexpectedReportBehavior
    def get = configService.rudder_compliance_unexpected_report_interpretation().map( _.isSet(prop))
    def set = (value : Boolean, _, _) => {
      for {
        config  <- configService.rudder_compliance_unexpected_report_interpretation()
        newConf =  if(value) {
                     config.set(prop)
                   } else {
                     config.unset(prop)
                   }
        updated <- configService.set_rudder_compliance_unexpected_report_interpretation(newConf)
      } yield {
        updated
      }
    }
  }

final case object RestChangeRequestUnexpectedUnboundVarValues extends RestChangeUnexpectedReportInterpretation {
    val startPolicyGeneration = false
    val key = "unexpected_unbound_var_values"
    val prop = UnexpectedReportBehavior.UnboundVarValues
  }

  case object RestRudderVerifyCertificates extends RestBooleanSetting {
    val key = "rudder_verify_certificates"
    val startPolicyGeneration = true
    def get = configService.rudder_verify_certificates()
    def set = (value : Boolean, actor : EventActor, reason : Option[String])  => configService.set_rudder_verify_certificates(value, actor, reason)
  }

  def startNewPolicyGeneration(actor : EventActor) = {
    val modId = ModificationId(uuidGen.newUuid)
    asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
  }

  def response ( function : Box[JValue], req : Req, errorMessage : String, id : Option[String])(implicit action : String) : LiftResponse = {
    RestUtils.response(restExtractorService, kind, id)(function, req, errorMessage)
  }

final case object RestComputeChanges extends RestBooleanSetting {
    val startPolicyGeneration = false
    val key = "rudder_compute_changes"
    def get = configService.rudder_compute_changes()
    def set = (value : Boolean, _, _) => configService.set_rudder_compute_changes(value)
  }

final case object RestGenerationComputeDynGroups extends RestBooleanSetting {
    val startPolicyGeneration = false
    val key = "rudder_generation_compute_dyngroups"
    def get = configService.rudder_generation_compute_dyngroups()
    def set = (value : Boolean, _, _) => configService.set_rudder_generation_compute_dyngroups(value)
  }

final case object RestPersistComplianceLevels extends RestBooleanSetting {
    val startPolicyGeneration = false
    val key = "rudder_save_db_compliance_levels"
    def get = configService.rudder_save_db_compliance_levels()
    def set = (value : Boolean, _, _) => configService.set_rudder_save_db_compliance_levels(value)
  }

final case object RestPersistComplianceDetails extends RestBooleanSetting {
    val startPolicyGeneration = false
    val key = "rudder_save_db_compliance_details"
    def get = configService.rudder_save_db_compliance_details()
    def set = (value : Boolean, _, _) => configService.set_rudder_save_db_compliance_details(value)
  }
final case object RestGenerationMaxParallelism extends RestStringSetting {
    val startPolicyGeneration = false
    val key = "rudder_generation_max_parallelism"
    def get = configService.rudder_generation_max_parallelism()
    def set = (value : String, _, _) => configService.set_rudder_generation_max_parallelism(value)
  }

final case object RestGenerationDelay extends RestSetting[Duration] {
  val startPolicyGeneration = false
  val key = "rudder_generation_delay"
  def get = configService.rudder_generation_delay()
  def set = (value : Duration, _, _) => configService.set_rudder_generation_delay(value)
  def toJson(value: Duration): JValue = JString(value.toString)
  def parseJson(json: JValue): Box[Duration] = json match {
    case JString(jstring) => parseParam(jstring)
    case _ => Failure( s"Could not extract a valid duration from ${net.liftweb.json.compactRender(json)}")
  }
  def parseParam(param: String): Box[Duration] = net.liftweb.util.ControlHelpers.tryo(Duration(param)) match {
    case eb : EmptyBox => eb ?~! s"Could not extract a valid duration from ${param}"
    case res => res
  }
}

final case object RestPolicyGenerationTrigger extends RestSetting[PolicyGenerationTrigger] {
  val startPolicyGeneration = false
  val key = "rudder_generation_policy"
  def get = configService.rudder_generation_trigger()
  def set = (value : PolicyGenerationTrigger, _, _) => configService.set_rudder_generation_trigger(value)
  def toJson(value: PolicyGenerationTrigger): JValue = JString(PolicyGenerationTrigger.serialize(value))
  def parseJson(json: JValue): Box[PolicyGenerationTrigger] = json match {
    case JString(jstring) => parseParam(jstring)
    case _ => Failure( s"Could not extract a valid generation policy from ${net.liftweb.json.compactRender(json)}")
  }
  def parseParam(value: String): Box[PolicyGenerationTrigger] = {
    PolicyGenerationTrigger(value).toBox
  }
}

final case object RestGenerationJsTimeout extends RestIntSetting {
    val startPolicyGeneration = false
    val key = "rudder_generation_js_timeout"
    def get = configService.rudder_generation_js_timeout()
    def set = (value : Int, _, _) => configService.set_rudder_generation_js_timeout(value)
  }

final case object RestContinueGenerationOnError extends RestBooleanSetting {
    val startPolicyGeneration = false
    val key = "rudder_generation_continue_on_error"
    def get = configService.rudder_generation_continue_on_error()
    def set = (value : Boolean, _, _) => configService.set_rudder_generation_continue_on_error(value)
  }

  final case object RestNodeAcceptDuplicatedHostname extends RestBooleanSetting {
    val startPolicyGeneration = false
    val key = "node_accept_duplicated_hostname"
    def get = configService.node_accept_duplicated_hostname()
    def set = (value : Boolean, _, _) => configService.set_node_accept_duplicated_hostname(value)
  }

  final case object RestComputeDynGroupMaxParallelism extends RestStringSetting {
    val startPolicyGeneration = false
    val key = "rudder_compute_dyngroups_max_parallelism"
    def get = configService.rudder_compute_dyngroups_max_parallelism()
    def set = (value : String, _, _) => configService.set_rudder_compute_dyngroups_max_parallelism(value)
  }

  final case object RestSetupDone extends RestBooleanSetting {
    val startPolicyGeneration = false
    val key = "rudder_setup_done"
    def get = configService.rudder_setup_done()
    def set = (value : Boolean, _, _) => configService.set_rudder_setup_done(value)
  }

  // if the directive is missing for policy server, it may be because it misses dedicated allowed networks.
  // We don't want to fail on that, so we need to special case Empty
  def getAllowedNetworksForServer(nodeId: NodeId): IOResult[Seq[String]] = {
    policyServerManagementService.getAllowedNetworks(nodeId).map(_.map(_.inet))
  }

  object GetAllAllowedNetworks extends LiftApiModule0 {

    def getAllowedNetworks() = {
      for {
        servers         <- nodeInfoService.getAllSystemNodeIds()
        allowedNetworks <- policyServerManagementService.getAllAllowedNetworks()
      } yield {
        val toKeep = servers.map(id => (id.value, allowedNetworks.getOrElse(id, Nil).map(_.inet)))
        import net.liftweb.json.JsonDSL._
        JArray(toKeep.toList.map {
          case (nodeid, networks) =>
            ("id" -> nodeid) ~ ("allowed_networks" -> networks.toList.sorted)
        })
      }
    }

    override val schema = API.GetAllAllowedNetworks
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action = "getAllAllowedNetworks"
      RestUtils.response(restExtractorService, "settings", None)(getAllowedNetworks().toBox, req, s"Could not get allowed networks")
    }
  }


  object GetAllowedNetworks extends LiftApiModule {
    override val schema = API.GetAllowedNetworks
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {

      implicit val action = "getAllowedNetworks"
      val result = for {
        nodeInfo <- nodeInfoService.getNodeInfo(NodeId(id))
        isServer <- nodeInfo match {
          case Some(info) if info.isPolicyServer =>
            ZIO.unit
          case Some(_) =>
            Inconsistency(s"Can get allowed networks of node with '${id}' because it is node a policy server (root or relay)").fail
          case None =>
            Inconsistency(s"Could not find node information for id '${id}', this node does not exist").fail
        }
        networks <- getAllowedNetworksForServer(NodeId(id))
      } yield {
        JObject(JField("allowed_networks", JArray(networks.toList.sorted.map(JString))))
      }
      RestUtils.response(restExtractorService, "settings", Some(id))(result.toBox, req, s"Could not get allowed networks for policy server '${id}'")
    }
  }

  object ModifyAllowedNetworks extends LiftApiModule {
    override val schema = API.ModifyAllowedNetworks
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {

      implicit val action = "modifyAllowedNetworks"

      def checkAllowedNetwork (v : String ) = {
          val netWithoutSpaces = v.replaceAll("""\s""", "")
          if(netWithoutSpaces.length != 0) {
            if (AllowedNetwork.isValid(netWithoutSpaces)) {
              Full(netWithoutSpaces)
            } else {
              Failure(s"${netWithoutSpaces} is not a valid allowed network")
            }
          }
          else {
            Failure("Cannot pass an empty allowed network")
          }
      }

      val actor = authzToken.actor
      val modificationId = new ModificationId(uuidGen.newUuid)
      val nodeId = NodeId(id)
      val result = for {
        nodeInfo <- nodeInfoService.getNodeInfo(nodeId).toBox
        isServer <- nodeInfo match {
          case Some(info) if info.isPolicyServer =>
            Full(())
          case Some(_) =>
            Failure(s"Can set allowed networks of node with '${id}' because it is node a policy server (root or relay)")
          case None =>
            Failure(s"Could not find node information for id '${id}', this node does not exist")
        }
        networks <- if (req.json_?) {
                      req.json.getOrElse(JNothing) \ "allowed_networks" match {
                        case JArray(values) => sequence(values) { _ match {
                                                 case JString(s) => checkAllowedNetwork(s)
                                                 case x          => Failure(s"Error extracting a string from json: '${x}'")
                                               } }
                        case _              => Failure(s"You must specify an array of IP for 'allowed_networks' parameter")
                      }
                    } else {
                      req.params.get("allowed_networks") match {
                        case None       => Failure(s"You must specify an array of IP for 'allowed_networks' parameter")
                        case Some(list) => bestEffort(list)(checkAllowedNetwork(_))
                      }
                    }
        // For now, we set name identical to inet
        nets     =  networks.map(inet => AllowedNetwork(inet, inet))
        set      <- policyServerManagementService.setAllowedNetworks(nodeId, nets, modificationId, actor).toBox
      } yield {
        asyncDeploymentAgent.launchDeployment(AutomaticStartDeployment(ModificationId(uuidGen.newUuid), actor))
        JArray(networks.map(JString).toList)
      }
      RestUtils.response(restExtractorService, "settings", Some(id))(result, req, s"Error when trying to modify allowed networks for policy server '${id}'")
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
    override val schema = API.ModifyDiffAllowedNetworks
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {

      implicit val action = "modifyDiffAllowedNetworks"

      def checkAllowedNetwork (v : String ) = {
          val netWithoutSpaces = v.replaceAll("""\s""", "")
          if(netWithoutSpaces.length != 0) {
            if (AllowedNetwork.isValid(netWithoutSpaces)) {
              Full(netWithoutSpaces)
            } else {
              Failure(s"${netWithoutSpaces} is not a valid allowed network")
            }
          }
          else {
            Failure("Cannot pass an empty allowed network")
          }
      }

      val actor = authzToken.actor
      val modificationId = new ModificationId(uuidGen.newUuid)
      val nodeId = NodeId(id)

      implicit val formats = DefaultFormats

      val result = for {
        nodeInfo <- nodeInfoService.getNodeInfo(nodeId).toBox
        isServer <- nodeInfo match {
          case Some(info) if info.isPolicyServer =>
            Full(())
          case Some(_) =>
            Failure(s"Can set allowed networks of node with '${id}' because it is node a policy server (root or relay)")
          case None =>
            Failure(s"Could not find node information for id '${id}', this node does not exist")
        }
        json     =  if (req.json_?) {
                      req.json.getOrElse(JNothing) \ "allowed_networks"
                    } else {
                      req.params.get("allowed_networks").flatMap(_.headOption.flatMap(parseOpt)).getOrElse(JNothing)
                    }
        // lift is dumb and have zero problem not erroring on extraction from JNothing
        msg      = s"""Impossible to parse allowed networks diff json. Expected {"allowed_networks": {"add":["192.168.2.0/24", ...]",""" +
                   s""" "delete":["192.168.1.0/24", ...]"}}, got: ${if(json == JNothing) "nothing" else compactRender(json)}"""
        _        <- if(json == JNothing) Failure(msg) else Full(())
        diff     <- try { Full(json.extract[AllowedNetDiff]) } catch { case NonFatal(ex) => Failure(msg) }
        _        <- sequence(diff.add)(checkAllowedNetwork)
        // for now, we use inet as the name, too
        nets     =  diff.add.map(inet => AllowedNetwork(inet, inet))
        res      <- policyServerManagementService.updateAllowedNetworks(nodeId, nets, diff.delete, modificationId, actor).toBox
      } yield {
        asyncDeploymentAgent.launchDeployment(AutomaticStartDeployment(ModificationId(uuidGen.newUuid), actor))
        JArray(res.map(n => JString(n.inet)).toList)
      }
      RestUtils.response(restExtractorService, "settings", Some(id))(result, req, s"Error when trying to modify allowed networks for policy server '${id}'")
    }
  }
}


// for lift-json extraction, must be top-level
final case class AllowedNetDiff(add: List[String], delete: List[String])

