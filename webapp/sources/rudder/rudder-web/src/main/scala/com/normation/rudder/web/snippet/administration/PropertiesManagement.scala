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

import bootstrap.liftweb.RudderConfig
import com.normation.appconfig.*
import com.normation.box.*
import com.normation.eventlog.ModificationId
import com.normation.rudder.AuthorizationType
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.services.policies.SendMetrics
import com.normation.rudder.services.servers.RelaySynchronizationMethod
import com.normation.rudder.services.servers.RelaySynchronizationMethod.*
import com.normation.rudder.users.CurrentUser
import com.normation.rudder.web.components.AgentPolicyModeEditForm
import com.normation.rudder.web.components.AgentScheduleEditForm
import com.normation.rudder.web.components.ComplianceModeEditForm
import com.normation.rudder.web.snippet.WithNonce
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermission
import net.liftweb.common.*
import net.liftweb.http.*
import net.liftweb.http.SHtml.*
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.*
import net.liftweb.util.Helpers.*
import scala.xml.NodeSeq
import scala.xml.Text

/**
 * This class manage the displaying of user configured properties.
 *
 * Methods on that classes are used in the template ""
 */
class PropertiesManagement extends DispatchSnippet with Loggable {

  private val configService: ReadConfigService & UpdateConfigService = RudderConfig.configService
  private val asyncDeploymentAgent = RudderConfig.asyncDeploymentAgent
  private val uuidGen              = RudderConfig.stringUuidGenerator

  private val genericReasonMessage = Some("Property modified from Rudder preference page")

  def startNewPolicyGeneration(): Unit = {
    val modId = ModificationId(uuidGen.newUuid)
    asyncDeploymentAgent ! AutomaticStartDeployment(modId, CurrentUser.actor)
  }

  def disableInputs: NodeSeq = {
    // If user does not have the Edit("administration") right, all inputs are disabled
    // else nothing is done because it enables what should not be.
    if (!CurrentUser.checkRights(AuthorizationType.Administration.Edit)) {

      S.appendJs(JsRaw(s"""$$("input, select").attr("disabled", "true")""")) // JsRaw ok, const
    }
    NodeSeq.Empty
  }

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = {
    case "changeMessage"                      => changeMessageConfiguration
    case "denyBadClocks"                      => cfserverNetworkConfiguration
    case "relaySynchronizationMethod"         => relaySynchronizationMethodManagement
    case "cfagentSchedule"                    => (xml) => cfagentScheduleConfiguration
    case "agentPolicyMode"                    => (xml) => agentPolicyModeConfiguration
    case "complianceMode"                     => (xml) => complianceModeConfiguration
    case "cfengineGlobalProps"                => cfengineGlobalProps
    case "loggingConfiguration"               => loggingConfiguration
    case "sendMetricsConfiguration"           => sendMetricsConfiguration
    case "displayGraphsConfiguration"         => displayGraphsConfiguration
    case "directiveScriptEngineConfiguration" => directiveScriptEngineConfiguration
    case "onloadScript"                       => _ => disableInputs
    case "nodeOnAcceptDefaults"               => nodeOnAcceptDefaultsConfiguration
    case "generationHookCfpromise"            => generationHookCfpromise
    case "generationHookTriggerNodeUpdate"    => generationHookTriggerNodeUpdate
  }

