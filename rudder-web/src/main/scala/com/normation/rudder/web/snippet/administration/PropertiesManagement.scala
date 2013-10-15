/*
*************************************************************************************
* Copyright 2013 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
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


class PropertiesManagement extends DispatchSnippet with Loggable {

  private[this] val configService : ReadConfigService with UpdateConfigService = RudderConfig.configService

  def dispatch = {
    case "changeMessage" => changeMessageConfiguration
    case "workflow"      => workflowConfiguration
  }


  def changeMessageConfiguration = { xml : NodeSeq =>


    // initial values
    var initEnabled = configService.rudder_ui_changeMessage_enabled
    var initMandatory = configService.rudder_ui_changeMessage_mandatory
    var initExplanation = configService.rudder_ui_changeMessage_explanation

    // mutable, default values won't be used (if error in property => edit form is not displayed)
    var enabled = initEnabled.getOrElse(false)
    var mandatory = configService.rudder_ui_changeMessage_mandatory.getOrElse(false)
    var explanation = configService.rudder_ui_changeMessage_explanation.getOrElse("Please enter a message explaining the reason for this change.")

    def submit = {

      // Save new value
      configService.set_rudder_ui_changeMessage_enabled(enabled).
        // If update is sucessful update the initial value used by the form
        foreach(updateOk => initEnabled = Full(enabled))

      configService.set_rudder_ui_changeMessage_mandatory(mandatory).foreach(updateOk => initMandatory = Full(mandatory))

      configService.set_rudder_ui_changeMessage_explanation(explanation).foreach(updateOk => initExplanation = Full(explanation))

      S.notice("updateChangeMsg","Change message configuration correctly updated")
      check()
    }

    // Check if there is no modification
    // Ignore error ones so we can still modify those not in error)
    def noModif = (
         initEnabled.map(_ == enabled).getOrElse(false)
      && initMandatory.map(_ == mandatory).getOrElse(false)
      && initExplanation.map(_ == explanation).getOrElse(false)
    )

    // Check that there is some modification to enabled/disable save
    def check() = {
      S.notice("updateChangeMsg","")
      Run(s"""$$("#changeMessageSubmit").button( "option", "disabled",${noModif});""")
    }

    // Initialisation of form
    // Determine if some fields should be disabled.
    def initJs(newStatus :Boolean) = {
      enabled = newStatus
      check() &
      Run(
        s"""
            $$("#mandatory").prop("disabled",${!newStatus});
            $$("#explanation").prop("disabled",${!newStatus});"""
      )
    }


    // Rendering
    ( "#enabled" #> {
        initEnabled match {
          case Full(value) =>
            SHtml.ajaxCheckbox(
                value
              , initJs _
              , ("id","enabled")
              , ("class","twoCol")
            )
          case eb: EmptyBox =>
            val fail = eb ?~ "there was an error, while fetching value of property: 'Enable change message' "
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
             $$("#changeMessageSubmit").button( "option", "disabled",noModif);""")
        }
        initExplanation match {
          case Full(value) =>


            SHtml.ajaxText(
                value
              , (s : String) => { explanation = s; check() }
              , ("id","explanation")
              , ("class","twoCol")
              , ("style","width:30%;")
              , ("onkeydown",ajaxCall("checkExplanation", checkExplanation(value) ).toJsCmd)
            )
          case eb: EmptyBox =>
            val fail = eb ?~ "there was an error, while fetching value of property: 'Explanation to display "
            <div class="error">{fail.msg}</div>
        }
      } &

      "#restoreExplanation " #> {
        initExplanation.map{ s:String =>
          ajaxButton(<span>Reset to default</span>, () => { explanation = "Please enter a message explaining the reason for this change."
            Run("""$("#explanation").val("Please enter a message explaining the reason for this change.");""") & check()

            }  ,("class","defaultButton"), ("id","restoreExplanation"))
        }.getOrElse(NodeSeq.Empty)
      } &

      "#mandatoryTooltip *" #> {
        initMandatory.map{ b:Boolean =>
          val tooltipid = Helpers.nextFuncName
          <span class="tooltipable" tooltipid={tooltipid} title="">
            <img   src="/images/icInfo.png" style="padding-left:15px; margin:0;"/>
          </span>
          <div class="tooltipContent" id={tooltipid}>
            If this option is enabled, users will be forced to enter a change message. Empty messages will not be accepted.
          </div>
        }.getOrElse(NodeSeq.Empty)
      } &

      "#explanationTooltip *" #> {
        initExplanation.map{ s:String =>
          val tooltipid = Helpers.nextFuncName
          <span class="tooltipable" tooltipid={tooltipid} title="">
            <img   src="/images/icInfo.png" style="padding-left:15px; margin:0;"/>
          </span>
          <div class="tooltipContent" id={tooltipid}>
            Content of the text displayed to prompt the user to enter a change message.
          </div>
        }.getOrElse(NodeSeq.Empty)
      } &

      "#changeMessageSubmit " #> {
         SHtml.ajaxSubmit("Save changes", submit _)
      }
    ) apply (xml ++ Script(Run("correctButtons();") & initJs(enabled)))
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
      S.notice("updateWorkflow","")
      Run(s"""$$("#workflowSubmit").button( "option", "disabled",${noModif});""")
    }
    def initJs(newStatus :Boolean) = {
      enabled = newStatus
      check() &
      Run(
        s"""
            $$("#selfVal").prop("disabled",${!newStatus});
            $$("#selfDep").prop("disabled",${!newStatus});"""
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
              <img   src="/images/icInfo.png" style="padding-left:15px; margin:0;"/>
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
              <img   src="/images/icInfo.png" style="padding-left:15px; margin:0;"/>
            </span>
            <div class="tooltipContent" id={tooltipid}>
              Allow users to deploy Change Requests they created themselves? Deploying is effectively applying a Change Request in the "<b>Pending deployment</b>" status.
            </div>
          case _ => NodeSeq.Empty
        }
      } &

      "#workflowSubmit " #> {
         SHtml.ajaxSubmit("Save changes", submit _)
      }
    ) apply (xml ++ Script(Run("correctButtons();") & initJs(enabled)))
  }


}


