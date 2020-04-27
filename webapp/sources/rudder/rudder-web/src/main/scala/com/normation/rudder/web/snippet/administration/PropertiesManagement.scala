/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

package com.normation.rudder.web.snippet.administration

import java.nio.charset.StandardCharsets

import net.liftweb.http._
import net.liftweb.common._
import bootstrap.liftweb.RudderConfig
import net.liftweb.http.js._
import JsCmds._
import JE._
import com.normation.eventlog.ModificationId

import scala.xml.NodeSeq
import net.liftweb.util._
import net.liftweb.util.Helpers._
import net.liftweb.http.SHtml._
import com.normation.appconfig._
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.web.components.AgentScheduleEditForm
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.web.components.ComplianceModeEditForm
import com.normation.rudder.reports.SyslogUDP
import com.normation.rudder.reports.SyslogTCP
import com.normation.rudder.reports.SyslogProtocol
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.web.components.AgentPolicyModeEditForm
import com.normation.rudder.AuthorizationType
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.services.servers.RelaySynchronizationMethod._
import com.normation.rudder.services.servers.RelaySynchronizationMethod
import com.normation.box._
import com.normation.rudder.reports.AgentReportingHTTPS
import com.normation.rudder.reports.AgentReportingProtocol
import com.normation.rudder.reports.AgentReportingSyslog

import scala.xml.Text

/**
 * This class manage the displaying of user configured properties.
 *
 * Methods on that classes are used in the template ""
 */
class PropertiesManagement extends DispatchSnippet with Loggable {

  private[this] val configService : ReadConfigService with UpdateConfigService = RudderConfig.configService
  private[this] val asyncDeploymentAgent = RudderConfig.asyncDeploymentAgent
  private[this] val uuidGen = RudderConfig.stringUuidGenerator

  private[this] val genericReasonMessage = Some("Property modified from Rudder preference page")

  def startNewPolicyGeneration() = {
    val modId = ModificationId(uuidGen.newUuid)
    asyncDeploymentAgent ! AutomaticStartDeployment(modId, CurrentUser.actor)
  }

  def disableInputs = {
    //If user does not have the Edit("administration") right, all inputs are disabled
    // else nothing is done because it enables what should not be.
    if(!CurrentUser.checkRights(AuthorizationType.Administration.Edit)) {
      S.appendJs(JsRaw(s"""$$("input, select").attr("disabled", "true")"""))
    }
    NodeSeq.Empty
  }

  def dispatch = {
    case "changeMessage" => changeMessageConfiguration
    case "denyBadClocks" => cfserverNetworkConfiguration
    case "relaySynchronizationMethod" => relaySynchronizationMethodManagement
    case "cfagentSchedule" => (xml) => cfagentScheduleConfiguration
    case "agentPolicyMode" => (xml) => agentPolicyModeConfiguration
    case "complianceMode" => (xml) => complianceModeConfiguration
    case "cfengineGlobalProps" => cfengineGlobalProps
    case "loggingConfiguration" => loggingConfiguration
    case "sendMetricsConfiguration" => sendMetricsConfiguration
    case "reportProtocolSection" => reportProtocolSection
    case "displayGraphsConfiguration" => displayGraphsConfiguration
    case "displayRuleColumnConfiguration" => displayRuleColumnConfiguration
    case "directiveScriptEngineConfiguration" => directiveScriptEngineConfiguration
    case "unexpectedReportInterpretation" => unexpectedReportInterpretation
    case "onloadScript" => _ => disableInputs
    case "nodeOnAcceptDefaults" => nodeOnAcceptDefaultsConfiguration
    case "generationHookCfpromise" => generationHookCfpromise
    case "generationHookTriggerNodeUpdate" => generationHookTriggerNodeUpdate
    case "enforceCertificateValidation" => enforceCertificateValidation
  }


  def changeMessageConfiguration = { xml : NodeSeq =>

    // initial values
    var initEnabled = configService.rudder_ui_changeMessage_enabled.toBox
    var initMandatory = configService.rudder_ui_changeMessage_mandatory.toBox
    var initExplanation = configService.rudder_ui_changeMessage_explanation.toBox

    // mutable, default values won't be used (if error in property => edit form is not displayed)
    var enabled = initEnabled.getOrElse(false)
    var mandatory = configService.rudder_ui_changeMessage_mandatory.toBox.getOrElse(false)
    var explanation = configService.rudder_ui_changeMessage_explanation.toBox.getOrElse("Please enter a reason explaining this change.")

    def submit() = {

      // Save new value
      configService.set_rudder_ui_changeMessage_enabled(enabled).toBox.
        // If update is sucessful update the initial value used by the form
        foreach(updateOk => initEnabled = Full(enabled))

      configService.set_rudder_ui_changeMessage_mandatory(mandatory).toBox.foreach(updateOk => initMandatory = Full(mandatory))

      configService.set_rudder_ui_changeMessage_explanation(explanation).toBox.foreach(updateOk => initExplanation = Full(explanation))
      check() & JsRaw("""createSuccessNotification("Change audit logs configuration correctly updated")""")
    }

    // Check if there is no modification
    // Ignore error ones so we can still modify those not in error)
    def noModif = (
         initEnabled.map(_ == enabled).getOrElse(false)
      && initMandatory.map(_ == mandatory).getOrElse(false)
      && initExplanation.map(_ == explanation).getOrElse(false)
    )
    def emptyString = explanation.trim().length==0
    // Check that there is some modification to enabled/disable save
    def check() = {
      Run(s"""$$("#changeMessageSubmit").attr("disabled",${noModif||emptyString});""")
    }

    // Initialisation of form
    // Determine if some fields should be disabled.
    def initJs(newStatus :Boolean) = {
      enabled = newStatus
      check() &
      Run(
        s"""
          $$("#mandatory").attr("disabled",${!newStatus});
          $$("#explanation").attr("disabled",${!newStatus});
          if(${!newStatus}){
            $$("#mandatory").parent().parent().addClass('disabled');
          }else{
            $$("#mandatory").parent().parent().removeClass('disabled');
          }
        """
      )
    }

    // Rendering
    ( "#configurationRepoPath" #> RudderConfig.RUDDER_DIR_GITROOT &
      "#enabled" #> {
        initEnabled match {
          case Full(value) =>
            SHtml.ajaxCheckbox(
                value
              , initJs _
              , ("id","enabled")
              , ("class","twoCol")
            )
          case eb: EmptyBox =>
            val fail = eb ?~ "there was an error, while fetching value of property: 'Enable change audit log' "
            <div class="error">{fail.msg}</div>
        }
      } &

      "#mandatory" #> {
        initMandatory match {
          case Full(value) =>
            SHtml.ajaxCheckbox(
                value
              , (b : Boolean) => { mandatory = b; check() }
              , ("id","mandatory")
              , ("class","twoCol")
              , ("disabled", s"${!mandatory}")
            )
          case eb: EmptyBox =>
            val fail = eb ?~ "there was an error, while fetching value of property: 'Make message mandatory "
            <div class="error">{fail.msg}</div>
        }
      } &