  def changeMessageConfiguration: NodeSeq => NodeSeq = { (xml: NodeSeq) =>
    // initial values
    var initEnabled     = configService.rudder_ui_changeMessage_enabled().toBox
    var initMandatory   = configService.rudder_ui_changeMessage_mandatory().toBox
    var initExplanation = configService.rudder_ui_changeMessage_explanation().toBox

    // mutable, default values won't be used (if error in property => edit form is not displayed)
    var enabled     = initEnabled.getOrElse(false)
    var mandatory   = configService.rudder_ui_changeMessage_mandatory().toBox.getOrElse(false)
    var explanation =
      configService.rudder_ui_changeMessage_explanation().toBox.getOrElse("Please enter a reason explaining this change.")

    def submit() = {

      // Save new value
      configService
        .set_rudder_ui_changeMessage_enabled(enabled)
        .toBox
        .
        // If update is sucessful update the initial value used by the form
        foreach(updateOk => initEnabled = Full(enabled))

      configService.set_rudder_ui_changeMessage_mandatory(mandatory).toBox.foreach(updateOk => initMandatory = Full(mandatory))

      configService
        .set_rudder_ui_changeMessage_explanation(explanation)
        .toBox
        .foreach(updateOk => initExplanation = Full(explanation))
      check() & JsRaw("""createSuccessNotification("Audit logs configuration correctly updated")""") // JsRaw ok, const
    }

    // Check if there is no modification
    // Ignore error ones so we can still modify those not in error)
    def noModif     = (
      initEnabled.map(_ == enabled).getOrElse(false)
        && initMandatory.map(_ == mandatory).getOrElse(false)
        && initExplanation.map(_ == explanation).getOrElse(false)
    )
    def emptyString = explanation.trim().length == 0
    // Check that there is some modification to enabled/disable save
    def check()     = {
      Run(s"""$$("#changeMessageSubmit").attr("disabled",${noModif || emptyString});""")
    }

    // Initialisation of form
    // Determine if some fields should be disabled.
    def initJs(newStatus: Boolean) = {
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
    ("#configurationRepoPath" #> RudderConfig.RUDDER_GIT_ROOT_CONFIG_REPO &
    "#enabled" #> {
      initEnabled match {
        case Full(value) =>
          SHtml.ajaxCheckbox(
            value,
            initJs,
            ("id", "enabled"),
            ("class", "twoCol")
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
            value,
            (b: Boolean) => { mandatory = b; check() },
            ("id", "mandatory"),
            ("class", "twoCol"),
            ("disabled", s"${!mandatory}")
          )
        case eb: EmptyBox =>
          val fail = eb ?~ "there was an error, while fetching value of property: 'Make message mandatory "
          <div class="error">{fail.msg}</div>
      }
    } &

    "#explanation " #> {

      // Need specific check on base value of the field
      // Maybe don't need to be done in Ajax and replaced by full client side
      def checkExplanation(initValue: String)(s: String) = {
        val mod = (initEnabled.map(_ == enabled).getOrElse(false)
          && initMandatory.map(_ == mandatory).getOrElse(false))
        Run(s"""
             var noModif = $mod && ($$("#explanation").val() == "$initValue");
             $$("#changeMessageSubmit").attr("disabled",noModif);""")
      }
      initExplanation match {
        case Full(value) =>
          SHtml.ajaxText(
            value,
            (s: String) => { explanation = s; check() },
            ("id", "explanation"),
            ("class", "form-control"),
            ("onkeydown", ajaxCall("checkExplanation", checkExplanation(value)).toJsCmd)
          )
        case eb: EmptyBox =>
          val fail = eb ?~ "there was an error, while fetching value of property: 'Explanation to display "
          <div class="error">{fail.msg}</div>
      }
    } &

    "#restoreExplanation " #> {
      initExplanation.map { (s: String) =>
        ajaxButton(
          <span>Reset to default</span>,
          () => {
            explanation = "Please enter a reason explaining this change."
            Run("""$("#explanation").val("Please enter a reason explaining this change.");""") & check()

          },
          ("class", "btn btn-default"),
          ("id", "restoreExplanation")
        )
      }.getOrElse(NodeSeq.Empty)
    } &

