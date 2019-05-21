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
import com.normation.rudder.batch.AsyncDeploymentActor
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.reports.ComplianceModeName
import com.normation.rudder.reports.SyslogProtocol
import com.normation.rudder.reports.SyslogTCP
import com.normation.rudder.reports.SyslogUDP
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.ApiVersion
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.RestUtils
import com.normation.rudder.rest.{SettingsApi => API}
import com.normation.rudder.services.reports.UnexpectedReportBehavior
import com.normation.rudder.services.servers.RelaySynchronizationMethod
import com.normation.rudder.services.servers.RelaySynchronizationMethod._
import com.normation.utils.StringUuidGenerator
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.json.JsonAST.JBool
import net.liftweb.json.JsonAST.JInt
import net.liftweb.json.JsonAST.JString
import net.liftweb.json.JsonAST.JValue
import net.liftweb.json.JsonAST._
import net.liftweb.json.JsonDSL._
import com.normation.box._
import com.normation.errors._
import scalaz.zio._
import scalaz.zio.syntax._

class SettingsApi(
    val restExtractorService: RestExtractorService
  , val configService       : ReadConfigService with UpdateConfigService
  , val asyncDeploymentAgent: AsyncDeploymentActor
  , val uuidGen             : StringUuidGenerator
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
      RestReportingProtocol ::
      RestReportingMode ::
      RestHeartbeat ::
      RestLogAllReports ::
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
      RestChangeRequestUnexpectedAllowsDuplicate ::
      RestChangeRequestUnexpectedUnboundVarValues ::
      Nil

  val allSettings_v8 = RestUseReverseDNS :: allSettings_v10

  def allSettings(version: ApiVersion) = {
    if(version.value <= 8) allSettings_v8 else allSettings_v10
  }

  val kind = "settings"


  def schemas = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => e match {
      case API.GetAllSettings => GetAllSettings
      case API.ModifySettings => ModifySettings
      case API.GetSetting     => GetSetting
      case API.ModifySetting  => ModifySetting
    }).toList
  }

  object GetAllSettings extends LiftApiModule0 {
    val schema = API.GetAllSettings
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val data = for {
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
      RestUtils.response(restExtractorService, "settings", None)(Full(data), req, s"Could not settings")("getAllSettings")
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

  case object RestPolicyMode extends RestSetting[PolicyMode] {
    val key = "global_policy_mode"
    val startPolicyGeneration = true
    def get = configService.rudder_policy_mode_name
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

  case object RestPolicyModeOverridable extends RestBooleanSetting {
    val key = "global_policy_mode_overridable"
    val startPolicyGeneration = true
    def get = configService.rudder_policy_overridable()
    def set =  configService.set_rudder_policy_overridable _
  }

  case object RestRunFrequency extends RestIntSetting {
    val key = "run_frequency"
    val startPolicyGeneration = true
    def get = configService.agent_run_interval()
    def set = configService.set_agent_run_interval _
  }
  case object RestRunHour extends RestIntSetting {
    val key = "first_run_hour"
    val startPolicyGeneration = true
    def get = configService.agent_run_start_hour()
    def set = configService.set_agent_run_start_hour _
  }
  case object RestRunMinute extends RestIntSetting {
    val key = "first_run_minute"
    val startPolicyGeneration = true
    def get = configService.agent_run_start_minute()
    def set = configService.set_agent_run_start_minute _
  }
  case object RestSplayTime extends RestIntSetting {
    val key = "splay_time"
    val startPolicyGeneration = true
    def get = configService.agent_run_splaytime()
    def set = configService.set_agent_run_splaytime _
  }
  case object RestModifiedFileTTL extends RestIntSetting {
    val key = "modified_file_ttl"
    val startPolicyGeneration = true
    def get = configService.cfengine_modified_files_ttl()
    def set = (value : Int, _, _) => configService.set_cfengine_modified_files_ttl(value)
  }
  case object RestOutputFileTTL extends RestIntSetting {
    val key = "output_file_ttl"
    val startPolicyGeneration = true
    def get = configService.cfengine_outputs_ttl()
    def set = (value : Int, _, _) => configService.set_cfengine_outputs_ttl(value)
  }
  case object RestReportingMode extends RestSetting[ComplianceModeName] {
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
  case object RestHeartbeat extends RestIntSetting {
    val startPolicyGeneration = true
    val key = "heartbeat_frequency"
    def get = configService.rudder_compliance_heartbeatPeriod()
    def set =  configService.set_rudder_compliance_heartbeatPeriod _
  }
  case object RestLogAllReports extends RestBooleanSetting {
    val key = "log_all_reports"
    val startPolicyGeneration = true
    def get = configService.rudder_store_all_centralized_logs_in_file()
    def set = (value : Boolean, _, _) => configService.set_rudder_store_all_centralized_logs_in_file(value)
  }
  case object RestChangeMessageEnabled extends RestBooleanSetting {
    val startPolicyGeneration = false
    val key = "enable_change_message"
    def get = configService.rudder_ui_changeMessage_enabled()
    def set = (value : Boolean, _, _) => configService.set_rudder_ui_changeMessage_enabled(value)
  }
  case object RestChangeMessageManadatory extends RestBooleanSetting {
    val startPolicyGeneration = false
    val key = "mandatory_change_message"
    def get = configService.rudder_ui_changeMessage_mandatory()
    def set = (value : Boolean, _, _) => configService.set_rudder_ui_changeMessage_mandatory(value)
  }
  case object RestChangeMessagePrompt extends RestStringSetting {
    val startPolicyGeneration = false
    val key = "change_message_prompt"
    def get = configService.rudder_ui_changeMessage_explanation()
    def set = (value : String, _, _) => configService.set_rudder_ui_changeMessage_explanation(value)
  }
  case object RestChangeRequestEnabled extends RestBooleanSetting {
    val startPolicyGeneration = false
    val key = "enable_change_request"
    def get = configService.rudder_workflow_enabled()
    def set = (value : Boolean, _, _) => configService.set_rudder_workflow_enabled(value)
  }
  case object RestChangeRequestSelfDeployment extends RestBooleanSetting {
    val startPolicyGeneration = false
    val key = "enable_self_deployment"
    def get = configService.rudder_workflow_self_deployment()
    def set = (value : Boolean, _, _) => configService.set_rudder_workflow_self_deployment(value)
  }
  case object RestChangeRequestSelfValidation extends RestBooleanSetting {
    val key = "enable_self_validation"
    val startPolicyGeneration = true
    def get = configService.rudder_workflow_self_validation()
    def set = (value : Boolean, _, _) => configService.set_rudder_workflow_self_validation(value)
  }
  case object RestRequireTimeSynch extends RestBooleanSetting {
    val key = "require_time_synchronization"
    val startPolicyGeneration = true
    def get = configService.cfengine_server_denybadclocks()
    def set = (value : Boolean, _, _) => configService.set_cfengine_server_denybadclocks(value)
  }
  case object RestUseReverseDNS extends RestBooleanSetting {
    val key = "use_reverse_dns"
    val startPolicyGeneration = true
    def get = false.succeed
    def set = (value : Boolean, _, _) => UIO.unit
  }
  case object RestRelaySyncMethod extends RestSetting[RelaySynchronizationMethod] {
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
  case object RestRelaySynchronizePolicies extends RestBooleanSetting {
    val key = "relay_server_synchronize_policies"
    val startPolicyGeneration = true
    def get = configService.relay_server_syncpromises()
    def set = (value : Boolean, _, _) => configService.set_relay_server_syncpromises(value)
  }
  case object RestRelaySynchronizeSharedFiles extends RestBooleanSetting {
    val key = "relay_server_synchronize_shared_files"
    val startPolicyGeneration = true
    def get = configService.relay_server_syncsharedfiles()
    def set = (value : Boolean, _, _) => configService.set_relay_server_syncsharedfiles(value)
  }
  case object RestReportingProtocol extends RestSetting[SyslogProtocol] {
    val key = "rsyslog_reporting_protocol"
    val startPolicyGeneration = true
    def get = configService.rudder_syslog_protocol()
    def set = configService.set_rudder_syslog_protocol _
    def toJson(value : SyslogProtocol) : JValue = value.value
    def parseJson(json: JValue) = {
      json match {
        case JString(value) => parseParam(value.toUpperCase())
        case x => Failure("Invalid value "+x)
      }
    }
    def parseParam(param : String) = {
      param.toUpperCase() match {
          case SyslogTCP.value => Full(SyslogTCP)
          case SyslogUDP.value => Full(SyslogUDP)
          case _ => Failure(s"Invalid value '${param}' for syslog protocol")
        }
    }
  }
  case object RestChangesGraphs extends RestBooleanSetting {
    val startPolicyGeneration = false
    val key = "display_recent_changes_graphs"
    def get = configService.display_changes_graph()
    def set = (value : Boolean, _, _) => configService.set_display_changes_graph(value)
  }
  case object RestSendMetrics extends RestSetting[Option[Boolean]] {
    val startPolicyGeneration = true
    def toJson(value : Option[Boolean]) : JValue = value.map(JBool(_)).getOrElse(JString("not defined"))
    def parseJson(json: JValue) = {
      json match {
        case JBool(value)           => Full(Some(value))
        case JString("not defined") => Full(None)
        case x                      => Failure("Invalid value "+x)
      }
    }
    def parseParam(param : String) = {
      param match {
        case "true"        => Full(Some(true))
        case "false"       => Full(Some(false))
        case "not defined" => Full(None)
        case _             => Failure(s"value for boolean should be true or false instead of ${param}")
      }
    }
    val key = "send_metrics"
    def get = configService.send_server_metrics()
    def set = configService.set_send_server_metrics _
  }

  case object RestJSEngine extends RestSetting[FeatureSwitch] {
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

  case object RestOnAcceptPolicyMode extends RestSetting[Option[PolicyMode]] {
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
  case object RestOnAcceptNodeState extends RestSetting[NodeState] {
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

  case object RestChangeRequestUnexpectedAllowsDuplicate extends RestChangeUnexpectedReportInterpretation {
    val startPolicyGeneration = false
    val key = "unexpected_allows_duplicate"
    val prop = UnexpectedReportBehavior.AllowsDuplicate
  }
  case object RestChangeRequestUnexpectedUnboundVarValues extends RestChangeUnexpectedReportInterpretation {
    val startPolicyGeneration = false
    val key = "unexpected_unbound_var_values"
    val prop = UnexpectedReportBehavior.UnboundVarValues
  }

  def startNewPolicyGeneration(actor : EventActor) = {
    val modId = ModificationId(uuidGen.newUuid)
    asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
  }

  def response ( function : Box[JValue], req : Req, errorMessage : String, id : Option[String])(implicit action : String) : LiftResponse = {
    RestUtils.response(restExtractorService, kind, id)(function, req, errorMessage)
  }


}


