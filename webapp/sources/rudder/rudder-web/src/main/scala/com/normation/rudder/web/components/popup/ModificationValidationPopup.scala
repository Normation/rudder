/*
*************************************************************************************
* Copyright 2011-2013 Normation SAS
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

import net.liftweb.http.js._
import JsCmds._
import com.normation.rudder.domain.policies._
import JE._
import net.liftweb.common._
import net.liftweb.http.{DispatchSnippet, SHtml}

import scala.xml._
import net.liftweb.util.Helpers._
import com.normation.rudder.web.model._
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.domain.workflows._
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.cfclerk.domain.TechniqueName
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.web.components.RuleGrid
import com.normation.cfclerk.domain.TechniqueId
import com.normation.rudder.domain.nodes.ChangeRequestNodeGroupDiff
import com.normation.eventlog.ModificationId
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.domain.nodes.AddNodeGroupDiff
import com.normation.rudder.domain.nodes.DeleteNodeGroupDiff
import com.normation.rudder.domain.nodes.ModifyToNodeGroupDiff
import com.normation.rudder.services.workflows.ChangeRequestService
import com.normation.rudder.services.workflows.DGModAction
import com.normation.rudder.services.workflows.DirectiveChangeRequest
import com.normation.rudder.services.workflows.NodeGroupChangeRequest
import com.normation.rudder.services.workflows.WorkflowService
import com.normation.rudder.web.ChooseTemplate
import com.normation.rudder.web.components.DisplayColumn

import com.normation.box._

/**
 * Validation pop-up for modification on group and directive.
 *
 * The validation pop-up contains 3 mains parts, optionnaly empty:
 *
 * - the list of impacted Rules by that modification
 * - the change message
 * - the workflows part (asking what to do if the wf is enable)
 *
 * For Creation or Clone, there is no Workflow, hence no ChangeRequest !
 * On success, we return on the forms with the newly created directive
 * On failure, we return on the form with the error
 *
 *
 */

object ModificationValidationPopup extends Loggable {
  val htmlId_popupContainer = "validationContainer"

  private def html = ChooseTemplate(
      "templates-hidden" :: "Popup" :: "ModificationValidationPopup" :: Nil
    , "component-validationpopup"
  )


  /* Text variation for
   * - Directive and groups,
   * - Enable & disable (for directive), delete, modify (save)
   *
   * Expects "Directive" or "Group" as argument
   */
  private def titles(item:String, name:String, action: DGModAction) = action match {
    case DGModAction.Enable            => s"Enable Directive '${name}'"
    case DGModAction.Disable           => s"Disable Directive '${name}'"
    case DGModAction.Delete            => s"Delete ${item} '${name}'"
    case DGModAction.Update            => s"Update ${item} '${name}'"
    case DGModAction.CreateSolo        => s"Create a ${item}"
    case DGModAction.CreateAndModRules => s"Create a ${item}"
  }

