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
package com.normation.rudder.web.components.popup

import bootstrap.liftweb.RudderConfig
import com.normation.GitVersion
import com.normation.errors.IOResult
import com.normation.errors.PureResult
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.InventoryError.Inconsistency
import com.normation.rudder.domain.properties.AddGlobalParameterDiff
import com.normation.rudder.domain.properties.ChangeRequestGlobalParameterDiff
import com.normation.rudder.domain.properties.DeleteGlobalParameterDiff
import com.normation.rudder.domain.properties.GenericProperty
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.InheritMode
import com.normation.rudder.domain.properties.ModifyToGlobalParameterDiff
import com.normation.rudder.domain.properties.Visibility
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.services.workflows.ChangeRequestService
import com.normation.rudder.services.workflows.GlobalParamChangeRequest
import com.normation.rudder.services.workflows.GlobalParamModAction
import com.normation.rudder.services.workflows.WorkflowService
import com.normation.rudder.users.CurrentUser
import com.normation.rudder.web.model.*
import com.normation.zio.UnsafeRun
import com.typesafe.config.ConfigValue
import com.typesafe.config.ConfigValueType
import java.time.Instant
import net.liftweb.common.*
import net.liftweb.http.*
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.FieldError
import net.liftweb.util.Helpers.*
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import scala.xml.*
import zio.syntax.*

