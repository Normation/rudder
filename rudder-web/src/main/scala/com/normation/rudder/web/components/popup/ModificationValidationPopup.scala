/*
*************************************************************************************
* Copyright 2011-2013 Normation SAS
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

package com.normation.rudder.web.components.popup

import net.liftweb.http.js._
import JsCmds._
import com.normation.rudder.domain.policies._
import JE._
import net.liftweb.common._
import net.liftweb.http.{SHtml,DispatchSnippet,Templates}
import scala.xml._
import net.liftweb.util.Helpers._
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.web.model.{
  WBTextField, FormTracker, WBTextAreaField
}
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.cfclerk.domain.{TechniqueVersion,TechniqueName}
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.domain.workflows._
import com.normation.rudder.domain.nodes.NodeGroup
import net.liftweb.http.SHtml.ChoiceHolder
import com.normation.rudder.web.model.WBSelectField
import com.normation.cfclerk.domain.TechniqueName
import com.normation.rudder.domain.nodes.AddNodeGroupDiff
import com.normation.rudder.web.model.CurrentUser
import org.joda.time.DateTime
import com.normation.rudder.domain.nodes.ModifyToNodeGroupDiff
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.web.components.RuleGrid
import com.normation.rudder.services.policies.OnlyDisableable
import com.normation.rudder.services.policies.OnlyEnableable
import com.normation.rudder.services.workflows.ChangeRequestService
import com.normation.rudder.repository.RoChangeRequestRepository
import com.normation.rudder.repository.WoChangeRequestRepository
import com.normation.rudder.services.workflows.WorkflowService
import com.normation.cfclerk.domain.SectionSpec
import com.normation.rudder.web.model.RudderBaseField
import com.normation.cfclerk.domain.TechniqueId
import com.normation.rudder.domain.nodes.NodeGroupDiff
import com.normation.rudder.domain.nodes.ChangeRequestNodeGroupDiff
import com.normation.eventlog.ModificationId
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.AddNodeGroupDiff
import com.normation.rudder.domain.nodes.DeleteNodeGroupDiff
import com.normation.rudder.domain.nodes.ModifyToNodeGroupDiff
import com.normation.utils.Control.boxSequence


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

  private def html = {
    val path = "templates-hidden" :: "Popup" :: "ModificationValidationPopup" :: Nil
    (for {
      xml <- Templates(path)
    } yield {
      chooseTemplate("component", "validationPopup", xml)
    }) openOr {
      logger.error("Missing template <component:validationPopup> at path: %s.html".format(path.mkString("/")))
      <div/>
    }
  }


sealed trait Action { def displayName: String }
final case object Save extends Action { val displayName: String = "Save" }
final case object Delete extends Action { val displayName: String = "Delete" }
final case object Enable extends Action { val displayName: String = "Enable" }
final case object Disable extends Action { val displayName: String = "Disabe" }

//create the directive without modifying rules (creation only - skip workflows)
final case object CreateSolo extends Action { val displayName: String = "Create" }

//create the directive and assign it to rules (creation of directive without wf, rules modification with wf)
final case object CreateAndModRules extends Action { val displayName: String = "Create" }


  /* Text variation for
   * - Directive and groups,
   * - Enable & disable (for directive), delete, modify (save)
   *
   * Expects "Directive" or "Group" as argument
   */
  private def titles(item:String, action: Action) = action match {
    case Enable => "Enable a Directive"
    case Disable => "Disable a Directive"
    case Delete  => s"Delete a ${item}"
    case Save    => s"Update a ${item}"
    case CreateSolo => s"Create a ${item}"
    case CreateAndModRules => s"Create a ${item}"
  }

  private def explanationMessages(item:String, action: Action) = action match {
    case Enable =>
      <div>
        <img src="/images/icWarn.png" alt="Warning!" height="32" width="32" class="warnicon"/>
        <h2>Are you sure that you want to enable this {item}?</h2>
        <br />
        <div id="dialogDisableWarning">
          Enabling this {item} will have an impact on the following Rules which apply it.
        </div>
      </div>
    case Disable =>
      <div>
        <img src="/images/icWarn.png" alt="Warning!" height="32" width="32" class="warnicon"/>
        <h2>Are you sure that you want to disable this {item}?</h2>
        <br />
        <div id="dialogDisableWarning">
          Disabling this {item} will have an impact on the following Rules which apply it.
        </div>
      </div>
    case Delete =>
      <div>
        <img src="/images/icWarn.png" alt="Warning!" height="32" width="32" class="warnicon"/>
        <h2>Are you sure that you want to delete this {item}?</h2>
        <br />
        <div id="dialogDisableWarning">
          Deleting this {item} will also remove it from the following Rules.
        </div>
      </div>
    case Save =>
      <div>
         <img src="/images/icDetails.png" alt="Details" height="20" width="22" class="icon"/>
         <h2>Are you sure that you want to update this {item}?</h2>
         <br />
         <div id="directiveDisabled" class="nodisplay">
           <img src="/images/icWarn.png" alt="Warning!" height="32" width="32" class="warnicon"/>
           <b>Warning:</b> This {item} is currently disabled. Your changes will not take effect until it is enabled.
         </div>
         <div  id="dialogDisableWarning">
           Updating this {item} will have an impact on the following Rules which apply it.
         </div>
      </div>
    case CreateSolo =>
      <div><h2>Are you sure you want to create this {item}?</h2></div>
    case CreateAndModRules =>
      <div>
         <img src="/images/icDetails.png" alt="Details" height="20" width="22" class="icon"/>
         <h2>Are you sure that you want to create this {item}?</h2>
         <br />
         <div id="directiveDisabled" class="nodisplay">
           <img src="/images/icWarn.png" alt="Warning!" height="32" width="32" class="warnicon"/>
           <b>Warning:</b> This {item} is currently disabled. Your changes will not take effect until it is enabled.
         </div>
         <div  id="dialogDisableWarning">
           Updating this {item} will have an impact on the following Rules which apply it.
         </div>
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
    item              : Either[
                            (TechniqueName, ActiveTechniqueId, SectionSpec ,Directive, Option[Directive], List[Rule], List[Rule])
                          , (NodeGroup, Option[NodeGroupCategoryId], Option[NodeGroup])
                        ]
  , action            : ModificationValidationPopup.Action
  , workflowEnabled   : Boolean
  , onSuccessCallback : ChangeRequestId => JsCmd = { x => Noop }
  , onFailureCallback : NodeSeq => JsCmd = { x => Noop }
  , onCreateSuccessCallBack : (Either[Directive,ChangeRequestId]) => JsCmd = { x => Noop }
  , onCreateFailureCallBack : () => JsCmd = { () => Noop }
  , parentFormTracker : FormTracker
) extends DispatchSnippet with Loggable {

  import ModificationValidationPopup._

  private[this] val userPropertyService      = RudderConfig.userPropertyService
  private[this] val changeRequestService     = RudderConfig.changeRequestService
  private[this] val workflowService          = RudderConfig.workflowService
  private[this] val dependencyService        = RudderConfig.dependencyAndDeletionService
  private[this] val uuidGen                  = RudderConfig.stringUuidGenerator
  private[this] val techniqueRepo            = RudderConfig.techniqueRepository
  private[this] val asyncDeploymentAgent     = RudderConfig.asyncDeploymentAgent
  private[this] val woNodeGroupRepository    = RudderConfig.woNodeGroupRepository
  private[this] val woDirectiveRepository    = RudderConfig.woDirectiveRepository
  private[this] val woRuleRepository         = RudderConfig.woRuleRepository
  private[this] val woChangeRequestRepo      = RudderConfig.woChangeRequestRepository

  //fonction to read state of things
  private[this] val getGroupLib              = RudderConfig.roNodeGroupRepository.getFullGroupLibrary _
  private[this] val getDirectiveLib          = RudderConfig.roDirectiveRepository.getFullDirectiveLibrary _
  private[this] val getAllNodeInfos          = RudderConfig.nodeInfoService.getAll _

  def dispatch = {
    case "popupContent" => { _ => popupContent }
  }

  private[this] val name = if(item.isLeft) "Directive" else "Group"
  private[this] val explanation = explanationMessages(name, action)
  // When there is no rules, we need to remove the warning message
  private[this] val explanationNoWarning = ("#dialogDisableWarning *" #> NodeSeq.Empty).apply(explanation)
  private[this] val allNodeInfos = getAllNodeInfos()
  private[this] val groupLib = getGroupLib()
  private[this] val directiveLib = getDirectiveLib()

  private[this] val rules = {
    action match {
      case CreateSolo =>
        Full(Set[Rule]())
      case _ =>
        item match {
          case Left((_, _, _, directive, _, baseRules, _)) =>
            dependencyService.directiveDependencies(directive.id, groupLib).map(_.rules ++ baseRules)

          case Right((nodeGroup, _, _)) =>
            dependencyService.targetDependencies(GroupTarget(nodeGroup.id)).map( _.rules)
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
          case CreateSolo =>
            if(crReasons.isDefined) {
              Some((explanationNoWarning,NodeSeq.Empty))
            } else {
              //new item, no reason, and it's creation so no wf no rules
              // => no pop-up
              None
            }
          case _ => //item update
            if(crReasons.isDefined || workflowEnabled) {
              Some((NodeSeq.Empty,NodeSeq.Empty))
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
    def workflowMessage(directiveCreation: Boolean) =
        <div>
          <br/>
          <img src="/images/ic_ChangeRequest.jpg" alt="Warning!" height="32" width="32" style="margin-top: 4px;" class="warnicon"/>
          <h2>Workflows are enabled in Rudder, your change has to be validated in a Change request</h2>
          <br />
          {if(directiveCreation) <h3>The directive will be directly created, only rule changes have to been validated.</h3> else NodeSeq.Empty}
        </div>

    val (buttonName, classForButton, titleWorkflow) = (workflowEnabled, action) match {
      case (false, _) => ("Save", "", NodeSeq.Empty)
      case (true, CreateSolo) => ("Create", "", NodeSeq.Empty)
      case (true, CreateAndModRules) => ("Submit for Validation", "wideButton", workflowMessage(true))
      case (true, _) => ("Submit for Validation", "wideButton", workflowMessage(false))
    }


    (
      "#validationForm" #> { (xml:NodeSeq) => SHtml.ajaxForm(xml) } andThen
      "#dialogTitle *" #> titles(name, action) &
      "#explanationMessageZone" #> popupWarningMessages.map( _._1).getOrElse(explanationNoWarning) &
      "#disableItemDependencies" #> popupWarningMessages.map( _._2).getOrElse(NodeSeq.Empty) &
      ".reasonsFieldsetPopup" #> {
        crReasons.map { f =>
          <div>
            <div style="margin:10px 0px 5px 0px; color:#444">
              {userPropertyService.reasonsFieldExplanation}
            </div>
              {f.toForm_!}
        </div>
        }
      } &
      "#titleWorkflow *" #> titleWorkflow &
      "#changeRequestName" #> {
        (workflowEnabled, action) match {
          case (true, CreateSolo) => Full(NodeSeq.Empty)
          case (true, _) => changeRequestName.toForm
          case _ => Full(NodeSeq.Empty)
        }
      } &
      "#saveStartWorkflow" #> (SHtml.ajaxSubmit(buttonName, () => onSubmitStartWorkflow(), ("class" -> classForButton)) % ("id", "createDirectiveSaveButton") % ("tabindex","3")) andThen
       ".notifications *" #> updateAndDisplayNotifications()

    )(html ++ Script(OnLoad(JsRaw("updatePopup();"))))
  }

  private[this] def showError(field:RudderBaseField) : NodeSeq = {
    if(field.hasErrors) {
      <ul>{field.errors.map { e => <li>{e}</li> }}</ul>
    } else { NodeSeq.Empty }
  }

  private[this] def showDependentRules(rules : Set[Rule]) : NodeSeq = {
    action match {
      case CreateSolo => NodeSeq.Empty
      case x if(rules.size <= 0) => NodeSeq.Empty
      case x =>
        val cmp = new RuleGrid("remove_popup_grid", rules.toSeq, None, false)
        cmp.rulesGrid(allNodeInfos, groupLib, directiveLib, popup = true,linkCompliancePopup = false)
    }
  }

  ///////////// fields for category settings ///////////////////

  def buildReasonField(mandatory:Boolean, containerClass:String = "twoCol") = {
    new WBTextAreaField("Message", "") {
      override def setFilter = notNull _ :: trim _ :: Nil
      override def inputField = super.inputField  %  ("style" -> "height:8em;") % ("tabindex" -> "2")
      //override def subContainerClassName = containerClass
      override def validations() = {
        if(mandatory){
          valMinLen(5, "The reason must have at least 5 characters.") _ :: Nil
        } else {
          Nil
        }
      }
    }
  }

  private[this] val defaultRequestName = {
    item match {
      case Left((t,a,r,d,opt,add,remove)) => s"${action.displayName} Directive ${d.name}"
      case Right((g,_,_)) => s"${action.displayName} Group ${g.name}"
    }
  }

  private[this] val changeRequestName = new WBTextField("Change request title", defaultRequestName) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def errorClassName = ""
    override def inputField = super.inputField % ("onkeydown" , "return processKey(event , 'createDirectiveSaveButton')") % ("tabindex","1")
    override def validations =
      valMinLen(3, "The name must have at least 3 characters") _ :: Nil
  }

  private[this] val changeRequestDescription = new WBTextAreaField("Description", "") {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField = super.inputField  % ("style" -> "height:7em") % ("tabindex","2") % ("class" -> "nodisplay")
    override def errorClassName = ""
    override def validations = Nil

  }

  // The formtracker needs to check everything only if its not a creation and there is workflow
  private[this] val formTracker = {
    (workflowEnabled, action) match {
      case (false, _) => new FormTracker(crReasons.toList)
      case (true, CreateSolo) => new FormTracker(crReasons.toList)
      case (true, _ ) =>
        new FormTracker(
                crReasons.toList
            ::: changeRequestName
             :: changeRequestDescription
             :: Nil
        )
    }
  }

  private[this] def error(msg:String) = <span class="error">{msg}</span>


  private[this] def closePopup() : JsCmd = {
    JsRaw("""$.modal.close();""")
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
              case Save | CreateAndModRules | CreateSolo =>
                Full(Some(AddDirectiveDiff(techniqueName,directive)))
              case _ =>
                Failure(s"Action ${action} is not possible on a new directive")
            }
          case Some(d) =>
            action match {
              case Delete => Full(Some(DeleteDirectiveDiff(techniqueName,directive)))
              case Save|Disable|Enable|CreateSolo|CreateAndModRules =>
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
          case Save | CreateSolo | CreateAndModRules =>
            Full(AddNodeGroupDiff(group))
          case _ =>
            Failure(s"Action ${action} is not possible on a new group")
        }
      case Some(d) =>
        action match {
          case Delete => Full(DeleteNodeGroupDiff(group))
          case Save | CreateSolo | CreateAndModRules => Full(ModifyToNodeGroupDiff(group))
          case _ => Failure(s"Action ${action} is not possible on a existing directive")
        }
    }
  }


  private[this] def saveChangeRequest() : JsCmd = {
    // we only have quick change request now
    val cr = item match {
      case Left((techniqueName, activeTechniqueId, oldRootSection, directive, optOriginal, baseRules, updatedRules)) =>
        //a def to avoid repetition in the following pattern matching
        def ruleCr() = {
          changeRequestService.createChangeRequestFromRules(
                changeRequestName.get
              , crReasons.map( _.get ).getOrElse("")
              , CurrentUser.getActor
              , crReasons.map( _.get )
              , baseRules
              , updatedRules
           )
        }


        DirectiveDiffFromAction(techniqueName, directive, optOriginal).flatMap{
          case None => ruleCr()
          case Some(_) if(action == CreateAndModRules) => ruleCr()
          case Some(diff) =>
            changeRequestService.createChangeRequestFromDirectiveAndRules(
                changeRequestName.get
              , crReasons.map( _.get ).getOrElse("")
              , techniqueName
              , oldRootSection
              , directive.id
              , optOriginal
              , diff
              , CurrentUser.getActor
              , crReasons.map( _.get )
              , baseRules
              , updatedRules
            )
          }

      case Right((nodeGroup, optParentCategory, optOriginal)) =>
        //if we have a optParentCategory, that means that we
        //have to start to move the group, and then create/save the cr.


        optParentCategory.foreach { parentCategoryId =>
          woNodeGroupRepository.move(
              nodeGroup.id
            , parentCategoryId
            , ModificationId(uuidGen.newUuid)
            , CurrentUser.getActor
            , crReasons.map( _.get )
          ) match {
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
        action.flatMap(
        changeRequestService.createChangeRequestFromNodeGroup(
            changeRequestName.get
          , crReasons.map( _.get ).getOrElse("")
          , nodeGroup
          , optOriginal
          , _
          , CurrentUser.getActor
          , crReasons.map(_.get))
        )
    }

    (for {
      crId <- cr.map(_.id)
      wf <- workflowService.startWorkflow(crId, CurrentUser.getActor, crReasons.map(_.get))
    } yield {
      crId
    }) match {
      case Full(cr) =>
        onSuccessCallback(cr)
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
        case CreateSolo | CreateAndModRules =>
          // if creation or clone, we create directive immediately and deploy if needed
          item match {
            case Left((techniqueName, activeTechniqueId, oldRootSection, directive, optOriginal, baseRules, remRules)) =>
              saveAndDeployDirective(directive, activeTechniqueId, crReasons.map( _.get ))

            case _ =>
              //no change request for group creation/clone
              logger.error("This feature is not implemented. Yell at developper for change request with groupe creation")
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
    woDirectiveRepository.saveDirective(activeTechniqueId, directive, modId, CurrentUser.getActor, why) match {
      case Full(optChanges) =>
        optChanges match {
          case Some(_) => // There is a modification diff, launch a deployment.
            asyncDeploymentAgent ! AutomaticStartDeployment(modId, RudderEventActor)
          case None => // No change, don't launch a deployment
        }

        //now, if rules were modified, also create a CR
        action match {
          case CreateAndModRules => saveChangeRequest()
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
    formTracker.addFormError(error("The form contains some errors, please correct them"))
    updateFormClientSide()
  }


  private[this] def updateAndDisplayNotifications() : NodeSeq = {
    val notifications = formTracker.formErrors
    formTracker.cleanErrors
    if(notifications.isEmpty) NodeSeq.Empty
    else {
      val html = <div id="notifications" class="notify"><ul>{notifications.map( n => <li>{n}</li>) }</ul></div>
      html
    }
  }
}