      "#explanation " #> {

        // Need specific check on base value of the field
        // Maybe don't need to be done in Ajax and replaced by full client side
        def checkExplanation (initValue : String) (s : String) = {
          val mod = (    initEnabled.map(_ == enabled).getOrElse(false)
                      && initMandatory.map(_ == mandatory).getOrElse(false)
                    )
           Run(s"""
             var noModif = $mod && ($$("#explanation").val() == "$initValue");
             $$("#changeMessageSubmit").attr("disabled",noModif);""")
        }
        initExplanation match {
          case Full(value) =>

            SHtml.ajaxText(
                value
              , (s : String) => { explanation = s; check() }
              , ("id","explanation")
              , ("class","form-control")
              , ("onkeydown",ajaxCall("checkExplanation", checkExplanation(value) ).toJsCmd)
            )
          case eb: EmptyBox =>
            val fail = eb ?~ "there was an error, while fetching value of property: 'Explanation to display "
            <div class="error">{fail.msg}</div>
        }
      } &

      "#restoreExplanation " #> {
        initExplanation.map{ s:String =>
          ajaxButton(<span>Reset to default</span>, () => { explanation = "Please enter a reason explaining this change."
            Run("""$("#explanation").val("Please enter a reason explaining this change.");""") & check()

            }  ,("class","btn btn-default"), ("id","restoreExplanation"))
        }.getOrElse(NodeSeq.Empty)
      } &

      "#mandatoryTooltip *" #> {
        initMandatory.map{ b:Boolean =>
          val tooltipid = Helpers.nextFuncName
          <span class="tooltipable" tooltipid={tooltipid} title="">
            <span class="glyphicon glyphicon-info-sign info"></span>
          </span>
          <div class="tooltipContent" id={tooltipid}>
            If this option is enabled, users will be forced to enter a change audit log. Empty messages will not be accepted.
          </div>
        }.getOrElse(NodeSeq.Empty)
      } &

      "#explanationTooltip *" #> {
        initExplanation.map{ s:String =>
          val tooltipid = Helpers.nextFuncName
          <span class="tooltipable" tooltipid={tooltipid} title="">
            <span class="glyphicon glyphicon-info-sign info"></span>
          </span>
          <div class="tooltipContent" id={tooltipid}>
            Content of the text displayed to prompt the user to enter a change audit log.
          </div>
        }.getOrElse(NodeSeq.Empty)
      } &

      "#changeMessageSubmit " #> {
         SHtml.ajaxSubmit("Save changes", submit _ , ("class","btn btn-default"))
      }
    ) apply (xml ++ Script(initJs(enabled)))
  }

  def cfserverNetworkConfiguration = { xml : NodeSeq =>

    //  initial values, updated on successfull submit
    var initDenyBadClocks = configService.cfengine_server_denybadclocks.toBox

    // form values
    var denyBadClocks = initDenyBadClocks.getOrElse(false)

    def submit() = {
      configService.set_cfengine_server_denybadclocks(denyBadClocks).toBox.foreach(updateOk => initDenyBadClocks = Full(denyBadClocks))

      // start a promise generation, Since we check if there is change to save, if we got there it mean that we need to redeploy
      startNewPolicyGeneration
      check() & JsRaw("""createSuccessNotification("Network security options correctly updated")""")
    }

    def noModif = (
      initDenyBadClocks.map(_ == denyBadClocks).getOrElse(false)
    )

    def check() = {
      Run(s"""$$("#cfserverNetworkSubmit").prop('disabled', ${noModif});""")
    }

    ( "#denyBadClocks" #> {
      initDenyBadClocks match {
        case Full(value) =>
          SHtml.ajaxCheckbox(
              value
            , (b : Boolean) => { denyBadClocks = b; check() }
            , ("id","denyBadClocks")
          )
          case eb: EmptyBox =>
            val fail = eb ?~ "there was an error while fetching value of property: 'Deny Bad Clocks' "
            <div class="error">{fail.msg}</div>
        }
      } &

      "#denyBadClocksTooltip *" #> {

        initDenyBadClocks match {
          case Full(_) =>
            val tooltipid = Helpers.nextFuncName

            <span class="tooltipable" tooltipid={tooltipid} title="">
              <span class="glyphicon glyphicon-info-sign info"></span>
            </span>
            <div class="tooltipContent" id={tooltipid}>
               By default, copying configuration policy to nodes requires system clocks to be synchronized
               to within an hour. Disabling this will bypass this check, but may open a window for replay attacks.
            </div>

          case _ => NodeSeq.Empty
        }
      } &

      "#cfserverNetworkSubmit " #> {
         SHtml.ajaxSubmit("Save changes", submit _ , ("class","btn btn-default"))
      }
    ) apply (xml ++ Script(check()))
  }

  def enforceCertificateValidation = { xml : NodeSeq =>

    //  initial values, updated on successfull submit
    var initEnforceCertificate = configService.rudder_verify_certificates().toBox

    // form values
    var enforceCertificate = initEnforceCertificate.getOrElse(false)

    def submit() = {
      configService.set_rudder_verify_certificates(enforceCertificate, CurrentUser.actor, genericReasonMessage).toBox.foreach(updateOk => initEnforceCertificate = Full(enforceCertificate))

      // start a promise generation, Since we check if there is change to save, if we got there it mean that we need to redeploy
      startNewPolicyGeneration
      check() & JsRaw("""createSuccessNotification("Validation of policy server certificate correctly updated")""")
    }

    def noModif = (
      initEnforceCertificate.map(_ == enforceCertificate).getOrElse(false)
      )

    def check() = {
      Run(s"""$$("#enforceCertificateSubmit").prop('disabled', ${noModif});""")
    }

    ( "#enforceCertificateValidation" #> {
      initEnforceCertificate match {
        case Full(value) =>
          SHtml.ajaxCheckbox(
            value
            , (b : Boolean) => { enforceCertificate = b; check() }
            , ("id","enforceCertificateValidation")
          )
        case eb: EmptyBox =>
          val fail = eb ?~ "there was an error while fetching value of property: 'Certificate value' "
          <div class="error">{fail.msg}</div>
      }
    } &

      "#enforceCertificateSubmit " #> {
        SHtml.ajaxSubmit("Save changes", submit _ , ("class","btn btn-default"))
      }
      ) apply (xml ++ Script(check()))
  }

  def relaySynchronizationMethodManagement = { xml : NodeSeq =>
    //  initial values, updated on successfull submit
    var initRelaySyncMethod = configService.relay_server_sync_method.toBox
    // Be careful, we store negative value
    var initRelaySyncPromises = configService.relay_server_syncpromises.toBox
    var initRelaySyncSharedFiles = configService.relay_server_syncsharedfiles.toBox

    // form values
    var relaySyncMethod = initRelaySyncMethod.getOrElse(Classic)
    var relaySyncPromises = initRelaySyncPromises.getOrElse(false)
    var relaySyncSharedFiles = initRelaySyncSharedFiles.getOrElse(false)

    def noModif = (
         initRelaySyncMethod.map(_ == relaySyncMethod).getOrElse(false)
      && initRelaySyncPromises.map(_ == relaySyncPromises).getOrElse(false)
      && initRelaySyncSharedFiles.map(_ == relaySyncSharedFiles).getOrElse(false)
    )

    def check() = {
      Run( s""" $$("#relaySynchronizationSubmit").prop('disabled', ${noModif});""".stripMargin )
    }

    def submit() = {
      configService.set_relay_server_sync_method(relaySyncMethod).toBox.foreach(updateOk => initRelaySyncMethod = Full(relaySyncMethod))
      configService.set_relay_server_syncpromises(relaySyncPromises).toBox.foreach(updateOk => initRelaySyncPromises = Full(relaySyncPromises))
      configService.set_relay_server_syncsharedfiles(relaySyncSharedFiles).toBox.foreach(updateOk => initRelaySyncSharedFiles = Full(relaySyncSharedFiles))

      // start a promise generation, Since we check if there is change to save, if we got there it mean that we need to redeploy
      startNewPolicyGeneration
      check() & JsRaw("""createSuccessNotification("Relay servers synchronization methods correctly updated")""")
    }

    def setRelaySyncMethodJs(input : String) : JsCmd = {
      RelaySynchronizationMethod.parse(input) match {
        case Full(method) =>
          relaySyncMethod = method
          method match {
            case Rsync              => JsRaw(""" $('#relayRsyncSynchronizeFiles').show(); """)
            case Classic | Disabled => JsRaw(""" $('#relayRsyncSynchronizeFiles').hide(); """)
          }
        case eb : EmptyBox =>
          Noop
      }
    }
    (
      "#relaySyncMethod" #> {
         initRelaySyncMethod match {
            case Full(value) =>
              def radioHtml(method:RelaySynchronizationMethod) : NodeSeq = {
                val label = method.value
                val inputId = label+"-id"
                val ajaxCall = SHtml.ajaxCall(Str(""), _ => setRelaySyncMethodJs(label) & check )._2.toJsCmd
                val inputCheck = if (initRelaySyncMethod == method) {
                  <input id={inputId} type="radio" name="relaySync" onclick={ajaxCall} checked=""/>
                } else {
                  <input id={inputId} type="radio" name="relaySync" onclick={ajaxCall} />
                }
                <li class="rudder-form">
                  <div class="input-group">
                    <label class="input-group-addon" for={inputId}>
                     {inputCheck}
                      <label for={inputId} class="label-radio">
                        <span class="ion ion-record"></span>
                      </label>
                      <span class="ion ion-checkmark-round check-icon"></span>
                    </label>
                    <label class="form-control" for={inputId}>
                      {label.capitalize}
                    </label>
                  </div>
                </li>
              }
              (<ul id="relaySyncMethod">{
              RelaySynchronizationMethod.all.map(radioHtml)
              }
              </ul>: NodeSeq) ++ Script(OnLoad(setRelaySyncMethodJs(value.value)))
            case eb: EmptyBox =>
              val fail = eb ?~ "there was an error while fetching value of property: 'Synchronize Policies using rsync' "
              <div class="error">{fail.msg}</div>
          }

       } &
       "#relaySyncPromises" #> {
          initRelaySyncPromises match {
            case Full(value) =>
              SHtml.ajaxCheckbox(
                value
              , (b : Boolean) => { relaySyncPromises = b; check() }
              , ("id","relaySyncPromises")
              )
            case eb: EmptyBox =>
              val fail = eb ?~ "there was an error while fetching value of property: 'Synchronize Policies using rsync' "
              <div class="error">{fail.msg}</div>
          }
        } &
        "#relaySyncPromisesTooltip *" #> {

        initRelaySyncPromises match {
          case Full(_) =>
            val tooltipid = Helpers.nextFuncName
            <span class="tooltipable" tooltipid={tooltipid} title="">
              <span><span class="glyphicon glyphicon-info-sign info"></span></span>
            </span>
            <div class="tooltipContent" id={tooltipid}>
              If this is checked, when rsync synchronization method is used, folder /var/rudder/share will be synchronized using rsync.
              If this is not checked, you'll have to synchronize yourself this folder
            </div>

          case _ => NodeSeq.Empty
        }
      } &
       "#relaySyncSharedFiles" #> {
          initRelaySyncSharedFiles match {
            case Full(value) =>
              SHtml.ajaxCheckbox(
                value
              , (b : Boolean) => { relaySyncSharedFiles = b; check() }
              , ("id","relaySyncSharedFiles")
              )
            case eb: EmptyBox =>
              val fail = eb ?~ "there was an error while fetching value of property: 'Synchronize Shared Files using rsync' "
              <div class="error">{fail.msg}</div>
          }
        } &
        "#relaySyncSharedFilesTooltip *" #> {

        initRelaySyncSharedFiles match {
          case Full(_) =>
            val tooltipid = Helpers.nextFuncName
            <span class="tooltipable" tooltipid={tooltipid} title="">
              <span><span class="glyphicon glyphicon-info-sign info"></span></span>
            </span>
            <div class="tooltipContent" id={tooltipid}>
              If this is checked, when rsync synchronization method is used, folder /var/rudder/configuration-repository/shared-files will be synchronized using rsync.
              If this is not checked, you'll have to synchronize yourself this folder
            </div>

          case _ => NodeSeq.Empty
        }
      } &
        "#relaySynchronizationSubmit " #> {
          SHtml.ajaxSubmit("Save changes", submit _ , ("class","btn btn-default"))
        }
    ) apply (xml ++ Script(check()))
  }


  def reportProtocolSection = { xml: NodeSeq =>
    //  initial values, updated on successful submit
    var initSyslogProtocol = configService.rudder_syslog_protocol.toBox
    var initReportProtocol = configService.rudder_report_protocol_default.toBox
    var initDisabledSyslog  = configService.rudder_syslog_protocol_disabled.toBox

    // form values
    var syslogProtocol = initSyslogProtocol.getOrElse(SyslogUDP)
    var reportProtocol = initReportProtocol.getOrElse(AgentReportingHTTPS)
    var disabledSyslog = initDisabledSyslog.getOrElse(true)

    def noModif = (
      for {
        initSyslog   <- initSyslogProtocol
        initProtocol <- initReportProtocol
        initDisabled <- initDisabledSyslog
      } yield {
        initSyslog   == syslogProtocol &&
        initProtocol == reportProtocol &&
        initDisabled == disabledSyslog
      }
    ).getOrElse(false)

    def check() = {
      Run( s""" $$("#reportProtocolSubmit").prop('disabled', ${noModif});""".stripMargin)
    }

    def submit() = {
      val actor = CurrentUser.actor
      configService.set_rudder_report_protocol_default(reportProtocol).toBox.foreach(updateOk => initReportProtocol = Full(reportProtocol))
      configService.set_rudder_syslog_protocol(syslogProtocol, actor, None).toBox.foreach(updateOk => initSyslogProtocol = Full(syslogProtocol))
      configService.set_rudder_syslog_protocol_disabled(disabledSyslog, actor, None).toBox.foreach(updateOk => initDisabledSyslog = Full(disabledSyslog))

      // start a promise generation, Since we check if there is change to save, if we got there it mean that we need to redeploy
      startNewPolicyGeneration
      check() & JsRaw("""createSuccessNotification("Reporting protocol correctly updated")""")
    }

    val httpsLegacySupport = "HttpsWithLegacy"

    def checkSyslogProtocol(input : String) : JsCmd = {
      SyslogProtocol.parse(input).toBox match {
        case Full(newValue) =>
          syslogProtocol = newValue
          Noop
        case eb: EmptyBox =>
          Noop
      }
    }

    def displayDisableSyslogSectionJS(input: String): JsCmd = {
      if (input == httpsLegacySupport) {
        reportProtocol = AgentReportingHTTPS
        disabledSyslog = false
        JsRaw(""" $('#syslogProtocol').show(); """)
      } else {
        AgentReportingProtocol.parse(input).toBox match {
          case Full(protocol) =>
            reportProtocol = protocol
            protocol match {
              case AgentReportingHTTPS =>
                disabledSyslog = true
                JsRaw(""" $('#syslogProtocol').hide(); """)
              case AgentReportingSyslog =>
                disabledSyslog = false
                JsRaw(""" $('#syslogProtocol').show(); """)
            }
          case eb: EmptyBox =>
            Noop
        }
      }
    }

    val reportRadioInitialState : Box[(AgentReportingProtocol,Boolean)] = (initReportProtocol,initDisabledSyslog) match {
      case (Full(a),Full(b)) =>
        Full((a,b))
      case (eb1 : EmptyBox, eb2: EmptyBox) =>
        val fail1 = eb1 ?~! "Error when getting reporting protocol"
        val fail2 = eb2 ?~! "Error when getting disabled syslog protocol"
        Failure(s"Error when fetch reporting protocol options: ${fail1.messageChain} - ${fail2.messageChain}")
      case (eb1 : EmptyBox, _) =>
        eb1 ?~! "Error when getting reporting protocol"
      case (_, eb2 : EmptyBox) =>
        eb2 ?~! "Error when getting disabled syslog protocol"
    }


    def labelAndValue(protocol : AgentReportingProtocol, disabledSyslog : Boolean) =
      protocol match {
        case AgentReportingHTTPS if ! disabledSyslog =>
          ( protocol.value +" with syslog support for migration purpose (old agent or legacy systems)",  httpsLegacySupport)
        case _ => ( protocol.value + " only",  protocol.value)
      }

      (
        "#reportProtocol" #> {
          reportRadioInitialState match {
            case Full((initReport, initDisabled)) =>
              val (_,initValue) = labelAndValue(initReport,initDisabled)
              def radioHtml(protocol : AgentReportingProtocol, disabledSyslog : Boolean): NodeSeq = {
                val (label,value) = labelAndValue(protocol,disabledSyslog)
                val inputId = value + "-id"
                val ajaxCall = SHtml.ajaxCall(Str(""), _ => displayDisableSyslogSectionJS(value) & check)._2.toJsCmd
                val inputCheck = if (initReport == protocol && initDisabled == disabledSyslog) {
                    <input id={inputId} type="radio" name="reportProtocol" onclick={ajaxCall} checked=""/>
                } else {
                    <input id={inputId} type="radio" name="reportProtocol" onclick={ajaxCall}/>
                }
                <li class="rudder-form">
                  <div class="input-group">
                    <label class="input-group-addon" for={inputId}>
                      {inputCheck}<label for={inputId} class="label-radio">
                      <span class="ion ion-record"></span>
                    </label>
                      <span class="ion ion-checkmark-round check-icon"></span>
                    </label>
                    <label class="form-control" for={inputId}>
                      {label}
                    </label>
                  </div>
                </li>
              }

              <ul id="reportProtocol">
                {((AgentReportingHTTPS, true) :: (AgentReportingHTTPS, false) :: (AgentReportingSyslog, false) :: Nil).map((radioHtml _).tupled)}
              </ul> ++ Script(OnLoad(displayDisableSyslogSectionJS(initValue) & check()))
            case eb: EmptyBox =>
              val fail = eb ?~ "there was an error while fetching value of reporting protocol' "
              <div class="error">
                {fail.msg}
              </div>
          }

        }&
          "#reportProtocolSubmit " #> {
            SHtml.ajaxSubmit("Save changes", submit _ , ("class","btn btn-default"))
          }
          &
          "#syslogProtocol" #> {
            initSyslogProtocol match {
              case Full(initSyslog) =>
                def radioHtml(syslogProtocol: SyslogProtocol): NodeSeq = {
                  val value = syslogProtocol.value
                  val inputId = value + "-id"
                  val ajaxCall = SHtml.ajaxCall(Str(""), _ => checkSyslogProtocol(value) & check)._2.toJsCmd
                  val inputCheck = if (initSyslog == syslogProtocol) {
                      <input id={inputId} type="radio" name="syslogProtocol" onclick={ajaxCall} checked=""/>
                  } else {
                      <input id={inputId} type="radio" name="syslogProtocol" onclick={ajaxCall}/>
                  }
                  <li class="rudder-form">
                    <div class="input-group">
                      <label class="input-group-addon" for={inputId}>
                        {inputCheck}<label for={inputId} class="label-radio">
                        <span class="ion ion-record"></span>
                      </label>
                        <span class="ion ion-checkmark-round check-icon"></span>
                      </label>
                      <label class="form-control" for={inputId}>
                        {value}
                      </label>
                    </div>
                  </li>
                }

                <ul id="syslogProtocol">
                  {(SyslogUDP :: SyslogTCP :: Nil).map(radioHtml)}
                </ul>
              case eb: EmptyBox =>
                val fail = eb ?~ "there was an error while fetching value of reporting protocol' "
                <div class="error">
                  {fail.msg}
                </div>
            }

          }
        ).apply (xml)

  }

  val agentScheduleEditForm = new AgentScheduleEditForm(
      () => getSchedule
    , saveSchedule
    , () => startNewPolicyGeneration
  )

  val complianceModeEditForm = {
    val globalMode = configService.rudder_compliance_mode().toBox
    new ComplianceModeEditForm[GlobalComplianceMode](
        globalMode
      , (complianceMode) => {
          configService.set_rudder_compliance_mode(complianceMode,CurrentUser.actor,genericReasonMessage).toBox
        }
      , () => startNewPolicyGeneration
      , globalMode
    )
  }
  val agentPolicyModeEditForm = {
    new AgentPolicyModeEditForm()
  }
  def getSchedule() : Box[AgentRunInterval] = {
    for {
      starthour <- configService.agent_run_start_hour
      startmin  <- configService.agent_run_start_minute
      splaytime <- configService.agent_run_splaytime
      interval  <- configService.agent_run_interval
    } yield {
      AgentRunInterval(
            None
          , interval
          , startmin
          , starthour
          , splaytime
        )
    }
  }.toBox

  def saveSchedule(schedule: AgentRunInterval) : Box[Unit] = {

    val actor = CurrentUser.actor
    for {
      _ <- configService.set_agent_run_interval(schedule.interval,actor,genericReasonMessage)
      _ <- configService.set_agent_run_start_hour(schedule.startHour,actor,genericReasonMessage)
      _ <- configService.set_agent_run_start_minute(schedule.startMinute,actor,genericReasonMessage)
      _ <- configService.set_agent_run_splaytime(schedule.splaytime,actor,genericReasonMessage)
    } yield {
      logger.info(s"Agent schedule updated to run interval: ${schedule.interval} min, start time: ${schedule.startHour} h ${schedule.startMinute} min, splaytime: ${schedule.splaytime} min")
    }
  }.toBox

  def cfagentScheduleConfiguration = agentScheduleEditForm.cfagentScheduleConfiguration
  def agentPolicyModeConfiguration = agentPolicyModeEditForm.cfagentPolicyModeConfiguration(None)
  def complianceModeConfiguration = complianceModeEditForm.complianceModeConfiguration

  def cfengineGlobalProps = { xml : NodeSeq =>

    //  initial values, updated on successful submit
    var initModifiedFilesTtl = configService.cfengine_modified_files_ttl.toBox
    // form values
    var modifiedFilesTtl = initModifiedFilesTtl.getOrElse(30).toString

    def submit() = {
      // first, check if the content are effectively Int
      try {
        val intModifiedFilesTtl = Integer.parseInt(modifiedFilesTtl)
        configService.set_cfengine_modified_files_ttl(intModifiedFilesTtl).toBox.foreach(updateOk => initModifiedFilesTtl = Full(intModifiedFilesTtl))
        // start a promise generation, Since we check if there is change to save, if we got there it mean that we need to redeploy
        startNewPolicyGeneration
        check() & JsRaw("""createSuccessNotification("File retention settings correctly updated")""")
      } catch {
        case ex:NumberFormatException =>
          Noop & JsRaw(s"""createErrorNotification("Invalid value ${ex.getMessage().replaceFirst("F", "f")})""")
      }
    }

    def noModif = (
      initModifiedFilesTtl.map(_.toString == modifiedFilesTtl).getOrElse(false)
    )

    def check() = {
      Run(s"""$$("#cfengineGlobalPropsSubmit").attr("disabled",${noModif});""")
    }

    ( "#modifiedFilesTtl" #> {
      initModifiedFilesTtl match {
        case Full(value) =>
          SHtml.ajaxText(
              value.toString
            , (s : String) => { modifiedFilesTtl = s; check() }
            , ("id","modifiedFilesTtl")
            , ("class","form-control number-day")
            , ("type","number")
          )
        case eb: EmptyBox =>
          val fail = eb ?~ "there was an error while fetching value of property: 'Modified files TTL' "
          <div class="error">{fail.msg}</div>
        }
      } &
      "#cfengineGlobalPropsSubmit " #> {
         SHtml.ajaxSubmit("Save changes", submit _ , ("class","btn btn-default"))
      }
    ) apply (xml ++ Script(check()))
  }

  def loggingConfiguration = { xml : NodeSeq =>

    //  initial values, updated on successfull submit
    var initStoreAllCentralizedLogsInFile = configService.rudder_store_all_centralized_logs_in_file.toBox
    var initCfengineOutputsTtl = configService.cfengine_outputs_ttl.toBox
    // form values
    var storeAllCentralizedLogsInFile  = initStoreAllCentralizedLogsInFile.getOrElse(false)
    var cfengineOutputsTtl = initCfengineOutputsTtl.getOrElse(7).toString

    def submit() = {
      try{
        val intCfengineOutputsTtl = Integer.parseInt(cfengineOutputsTtl)
        configService.set_cfengine_outputs_ttl(intCfengineOutputsTtl).toBox.foreach(updateOk => initCfengineOutputsTtl = Full(intCfengineOutputsTtl))

      } catch{
        case ex:NumberFormatException =>
          Noop & JsRaw(s"""createErrorNotification("Invalid value ${ex.getMessage().replaceFirst("F", "f")})""")
      }
      configService.set_rudder_store_all_centralized_logs_in_file(storeAllCentralizedLogsInFile).toBox.foreach(updateOk => initStoreAllCentralizedLogsInFile = Full(storeAllCentralizedLogsInFile))

      // start a promise generation, Since we check if there is change to save, if we got there it mean that we need to redeploy
      startNewPolicyGeneration
      val notifMessage =  storeAllCentralizedLogsInFile match {
        case true  => "Logging will be enabled during the next agent run on this server (5 minutes maximum)"
        case false => "Logging will be disabled during the next agent run on this server (5 minutes maximum)"
      }
      check() & JsRaw(s"""createSuccessNotification("${notifMessage}")""")
    }

    def noModif = (
       initStoreAllCentralizedLogsInFile.map(_ == storeAllCentralizedLogsInFile).getOrElse(false)
    && initCfengineOutputsTtl.map(_.toString == cfengineOutputsTtl).getOrElse(false)
    )

    def check() = {
      Run(s"""$$("#loggingConfigurationSubmit").attr("disabled",${noModif});""")
    }

    ( "#storeAllLogsOnFile" #> {
      initStoreAllCentralizedLogsInFile match {
        case Full(value) =>
          SHtml.ajaxCheckbox(
              value
            , (b : Boolean) => { storeAllCentralizedLogsInFile = b; check() }
            , ("id","storeAllLogsOnFile")
          )
          case eb: EmptyBox =>
            val fail = eb ?~ "there was an error while fetching value of property: 'Store all centralized logs in file' "
            <div class="error">{fail.msg}</div>
        }
      } &
       "#cfengineOutputsTtl" #> {
        initCfengineOutputsTtl match {
          case Full(value) =>
            SHtml.ajaxText(
                value.toString
              , (s : String) => { cfengineOutputsTtl = s; check() }
              , ("id","cfengineOutputsTtl")
              , ("class","form-control number-day")
              , ("type","number")
            )
          case eb: EmptyBox =>
            val fail = eb ?~ "there was an error while fetching value of property: 'CFEngine Outputs TTL' "
            <div class="error">{fail.msg}</div>
          }
        } &
      "#loggingConfigurationSubmit " #> {
         SHtml.ajaxSubmit("Save changes", submit _ , ("class","btn btn-default"))
      }
    ) apply (xml ++ Script(check()))
  }


  def addDisabled(disabled: Boolean) = {
    if (disabled) "* [disabled]" #> "disabled"
    else PassThru
  }

  /*
   * Get the set of perm for the property given "isexec":
   * - isExec == true =>  rwxr-xr-x
   * - isExec == false => rw-r--r--
   */
  def getHookPerm(isExec: Boolean) = {
    // property file should be either rwxr--r-- or rw-r--r--
    import java.nio.file.attribute.PosixFilePermission._
    val perms = Set(OWNER_READ, OWNER_WRITE, GROUP_READ, OTHERS_READ)
    if(isExec) perms + OWNER_EXECUTE + GROUP_EXECUTE + OTHERS_EXECUTE
    else perms
  }

  def generationHookCfpromise = { xml : NodeSeq =>
    {
      import better.files._
      val hook = File("/opt/rudder/etc/hooks.d/policy-generation-node-ready/10-cf-promise-check")

      val disabled = !(hook.exists() && hook.isWriteable)
      val isEnabled = hook.isExecutable

      var initIsEnabled = isEnabled
      var currentIsEnabled = isEnabled
      def noModif() = initIsEnabled == currentIsEnabled
      def check() = {
        S.notice("generationHookCfpromiseMsg","")
        Run(s"""$$("#generationHookCfpromiseSubmit").attr("disabled",${noModif});""")
      }
      def submit() = {
        // exec must be set/unset for all users
        val save =
          try {
            hook.setPermissions(getHookPerm(currentIsEnabled))
            Right(())
          } catch {
            case ex:Exception => Left(ex)
          }
        S.notice("generationHookCfpromiseMsg", save match {
          case Right(())  =>
            initIsEnabled = currentIsEnabled
            Text("'check generated policies' property updated")
          case Left(ex) =>
            <span class="error">There was an error when updating the value of the 'check generated policies' property: {ex.getMessage}</span>
        } )
        check
      }

      ( "#generationHookCfpromiseCheckbox" #> {
          addDisabled(disabled)(SHtml.ajaxCheckbox(
              isEnabled
            , (b : Boolean) => { currentIsEnabled = b; check}
            , ("id","generationHookCfpromiseCheckbox")
          ) )
        }&
        "#generationHookCfpromiseSubmit " #> {
          SHtml.ajaxSubmit("Save changes", submit _, ("class","btn btn-default"))
        }&
        "#generationHookCfpromiseSubmit *+" #> {
          Script(check())
        }
      ) apply (xml)
    }
  }

  def generationHookTriggerNodeUpdate : NodeSeq => NodeSeq = {
    import better.files._
    type Result[T] = Either[String, T]
    val hookPath = "/opt/rudder/etc/hooks.d/policy-generation-finished/60-trigger-node-update"
    val propPath = hookPath+".properties"
final case class TriggerProp(maxNodes: Result[Int], percent: Result[Int])
    object TriggerProp {
      val MAX_NODES = "MAX_NODES"
      val NODE_PERCENT = "NODE_PERCENT"
      val propRegex ="""^(\w+)=(.*)$""".r
    }

    def findProp(path: String, name: String, lines: Seq[String]): Result[Int] = {
      val prop = lines.collect { case TriggerProp.propRegex(n, v) if(name == n) => v }
      prop.headOption match {
        case Some(i) => try {
                          Right(i.toInt)
                        } catch {
                          case ex:NumberFormatException => Left(s"Error: property '${name}' must be an int but is: '${i}'")
                        }
        case None => Left(s"Property '${name}' was not found in hook property file '${path}'")
    } }

    def readProp(): TriggerProp = {
      (try { Right(File(propPath).lineIterator(StandardCharsets.UTF_8).toVector) }
       catch {
         case ex: Exception => Left(s"Error when trying to read properties for hook in '${propPath}': ${ex.getClass.getSimpleName}: ${ex.getMessage}")
       }).fold(
          msg   => TriggerProp(Left(msg), Left(msg))
        , lines => TriggerProp(findProp(propPath, TriggerProp.MAX_NODES, lines), findProp(propPath, TriggerProp.NODE_PERCENT, lines))
      )
    }

    def writeProp(maxNodes: Int, percent: Int): Result[Unit] = {
      try {
        val lines = File(propPath).lineIterator(StandardCharsets.UTF_8).toVector
        val replaced = lines.map { case l => l match {
          case TriggerProp.propRegex(k, v) => k match {
            case TriggerProp.MAX_NODES    => s"${TriggerProp.MAX_NODES}=${maxNodes}"
            case TriggerProp.NODE_PERCENT => s"${TriggerProp.NODE_PERCENT}=${percent}"
            case _ => l
          }
          case _ => l
        } }
        File(propPath).writeText(replaced.mkString("\n"))(better.files.File.OpenOptions.default, StandardCharsets.UTF_8)
        Right(())
      } catch {
        case ex: Exception =>
          Left(s"Error when trying to read properties for hook in '${propPath}': ${ex.getMessage}")
      }
    }

    def saveAll(hook: File, isEnabled: Boolean, max: Int, percent: Int): Result[Unit] = {
      for {
        _ <- try {
               Right(hook.setPermissions(getHookPerm(isEnabled)))
             } catch {
               case ex: Exception => Left(s"Error when saving hook state: ${ex.getMessage}")
             }
        _ <- writeProp(max, percent)
      } yield {
        ()
      }
    }

    def hasError(hook: File, props: TriggerProp): Option[String] = {
      (for {
       _ <- if(hook.exists() && hook.isWriteable && hook.isRegularFile) { // ok
                 Right("ok")
               } else {
                 Left(s"Please check that file '${hook.pathAsString}' exists and is readable and writtable.")
               }
       _ <- props.maxNodes
       _ <- props.maxNodes
      } yield {
        ()
      }).swap.toOption
    }

    (xml : NodeSeq) => {
      val hook = File(hookPath)
      var hookProps = readProp()

      // initial errors
      (hookProps.maxNodes :: hookProps.percent :: Nil).map(_.swap.toOption).flatten.distinct match {
        case Nil => //nothing
        case seq => S.notice("generationHookTriggerNodeUpdateMsg", seq.mkString("; "))
      }

      // if the hook file is missing, display an error message
      hasError(hook, hookProps) match {
        case Some(error) =>
          ("#generationHookTriggerNodeUpdateForm" #> ("There was an error when trying to access the file. It can not be configured. " +
                                                    s"Error was: ${error}"
                                                    )
          ) apply(xml)
        case None =>
          var isEnabled = hook.isExecutable
          var currentIsEnabled = isEnabled
          var currentMax = hookProps.maxNodes.getOrElse(1000)
          var currentMaxStr = currentMax.toString
          var currentPercent = hookProps.percent.getOrElse(1000)
          var currentPercentStr = currentPercent.toString

          def noModif() = {
            isEnabled == currentIsEnabled && TriggerProp(Right(currentMax), Right(currentPercent)) == hookProps
          }
          def check() = {
            val msg = scala.collection.mutable.Buffer[String]()
            try {
              currentMax = currentMaxStr.toInt
              if(currentMax < 0) {
                 msg += "Error: max number of nodes must be positive"
              }
            } catch {
              case ex: NumberFormatException => msg += "Error: max number of node must be an int"
            }
            try {
              currentPercent = currentPercentStr.toInt
              if(currentPercent < 0 || currentPercent > 100) {
                 msg += "Error: percent of nodes must be in interval [0,100]"
              }
            } catch {
              case ex: NumberFormatException => msg += "Error: percent of nodes must be an int"
            }
            S.notice("generationHookTriggerNodeUpdateMsg", <span class="error">{msg.mkString("; ")}</span>)
            Run(s"""$$("#generationHookTriggerNodeUpdateSubmit").attr("disabled",${noModif});""")
          }

          def submit() = {
            S.notice("generationHookTriggerNodeUpdateMsg", saveAll(hook, currentIsEnabled, currentMax, currentPercent) match {
              case Right(())  =>
                isEnabled = currentIsEnabled
                hookProps = TriggerProp(Right(currentMax), Right(currentPercent))
                "'trigger node update' property updated"
              case Left(s) =>
                s"There was an error when updating the value of the 'trigger node update' property: ${s}"
            } )
            check
          }

          ( "#generationHookTriggerNodeUpdateCheckbox" #> {
            SHtml.ajaxCheckbox(
                isEnabled
              , (b : Boolean) => { currentIsEnabled = b; check}
              , ("id","generationHookTriggerNodeUpdateCheckbox")
            )
          }&
            "#generationHookTriggerNodeUpdateMaxNode" #> {
              SHtml.ajaxText(
                  currentMaxStr
                , (s : String) => { currentMaxStr = s; check() }
                , ("id","generationHookTriggerNodeUpdateMaxNode")
                , ("class","form-control")
                , ("type","number")
              )
            }&
            "#generationHookTriggerNodeUpdateRatio" #> {
              SHtml.ajaxText(
                  currentPercentStr
                , (s : String) => { currentPercentStr = s; check() }
                , ("id","generationHookTriggerNodeUpdateRatio")
                , ("class","form-control")
                , ("type","number")
              )
            }&
            "#generationHookTriggerNodeUpdateSubmit " #> {
              SHtml.ajaxSubmit("Save changes", submit _, ("class","btn btn-default"))
            }&
            "#generationHookTriggerNodeUpdateSubmit *+" #> {
              Script(check())
            }
          ) apply (xml)
      }
    }
  }

  def sendMetricsConfiguration = { xml : NodeSeq =>
    ( configService.send_server_metrics.toBox match {
      case Full(value) =>
        var initSendMetrics = value
        var currentSendMetrics = value
        def noModif() = initSendMetrics == currentSendMetrics
        def check() = {
          Run(s"""$$("#sendMetricsSubmit").attr("disabled", ${noModif()});""")
        }
        def submit() = {
          val save = configService.set_send_server_metrics(currentSendMetrics,CurrentUser.actor,genericReasonMessage).toBox
          val createNotification = save match {
            case Full(_)  =>
              initSendMetrics = currentSendMetrics
              // start a promise generation, Since we may have change the mode, if we got there it mean that we need to redeploy
              startNewPolicyGeneration
              JsRaw("""createSuccessNotification("'send server metrics' property updated")""")
            case eb: EmptyBox =>
              JsRaw("""createErrorNotification("There was an error when updating the value of the 'send server metrics' property")""")
          }
          check & createNotification
        }

        ( "#sendMetricsCheckbox" #> {
            SHtml.ajaxCheckbox(
                value.getOrElse(false)
              , (b : Boolean) => { currentSendMetrics = Some(b); check}
              , ("id","sendMetricsCheckbox")
            )
          }&
          "#sendMetricsSubmit " #> {
            SHtml.ajaxSubmit("Save changes", submit _, ("class","btn btn-default"))
          }&
          "#sendMetricsSubmit *+" #> {
            Script(check())
          }
        )
      case eb: EmptyBox =>
        ( "#sendMetrics" #> {
          val fail = eb ?~ "there was an error while fetching value of property: 'Send server metrics"
          logger.error(fail.messageChain)
          <div class="error">{fail.messageChain}</div>
        } )
    } ) apply (xml)
  }

  def displayGraphsConfiguration = { xml : NodeSeq =>

    ( configService.display_changes_graph().toBox match {
      case Full(value) =>
        var initDisplayGraphs = value
        var currentdisplayGraphs = value
        def noModif() = initDisplayGraphs == currentdisplayGraphs
        def check() = {
          Run(s"""$$("#displayGraphsSubmit").attr("disabled",${noModif()});""")
        }

        def submit() = {
          val save = configService.set_display_changes_graph(currentdisplayGraphs).toBox
          val createNotification = save match {
            case Full(_)  =>
              initDisplayGraphs = currentdisplayGraphs
              JsRaw("""createSuccessNotification("'display change graphs' property updated")""")
            case eb: EmptyBox =>
              JsRaw("""createErrorNotification("There was an error when updating the value of the 'display change graphs' property")""")
          }
          check & createNotification
        }

        ( "#displayGraphsCheckbox" #> {
            SHtml.ajaxCheckbox(
                value
              , (b : Boolean) => { currentdisplayGraphs = b; check}
              , ("id","displayGraphsCheckbox")
            )
          } &
          "#displayGraphsSubmit " #> {
            SHtml.ajaxSubmit("Save changes", submit _, ("class","btn btn-default"))
          } &
          "#displayGraphsSubmit *+" #> {
            Script(check())
          }
        )
      case eb: EmptyBox =>
        ( "#displayGraphs" #> {
          val fail = eb ?~ "there was an error while fetching value of property: 'display change graphs'"
          logger.error(fail.messageChain)
          <div class="error">{fail.messageChain}</div>
        } )
    } ) apply xml
  }

  def displayRuleColumnConfiguration = { xml : NodeSeq =>

    ( configService.rudder_ui_display_ruleComplianceColumns().toBox match {
      case Full(value) =>
        var initDisplayColumns = value
        var currentDisplayColumns = value
        def noModif() = initDisplayColumns  == currentDisplayColumns
        def check() = {
          Run(s"""$$("#displayColumnsSubmit").prop( "checked", ${noModif()} );""")
        }

        def submit() = {
          val save = configService.set_rudder_ui_display_ruleComplianceColumns(currentDisplayColumns).toBox
          val createNotifcation = save match {
            case Full(_)  =>
              initDisplayColumns  = currentDisplayColumns
              JsRaw("""createSuccessNotification("'Display compliance and recent changes columns on rule summary' property updated")""")
            case eb: EmptyBox =>
              JsRaw("""createErrorNotification("There was an error when updating the value of the 'Display compliance and recent changes columns on rule summary' property")""")
          }
          check & createNotifcation
        }

        ( "#displayColumnsCheckbox" #> {
            SHtml.ajaxCheckbox(
                value
              , (b : Boolean) => { currentDisplayColumns = b; check}
              , ("id","displayColumnsCheckbox")
            )
          } &
          "#displayColumnsSubmit " #> {
            SHtml.ajaxSubmit("Save changes", submit _, ("class","btn btn-default"))
          } &
          "#displayColumnsSubmit *+" #> {
            check()
          }
        )
      case eb: EmptyBox =>
        ( "#displayColumns" #> {
          val fail = eb ?~ "there was an error while fetching value of property: 'Display compliance and recent changes columns on rule summary'"
          logger.error(fail.messageChain)
          <div class="error">{fail.messageChain}</div>
        } )
    } ) apply xml
  }

  def directiveScriptEngineConfiguration = { xml : NodeSeq =>
    import com.normation.rudder.domain.appconfig.FeatureSwitch._

    ( configService.rudder_featureSwitch_directiveScriptEngine().toBox match {
      case Full(initialValue) =>

        var initSavedValued = initialValue
        var x = initialValue
        def noModif() = x == initSavedValued
        def check() = {
          Run(s"""$$("#directiveScriptEngineSubmit").attr("disabled",${noModif()});""")
        }

        def submit() = {
          val save = configService.set_rudder_featureSwitch_directiveScriptEngine(x).toBox
          val createNotification = save match {
            case Full(_)  =>
              initSavedValued = x
              // If we disable this feature we want to start policy generation because some data may be invalid
              if (x == Disabled) {
                startNewPolicyGeneration
              }
              JsRaw("""createSuccessNotification("'directive script engine' property updated. The feature will be loaded as soon as you go to another page or reload this one.")""")
            case eb: EmptyBox =>
              JsRaw("""createErrorNotification("There was an error when updating the value of the 'directive script engine' property")""")
          }
          check() & createNotification
        }

        ( "#directiveScriptEngineCheckbox" #> {
            SHtml.ajaxCheckbox(
                x == Enabled
              , (b : Boolean) => { if(b) { x = Enabled } else { x = Disabled }; check}
              , ("id","directiveScriptEngineCheckbox")
            )
          } &
          "#directiveScriptEngineSubmit " #> {
            SHtml.ajaxSubmit("Save changes", submit _, ("class","btn btn-default"))
          } &
          "#directiveScriptEngineSubmit *+" #> {
            Script(check())
          }
        )

      case eb: EmptyBox =>
        ( "#directiveScriptEngine" #> {
          val fail = eb ?~ "there was an error while fetching value of property: 'directive script engine'"
          logger.error(fail.messageChain)
          <div class="error">{fail.messageChain}</div>
        } )
    } ) apply xml
  }

  def nodeOnAcceptDefaultsConfiguration = { xml : NodeSeq =>

    val modes = SelectableOption[Option[PolicyMode]](None, "Use global value") :: PolicyMode.allModes.map { x =>
      SelectableOption[Option[PolicyMode]](Some(x), x.name.capitalize)
    }.toList
    // node states, sorted [init, enable, other]
    val states = NodeState.labeledPairs.map{ case (x, label) =>
      SelectableOption[NodeState](x, label)
    }

    val process = (for {
      initialPolicyMode <- configService.rudder_node_onaccept_default_policy_mode().toBox
      initialNodeState  <- configService.rudder_node_onaccept_default_state().toBox
    } yield {

      // initialise values for the form (last time it was saved and current user input)
      var initPolicyMode = initialPolicyMode
      var initNodeState = initialNodeState

      var policyMode = initialPolicyMode
      var state = initialNodeState

      def noModif() = initPolicyMode == policyMode && initNodeState == state
      def check() = {
        Run(s"""$$("#nodeOnAcceptDefaultsSubmit").attr("disabled",${noModif()});""")
      }

      def submit() = {
        val save =
          for {
            _ <- configService.set_rudder_node_onaccept_default_state(state).toBox
            _ <- configService.set_rudder_node_onaccept_default_policy_mode(policyMode).toBox
          } yield {
            ()
          }


        val createNotification =
          save match {
            case Full(_)  =>
              initNodeState = state
              initPolicyMode = policyMode
              JsRaw("""createSuccessNotification("Node default configuration post-acceptation correctly saved. Next accepted node will get these default configuration.")""")
          case eb: EmptyBox =>
            JsRaw("""createErrorNotification("There was an error when updating node default proerties")""")
        }
        check() & createNotification
      }

      "#nodeOnAcceptState" #> SHtml.ajaxSelectObj(states, Full(initNodeState), { (x:NodeState) => state = x; check}, ("id","nodeOnAcceptState")) &
      "#nodeOnAcceptPolicyMode" #> SHtml.ajaxSelectObj(modes, Full(initPolicyMode), { (x: Option[PolicyMode]) => policyMode = x; check}, ("id","nodeOnAcceptPolicyMode")) &
      "#nodeOnAcceptDefaultsSubmit" #> {
        SHtml.ajaxSubmit("Save changes", submit _, ("class","btn btn-default"))
      } &
        "#nodeOnAcceptDefaultsSubmit *+" #> {
        Script(check())
      }

    }) match {
      case eb: EmptyBox =>
        val fail = eb ?~ "there was an error while fetching value of property: 'node on accept parameters'"
        logger.error(fail.messageChain)

        (xml: NodeSeq) => <div class="error">{fail.messageChain}</div>

      case Full(process) =>
        process
    }

    ("#nodeOnAcceptDefaults" #> process).apply(xml)
  }

  def unexpectedReportInterpretation = { xml : NodeSeq =>
    import com.normation.rudder.services.reports.UnexpectedReportBehavior._

    ( configService.rudder_compliance_unexpected_report_interpretation().toBox match {
      case Full(initialValue) =>

        var initSavedValued = initialValue
        var x = initialValue
        def noModif() = x == initSavedValued
        def check() = {
          Run(s"""$$("#unexpectedReportInterpretationFormSubmit").prop("disabled",${noModif()});""")
        }

        def submit() = {
          val save = configService.set_rudder_compliance_unexpected_report_interpretation(x).toBox
           val createNotification = save match {
            case Full(_)  =>
              initSavedValued = x
              // If we disable this feature we want to start policy generation because some data may be invalid
              JsRaw("""createSuccessNotification("'interpretation of unexpected compliance reports' property updated.")""")
            case eb: EmptyBox =>
              JsRaw("""createErrorNotification("There was an error when updating the value of the 'interpretation of unexpected compliance reports' property")""")
          }
          check() & createNotification
        }

        ( "#allowsDuplicate" #> {
            SHtml.ajaxCheckbox(
                x.isSet(AllowsDuplicate)
              , (b : Boolean) => { if(b) { x = x.set(AllowsDuplicate) } else { x = x.unset(AllowsDuplicate) }; check}
              , ("id","allowsDuplicate")
            )
          } &
          "#unboundVarValues" #> {
            SHtml.ajaxCheckbox(
                x.isSet(UnboundVarValues)
              , (b : Boolean) => { if(b) { x = x.set(UnboundVarValues) } else { x = x.unset(UnboundVarValues) }; check}
              , ("id","unboundVarValues")
            )
          } &
          "#unexpectedReportInterpretationFormSubmit " #> {
              SHtml.ajaxSubmit("Save changes", submit _, ("class","btn btn-default"), ("disabled", "disabled"))
          }
        )

      case eb: EmptyBox =>
        ( "#unexpectedReportInterpretation" #> {
          val fail = eb ?~ "there was an error while fetching value of property: 'interpretation of unexpected compliance reports'"
          logger.error(fail.messageChain)
          <div class="error">{fail.messageChain}</div>
        } )
    } ) apply xml
  }
}