    "#mandatoryTooltip *" #> {
      initMandatory.map { (b: Boolean) =>
        <span data-bs-toggle="tooltip" title="If this option is enabled, users will be forced to enter a change audit log. Empty messages will not be accepted.">
            <span class="fa fa-info-circle info"></span>
          </span>
      }.getOrElse(NodeSeq.Empty)
    } &

    "#explanationTooltip *" #> {
      initExplanation.map { (s: String) =>
        <span data-bs-toggle="tooltip" title="Content of the text displayed to prompt the user to enter a change audit log.">
            <span class="fa fa-info-circle info"></span>
          </span>
      }.getOrElse(NodeSeq.Empty)
    } &

    "#changeMessageSubmit " #> {
      SHtml.ajaxSubmit("Save changes", submit, ("class", "btn btn-success"))
    }) apply (xml ++ WithNonce.scriptWithNonce(Script(initJs(enabled))))
  }

  def cfserverNetworkConfiguration: NodeSeq => NodeSeq = { (xml: NodeSeq) =>
    //  initial values, updated on successfull submit
    var initDenyBadClocks = configService.cfengine_server_denybadclocks().toBox

    // form values
    var denyBadClocks = initDenyBadClocks.getOrElse(false)

    def submit() = {
      configService
        .set_cfengine_server_denybadclocks(denyBadClocks)
        .toBox
        .foreach(updateOk => initDenyBadClocks = Full(denyBadClocks))

      // start a promise generation, Since we check if there is change to save, if we got there it mean that we need to redeploy
      startNewPolicyGeneration()
      check() & JsRaw("""createSuccessNotification("Security options correctly updated")""") // JsRaw ok, const
    }

    def noModif = (
      initDenyBadClocks.map(_ == denyBadClocks).getOrElse(false)
    )

    def check() = {
      Run(s"""$$("#cfserverNetworkSubmit").prop('disabled', ${noModif});""")
    }

    ("#denyBadClocks" #> {
      initDenyBadClocks match {
        case Full(value) =>
          SHtml.ajaxCheckbox(
            value,
            (b: Boolean) => { denyBadClocks = b; check() },
            ("id", "denyBadClocks")
          )
        case eb: EmptyBox =>
          val fail = eb ?~ "there was an error while fetching value of property: 'Deny Bad Clocks' "
          <div class="error">{fail.msg}</div>
      }
    } &

    "#denyBadClocksTooltip *" #> {

      initDenyBadClocks match {
        case Full(_) =>
          <span data-bs-toggle="tooltip" title="By default, copying configuration policy to nodes requires system clocks to be synchronized to within an hour. Disabling this will bypass this check, but may open a window for replay attacks.">
              <span class="fa fa-info-circle info"></span>
            </span>

        case _ => NodeSeq.Empty
      }
    } &

    "#cfserverNetworkSubmit " #> {
      SHtml.ajaxSubmit("Save changes", submit, ("class", "btn btn-success space-top"))
    }) apply (xml ++ WithNonce.scriptWithNonce(Script(check())))
  }

  def relaySynchronizationMethodManagement: NodeSeq => NodeSeq = { (xml: NodeSeq) =>
    //  initial values, updated on successfull submit
    var initRelaySyncMethod      = configService.relay_server_sync_method().toBox
    // Be careful, we store negative value
    var initRelaySyncPromises    = configService.relay_server_syncpromises().toBox
    var initRelaySyncSharedFiles = configService.relay_server_syncsharedfiles().toBox

    // form values
    var relaySyncMethod      = initRelaySyncMethod.getOrElse(Classic)
    var relaySyncPromises    = initRelaySyncPromises.getOrElse(false)
    var relaySyncSharedFiles = initRelaySyncSharedFiles.getOrElse(false)

    def noModif = (
      initRelaySyncMethod.map(_ == relaySyncMethod).getOrElse(false)
        && initRelaySyncPromises.map(_ == relaySyncPromises).getOrElse(false)
        && initRelaySyncSharedFiles.map(_ == relaySyncSharedFiles).getOrElse(false)
    )

    def check() = {
      Run(s""" $$("#relaySynchronizationSubmit").prop('disabled', ${noModif});""".stripMargin)
    }

    def submit() = {
      configService
        .set_relay_server_sync_method(relaySyncMethod)
        .toBox
        .foreach(updateOk => initRelaySyncMethod = Full(relaySyncMethod))
      configService
        .set_relay_server_syncpromises(relaySyncPromises)
        .toBox
        .foreach(updateOk => initRelaySyncPromises = Full(relaySyncPromises))
      configService
        .set_relay_server_syncsharedfiles(relaySyncSharedFiles)
        .toBox
        .foreach(updateOk => initRelaySyncSharedFiles = Full(relaySyncSharedFiles))

      // start a promise generation, Since we check if there is change to save, if we got there it mean that we need to redeploy
      startNewPolicyGeneration()
      check() & JsRaw(
        """createSuccessNotification("Relay servers synchronization methods correctly updated")"""
      ) // JsRaw ok, const
    }

    def setRelaySyncMethodJs(input: String): JsCmd = {
      RelaySynchronizationMethod.parse(input) match {
        case Full(method) =>
          relaySyncMethod = method
          method match {
            case Rsync              => JsRaw(""" $('#relayRsyncSynchronizeFiles').show(); """) // JsRaw ok, const
            case Classic | Disabled => JsRaw(""" $('#relayRsyncSynchronizeFiles').hide(); """) // JsRaw ok, const
          }
        case eb: EmptyBox =>
          Noop
      }
    }
    (
      "#relaySyncMethod" #> {
        initRelaySyncMethod match {
          case Full(value) =>
            def radioHtml(method: RelaySynchronizationMethod): NodeSeq = {
              val label      = method.value
              val inputId    = label + "-id"
              val ajaxCall   = SHtml.ajaxCall(Str(""), _ => setRelaySyncMethodJs(label) & check())._2.toJsCmd
              val inputCheck = if (initRelaySyncMethod == method) {
                <input id={inputId} type="radio" name="relaySync" onclick={ajaxCall} checked=""/>
              } else {
                <input id={inputId} type="radio" name="relaySync" onclick={ajaxCall} />
              }
              <li class="rudder-form">
                  <div class="input-group">
                    <label class="input-group-text" for={inputId}>
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
              RelaySynchronizationMethod.values.map(radioHtml)
            }
              </ul>: NodeSeq) ++ WithNonce.scriptWithNonce(Script(OnLoad(setRelaySyncMethodJs(value.value))))
          case eb: EmptyBox =>
            val fail = eb ?~ "there was an error while fetching value of property: 'Synchronize Policies using rsync' "
            <div class="error">{fail.msg}</div>
        }

      } &
      "#relaySyncPromises" #> {
        initRelaySyncPromises match {
          case Full(value) =>
            SHtml.ajaxCheckbox(
              value,
              (b: Boolean) => { relaySyncPromises = b; check() },
              ("id", "relaySyncPromises")
            )
          case eb: EmptyBox =>
            val fail = eb ?~ "there was an error while fetching value of property: 'Synchronize Policies using rsync' "
            <div class="error">{fail.msg}</div>
        }
      } &
      "#relaySyncPromisesTooltip *" #> {

        initRelaySyncPromises match {
          case Full(_) =>
            <span data-bs-toggle="tooltip" title="If this is checked, when rsync synchronization method is used, folder /var/rudder/share will be synchronized using rsync.
              If this is not checked, you'll have to synchronize yourself this folder">
              <span><span class="fa fa-info-circle info"></span></span>
            </span>

          case _ => NodeSeq.Empty
        }
      } &
      "#relaySyncSharedFiles" #> {
        initRelaySyncSharedFiles match {
          case Full(value) =>
            SHtml.ajaxCheckbox(
              value,
              (b: Boolean) => { relaySyncSharedFiles = b; check() },
              ("id", "relaySyncSharedFiles")
            )
          case eb: EmptyBox =>
            val fail = eb ?~ "there was an error while fetching value of property: 'Synchronize Shared Files using rsync' "
            <div class="error">{fail.msg}</div>
        }
      } &
      "#relaySyncSharedFilesTooltip *" #> {

        initRelaySyncSharedFiles match {
          case Full(_) =>
            <span data-bs-toggle="tooltip" title="If this is checked, when rsync synchronization method is used, folder /var/rudder/configuration-repository/shared-files will be synchronized using rsync.
              If this is not checked, you'll have to synchronize yourself this folder">
              <span><span class="fa fa-info-circle info"></span></span>
            </span>
          case _       => NodeSeq.Empty
        }
      } &
      "#relaySynchronizationSubmit " #> {
        SHtml.ajaxSubmit("Save changes", submit, ("class", "btn btn-success"))
      }
    ) apply (xml ++ WithNonce.scriptWithNonce(Script(check())))
  }

  val agentScheduleEditForm = new AgentScheduleEditForm(
    () => getSchedule(),
    saveSchedule,
    () => startNewPolicyGeneration()
  )

  val complianceModeEditForm:  ComplianceModeEditForm[GlobalComplianceMode] = {
    val globalMode = configService.rudder_compliance_mode().toBox
    new ComplianceModeEditForm[GlobalComplianceMode](
      globalMode,
      (complianceMode) => {
        configService.set_rudder_compliance_mode(complianceMode, CurrentUser.actor, genericReasonMessage).toBox
      },
      () => startNewPolicyGeneration(),
      globalMode
    )
  }
  val agentPolicyModeEditForm: AgentPolicyModeEditForm                      = {
    new AgentPolicyModeEditForm()
  }
  def getSchedule():           Box[AgentRunInterval]                        = {
    for {
      starthour <- configService.agent_run_start_hour()
      startmin  <- configService.agent_run_start_minute()
      splaytime <- configService.agent_run_splaytime()
      interval  <- configService.agent_run_interval()
    } yield {
      AgentRunInterval(
        None,
        interval,
        startmin,
        starthour,
        splaytime
      )
    }
  }.toBox

  def saveSchedule(schedule: AgentRunInterval): Box[Unit] = {

    val actor = CurrentUser.actor
    for {
      _ <- configService.set_agent_run_interval(schedule.interval, actor, genericReasonMessage)
      _ <- configService.set_agent_run_start_hour(schedule.startHour, actor, genericReasonMessage)
      _ <- configService.set_agent_run_start_minute(schedule.startMinute, actor, genericReasonMessage)
      _ <- configService.set_agent_run_splaytime(schedule.splaytime, actor, genericReasonMessage)
    } yield {
      logger.info(
        s"Agent schedule updated to run interval: ${schedule.interval} min, start time: ${schedule.startHour} h ${schedule.startMinute} min, splaytime: ${schedule.splaytime} min"
      )
    }
  }.toBox

  def cfagentScheduleConfiguration = agentScheduleEditForm.cfagentScheduleConfiguration
  def agentPolicyModeConfiguration: NodeSeq = agentPolicyModeEditForm.cfagentPolicyModeConfiguration(None)
  def complianceModeConfiguration = complianceModeEditForm.complianceModeConfiguration

  def cfengineGlobalProps: NodeSeq => NodeSeq = { (xml: NodeSeq) =>
    //  initial values, updated on successful submit
    var initModifiedFilesTtl = configService.cfengine_modified_files_ttl().toBox
    // form values
    var modifiedFilesTtl     = initModifiedFilesTtl.getOrElse(30).toString

    def submit() = {
      // first, check if the content are effectively Int
      try {
        val intModifiedFilesTtl = Integer.parseInt(modifiedFilesTtl)
        configService
          .set_cfengine_modified_files_ttl(intModifiedFilesTtl)
          .toBox
          .foreach(updateOk => initModifiedFilesTtl = Full(intModifiedFilesTtl))
        // start a promise generation, Since we check if there is change to save, if we got there it mean that we need to redeploy
        startNewPolicyGeneration()
        check() & JsRaw("""createSuccessNotification("File retention settings correctly updated")""") // JsRaw ok, const
      } catch {
        case ex: NumberFormatException =>
          Noop & JsRaw(
            s"""createErrorNotification("Invalid value ${ex.getMessage().replaceFirst("F", "f")})"""
          ) // JsRaw ok, no user input
      }
    }

    def noModif = (
      initModifiedFilesTtl.map(_.toString == modifiedFilesTtl).getOrElse(false)
    )

    def check() = {
      Run(s"""$$("#cfengineGlobalPropsSubmit").attr("disabled",${noModif});""")
    }

    ("#modifiedFilesTtl" #> {
      initModifiedFilesTtl match {
        case Full(value) =>
          SHtml.ajaxText(
            value.toString,
            (s: String) => { modifiedFilesTtl = s; check() },
            ("id", "modifiedFilesTtl"),
            ("class", "form-control number-day"),
            ("type", "number")
          )
        case eb: EmptyBox =>
          val fail = eb ?~ "there was an error while fetching value of property: 'Modified files TTL' "
          <div class="error">{fail.msg}</div>
      }
    } &
    "#cfengineGlobalPropsSubmit " #> {
      SHtml.ajaxSubmit("Save changes", submit, ("class", "btn btn-success"))
    }) apply (xml ++ WithNonce.scriptWithNonce(Script(check())))
  }

  def loggingConfiguration: NodeSeq => NodeSeq = { (xml: NodeSeq) =>
    //  initial values, updated on successfull submit
    var initCfengineOutputsTtl = configService.cfengine_outputs_ttl().toBox
    // form values
    var cfengineOutputsTtl     = initCfengineOutputsTtl.getOrElse(7).toString

    def submit() = {
      try {
        val intCfengineOutputsTtl = Integer.parseInt(cfengineOutputsTtl)
        configService
          .set_cfengine_outputs_ttl(intCfengineOutputsTtl)
          .toBox
          .foreach(updateOk => initCfengineOutputsTtl = Full(intCfengineOutputsTtl))

      } catch {
        case ex: NumberFormatException =>
          Noop & JsRaw(
            s"""createErrorNotification("Invalid value ${ex.getMessage().replaceFirst("F", "f")})"""
          ) // JsRaw ok, no user inputs
      }

      // start a promise generation, Since we check if there is change to save, if we got there it mean that we need to redeploy
      startNewPolicyGeneration()
      check() & JsRaw(s"""createSuccessNotification("'Agent log files duration' property updated.")""") // JsRaw ok, const
    }

    def noModif = (
      initCfengineOutputsTtl.map(_.toString == cfengineOutputsTtl).getOrElse(false)
    )

    def check() = {
      Run(s"""$$("#loggingConfigurationSubmit").attr("disabled",${noModif});""")
    }

    ("#cfengineOutputsTtl" #> {
      initCfengineOutputsTtl match {
        case Full(value) =>
          SHtml.ajaxText(
            value.toString,
            (s: String) => { cfengineOutputsTtl = s; check() },
            ("id", "cfengineOutputsTtl"),
            ("class", "form-control number-day"),
            ("type", "number")
          )
        case eb: EmptyBox =>
          val fail = eb ?~ "there was an error while fetching value of property: 'CFEngine Outputs TTL' "
          <div class="error">{fail.msg}</div>
      }
    } &
    "#loggingConfigurationSubmit " #> {
      SHtml.ajaxSubmit("Save changes", submit, ("class", "btn btn-success"))
    }) apply (xml ++ WithNonce.scriptWithNonce(Script(check())))
  }

  def addDisabled(disabled: Boolean): NodeSeq => NodeSeq = {
    if (disabled) "* [disabled]" #> "disabled"
    else PassThru
  }

  /*
   * Get the set of perm for the property given "isexec":
   * - isExec == true =>  rwxr-xr-x
   * - isExec == false => rw-r--r--
   */
  def getHookPerm(isExec: Boolean): Set[PosixFilePermission] = {
    // property file should be either rwxr--r-- or rw-r--r--
    import java.nio.file.attribute.PosixFilePermission.*
    val perms = Set(OWNER_READ, OWNER_WRITE, GROUP_READ, OTHERS_READ)
    if (isExec) perms + OWNER_EXECUTE + GROUP_EXECUTE + OTHERS_EXECUTE
    else perms
  }

  def generationHookCfpromise: NodeSeq => NodeSeq = { (xml: NodeSeq) =>
    {
      import better.files.*
      val hook = File("/opt/rudder/etc/hooks.d/policy-generation-node-ready/10-cf-promise-check")

      val disabled  = !(hook.exists() && hook.isWritable)
      val isEnabled = hook.isExecutable

      var initIsEnabled    = isEnabled
      var currentIsEnabled = isEnabled
      def noModif()        = initIsEnabled == currentIsEnabled
      def check()          = {
        S.notice("generationHookCfpromiseMsg", "")
        Run(s"""$$("#generationHookCfpromiseSubmit").attr("disabled",${noModif()});""")
      }
      def submit()         = {
        // exec must be set/unset for all users
        val save = {
          try {
            hook.setPermissions(getHookPerm(currentIsEnabled))
            Right(())
          } catch {
            case ex: Exception => Left(ex)
          }
        }
        S.notice(
          "generationHookCfpromiseMsg",
          save match {
            case Right(()) =>
              initIsEnabled = currentIsEnabled
              Text("'check generated policies' property updated")
            case Left(ex)  =>
              <span class="error">There was an error when updating the value of the 'check generated policies' property: {
                ex.getMessage
              }</span>
          }
        )
        check()
      }

      ("#generationHookCfpromiseCheckbox" #> {
        addDisabled(disabled)(
          SHtml.ajaxCheckbox(
            isEnabled,
            (b: Boolean) => { currentIsEnabled = b; check() },
            ("id", "generationHookCfpromiseCheckbox")
          )
        )
      } &
      "#generationHookCfpromiseSubmit " #> {
        SHtml.ajaxSubmit("Save changes", submit, ("class", "btn btn-success"))
      } &
      "#generationHookCfpromiseSubmit *+" #> {
        WithNonce.scriptWithNonce(Script(check()))
      }) apply (xml)
    }
  }

  def generationHookTriggerNodeUpdate: NodeSeq => NodeSeq = {
    import better.files.*
    type Result[T] = Either[String, T]
    val hookPath = "/opt/rudder/etc/hooks.d/policy-generation-finished/60-trigger-node-update"
    val propPath = hookPath + ".properties"
    final case class TriggerProp(maxNodes: Result[Int], percent: Result[Int])
    object TriggerProp {
      val MAX_NODES    = "MAX_NODES"
      val NODE_PERCENT = "NODE_PERCENT"
      val propRegex    = """^(\w+)=(.*)$""".r
    }

    def findProp(path: String, name: String, lines: Seq[String]): Result[Int] = {
      val prop = lines.collect { case TriggerProp.propRegex(n, v) if (name == n) => v }
      prop.headOption match {
        case Some(i) =>
          try {
            Right(i.toInt)
          } catch {
            case ex: NumberFormatException => Left(s"Error: property '${name}' must be an int but is: '${i}'")
          }
        case None    => Left(s"Property '${name}' was not found in hook property file '${path}'")
      }
    }

    def readProp(): TriggerProp = {
      (try { Right(File(propPath).lineIterator(using StandardCharsets.UTF_8).toVector) }
      catch {
        case ex: Exception =>
          Left(s"Error when trying to read properties for hook in '${propPath}': ${ex.getClass.getSimpleName}: ${ex.getMessage}")
      }).fold(
        msg => TriggerProp(Left(msg), Left(msg)),
        lines =>
          TriggerProp(findProp(propPath, TriggerProp.MAX_NODES, lines), findProp(propPath, TriggerProp.NODE_PERCENT, lines))
      )
    }

    def writeProp(maxNodes: Int, percent: Int): Result[Unit] = {
      try {
        val lines    = File(propPath).lineIterator(using StandardCharsets.UTF_8).toVector
        val replaced = lines.map {
          case l =>
            l match {
              case TriggerProp.propRegex(k, v) =>
                k match {
                  case TriggerProp.MAX_NODES    => s"${TriggerProp.MAX_NODES}=${maxNodes}"
                  case TriggerProp.NODE_PERCENT => s"${TriggerProp.NODE_PERCENT}=${percent}"
                  case _                        => l
                }
              case _                           => l
            }
        }
        File(propPath).writeText(replaced.mkString("\n"))(using better.files.File.OpenOptions.default, StandardCharsets.UTF_8)
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
        _ <- if (hook.exists() && hook.isWritable && hook.isRegularFile) { // ok
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

    (xml: NodeSeq) => {
      val hook      = File(hookPath)
      var hookProps = readProp()

      // initial errors
      (hookProps.maxNodes :: hookProps.percent :: Nil).map(_.swap.toOption).flatten.distinct match {
        case Nil => // nothing
        case seq => S.notice("generationHookTriggerNodeUpdateMsg", seq.mkString("; "))
      }

      // if the hook file is missing, display an error message
      hasError(hook, hookProps) match {
        case Some(error) =>
          ("#generationHookTriggerNodeUpdateForm" #> ("There was an error when trying to access the file. It can not be configured. " +
          s"Error was: ${error}")) apply (xml)
        case None        =>
          var isEnabled         = hook.isExecutable
          var currentIsEnabled  = isEnabled
          var currentMax        = hookProps.maxNodes.getOrElse(1000)
          var currentMaxStr     = currentMax.toString
          var currentPercent    = hookProps.percent.getOrElse(1000)
          var currentPercentStr = currentPercent.toString

          def noModif() = {
            isEnabled == currentIsEnabled && TriggerProp(Right(currentMax), Right(currentPercent)) == hookProps
          }
          def check()   = {
            val msg = scala.collection.mutable.Buffer[String]()
            try {
              currentMax = currentMaxStr.toInt
              if (currentMax < 0) {
                msg += "Error: max number of nodes must be positive"
              }
            } catch {
              case ex: NumberFormatException => msg += "Error: max number of node must be an int"
            }
            try {
              currentPercent = currentPercentStr.toInt
              if (currentPercent < 0 || currentPercent > 100) {
                msg += "Error: percent of nodes must be in interval [0,100]"
              }
            } catch {
              case ex: NumberFormatException => msg += "Error: percent of nodes must be an int"
            }
            S.notice("generationHookTriggerNodeUpdateMsg", <span class="error">{msg.mkString("; ")}</span>)
            Run(s"""$$("#generationHookTriggerNodeUpdateSubmit").attr("disabled",${noModif()});""")
          }

          def submit() = {
            S.notice(
              "generationHookTriggerNodeUpdateMsg",
              saveAll(hook, currentIsEnabled, currentMax, currentPercent) match {
                case Right(()) =>
                  isEnabled = currentIsEnabled
                  hookProps = TriggerProp(Right(currentMax), Right(currentPercent))
                  "'trigger node update' property updated"
                case Left(s)   =>
                  s"There was an error when updating the value of the 'trigger node update' property: ${s}"
              }
            )
            check()
          }

          ("#generationHookTriggerNodeUpdateCheckbox" #> {
            SHtml.ajaxCheckbox(
              isEnabled,
              (b: Boolean) => { currentIsEnabled = b; check() },
              ("id", "generationHookTriggerNodeUpdateCheckbox")
            )
          } &
          "#generationHookTriggerNodeUpdateMaxNode" #> {
            SHtml.ajaxText(
              currentMaxStr,
              (s: String) => { currentMaxStr = s; check() },
              ("id", "generationHookTriggerNodeUpdateMaxNode"),
              ("class", "form-control"),
              ("type", "number")
            )
          } &
          "#generationHookTriggerNodeUpdateRatio" #> {
            SHtml.ajaxText(
              currentPercentStr,
              (s: String) => { currentPercentStr = s; check() },
              ("id", "generationHookTriggerNodeUpdateRatio"),
              ("class", "form-control"),
              ("type", "number")
            )
          } &
          "#generationHookTriggerNodeUpdateSubmit " #> {
            SHtml.ajaxSubmit("Save changes", submit, ("class", "btn btn-success"))
          } &
          "#generationHookTriggerNodeUpdateSubmit *+" #> {
            WithNonce.scriptWithNonce(Script(check()))
          }) apply (xml)
      }
    }
  }

  def sendMetricsConfiguration: NodeSeq => NodeSeq = { (xml: NodeSeq) =>
    (configService.send_server_metrics().toBox match {
      case Full(value) =>
        var initSendMetrics    = value
        var currentSendMetrics = value
        def noModif()          = initSendMetrics == currentSendMetrics
        def check()            = {
          Run(s"""$$("#sendMetricsSubmit").attr("disabled", ${noModif()});""")
        }
        def submit()           = {
          val save               = configService.set_send_server_metrics(currentSendMetrics, CurrentUser.actor, genericReasonMessage).toBox
          val createNotification = save match {
            case Full(_) =>
              initSendMetrics = currentSendMetrics
              // start a promise generation, Since we may have change the mode, if we got there it mean that we need to redeploy
              startNewPolicyGeneration()
              JsRaw("""createSuccessNotification("'send server metrics' property updated")""") // JsRaw ok, const
            case eb: EmptyBox =>
              JsRaw(
                """createErrorNotification("There was an error when updating the value of the 'send server metrics' property")"""
              ) // JsRaw ok, const
          }
          check() & createNotification
        }

        ("#sendMetricsCheckbox" #> {
          SHtml.ajaxSelectElem(
            Seq(SendMetrics.CompleteMetrics, SendMetrics.MinimalMetrics, SendMetrics.NoMetrics),
            value,
            ("id", "sendMetricsCheckbox")
          )((v: SendMetrics) => { currentSendMetrics = Some(v); check() })

        } &
        "#sendMetricsSubmit " #> {
          SHtml.ajaxSubmit("Save changes", submit, ("class", "btn btn-success"))
        } &
        "#sendMetricsSubmit *+" #> {
          WithNonce.scriptWithNonce(Script(check()))
        })
      case eb: EmptyBox =>
        ("#sendMetrics" #> {
          val fail = eb ?~ "there was an error while fetching value of property: 'Send server metrics"
          logger.error(fail.messageChain)
          <div class="error">{fail.messageChain}</div>
        })
    }) apply (xml)
  }

  def displayGraphsConfiguration: NodeSeq => NodeSeq = { (xml: NodeSeq) =>
    ((configService.display_changes_graph().toBox, configService.rudder_ui_display_ruleComplianceColumns().toBox) match {
      case (Full(valueGraphs), Full(valueColumns)) =>
        var initDisplayGraphs     = valueGraphs
        var currentDisplayGraphs  = valueGraphs
        var initDisplayColumns    = valueColumns
        var currentDisplayColumns = valueColumns

        def noModif() = initDisplayGraphs == currentDisplayGraphs && initDisplayColumns == currentDisplayColumns

        def check() = {
          Run(s"""$$("#displayGraphsSubmit").attr("disabled",${noModif()});""")
        }

        def submit() = {
          val saveGraphs         = configService.set_display_changes_graph(currentDisplayGraphs).toBox
          val saveColumns        = configService.set_rudder_ui_display_ruleComplianceColumns(currentDisplayColumns).toBox
          val createNotification = (saveGraphs, saveColumns) match {
            case (Full(_), Full(_)) =>
              initDisplayGraphs = currentDisplayGraphs
              initDisplayColumns = currentDisplayColumns
              JsRaw("""createSuccessNotification("'Compliance display' properties updated")""") // JsRaw ok, const
            case (_, _)             =>
              JsRaw(
                """createErrorNotification("There was an error when updating the value of the 'display change graphs' property")"""
              ) // JsRaw ok, const
          }
          check() & createNotification
        }

        ("#displayGraphsCheckbox" #> {
          SHtml.ajaxCheckbox(
            valueGraphs,
            (b: Boolean) => { currentDisplayGraphs = b; check() },
            ("id", "displayGraphsCheckbox")
          )
        } &
        "#displayGraphsSubmit " #> {
          SHtml.ajaxSubmit("Save changes", submit, ("class", "btn btn-success"))
        } &
        "#displayColumnsCheckbox" #> {
          SHtml.ajaxCheckbox(
            valueColumns,
            (b: Boolean) => { currentDisplayColumns = b; check() },
            ("id", "displayColumnsCheckbox")
          )
        } &
        "#displayGraphsSubmit *+" #> {
          WithNonce.scriptWithNonce(Script(check()))
        })
      case (eb: EmptyBox, _)                       =>
        ("#displayGraphs" #> {
          val failGraphs = eb ?~ "there was an error while fetching value of 'Display changes graph' property"
          logger.error(failGraphs.messageChain)
          <div class="error">
            {failGraphs.messageChain}
          </div>
        })
      case (_, eb: EmptyBox)                       =>
        ("#displayGraphs" #> {
          val failColumns = eb ?~ "there was an error while fetching value of 'Display rule compliance columns' property"
          logger.error(failColumns.messageChain)
          <div class="error">
            {failColumns.messageChain}
          </div>
        })
    }) apply (xml ++ WithNonce.scriptWithNonce(Script(Run(s"""$$("#displayGraphsSubmit").attr("disabled",true);"""))))
  }

  def directiveScriptEngineConfiguration: NodeSeq => NodeSeq = { (xml: NodeSeq) =>
    import com.normation.rudder.domain.appconfig.FeatureSwitch.*

    (configService.rudder_featureSwitch_directiveScriptEngine().toBox match {
      case Full(initialValue) =>
        var initSavedValued = initialValue
        var x               = initialValue
        def noModif()       = x == initSavedValued
        def check()         = {
          Run(s"""$$("#directiveScriptEngineSubmit").attr("disabled",${noModif()});""")
        }

        def submit() = {
          val save               = configService.set_rudder_featureSwitch_directiveScriptEngine(x).toBox
          val createNotification = save match {
            case Full(_) =>
              initSavedValued = x
              // If we disable this feature we want to start policy generation because some data may be invalid
              if (x == Disabled) {
                startNewPolicyGeneration()
              }
              JsRaw(
                """createSuccessNotification("'directive script engine' property updated. The feature will be loaded as soon as you go to another page or reload this one.")"""
              ) // JsRaw ok, const
            case eb: EmptyBox =>
              JsRaw(
                """createErrorNotification("There was an error when updating the value of the 'directive script engine' property")"""
              ) // JsRaw ok, const
          }
          check() & createNotification
        }

        ("#directiveScriptEngineCheckbox" #> {
          SHtml.ajaxCheckbox(
            x == Enabled,
            (b: Boolean) => {
              if (b) { x = Enabled }
              else { x = Disabled }; check()
            },
            ("id", "directiveScriptEngineCheckbox")
          )
        } &
        "#directiveScriptEngineSubmit " #> {
          SHtml.ajaxSubmit("Save changes", submit, ("class", "btn btn-success"))
        } &
        "#directiveScriptEngineSubmit *+" #> {
          WithNonce.scriptWithNonce(Script(check()))
        })

      case eb: EmptyBox =>
        ("#directiveScriptEngine" #> {
          val fail = eb ?~ "there was an error while fetching value of property: 'directive script engine'"
          logger.error(fail.messageChain)
          <div class="error">{fail.messageChain}</div>
        })
    }) apply xml
  }

  def nodeOnAcceptDefaultsConfiguration: NodeSeq => NodeSeq = { (xml: NodeSeq) =>
    val modes  = SelectableOption[Option[PolicyMode]](None, "Use global value") :: PolicyMode.values.map { x =>
      SelectableOption[Option[PolicyMode]](Some(x), x.name.capitalize)
    }.toList
    // node states, sorted [init, enable, other]
    val states = NodeState.labeledPairs.map {
      case (x, label) =>
        SelectableOption[NodeState](x, S.?(label))
    }

    val process = (for {
      initialPolicyMode <- configService.rudder_node_onaccept_default_policy_mode().toBox
      initialNodeState  <- configService.rudder_node_onaccept_default_state().toBox
    } yield {

      // initialise values for the form (last time it was saved and current user input)
      var initPolicyMode = initialPolicyMode
      var initNodeState  = initialNodeState

      var policyMode = initialPolicyMode
      var state      = initialNodeState

      def noModif() = initPolicyMode == policyMode && initNodeState == state
      def check()   = {
        Run(s"""$$("#nodeOnAcceptDefaultsSubmit").attr("disabled",${noModif()});""")
      }

      def submit() = {
        val save = {
          for {
            _ <- configService.set_rudder_node_onaccept_default_state(state).toBox
            _ <- configService.set_rudder_node_onaccept_default_policy_mode(policyMode).toBox
          } yield {
            ()
          }
        }

        val createNotification = {
          save match {
            case Full(_) =>
              initNodeState = state
              initPolicyMode = policyMode
              JsRaw(
                """createSuccessNotification("Node default configuration post-acceptation correctly saved. Next accepted node will get these default configuration.")"""
              ) // JsRaw ok, const
            case eb: EmptyBox =>
              JsRaw("""createErrorNotification("There was an error when updating node default proerties")""") // JsRaw ok, const
          }
        }
        check() & createNotification
      }

      "#nodeOnAcceptState" #> SHtml.ajaxSelectObj(
        states,
        Full(initNodeState),
        { (x: NodeState) => state = x; check() },
        ("id", "nodeOnAcceptState")
      ) &
      "#nodeOnAcceptPolicyMode" #> SHtml.ajaxSelectObj(
        modes,
        Full(initPolicyMode),
        { (x: Option[PolicyMode]) => policyMode = x; check() },
        ("id", "nodeOnAcceptPolicyMode")
      ) &
      "#nodeOnAcceptDefaultsSubmit" #> {
        SHtml.ajaxSubmit("Save changes", submit, ("class", "btn btn-success"))
      } &
      "#nodeOnAcceptDefaultsSubmit *+" #> {
        WithNonce.scriptWithNonce(Script(check()))
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
}
