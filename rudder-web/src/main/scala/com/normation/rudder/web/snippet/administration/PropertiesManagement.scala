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
import com.normation.rudder.appconfig._
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
import com.normation.rudder.services.servers.{RelaySynchronizationMethod,ClassicSynchronization, RsyncSynchronization, DisabledSynchronization}
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
    asyncDeploymentAgent ! AutomaticStartDeployment(modId, CurrentUser.getActor)
  }

  def disableInputs = {
    import com.normation.rudder.authorization.Edit
    //If user does not have the Edit("administration") right, all inputs are disabled
    val disable = !CurrentUser.checkRights(Edit("administration"))
    S.appendJs(JsRaw(s"""$$("input, select").attr("disabled",${disable})"""))
    NodeSeq.Empty
  }

  def dispatch = {
    case "changeMessage" => changeMessageConfiguration
    case "workflow"      => workflowConfiguration
    case "denyBadClocks" => cfserverNetworkConfiguration
    case "relaySynchronizationMethod" => relaySynchronizationMethodManagement
    case "cfagentSchedule" => (xml) => cfagentScheduleConfiguration
    case "agentPolicyMode" => (xml) => agentPolicyModeConfiguration
    case "complianceMode" => (xml) => complianceModeConfiguration
    case "cfengineGlobalProps" => cfengineGlobalProps
    case "loggingConfiguration" => loggingConfiguration
    case "sendMetricsConfiguration" => sendMetricsConfiguration
    case "networkProtocolSection" => networkProtocolSection
    case "displayGraphsConfiguration" => displayGraphsConfiguration
    case "displayRuleColumnConfiguration" => displayRuleColumnConfiguration
    case "directiveScriptEngineConfiguration" => directiveScriptEngineConfiguration
    case "onloadScript" => _ => disableInputs
  }

  def changeMessageConfiguration = { xml : NodeSeq =>

    // initial values
    var initEnabled = configService.rudder_ui_changeMessage_enabled
    var initMandatory = configService.rudder_ui_changeMessage_mandatory
    var initExplanation = configService.rudder_ui_changeMessage_explanation

    // mutable, default values won't be used (if error in property => edit form is not displayed)
    var enabled = initEnabled.getOrElse(false)
    var mandatory = configService.rudder_ui_changeMessage_mandatory.getOrElse(false)
    var explanation = configService.rudder_ui_changeMessage_explanation.getOrElse("Please enter a reason explaining this change.")

    def submit = {

      // Save new value
      configService.set_rudder_ui_changeMessage_enabled(enabled).
        // If update is sucessful update the initial value used by the form
        foreach(updateOk => initEnabled = Full(enabled))

      configService.set_rudder_ui_changeMessage_mandatory(mandatory).foreach(updateOk => initMandatory = Full(mandatory))

      configService.set_rudder_ui_changeMessage_explanation(explanation).foreach(updateOk => initExplanation = Full(explanation))
      S.clearCurrentNotices
      S.notice("updateChangeMsg","Change audit logs configuration correctly updated")
      check()
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
      if(!noModif){
        if(emptyString){
          S.error("updateChangeMsg","The message field cannot be empty.")
        }else{
          S.notice("updateChangeMsg","")
        }
      }
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

  def workflowConfiguration = { xml : NodeSeq =>

    //  initial values, updated on successfull submit
    var initEnabled = configService.rudder_workflow_enabled
    var initSelfVal = configService.rudder_workflow_self_validation
    var initSelfDep = configService.rudder_workflow_self_deployment

    // form values
    var enabled = initEnabled.getOrElse(false)
    var selfVal = initSelfVal.getOrElse(false)
    var selfDep = initSelfDep.getOrElse(false)

    def submit = {
      configService.set_rudder_workflow_enabled(enabled).foreach(updateOk => initEnabled = Full(enabled))
      configService.set_rudder_workflow_self_validation(selfVal).foreach(updateOk => initSelfVal = Full(selfVal))
      configService.set_rudder_workflow_self_deployment(selfDep).foreach(updateOk => initSelfDep = Full(selfDep))
        S.notice("updateWorkflow","Change Requests (validation workflow) configuration correctly updated")
      check()
    }

    def noModif = (    initEnabled.map(_ == enabled).getOrElse(false)
                    && initSelfVal.map(_ == selfVal).getOrElse(false)
                    && initSelfDep.map(_ == selfDep).getOrElse(false)
                  )

    def check() = {
      if(!noModif){
        S.notice("updateWorkflow","")
      }
      Run(s"""$$("#workflowSubmit").attr("disabled",${noModif});""")
    }
    def initJs(newStatus :Boolean) = {
      enabled = newStatus
      check() &
      Run(
        s"""
            $$("#selfVal").attr("disabled",${!newStatus});
            $$("#selfDep").attr("disabled",${!newStatus});
            if(${!newStatus}){
              $$("#selfDep").parent().parent().addClass('disabled');
              $$("#selfVal").parent().parent().addClass('disabled');
            }else{
              $$("#selfDep").parent().parent().removeClass('disabled');
              $$("#selfVal").parent().parent().removeClass('disabled');
            }
        """
      )
    }
    ( "#workflowEnabled" #> {
      initEnabled match {
        case Full(value) =>
          SHtml.ajaxCheckbox(
              value
            , initJs _
            , ("id","workflowEnabled")
            , ("class","twoCol")
          )
          case eb: EmptyBox =>
            val fail = eb ?~ "there was an error, while fetching value of property: 'Enable Change Requests' "
            <div class="error">{fail.msg}</div>
        }
      } &

      "#selfVal" #> {
        initSelfVal match {
          case Full(value) =>
            SHtml.ajaxCheckbox(
                value
              , (b : Boolean) => { selfVal = b; check() }
              , ("id","selfVal")
              , ("class","twoCol")
            )
          case eb: EmptyBox =>
            val fail = eb ?~ "there was an error, while fetching value of property: 'Allow self validation' "
            <div class="error">{fail.msg}</div>
        }

      } &

      "#selfDep " #> {
        initSelfDep match {
          case Full(value) =>
            SHtml.ajaxCheckbox(
                value
              , (b : Boolean) => { selfDep = b; check() }
              , ("id","selfDep")
              , ("class","twoCol")
            )
          case eb: EmptyBox =>
            val fail = eb ?~ "there was an error, while fetching value of property: 'Allow self deployment' "
            <div class="error">{fail.msg}</div>
        }
      } &

      "#selfValTooltip *" #> {

        initSelfVal match {
          case Full(_) =>
            val tooltipid = Helpers.nextFuncName
            <span class="tooltipable" tooltipid={tooltipid} title="">
              <span class="glyphicon glyphicon-info-sign info"></span>
            </span>
            <div class="tooltipContent" id={tooltipid}>
              Allow users to validate Change Requests they created themselves? Validating is moving a Change Request to the "<b>Pending deployment</b>" status
            </div>
          case _ => NodeSeq.Empty
        }
      } &

      "#selfDepTooltip *" #> {
        initSelfDep match {
          case Full(_) =>
            val tooltipid = Helpers.nextFuncName
            <span class="tooltipable" tooltipid={tooltipid} title="">
              <span class="glyphicon glyphicon-info-sign info"></span>
            </span>
            <div class="tooltipContent" id={tooltipid}>
              Allow users to deploy Change Requests they created themselves? Deploying is effectively applying a Change Request in the "<b>Pending deployment</b>" status.
            </div>
          case _ => NodeSeq.Empty
        }
      } &

      "#workflowSubmit " #> {
         SHtml.ajaxSubmit("Save changes", submit _ , ("class","btn btn-default"))
      }
    ) apply (xml ++ Script(initJs(enabled)))
  }

  def cfserverNetworkConfiguration = { xml : NodeSeq =>

    //  initial values, updated on successfull submit
    var initDenyBadClocks = configService.cfengine_server_denybadclocks
    //be careful, we want "No skipIdentify"
    //convention: we negate on server i/o, not anywhere else
    var initNoSkipIdentify = configService.cfengine_server_skipidentify.map( !_ )

    // form values
    var denyBadClocks = initDenyBadClocks.getOrElse(false)
    var noSkipIdentify = initNoSkipIdentify.getOrElse(false)

    def submit = {
      configService.set_cfengine_server_denybadclocks(denyBadClocks).foreach(updateOk => initDenyBadClocks = Full(denyBadClocks))
      configService.set_cfengine_server_skipidentify(!noSkipIdentify).foreach(updateOk => initNoSkipIdentify = Full(noSkipIdentify))

      // start a promise generation, Since we check if there is change to save, if we got there it mean that we need to redeploy
      startNewPolicyGeneration
      S.notice("updateCfserverNetwork","Network security options correctly updated")
      check()
    }

    def noModif = (
         initDenyBadClocks.map(_ == denyBadClocks).getOrElse(false)
      && initNoSkipIdentify.map(_ == noSkipIdentify).getOrElse(false)
    )

    def check() = {
      if(!noModif){
        S.notice("updateCfserverNetwork","")
      }
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
     "#skipIdentify" #> {
      initNoSkipIdentify match {
        case Full(value) =>
          SHtml.ajaxCheckbox(
              value
            , (b : Boolean) => { noSkipIdentify = b; check() }
            , ("id","skipIdentify")
          )
          case eb: EmptyBox =>
            val fail = eb ?~ "there was an error while fetching value of property: 'Skip verify' "
            <div class="error">{fail.msg}</div>
        }
      } &

      "#skipIdentifyTooltip *" #> {

        initNoSkipIdentify match {
          case Full(_) =>
            val tooltipid = Helpers.nextFuncName
            <span class="tooltipable" tooltipid={tooltipid} title="">
              <span class="glyphicon glyphicon-info-sign info"></span>
            </span>
            <div class="tooltipContent" id={tooltipid}>
              By default, copying configuration policy requires nodes to be able to
              perform a reverse DNS lookup for the IP of the interface used to connect to the Rudder
              server. This is then checked by a forward DNS lookup on the server. Disabling this will
              bypass this check, thus slightly improving performance on each node without providing
              any significant window for attack. It is necessary to disable this option if any of
              your nodes are behind a NAT or if you don't have a full reverse DNS setup.
            </div>

          case _ => NodeSeq.Empty
        }
      } &

      "#cfserverNetworkSubmit " #> {
         SHtml.ajaxSubmit("Save changes", submit _ , ("class","btn btn-default"))
      }
    ) apply (xml ++ Script(check()))
  }

  def relaySynchronizationMethodManagement = { xml : NodeSeq =>
    //  initial values, updated on successfull submit
    var initRelaySyncMethod = configService.relay_server_sync_method
    // Be careful, we store negative value
    var initRelaySyncPromises = configService.relay_server_syncpromises
    var initRelaySyncSharedFiles = configService.relay_server_syncsharedfiles

    // form values
    var relaySyncMethod = initRelaySyncMethod.getOrElse(ClassicSynchronization)
    var relaySyncPromises = initRelaySyncPromises.getOrElse(false)
    var relaySyncSharedFiles = initRelaySyncSharedFiles.getOrElse(false)

    def noModif = (
         initRelaySyncMethod.map(_ == relaySyncMethod).getOrElse(false)
      && initRelaySyncPromises.map(_ == relaySyncPromises).getOrElse(false)
      && initRelaySyncSharedFiles.map(_ == relaySyncSharedFiles).getOrElse(false)
    )

    def check() = {
      if(!noModif){
        S.notice("updateRelaySynchronization","")
      }
      Run(s"""$$("#relaySynchronizationSubmit").prop('disabled', ${noModif});""")
    }

    def submit = {
      configService.set_relay_server_sync_method(relaySyncMethod).foreach(updateOk => initRelaySyncMethod = Full(relaySyncMethod))
      configService.set_relay_server_syncpromises(relaySyncPromises).foreach(updateOk => initRelaySyncPromises = Full(relaySyncPromises))
      configService.set_relay_server_syncsharedfiles(relaySyncSharedFiles).foreach(updateOk => initRelaySyncSharedFiles = Full(relaySyncSharedFiles))

      // start a promise generation, Since we check if there is change to save, if we got there it mean that we need to redeploy
      startNewPolicyGeneration
      S.notice("updateRelaySynchronization","Relay servers synchronization methods correctly updated")
      check()
    }

    def setRelaySyncMethodJs(t:String) : JsCmd = {
      t.toLowerCase() match {
        case ClassicSynchronization.value => relaySyncMethod = ClassicSynchronization ; JsRaw(""" $('#relayRsyncSynchronizeFiles').hide(); """)
        case RsyncSynchronization.value =>   relaySyncMethod = RsyncSynchronization   ; JsRaw(""" $('#relayRsyncSynchronizeFiles').show(); """)
        case DisabledSynchronization.value =>relaySyncMethod = DisabledSynchronization; JsRaw(""" $('#relayRsyncSynchronizeFiles').hide(); """)
      }
    }
    (
       "#relaySyncMethod" #> {
         initRelaySyncMethod match {
            case Full(value) =>
              SHtml.ajaxRadio(
                Seq(ClassicSynchronization.value, RsyncSynchronization.value, DisabledSynchronization.value).map(_.capitalize)
              , initRelaySyncMethod.map(_.value.capitalize)
              , (t:String) => setRelaySyncMethodJs(t)
              , ("id","relaySyncMethod")
              ).toForm ++ Script(OnLoad(setRelaySyncMethodJs(value.value)))

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
              <span class="tw-bs"><span class="glyphicon glyphicon-info-sign info"></span></span>
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
              <span class="tw-bs"><span class="glyphicon glyphicon-info-sign info"></span></span>
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


  def networkProtocolSection = { xml : NodeSeq =>
    //  initial values, updated on successfull submit
    def networkForm(initValue : SyslogProtocol) = {
      var initReportsProtocol = initValue
      var reportProtocol = initValue
      def check = {
        val noChange = initReportsProtocol == reportProtocol
        S.notice("updateNetworkProtocol","")
        Run(s"""$$("#networkProtocolSubmit").attr("disabled",${noChange});""")
      }

      def submit = {
        val actor = CurrentUser.getActor
        configService.set_rudder_syslog_protocol(reportProtocol,actor,None) match {
          case Full(_) =>
            // Update the initial value of the form
            initReportsProtocol = reportProtocol
            startNewPolicyGeneration
            S.notice("updateNetworkProtocol","Network protocol options correctly updated")
            check
          case eb:EmptyBox =>
            S.error("updateNetworkProtocol","Error when saving network protocol options")
            Noop
        }
      }

      val checkboxInitValue = initReportsProtocol == SyslogUDP

     ( "#reportProtocol" #> {
          SHtml.ajaxCheckbox(
              checkboxInitValue
            , (newValue : Boolean) => {
                reportProtocol = if (newValue) SyslogUDP else SyslogTCP
                check
              }
            , ("id","reportProtocol")
          )
       } &
       "#networkProtocolSubmit " #> {
         SHtml.ajaxSubmit("Save changes", submit _ ,("class","btn btn-default"))
       }
      )(xml ++ Script(check))

    }

    configService.rudder_syslog_protocol match {
      case Full(value) =>
        networkForm(value)
      case eb: EmptyBox =>
        // We could not read current protocol, try repairing by setting protocol to UDP and warn user
        val actor = CurrentUser.getActor
        configService.set_rudder_syslog_protocol(SyslogUDP,actor,Some("Property automatically reset to 'UDP' due to an error"))
        S.error("updateNetworkProtocol","Error when fetching 'Syslog protocol' property, Setting it to UDP")
        networkForm(SyslogUDP)
    }
  }

  val agentScheduleEditForm = new AgentScheduleEditForm(
      getSchedule
    , saveSchedule
    , () => startNewPolicyGeneration
  )

  val complianceModeEditForm = {
    val globalMode = configService.rudder_compliance_mode()
    new ComplianceModeEditForm[GlobalComplianceMode](
        globalMode
      , (complianceMode) => {
          configService.set_rudder_compliance_mode(complianceMode,CurrentUser.getActor,genericReasonMessage)
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
  }

  def saveSchedule(schedule: AgentRunInterval) : Box[Unit] = {

    val actor = CurrentUser.getActor
    for {
      _ <- configService.set_agent_run_interval(schedule.interval,actor,genericReasonMessage)
      _ <- configService.set_agent_run_start_hour(schedule.startHour,actor,genericReasonMessage)
      _ <- configService.set_agent_run_start_minute(schedule.startMinute,actor,genericReasonMessage)
      _ <- configService.set_agent_run_splaytime(schedule.splaytime,actor,genericReasonMessage)
    } yield {
      logger.info(s"Agent schedule updated to run interval: ${schedule.interval} min, start time: ${schedule.startHour} h ${schedule.startMinute} min, splaytime: ${schedule.splaytime} min")
    }
  }

  def cfagentScheduleConfiguration = agentScheduleEditForm.cfagentScheduleConfiguration
  def agentPolicyModeConfiguration = agentPolicyModeEditForm.cfagentPolicyModeConfiguration
  def complianceModeConfiguration = complianceModeEditForm.complianceModeConfiguration

  def cfengineGlobalProps = { xml : NodeSeq =>

    //  initial values, updated on successful submit
    var initModifiedFilesTtl = configService.cfengine_modified_files_ttl
    // form values
    var modifiedFilesTtl = initModifiedFilesTtl.getOrElse(30).toString


    def submit = {
      // first, check if the content are effectively Int
      try {
        val intModifiedFilesTtl = Integer.parseInt(modifiedFilesTtl)
        configService.set_cfengine_modified_files_ttl(intModifiedFilesTtl).foreach(updateOk => initModifiedFilesTtl = Full(intModifiedFilesTtl))
        // start a promise generation, Since we check if there is change to save, if we got there it mean that we need to redeploy
        startNewPolicyGeneration
        S.notice("updateCfengineGlobalProps","File retention settings correctly updated")
        check()
      } catch {
        case ex:NumberFormatException =>
          S.error("updateCfengineGlobalProps", "Invalid value "+ex.getMessage().replaceFirst("F", "f"))
          Noop
      }
    }

    def noModif = (
      initModifiedFilesTtl.map(_.toString == modifiedFilesTtl).getOrElse(false)
    )

    def check() = {
      if(!noModif){
        S.notice("updateCfengineGlobalProps","")
      }
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
    var initStoreAllCentralizedLogsInFile = configService.rudder_store_all_centralized_logs_in_file
    var initCfengineOutputsTtl = configService.cfengine_outputs_ttl
    // form values
    var storeAllCentralizedLogsInFile  = initStoreAllCentralizedLogsInFile.getOrElse(false)
    var cfengineOutputsTtl = initCfengineOutputsTtl.getOrElse(7).toString

    def submit = {
      try{
        val intCfengineOutputsTtl = Integer.parseInt(cfengineOutputsTtl)
        configService.set_cfengine_outputs_ttl(intCfengineOutputsTtl).foreach(updateOk => initCfengineOutputsTtl = Full(intCfengineOutputsTtl))

      } catch{
        case ex:NumberFormatException =>
          S.error("sendMetricsMsg", "Invalid value "+ex.getMessage().replaceFirst("F", "f"))
          Noop
      }
      configService.set_rudder_store_all_centralized_logs_in_file(storeAllCentralizedLogsInFile).foreach(updateOk => initStoreAllCentralizedLogsInFile = Full(storeAllCentralizedLogsInFile))

      // start a promise generation, Since we check if there is change to save, if we got there it mean that we need to redeploy
      startNewPolicyGeneration
      S.notice("loggingConfiguration", storeAllCentralizedLogsInFile match {
        case true  => "Logging will be enabled during the next agent run on this server (5 minutes maximum)"
        case false => "Logging will be disabled during the next agent run on this server (5 minutes maximum)"
        })
      check()
    }

    def noModif = (
       initStoreAllCentralizedLogsInFile.map(_ == storeAllCentralizedLogsInFile).getOrElse(false)
    && initCfengineOutputsTtl.map(_.toString == cfengineOutputsTtl).getOrElse(false)
    )

    def check() = {
      if(!noModif){
        S.notice("loggingConfiguration","")
      }
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

  def sendMetricsConfiguration = { xml : NodeSeq =>
    ( configService.send_server_metrics match {
      case Full(value) =>
        var initSendMetrics = value
        var currentSendMetrics = value
        def noModif() = initSendMetrics == currentSendMetrics
        def check() = {
          S.notice("sendMetricsMsg","")
          Run(s"""$$("#sendMetricsSubmit").button( "option", "disabled",${noModif()});""")
        }
        def submit() = {
          val save = configService.set_send_server_metrics(currentSendMetrics,CurrentUser.getActor,genericReasonMessage)
          S.notice("sendMetricsMsg", save match {
            case Full(_)  =>
              initSendMetrics = currentSendMetrics
              // start a promise generation, Since we may have change the mode, if we got there it mean that we need to redeploy
              startNewPolicyGeneration
              "'send server metrics' property updated"
            case eb: EmptyBox =>
              "There was an error when updating the value of the 'send server metrics' property"
          } )
          check
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
            Script(Run("correctButtons();") & check())

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

    ( configService.display_changes_graph() match {
      case Full(value) =>
        var initDisplayGraphs = value
        var currentdisplayGraphs = value
        def noModif() = initDisplayGraphs == currentdisplayGraphs
        def check() = {
          S.notice("displayGraphsMsg","")
          Run(s"""$$("#displayGraphsSubmit").attr("disabled",${noModif()});""")
        }

        def submit() = {
          val save = configService.set_display_changes_graph(currentdisplayGraphs)
          S.notice("displayGraphsMsg", save match {
            case Full(_)  =>
              initDisplayGraphs = currentdisplayGraphs
              "'display change graphs' property updated"
            case eb: EmptyBox =>
              "There was an error when updating the value of the 'display change graphs' property"
          } )
          check
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

    ( configService.rudder_ui_display_ruleComplianceColumns() match {
      case Full(value) =>
        var initDisplayColumns = value
        var currentDisplayColumns = value
        def noModif() = initDisplayColumns  == currentDisplayColumns
        def check() = {
          S.notice("displayColumnsMsg","")
          Run(s"""$$("#displayColumnsSubmit").prop( "checked", ${noModif()} );""")
        }

        def submit() = {
          val save = configService.set_rudder_ui_display_ruleComplianceColumns(currentDisplayColumns)
          S.notice("displayColumnsMsg", save match {
            case Full(_)  =>
              initDisplayColumns  = currentDisplayColumns
              "'Display compliance and recent changes columns on rule summary' property updated"
            case eb: EmptyBox =>
              "There was an error when updating the value of the 'Display compliance and recent changes columns on rule summary' property"
          } )
          check
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

    ( configService.rudder_featureSwitch_directiveScriptEngine() match {
      case Full(initialValue) =>

        var initSavedValued = initialValue
        var x = initialValue
        def noModif() = x == initSavedValued
        def check() = {
          S.notice("directiveScriptEngineMsg","")
          Run(s"""$$("#directiveScriptEngineSubmit").attr("disabled",${noModif()});""")
        }

        def submit() = {
          val save = configService.set_rudder_featureSwitch_directiveScriptEngine(x)
          S.notice("directiveScriptEngineMsg", save match {
            case Full(_)  =>
              initSavedValued = x
              // If we disable this feature we want to start policy generation because some data may be invalid
              if (x == Disabled) {
                startNewPolicyGeneration
              }
              "'directive script engine' property updated. The feature will be loaded as soon as you go to another page or reload this one."
            case eb: EmptyBox =>
              "There was an error when updating the value of the 'directive script engine' property"
          } )
          check()
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
}