  private def explanationMessages(item:String, action: DGModAction, disabled: Boolean) = action match {
    case DGModAction.Enable  =>
    <div class="row">
        <h4 class="col-lg-12 col-sm-12 col-xs-12 text-center">
            Are you sure that you want to enable this {item}?
  ²      </h4>
    </div>
        <div id="dialogDisableWarning" class="col-lg-12 col-sm-12 col-xs-12 alert alert-info text-center space-top">
            Enabling this {item} will have an impact on the following Rules which apply it.
        </div>
    case DGModAction.Disable =>
      <div class="row">
        <h4 class="col-lg-12 col-sm-12 col-xs-12 text-center">
            Are you sure that you want to disable this {item}?
        </h4>
    </div>
        <div id="dialogDisableWarning" class="col-lg-12 col-sm-12 col-xs-12 alert alert-info text-center space-top">
            Disabling this {item} will have an impact on the following Rules which apply it.
        </div>
    case DGModAction.Delete  =>
      <div class="row">
        <h4 class="col-lg-12 col-sm-12 col-xs-12 text-center">
            Are you sure that you want to delete this {item}?
        </h4>
    </div>
        <div id="dialogDisableWarning" class="col-lg-12 col-sm-12 col-xs-12 alert alert-info text-center space-top">
            Deleting this {item} will have an impact on the following Rules which apply it.
        </div>
    case DGModAction.Update  =>
        <div class="row">
            <h4 class="col-lg-12 col-sm-12 col-xs-12 text-center">
                Are you sure that you want to update this {item}?
            </h4>
        </div>++
            { if (disabled)
                <div class="col-lg-12 col-sm-12 col-xs-12 alert alert-warning text-center" role="alert" >
                  <div class="col-lg-12 col-sm-12 col-xs-12 text-center">
                  <b>Warning:</b> This {item} is currently disabled. Your changes will not take effect until it is enabled.
                  </div>
                </div>
            else NodeSeq.Empty
             } ++
        <div id="dialogDisableWarning" class="col-lg-12 col-sm-12 col-xs-12 alert alert-info text-center">
            Updating this {item} will have an impact on the following Rules which apply it.
        </div>
    case DGModAction.CreateSolo =>
      <div class="row">
        <h4 class="col-lg-12 col-sm-12 col-xs-12 text-center">
            Are you sure you want to create this {item}?
        </h4>
    </div>
    case DGModAction.CreateAndModRules =>
      <div class="row">
        <h4 class="col-lg-12 col-sm-12 col-xs-12 text-center">
            Are you sure that you want to create this {item}?
        </h4>
    </div> ++
        { if (disabled)
          <div class="alert alert-warning col-lg-12 col-sm-12 col-xs-12 text-center">
            <div class="col-lg-12 col-sm-12 col-xs-12 text-center">
              <b>Warning:</b> This {item} is currently disabled. Your changes will not take effect until it is enabled.
            </div>
          </div> else NodeSeq.Empty
        } ++
   <div id="dialogDisableWarning" class="alert alert-info col-lg-12 col-sm-12 col-xs-12 text-center space-bottom">
      <b>Info:</b> Updating this {item} will have an impact on the following Rules which apply it.
    </div>
  }
}

/*
 * Pop-up logic:
 * - popupContent allow the pop-up to process a lot of thing and choose
 *   if it should be displayed (Some(node seq)) or not (None)
 * - in all case, the logic is OK and the pop-up can be displayed, even
 *   if the user won't be any choice
 * - in all case, the user can try to submit directly submitted
 *   with the call to onSubmit (but in some case, it can fail)
 */