class CreateOrUpdateGlobalParameterPopup(
    change:            GlobalParamChangeRequest,
    onSuccessCallback: (Either[GlobalParameter, ChangeRequestId], WorkflowService, String) => JsCmd,
    onFailureCallback: () => JsCmd = { () => Noop }
) extends DispatchSnippet with Loggable {

  private val workflowLevelService = RudderConfig.workflowLevelService
  private val userPropertyService  = RudderConfig.userPropertyService
  private[this] val uuidGen        = RudderConfig.stringUuidGenerator
  implicit private val qc: QueryContext = CurrentUser.queryContext // bug https://issues.rudder.io/issues/26605
  private val actor:       EventActor   = CurrentUser.actor
  private val contextPath: String       = S.contextPath

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = { case "popupContent" => { _ => popupContent() } }

  /* Text variation for
   * - Global Parameter
   * - Create, delete, modify (save)
   */
  private def titles = change.action match {
    case GlobalParamModAction.Delete => "Delete a global property"
    case GlobalParamModAction.Update => "Update a global property"
    case GlobalParamModAction.Create => "Add a global property"
  }

  private val workflowEnabled = workflowLevelService.getWorkflowService().needExternalValidation()
  private val titleWorkflow   = workflowEnabled match {
    case true  =>
      <h4 class="col-xl-12 col-md-12 col-sm-12 audit-title">Change Request</h4>
              <hr class="css-fix"/>
              <div class="text-center alert alert-info">
                <span class="fa fa-info-circle"></span>
                Workflows are enabled, your change has to be validated in a Change request
              </div>
    case false => NodeSeq.Empty
  }

  private def globalParamDiffFromAction(newParameter: GlobalParameter): IOResult[ChangeRequestGlobalParameterDiff] = {
    change.previousGlobalParam match {
      case None    =>
        if ((change.action == GlobalParamModAction.Update) || (change.action == GlobalParamModAction.Create))
          AddGlobalParameterDiff(newParameter).succeed
        else
          Inconsistency(s"Action ${change.action.name} is not possible on a new global property").fail
      case Some(d) =>
        change.action match {
          case GlobalParamModAction.Delete                               => DeleteGlobalParameterDiff(d).succeed
          case GlobalParamModAction.Update | GlobalParamModAction.Create => ModifyToGlobalParameterDiff(newParameter).succeed
        }
    }
  }

  private def parseValue(value: String, jsonRequired: Boolean): PureResult[ConfigValue] = {
    import GenericProperty.*
    for {
      // in case of string, we need to force parse as string
      v <- if (jsonRequired) GenericProperty.parseValue(value) else Right(value.toConfigValue)
      _ <- if (jsonRequired && v.valueType() == ConfigValueType.STRING) {
             Left(Inconsistency("JSON check is enabled, but the value format is invalid."))
           } else Right(())
    } yield {
      v
    }
  }

  private def onSubmit()(implicit qc: QueryContext): JsCmd = {
    if (formTracker.hasErrors) {
      onFailure
    } else {
      val jsonCheck = parameterFormat.get match {
        case "json" => true
        case _      => false
      }

      val savedChangeRequest = {
        for {
          value           <- parseValue(parameterValue.get, jsonCheck).toIO
          param            = GlobalParameter(
                               parameterName.get,
                               GitVersion.DEFAULT_REV,
                               value,
                               InheritMode.parseString(parameterInheritMode.get).toOption,
                               parameterDescription.get,
                               None,
                               Visibility.default
                             )
          diff            <- globalParamDiffFromAction(param)
          cr               = ChangeRequestService.createChangeRequestFromGlobalParameter(
                               changeRequestName.get,
                               paramReasons.map(_.get).getOrElse(""),
                               param,
                               change.previousGlobalParam,
                               diff,
                               CurrentUser.actor,
                               paramReasons.map(_.get)
                             )
          workflowService <- workflowLevelService.getForGlobalParam(actor, change)
          id              <- workflowLevelService
                               .getWorkflowService()
                               .startWorkflow(cr)(
                                 ChangeContext(
                                   ModificationId(uuidGen.newUuid),
                                   qc.actor,
                                   Instant.now(),
                                   paramReasons.map(_.get),
                                   None,
                                   qc.nodePerms
                                 )
                               )

        } yield {
          if (workflowEnabled) {
            closePopup() & onSuccessCallback(Right(id), workflowService, contextPath)
          } else {
            closePopup() & onSuccessCallback(Left(param), workflowService, contextPath)
          }
        }
      }

      savedChangeRequest
        .chainError("An error occurred while updating the parameter")
        .either
        .runNow match {
        case Right(jsCmd) => jsCmd
        case Left(err)    =>
          logger.error(err.fullMsg)
          formTracker.addFormError(error(err.fullMsg))
          onFailure
      }
    }
  }

  private def onFailure(implicit qc: QueryContext): JsCmd = {
    formTracker.addFormError(error("The form contains some errors, please correct them"))
    updateFormClientSide()
  }

  private def closePopup(): JsCmd = {
    JsRaw("""hideBsModal('createGlobalParameterPopup');""") // JsRaw ok, const
  }

  /**
   * Update the form when something happened
   */
  private def updateFormClientSide()(implicit qc: QueryContext): JsCmd = {
    SetHtml(CreateOrUpdateGlobalParameterPopup.htmlId_popupContainer, popupContent())
  }

  private def updateAndDisplayNotifications(formTracker: FormTracker): NodeSeq = {
    val notifications = formTracker.formErrors
    formTracker.cleanErrors

    if (notifications.isEmpty) {
      NodeSeq.Empty
    } else {
      <div id="notifications" class="alert alert-danger text-center col-xl-12 col-sm-12 col-md-12" role="alert">
        <ul class="text-danger">{notifications.map(n => <li>{n}</li>)}</ul>
      </div>
    }
  }

  ////////////////////////// fields for form ////////////////////////

  private val parameterName = new WBTextField("Name", change.previousGlobalParam.map(_.name).getOrElse("")) {
    override def setFilter      = notNull _ :: trim _ :: Nil
    override def errorClassName = "col-xl-12 errors-container"
    override def inputField     = (change.previousGlobalParam match {
      case Some(entry) => super.inputField % ("disabled" -> "true")
      case None        => super.inputField
    }) % ("onkeydown" -> "return processKey(event , 'createParameterSaveButton')") % ("tabindex" -> "1")
    override def validations    = {
      valMinLen(1, "The name must not be empty") _ :: Nil
    }
  }

  // The value may be empty
  private val parameterFormat = {

    val defaultMode = change.previousGlobalParam.map { p =>
      if (p.value.valueType() == ConfigValueType.STRING) "string" else "json"
    }.getOrElse("string")

    // in delete mode, only show the actual value used by the prop
    val l = change.action match {
      case GlobalParamModAction.Delete => Seq(defaultMode)
      case _                           => Seq("string", "json")
    }

    new WBRadioField(
      "Format",
      l,
      defaultMode,
      {
        case "string" => <span class="audit-btn" > String </span>
        case "json"   => <span class="global-btn"> JSON   </span>
        case _        => NodeSeq.Empty
      }
    ) {
      override def setFilter = notNull _ :: trim _ :: Nil
      override def className = "checkbox-group"
    }
  }

  // The value may be empty
  private val parameterValue = {
    new WBTextAreaField("Value", change.previousGlobalParam.map(p => p.valueAsString).getOrElse("")) {
      override def setFilter      = trim _ :: Nil
      override def inputField     = (change.action match {
        case GlobalParamModAction.Delete => super.inputField % ("disabled" -> "true")
        case _                           => super.inputField
      }) % ("style" -> "height:4em") % ("tabindex" -> "2")
      override def errorClassName = "col-xl-12 errors-container"
      override def validations: List[String => List[FieldError]] = Nil
    }
  }

  private val parameterDescription = {
    new WBTextAreaField("Description", change.previousGlobalParam.map(_.description).getOrElse("")) {
      override def setFilter      = notNull _ :: trim _ :: Nil
      override def inputField     = (change.action match {
        case GlobalParamModAction.Delete => super.inputField % ("disabled" -> "true")
        case _                           => super.inputField
      }) % ("tabindex" -> "3")
      override def errorClassName = "col-xl-12 errors-container"
      override def validations: List[String => List[FieldError]] = Nil
    }
  }

  private val parameterInheritMode = {
    new WBTextField("Inherit Mode", change.previousGlobalParam.flatMap(_.inheritMode.map(_.value)).getOrElse("")) {
      override val maxLen         = 3
      override def setFilter      = trim _ :: Nil
      override def inputField     = ((change.action match {
        case GlobalParamModAction.Delete => super.inputField % ("disabled" -> "true")
        case _                           => super.inputField
      }) % ("tabindex" -> "4"))
      override def errorClassName = "col-xl-12 errors-container"
      override def validations: List[String => List[FieldError]] = Nil
      override val helpAsHtml:  Box[NodeSeq]                     = Full(
        <div class="text-muted small">Define inheritance behavior for the value with 3 chars: first for
      json object (m=merge, o=override), 2nd for array and 3rd for string (o=override, a=append, p=prepend). Default to 'moo'.</div>
      )
    }
  }

  private def defaultClassName = change.action match {
    case GlobalParamModAction.Update => "btn-success"
    case GlobalParamModAction.Create => "btn-success"
    case GlobalParamModAction.Delete => "btn-danger"
  }

  private val defaultRequestName =
    s"${change.action.name.capitalize} Global Parameter " + change.previousGlobalParam.map(_.name).getOrElse("")
  private val changeRequestName  = new WBTextField("Change request title", defaultRequestName) {
    override def setFilter      = notNull _ :: trim _ :: Nil
    override def errorClassName = "col-xl-12 errors-container"
    override def inputField     =
      super.inputField % ("onkeydown" -> "return processKey(event , 'createDirectiveSaveButton')") % ("tabindex" -> "5")
    override def validations =
      valMinLen(1, "Name must not be empty") _ :: Nil
  }

  val parameterOverridable = true

  private val paramReasons = {
    import com.normation.rudder.config.ReasonBehavior.*
    userPropertyService.reasonsFieldBehavior match {
      case Disabled  => None
      case Mandatory => Some(buildReasonField(true, "px-1"))
      case Optional  => Some(buildReasonField(false, "px-1"))
    }
  }

  def buildReasonField(mandatory: Boolean, containerClass: String = "twoCol"): WBTextAreaField = {
    new WBTextAreaField("Change audit message", "") {
      override def setFilter  = notNull _ :: trim _ :: Nil
      override def inputField = super.inputField %
        ("style" -> "height:5em;") % ("tabindex" -> "6") % ("placeholder" -> { userPropertyService.reasonsFieldExplanation })
      override def errorClassName = "col-xl-12 errors-container"
      override def validations    = {
        if (mandatory) {
          valMinLen(5, "The reason must have at least 5 characters.") _ :: Nil
        } else {
          Nil
        }
      }
    }
  }

  private val formTracker = {
    val fields = parameterName :: parameterValue :: paramReasons.toList ::: {
      if (workflowEnabled) changeRequestName :: Nil
      else Nil
    }
    new FormTracker(fields)
  }

  private def error(msg: String) = Text(msg)

  def popupContent()(implicit qc: QueryContext): NodeSeq = {
    val (buttonName, classForButton) = workflowEnabled match {
      case true  =>
        ("Open Request", "wideButton btn-primary")
      case false => (change.action.name.capitalize, defaultClassName)
    }

    (
      "#title *" #> titles &
      ".name" #> parameterName.toForm_! &
      ".value" #> parameterValue.toForm_! &
      ".format" #> parameterFormat.toForm_! &
      ".description *" #> parameterDescription.toForm_! &
      ".inheritMode *" #> parameterInheritMode.toForm_! &
      "#titleWorkflow *" #> titleWorkflow &
      "#changeRequestName" #> {
        if (workflowEnabled) {
          changeRequestName.toForm
        } else {
          Full(NodeSeq.Empty)
        }
      } &
      "#delete *" #> {
        if (change.action == GlobalParamModAction.Delete) {
          <div class="row">
        </div>
        } else {
          NodeSeq.Empty
        }
      } &
      ".itemReason *" #> {
        // if (buttonName=="Delete")
        paramReasons.map { f =>
          <div>
          {
            if (!workflowEnabled) {
              <h4 class="col-xl-12 col-md-12 col-sm-12 audit-title">Change Audit Log</h4>
            }
          }
            {f.toForm_!}
        </div>
        }
      } &

      "#cancel" #> (SHtml.ajaxButton("Cancel", () => closePopup()) % ("tabindex" -> "7")                         % ("class"    -> "btn btn-default")) &
      "#save" #> (SHtml.ajaxSubmit(
        buttonName,
        onSubmit _
      )                                                            % ("id"       -> "createParameterSaveButton") % ("tabindex" -> "8") % ("class" -> s"btn ${classForButton}")) andThen
      ".notifications *" #> { updateAndDisplayNotifications(formTracker) }
    ).apply(formXml())

  }
  private def formXml(): NodeSeq = {
    SHtml.ajaxForm(
      <div class="modal-dialog">
        <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title" id="title">Here come title</h5>
              <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
            </div>
            <div class="modal-body">
                <div class="notifications">Here comes validation messages</div>
                <div class="name"/>
                <div class="value"/>
                <div class="format"/>
                <div class="description"/>
                <div class="inheritMode"/>
                <div id="changeRequestZone">
                    <div id="titleWorkflow"/>
                    <input type="text" id="changeRequestName" />
                </div>
                <div class="itemReason"/>
                <div id="delete" />
            </div>
            <div class="modal-footer">
                <div id="cancel"/>
                <div id="save"/>
            </div>
        <!-- TODO: migration-scala3 - bug: https://github.com/lampepfl/dotty/issues/16458 -->
        </div>
    </div>
    )
  }
}

object CreateOrUpdateGlobalParameterPopup {
  val htmlId_popupContainer = "createGlobalParameterContainer"
  val htmlId_popup          = "createGlobalParameterPopup"
}