class ModificationValidationPopup(
    //if we are creating a new item, then None, else Some(x)
    item                    : Either[DirectiveChangeRequest, NodeGroupChangeRequest]
  , workflowService         : WorkflowService // workflow used to validate that change request
  , onSuccessCallback       : ChangeRequestId => JsCmd = { x => Noop }
  , onFailureCallback       : NodeSeq => JsCmd = { x => Noop }
  , onCreateSuccessCallBack : (Either[Directive,ChangeRequestId]) => JsCmd = { x => Noop }
  , onCreateFailureCallBack : () => JsCmd = { () => Noop }
  , parentFormTracker       : FormTracker
) extends DispatchSnippet with Loggable {

  import ModificationValidationPopup._

  private[this] val userPropertyService      = RudderConfig.userPropertyService
  private[this] val dependencyService        = RudderConfig.dependencyAndDeletionService
  private[this] val uuidGen                  = RudderConfig.stringUuidGenerator
  private[this] val techniqueRepo            = RudderConfig.techniqueRepository
  private[this] val asyncDeploymentAgent     = RudderConfig.asyncDeploymentAgent
  private[this] val woNodeGroupRepository    = RudderConfig.woNodeGroupRepository
  private[this] val woDirectiveRepository    = RudderConfig.woDirectiveRepository

  //fonction to read state of things
  private[this] val getGroupLib              = RudderConfig.roNodeGroupRepository.getFullGroupLibrary _

  def dispatch = {
    case "popupContent" => { _ => popupContent }
  }

  private[this] val disabled = item match {
    case Left(item) => ! item.newDirective.isEnabled
    case Right(item) => ! item.newGroup.isEnabled
  }

  private[this] val validationRequired = workflowService.needExternalValidation()
  private[this] val (defaultRequestName, itemType, itemName, action) = {
    item match {
      case Left(DirectiveChangeRequest(action,t,a,r,d,opt,add,remove)) =>
        (s"${action.name} Directive ${d.name}" , "Directive", d.name, action)
      case Right(NodeGroupChangeRequest(action,g,_,_))                         =>
        (s"${action.name} Group ${g.name}"     , "Group", g.name, action)
    }
  }

  private[this] val explanation : NodeSeq = explanationMessages(itemType, action, disabled)
  // When there is no rules, we need to remove the warning message
  private[this] val explanationNoWarning = ("#dialogDisableWarning" #> NodeSeq.Empty).apply(explanation)
  private[this] val groupLib = getGroupLib()

  private[this] val rules = {
    action match {
      case DGModAction.CreateSolo =>
        Full(Set[Rule]())
      case _ =>
        item match {
          case Left(directiveChange) =>
            dependencyService.directiveDependencies(directiveChange.newDirective.id, groupLib.toBox).map(_.rules ++ directiveChange.baseRules)

          case Right(nodeGroupChange) =>
            dependencyService.targetDependencies(GroupTarget(nodeGroupChange.newGroup.id)).map( _.rules)
        }
    }
  }

  //must be here because used in val popupWarningMessages
  private[this] val crReasons = {
    import com.normation.rudder.web.services.ReasonBehavior._
    userPropertyService.reasonsFieldBehavior match {
      case Disabled => None
      case Mandatory => Some(buildReasonField(true, "subContainerReasonField"))
      case Optionnal => Some(buildReasonField(false, "subContainerReasonField"))
    }
  }

  val popupWarningMessages : Option[(NodeSeq,NodeSeq)] = {

    rules match {
      // Error while fetch dependent Rules => display message and error
      case eb:EmptyBox =>
        val error = <div class="error">An error occurred while trying to find dependent item</div>
        Some((explanation, error))
      // Nothing to display, but if workflow are disabled or the this is a new Item, display the explanation message
      case Full(emptyRules) if emptyRules.size == 0 =>

        //if there is nothing to validate and no workflows,
        action match {
          case DGModAction.CreateSolo =>
            if(crReasons.isDefined) {
              Some((explanationNoWarning,NodeSeq.Empty))
            } else {
              //new item, no reason, and it's creation so no wf no rules
              // => no pop-up
              None
            }
          case _ => //item update
            if(crReasons.isDefined || validationRequired) {
              Some((explanationNoWarning,NodeSeq.Empty))
            } else { //no wf, no message => the user can't do anything
              None
            }
        }

      case Full(rules) =>
        Some((explanation,showDependentRules(rules)))
    }
  }

  // _1 is explanation message, _2 is dependant rules
  def popupContent() : NodeSeq = {

    def workflowMessage(directiveCreation: Boolean) = (
      <h4 class="col-lg-12 col-sm-12 col-xs-12 audit-title">Change Request</h4>
      <hr class="css-fix"/>
      <div class="col-lg-12 col-sm-12 col-xs-12 alert alert-info text-center">
        <span class="glyphicon glyphicon-info-sign"></span>
        Workflows are enabled in Rudder, your change has to be validated in a Change request
        {
          if(directiveCreation)
            <p>The directive will be directly created, only rule changes have to been validated.</p>
          else
            NodeSeq.Empty
        }
      </div>
    )
    val (buttonName, classForButton, titleWorkflow) = (validationRequired, action) match {
      case (false, DGModAction.CreateSolo       )   => ("Create"      , "btn-success", NodeSeq.Empty)
      case (false, DGModAction.CreateAndModRules)   => ("Create"      , "btn-success", NodeSeq.Empty)
      case (false, DGModAction.Delete           )   => ("Delete"      , "btn-danger" , NodeSeq.Empty)
      case (false, DGModAction.Update           )   => ("Update"      , "btn-success", NodeSeq.Empty)
      case (false, DGModAction.Enable           )   => ("Enable"      , "btn-primary", NodeSeq.Empty)
      case (false, DGModAction.Disable          )   => ("Disable"     , "btn-primary", NodeSeq.Empty)
      case (true , DGModAction.CreateSolo       )   => ("Create"      , "btn-success", NodeSeq.Empty)
      case (true , DGModAction.CreateAndModRules)   => ("Open request", "btn-primary", workflowMessage(true))
      case (true , DGModAction.Delete           )   => ("Open request", "btn-primary", workflowMessage(false))
      case (true , DGModAction.Update           )   => ("Open request", "btn-primary", workflowMessage(false))
      case (true , DGModAction.Enable           )   => ("Open request", "btn-primary", workflowMessage(false))
      case (true , DGModAction.Disable          )   => ("Open request", "btn-primary", workflowMessage(false))
    }
    (
      "#validationForm" #> { (xml:NodeSeq) => SHtml.ajaxForm(xml) } andThen
      "#dialogTitle *" #> titles(itemType, itemName, action) &
      "#explanationMessageZone" #> popupWarningMessages.map( _._1).getOrElse(explanationNoWarning) &
      "#disableItemDependencies" #> popupWarningMessages.map( _._2).getOrElse(NodeSeq.Empty) &
      ".reasonsFieldsetPopup" #> {
        crReasons.map { f =>
          <div>
              {
                (validationRequired, action) match {
                  case (true, DGModAction.CreateSolo) => <h4 class="col-lg-12 col-sm-12 col-xs-12 audit-title">Change Audit Log</h4>
                  case (true, _) => NodeSeq.Empty
                  case _ => <h4 class="col-lg-12 col-sm-12 col-xs-12 audit-title">Change Audit Log</h4>
                }
              }
              {f.toForm_!}
          </div>
        }
      } &
      "#titleWorkflow" #> titleWorkflow &
      "#changeRequestName" #> {
        (validationRequired, action) match {
          case (true, DGModAction.CreateSolo) => Full(NodeSeq.Empty)
          case (true, _) => changeRequestName.toForm
          case _ => Full(NodeSeq.Empty)
        }
      } &
      "#saveStartWorkflow" #> (SHtml.ajaxSubmit(buttonName, () => onSubmitStartWorkflow(), ("class" -> classForButton)) % ("id" -> "createDirectiveSaveButton") % ("tabindex" -> "3")) andThen
       ".notifications *" #> updateAndDisplayNotifications()

    )(html)
  }

  private[this] def showDependentRules(rules : Set[Rule]) : NodeSeq = {
    action match {
      case DGModAction.CreateSolo => NodeSeq.Empty
      case x if(rules.size <= 0)  => NodeSeq.Empty
      case x                      =>
        val noDisplay = DisplayColumn.Force(false)
        val cmp = new RuleGrid("remove_popup_grid", None, false, None, noDisplay, noDisplay)
        cmp.rulesGridWithUpdatedInfo(Some(rules.toSeq), false, true)
    }
  }

  ///////////// fields for category settings ///////////////////

  def buildReasonField(mandatory:Boolean, containerClass:String = "twoCol") = {
    new WBTextAreaField("Change audit message", "") {
      override def setFilter = notNull _ :: trim _ :: Nil
      override def inputField = super.inputField  %  ("style" -> "height:8em;") % ("tabindex" -> "2") % ("placeholder" -> {userPropertyService.reasonsFieldExplanation})
      override def validations = {
        if(mandatory){
          valMinLen(5, "The reason must have at least 5 characters.") _ :: Nil
        } else {
          Nil
        }
      }
    }
  }

  private[this] val changeRequestName = new WBTextField("Change request title", defaultRequestName) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def errorClassName = "col-lg-12 errors-container"
    override def inputField = super.inputField % ("onkeydown" -> "return processKey(event , 'createDirectiveSaveButton')") % ("tabindex" -> "1")
    override def validations =
      valMinLen(1, "Name must not be empty") _ :: Nil
  }

  private[this] val changeRequestDescription = new WBTextAreaField("Description", "") {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField = super.inputField  % ("style" -> "height:7em") % ("tabindex" -> "2") % ("class" -> "nodisplay")
    override def errorClassName = "col-lg-12 errors-container"
    override def validations = Nil

  }

  // The formtracker needs to check everything only if its not a creation and there is workflow
  private[this] val formTracker = {
    (validationRequired, action) match {
      case (false, _                     ) => new FormTracker(crReasons.toList)
      case (true , DGModAction.CreateSolo) => new FormTracker(crReasons.toList)
      case (true , _                     ) =>
        new FormTracker(
                crReasons.toList
            ::: changeRequestName
             :: changeRequestDescription
             :: Nil
        )
    }
  }

  private[this] def error(msg:String) = <span class="col-lg-12 errors-container">{msg}</span>

  private[this] def closePopup() : JsCmd = {
    JsRaw("""$('#confirmUpdateActionDialog').bsModal('hide');""")
  }

  /**
   * Update the form when something happened
   */
  private[this] def updateFormClientSide() : JsCmd = {
    SetHtml(htmlId_popupContainer, popupContent())
  }

  private[this] def onSubmitStartWorkflow() : JsCmd = {
    onSubmit()
  }

  private[this] def DirectiveDiffFromAction(
      techniqueName: TechniqueName
    , directive    : Directive
    , initialState : Option[Directive]
  ) : Box[Option[ChangeRequestDirectiveDiff]] = {

    techniqueRepo.get(TechniqueId(techniqueName,directive.techniqueVersion)).map(_.rootSection) match {
      case None => Failure(s"Could not get root section for technique ${techniqueName.value} version ${directive.techniqueVersion}")
      case Some(rootSection) =>
        initialState match {
          case None =>
            action match {
              case DGModAction.Update | DGModAction.CreateAndModRules | DGModAction.CreateSolo =>
                Full(Some(AddDirectiveDiff(techniqueName,directive)))
              case _                                                                           =>
                Failure(s"Action ${action} is not possible on a new directive")
            }
          case Some(d) =>
            action match {
              case DGModAction.Delete                                                                                                     => Full(Some(DeleteDirectiveDiff(techniqueName,directive)))
              case DGModAction.Update | DGModAction.Disable | DGModAction.Enable | DGModAction.CreateSolo | DGModAction.CreateAndModRules =>
                if (d == directive) {
                  Full(None)
                } else {
                  Full(Some(ModifyToDirectiveDiff(techniqueName,directive,rootSection)))
                }
            }
        }
    }
  }

  private[this] def groupDiffFromAction(
      group        : NodeGroup
    , initialState : Option[NodeGroup]
  ) : Box[ChangeRequestNodeGroupDiff] = {
    initialState match {
      case None =>
        action match {
          case DGModAction.Update | DGModAction.CreateSolo | DGModAction.CreateAndModRules =>
            Full(AddNodeGroupDiff(group))
          case _                                                                           =>
            Failure(s"Action ${action} is not possible on a new group")
        }
      case Some(d) =>
        action match {
          case DGModAction.Delete                                                          => Full(DeleteNodeGroupDiff(group))
          case DGModAction.Update | DGModAction.CreateSolo | DGModAction.CreateAndModRules => Full(ModifyToNodeGroupDiff(group))
          case _                                                                           => Failure(s"Action ${action} is not possible on a existing directive")
        }
    }
  }

  private[this] def saveChangeRequest() : JsCmd = {
    // we only have quick change request now
    val boxcr = item match {
      case Left(DirectiveChangeRequest(action, techniqueName, activeTechniqueId, oldRootSection, directive, optOriginal, baseRules, updatedRules)) =>
        //a def to avoid repetition in the following pattern matching
        def ruleCr() = {
          ChangeRequestService.createChangeRequestFromRules(
                changeRequestName.get
              , crReasons.map( _.get ).getOrElse("")
              , CurrentUser.actor
              , crReasons.map( _.get )
              , baseRules
              , updatedRules
           )
        }

        DirectiveDiffFromAction(techniqueName, directive, optOriginal).map{
          case None => ruleCr()
          case Some(_) if(action == DGModAction.CreateAndModRules) => ruleCr()
          case Some(diff) =>
            ChangeRequestService.createChangeRequestFromDirectiveAndRules(
                changeRequestName.get
              , crReasons.map( _.get ).getOrElse("")
              , techniqueName
              , oldRootSection
              , directive.id
              , optOriginal
              , diff
              , CurrentUser.actor
              , crReasons.map( _.get )
              , baseRules
              , updatedRules
            )
          }

      case Right(NodeGroupChangeRequest(action, nodeGroup, optParentCategory, optOriginal)) =>
        //if we have a optParentCategory, that means that we
        //have to start to move the group, and then create/save the cr.

        optParentCategory.foreach { parentCategoryId =>
          woNodeGroupRepository.move(
              nodeGroup.id
            , parentCategoryId
            , ModificationId(uuidGen.newUuid)
            , CurrentUser.actor
            , crReasons.map( _.get )
          ).toBox match {
            case Full(_) => //ok, continue
            case eb:EmptyBox =>
              val e = eb ?~! "Error when moving the group (no change request was created)"
              //early return here
              e.chain.foreach { ex =>
                parentFormTracker.addFormError(error(ex.messageChain))
                logger.error(s"Exception when trying to update a change request:", ex)
              }
              return onFailureCallback(Text(e.messageChain))
          }
        }

        val action = groupDiffFromAction(nodeGroup, optOriginal)
        action.map(
        ChangeRequestService.createChangeRequestFromNodeGroup(
            changeRequestName.get
          , crReasons.map( _.get ).getOrElse("")
          , nodeGroup
          , optOriginal
          , _
          , CurrentUser.actor
          , crReasons.map(_.get))
        )
    }

    (for {
      cr <- boxcr
      id <- workflowService.startWorkflow(cr, CurrentUser.actor, crReasons.map(_.get))
    } yield {
      id
    }) match {
      case Full(id) =>
        onSuccessCallback(id)
      case eb:EmptyBox =>
        val e = (eb ?~! "Error when trying to save your modification")
        e.chain.foreach { ex =>
          parentFormTracker.addFormError(error(ex.messageChain))
          logger.error(s"Exception when trying to update a change request:", ex)
        }
        onFailureCallback(Text(e.messageChain))
    }
  }

  def onSubmit() : JsCmd = {
    if(formTracker.hasErrors) {
      onFailure
    } else {
      // we create a CR only if we are not creating
      action match {
        case DGModAction.CreateSolo | DGModAction.CreateAndModRules =>
          // if creation or clone, we create directive immediately and deploy if needed
          item match {
            case Left(DirectiveChangeRequest(action, techniqueName, activeTechniqueId, oldRootSection, directive, optOriginal, baseRules, remRules)) =>
              saveAndDeployDirective(directive, activeTechniqueId, crReasons.map( _.get ))

            case _ =>
              //no change request for group creation/clone
              logger.error("This feature is not implemented. Please ask developers for change requests with group creation")
              formTracker.addFormError(Text("System error: feature missing"))
              onFailure
          }
        case _ =>
          saveChangeRequest()
      }
    }
  }

  private[this] def saveAndDeployDirective(
      directive: Directive
    , activeTechniqueId: ActiveTechniqueId
    , why:Option[String]
    ): JsCmd = {
    val modId = ModificationId(uuidGen.newUuid)
    woDirectiveRepository.saveDirective(activeTechniqueId, directive, modId, CurrentUser.actor, why).toBox match {
      case Full(optChanges) =>
        optChanges match {
          case Some(diff) if diff.needDeployment =>
            // There is a modification diff that required deployment, launch a deployment.
            asyncDeploymentAgent ! AutomaticStartDeployment(modId, RudderEventActor)
          case _ => // No change worthy of deployment, don't launch a deployment
        }

        //now, if rules were modified, also create a CR
        action match {
          case DGModAction.CreateAndModRules => saveChangeRequest()
          case _ => closePopup() & onCreateSuccessCallBack(Left(directive))
        }
      case Empty =>
        parentFormTracker.addFormError(Text("There was an error on creating this directive"))
        closePopup() & onCreateFailureCallBack()

      case Failure(m, _, _) =>
        parentFormTracker.addFormError(Text(m))
        closePopup() & onCreateFailureCallBack()
    }
  }

  private[this] def onFailure : JsCmd = {
    formTracker.addFormError(error("There was problem with your request"))
    updateFormClientSide & JsRaw("""scrollToElementPopup('#notifications', 'confirmUpdateActionDialogconfirmUpdateActionDialog')""")
  }

  private[this] def updateAndDisplayNotifications() : NodeSeq = {
    val notifications = formTracker.formErrors
    formTracker.cleanErrors
    if(notifications.isEmpty) NodeSeq.Empty
    else {
      val html = <div id="notifications" class="alert alert-danger text-center col-lg-12 col-xs-12 col-sm-12" role="alert"><ul class="text-danger">{notifications.map( n => <li>{n}</li>) }</ul></div>
      html
    }
  }
}
