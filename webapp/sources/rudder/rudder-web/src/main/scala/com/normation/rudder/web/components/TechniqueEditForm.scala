/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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

package com.normation.rudder.web.components

import bootstrap.liftweb.RudderConfig
import com.normation.box.*
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueCategory
import com.normation.eventlog.ModificationId
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.domain.policies.*
import com.normation.rudder.ncf.BundleName
import com.normation.rudder.services.policies.*
import com.normation.rudder.tenants.ChangeContext
import com.normation.rudder.users.CurrentUser
import com.normation.rudder.web.ChooseTemplate
import com.normation.rudder.web.model.*
import net.liftweb.common.*
import net.liftweb.http.*
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.*
import net.liftweb.util.Helpers.*
import org.joda.time.DateTime
import scala.xml.*

object TechniqueEditForm {

  val componentTechniqueEditForm: List[String] = "templates-hidden" :: "components" :: "ComponentTechniqueEditForm" :: Nil

  /**
   * This is part of component static initialization.
   * Any page which contains (or may contains after an ajax request)
   * that component have to add the result of that method in it.
   */
  def staticInit: NodeSeq = ChooseTemplate(componentTechniqueEditForm, "component-staticinit")

  private def body: NodeSeq = ChooseTemplate(componentTechniqueEditForm, "component-body")

  private def popupRemoveForm: NodeSeq = ChooseTemplate(componentTechniqueEditForm, "component-popupremoveform")

  private def popupDisactivateForm: NodeSeq = ChooseTemplate(componentTechniqueEditForm, "component-popupdisableform")

  private def crForm: NodeSeq = ChooseTemplate(componentTechniqueEditForm, "component-form")

  val htmlId_techniqueConf         = "techniqueConfiguration"
  val htmlId_addPopup              = "addPopup"
  val htmlId_addToActiveTechniques = "addToActiveTechniques"
  val htmlId_userCategoryDetails   = "userCategoryDetails"
  val htmlId_addUserCategoryForm   = "addUserCategoryForm"

}

/**
 * showTechnique(technique, policyPackageCategoryService.getReferenceTechniqueLibrary, activeTechniqueCategoryRepository.getActiveTechniqueLibrary)
 */
class TechniqueEditForm(
    htmlId_technique:    String, // HTML id for the div around the form
    val technique:       Option[Technique],
    val activeTechnique: Option[ActiveTechnique],
    userCategoryLibrary: Option[ActiveTechniqueCategory],
    // JS to execute on form success (update UI parts)
    // there are call by name to have the context matching their execution when called
    onSuccessCallback:   () => JsCmd = { () => Noop },
    onFailureCallback:   () => JsCmd = { () => Noop }
) extends DispatchSnippet with Loggable {
  import TechniqueEditForm.*

  // find Technique
  private val techniqueRepository               = RudderConfig.techniqueRepository
  private val roActiveTechniqueRepository       = RudderConfig.roDirectiveRepository
  private val rwActiveTechniqueRepository       = RudderConfig.woDirectiveRepository
  private val techniqueCompilationStatusService = RudderConfig.techniqueCompilationStatusService
  private val uuidGen                           = RudderConfig.stringUuidGenerator
  // transform Technique variable to human viewable HTML fields
  private val directiveEditorService            = RudderConfig.directiveEditorService
  private val dependencyService                 = RudderConfig.dependencyAndDeletionService
  private val asyncDeploymentAgent              = RudderConfig.asyncDeploymentAgent
  private val userPropertyService               = RudderConfig.userPropertyService

  private var currentActiveTechnique: Box[ActiveTechnique] = Box(activeTechnique).or {
    for {
      tech          <- Box(technique)
      optActiveTech <- roActiveTechniqueRepository.getActiveTechnique(tech.id.name).toBox
      activeTech    <- optActiveTech
    } yield {
      activeTech
    }
  }

  currentActiveTechnique match {
    case f: Failure =>
      logger.warn(s"An error was encountered when trying to find a technique in user library: ${f.messageChain}")
      f.rootExceptionCause.foreach(ex => logger.warn("Root exception was: ", ex))
    case _ => //
  }

  //////////////////////////// public methods ////////////////////////////

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = { case "showForm" => { (_: NodeSeq) => showForm() } }

  def showForm(): NodeSeq = {
    (
      "#editForm" #> showCrForm() &
      "#removeActionDialog" #> showRemovePopupForm() &
      "#disactivateActionDialog" #> showDisactivatePopupForm()
    )(body)
  }

  def showRemovePopupForm(): NodeSeq = {
    currentActiveTechnique match {
      case e: EmptyBox => NodeSeq.Empty
      case Full(activeTechnique_) =>
        (
          "#deleteActionDialog *" #> { (n: NodeSeq) => SHtml.ajaxForm(n) } andThen
          "#dialogDeleteButton" #> { deleteButton(activeTechnique_.id) % ("id" -> "deleteButton") } &
          "#deleteItemDependencies *" #> dialogDeleteTree("deleteItemDependencies", activeTechnique_) &
          ".reasonsFieldset" #> {
            crReasonsDisablePopup.map { f =>
              "#explanationMessage" #> <h4 class="col-xl-12 col-md-12 col-sm-12 audit-title">Change Audit Log</h4> &
              "#reasonsField" #> f.toForm_!
            }
          } &
          "#errorDisplay" #> { updateAndDisplayNotifications(formTrackerRemovePopup) }
        )(popupRemoveForm)
    }
  }

  def showDisactivatePopupForm(): NodeSeq = {
    currentActiveTechnique match {
      case e: EmptyBox => NodeSeq.Empty
      case Full(activeTechnique_) =>
        (
          "#disableActionDialog *" #> { (n: NodeSeq) => SHtml.ajaxForm(n) } andThen
          "#dialogDisableButton" #> { disableButton(activeTechnique_) % ("id" -> "disableButton") } &
          "#dialogDisableTitle" #> { if (activeTechnique_.isEnabled) "Disable" else "Enable" } &
          "#dialogDisableLabel" #> { if (activeTechnique_.isEnabled) "disable" else "enable" } &
          "#disableItemDependencies *" #> dialogDisableTree("disableItemDependencies", activeTechnique_) &
          ".reasonsFieldset" #> {
            crReasonsDisablePopup.map { f =>
              "#explanationMessage" #> <h4 class="col-xl-12 col-md-12 col-sm-12 audit-title">Change Audit Log</h4> &
              "#reasonsField" #> f.toForm_!
            }
          } &
          "#time" #> <div>{DateTime.now} </div> &
          "#errorDisplay" #> { updateAndDisplayNotifications(formTrackerDisactivatePopup) }
        )(popupDisactivateForm)
    }
  }

  def showCrForm(): NodeSeq = {
    (
      ClearClearable &
      // all the top level action are displayed only if the template is on the user library
      "#userTemplateAction" #> { (xml: NodeSeq) =>
        currentActiveTechnique match {
          case e: EmptyBox => NodeSeq.Empty
          case Full(activeTechnique) =>
            (
              ClearClearable &
              // if no technique, no disable button
              "#disableButton" #> ((button: NodeSeq) => if (technique.isDefined) button else NodeSeq.Empty) andThen
              // activation button: show disable if activated
              "#disableButtonLabel" #> { if (activeTechnique.isEnabled) "Disable" else "Enable" } &
              "#dialogDisableTitle" #> { if (activeTechnique.isEnabled) "Disable" else "Enable" } &
              "#dialogdisableWarning" #> dialogDisableWarning(activeTechnique) &
              ".reasonsFieldset" #> {
                crReasonsDisablePopup.map { f =>
                  "#explanationMessage" #> <h4 class="col-xl-12 col-md-12 col-sm-12 audit-title">Change Audit Log</h4> &
                  "#reasonsField" #> f.toForm_!
                }
              }
            )(xml)
        }
      } &
      ".groupedEditZone" #> ((div: NodeSeq) => {
        technique match {
          case None    =>
            <div class="groupedEditZone">
            No technique were found in the file system for the selection.
            This most likelly mean that the technique was deleted on the it.
          </div>
          case Some(t) =>
            (
              "#techniqueName" #> t.name &
              "#techniqueDescription *" #> t.description &
              "#techniqueDocumentation [class]" #> (if (t.longDescription.isEmpty) "d-none" else "") &
              "#techniqueLongDescription" #> Script(
                OnLoad(
                  JsRaw(s"""generateMarkdown(${Str(t.longDescription).toJsCmd}, "#techniqueLongDescription")""")
                ) // JsRaw ok, escaped
              ) &
              "#isSingle *" #> showIsSingle(t)
            )(div)
        }
      }) &
      ".editZone" #> ((div: NodeSeq) => {
        technique match {
          case None    => NodeSeq.Empty
          case Some(t) =>
            (
              "#breadcrumpReferenceCategory" #> showReferenceLibBreadcrump(t) &
              "#templateParameters" #> showParameters(t) &
              "#clientCategoryZone" #> showTechniqueUserCategory(t)
            )(div)
        }
      }) &
      "#editForm [id]" #> htmlId_technique
    )(crForm) ++
    Script(OnLoad(JsRaw("""
      $('#deleteButton').click(function() {
        initBsModal("deleteActionDialog");
        return false;
      });

      $('#disableButton').click(function() {
        initBsModal("disableActionDialog");
        return false;
      });

      //Scroll to details section
      $([document.documentElement, document.body]).animate({
        scrollTop: $("#bottomPanel").offset().top-50
      }, 400);
    """))) // JsRaw ok, const
  }

  private val crReasons = {
    import com.normation.rudder.config.ReasonBehavior.*
    userPropertyService.reasonsFieldBehavior match {
      case Disabled  => None
      case Mandatory => Some(buildReasonField(true, "px-1"))
      case Optional  => Some(buildReasonField(false, "px-1"))
    }
  }

  private val crReasonsRemovePopup = {
    import com.normation.rudder.config.ReasonBehavior.*
    userPropertyService.reasonsFieldBehavior match {
      case Disabled  => None
      case Mandatory => Some(buildReasonField(true, "px-1"))
      case Optional  => Some(buildReasonField(false, "px-1"))
    }
  }

  private val crReasonsDisablePopup = {
    import com.normation.rudder.config.ReasonBehavior.*
    userPropertyService.reasonsFieldBehavior match {
      case Disabled  => None
      case Mandatory => Some(buildReasonField(true, "px-1"))
      case Optional  => Some(buildReasonField(false, "px-1"))
    }
  }

  def buildReasonField(mandatory: Boolean, containerClass: String = "twoCol"): WBTextAreaField = {
    new WBTextAreaField("Change audit message", "") {
      override def setFilter             = notNull :: trim :: Nil
      override def inputField            = super.inputField % ("placeholder" -> { userPropertyService.reasonsFieldExplanation })
      override def subContainerClassName = containerClass
      override def validations           = {
        if (mandatory) {
          valMinLen(5, "The reason must have at least 5 characters.") :: Nil
        } else {
          Nil
        }
      }
    }
  }

  private val formTracker = {
    new FormTracker(crReasons.toList)
  }

  private val formTrackerRemovePopup = {
    new FormTracker(crReasonsRemovePopup.toList)
  }

  private val formTrackerDisactivatePopup = {
    new FormTracker(crReasonsDisablePopup.toList)
  }

  ////////////// Callbacks //////////////

  private def onSuccess(): JsCmd = {
    // MUST BE THIS WAY, because the parent may change some reference to JsNode
    // and so, our AJAX could be broken
    cleanTrackers()
    onSuccessCallback() & updateFormClientSide() &
    // show success popup
    successPopup
  }

  private def cleanTrackers(): Unit = {
    formTracker.clean
    formTrackerRemovePopup.clean
    formTrackerDisactivatePopup.clean
  }

  private def onFailure(): JsCmd = {
    formTracker.addFormError(error("There was a problem with your request"))
    updateFormClientSide()
  }

  private def onFailureRemovePopup(): JsCmd = {
    formTrackerRemovePopup.addFormError(error("There was a problem with your request"))
    updateRemoveFormClientSide()
  }

  private def onFailureDisablePopup(): JsCmd = {
    formTrackerDisactivatePopup.addFormError(error("There was a problem with your request"))
    updateDisableFormClientSide()
  }

  private def updateRemoveFormClientSide(): JsCmd = {
    val jsDisplayRemoveDiv = JsRaw("""initBsModal("deleteActionDialog");""") // JsRaw ok, const
    Replace("deleteActionDialog", this.showRemovePopupForm()) &
    jsDisplayRemoveDiv
  }

  private def updateDisableFormClientSide(): JsCmd = {
    val jsDisplayRemoveDiv = JsRaw("""initBsModal("disableActionDialog")""") // JsRaw ok, const
    Replace("disableActionDialog", this.showDisactivatePopupForm()) &
    jsDisplayRemoveDiv
  }

  ///////////// Delete /////////////

  private def deleteButton(id: ActiveTechniqueId): Elem = {

    def deleteActiveTechnique(): JsCmd = {
      if (formTrackerRemovePopup.hasErrors) {
        onFailureRemovePopup()
      } else {
        JsRaw("hideBsModal('deleteActionDialog');") & { // JsRaw ok, const
          val modId = ModificationId(uuidGen.newUuid)
          (for {
            deleted <- dependencyService.cascadeDeleteTechnique(id, modId, CurrentUser.actor, crReasonsRemovePopup.map(_.get))
            deploy  <- {
              asyncDeploymentAgent ! AutomaticStartDeployment(modId, RudderEventActor)
              Full("Policy update request sent")
            }
          } yield {
            deploy
          }) match {
            case Full(_)          =>
              formTrackerRemovePopup.clean
              onSuccessCallback() &
              SetHtml(htmlId_technique, <div id={htmlId_technique}>Technique successfully deleted</div>) &
              // show success popup
              successPopup
            case Empty            => // arg.
              formTrackerRemovePopup.addFormError(error("An error occurred while deleting the Technique."))
              onFailure()
            case Failure(m, _, _) =>
              formTrackerRemovePopup.addFormError(error("An error occurred while deleting the Technique: " + m))
              onFailure()
          }
        }
      }
    }

    SHtml.ajaxSubmit("Delete", deleteActiveTechnique)
  }

  private def dialogDeleteTree(htmlId: String, activeTechnique: ActiveTechnique): NodeSeq = {
    (new TechniqueTree(htmlId, activeTechnique.id, DontCare)).tree()
  }

  ///////////// Enable / disable /////////////

  private def disableButton(activeTechnique: ActiveTechnique): Elem = {
    def switchActivation(status: Boolean)(): JsCmd = {
      if (formTrackerDisactivatePopup.hasErrors) {
        onFailureDisablePopup()
      } else {
        currentActiveTechnique = currentActiveTechnique.map(activeTechnique => activeTechnique.copy(_isEnabled = status))
        JsRaw("hideBsModal('disableActionDialog');") & // JsRaw ok, const
        statusAndDeployTechnique(activeTechnique, status)
      }
    }

    if (activeTechnique.isEnabled) {
      SHtml.ajaxSubmit("Disable", switchActivation(false))
    } else {
      SHtml.ajaxSubmit("Enable", switchActivation(true))
    }
  }

  private def dialogDisableWarning(activeTechnique: ActiveTechnique): NodeSeq = {
    if (activeTechnique.isEnabled) {
      <h2>Disabling this Technique will also affect the following Directives and Rules.</h2>
    } else {
      <h2>Enabling this Technique will also affect the following Directives and Rules.</h2>
    }
  }

  private def dialogDisableTree(htmlId: String, activeTechnique: ActiveTechnique): NodeSeq = {
    val switchFilterStatus = if (activeTechnique.isEnabled) OnlyDisableable else OnlyEnableable
    (new TechniqueTree(htmlId, activeTechnique.id, switchFilterStatus)).tree()
  }

  /////////////////////////////////////////////////////////////////////////

  def showReferenceLibBreadcrump(technique: Technique): NodeSeq = {
    <ul class="inlinenotop">{findBreadCrump(technique).map(cat => <li class="inlineml">&#187; {cat.name}</li>)}
    </ul>
  }

  /**
   * Display user library category information in the details of a Technique.
   * That detail has its own snippet because it is multi-stated and state are
   * updated by ajax:
   * - display the category breadcrump if Technique is already in user lib;
   * - display a "click on a category" message if not set in user lib and no category previously chosen
   * - else display an add button to add in the current category
   */
  def showTechniqueUserCategory(technique: Technique): NodeSeq = {
    <div id={htmlId_addToActiveTechniques}>Client category: {
      findUserBreadCrump(technique) match {
        case Some(listCat) =>
          <ul class="inlinenotop">
                {listCat.map(cat => <li class="inlineml">&#187; {cat.name}</li>)}
            </ul>
        case None          => // display the add button if a user lib category is defined
          userCategoryLibrary match {
            case None           => <span class="text-success">Click on a category in the user library</span>
            case Some(category) => {
              /*
               * Actually add the Technique to category:
               * - add it in the backend storage
               * - trigger user lib js tree update
               * - trigger Technique details update
               */
              def onClickAddTechniqueToCategory(): JsCmd = {
                // back-end action
                rwActiveTechniqueRepository.addTechniqueInUserLibrary(
                  category.id,
                  technique.id.name,
                  techniqueRepository.getTechniqueVersions(technique.id.name).toSeq,
                  technique.policyTypes
                )(using CurrentUser.changeContext(Some("User added a technique from UI")))

                // update UI
                Replace(htmlId_addToActiveTechniques, showTechniqueUserCategory(technique)) &
                onSuccessCallback()
              }

              SHtml.ajaxButton(
                Text("Add this Technique to user library category ") ++ <b>{category.name}</b>,
                () => onClickAddTechniqueToCategory()
              )
            }
          }
      }
    }</div>
  }

  private def showParameters(technique: Technique): NodeSeq = {
    directiveEditorService.get(technique.id, DirectiveUid("just-for-read-only")) match {
      case Full(pe) => pe.toHtmlNodeSeq
      case e: EmptyBox =>
        val msg = "Error when fetching parameter of Technique."
        logger.error(msg, e)
        <span class="error">{msg}</span>
    }
  }

  private def showIsSingle(technique: Technique): NodeSeq = {
    <span>
      {
      if (technique.isMultiInstance) {
        { <b>Multi instance</b> } ++ Text(": several Directives derived from that template can be deployed on a given node")
      } else {
        { <b>Unique</b> } ++ Text(": an unique Directive derived from that template can be deployed on a given node")
      }
    }
    </span>
  }

  /**
   * Build the breadcrump of categories that leads to given target Technique in the context
   * of given root Technique library.
   * The template must be in the library.
   * @throws
   *   RuntimeException if the library does not contain the Technique
   */
  @throws(classOf[RuntimeException])
  private def findBreadCrump(target: Technique): Seq[TechniqueCategory] = {
    techniqueRepository.getTechniqueCategoriesBreadCrump(target.id).toBox match {
      case Full(b) => b
      case e: EmptyBox =>
        logger.debug("Bread crumb error: %s".format(e))
        throw new RuntimeException("The reference Technique category does not hold target node %s".format(target.name))
    }
  }

  /**
   * Build the breadcrump of categories that leads to given target Technique in the context
   * of given root Active Technique library.
   * The template may not be present in the library.
   */
  private def findUserBreadCrump(target: Technique): Option[List[ActiveTechniqueCategory]] = {
    // find the potential WBUsreTechnique for given WBTechnique
    (for {
      activeTechnique <- roActiveTechniqueRepository.getActiveTechnique(target.id.name).toBox.flatMap(Box(_))
      crump           <- roActiveTechniqueRepository.activeTechniqueBreadCrump(activeTechnique.id).toBox
    } yield {
      crump.reverse
    }) match {
      case Full(b) => Some(b)
      case e: EmptyBox =>
        logger.debug("User bread crumb error: %s".format(e))
        None
    }
  }

  /////////////////////////////////////////////////////////////////////////
  /////////////////////////////// Edit form ///////////////////////////////
  /////////////////////////////////////////////////////////////////////////

  private def updateFormClientSide(): JsCmd = {
    SetHtml(htmlId_technique, this.showCrForm())
  }

  private def error(msg: String) = <span class="error">{msg}</span>

  private def statusAndDeployTechnique(activeTechnique: ActiveTechnique, status: Boolean): JsCmd = {
    implicit val cc: ChangeContext = CurrentUser.changeContext(crReasonsDisablePopup.map(_.get))
    (for {
      save   <-
        (rwActiveTechniqueRepository.changeStatus(activeTechnique.id, status)
        <* techniqueCompilationStatusService.syncTechniqueActiveStatus(BundleName(activeTechnique.techniqueName.value))).toBox
      deploy <- {
        asyncDeploymentAgent ! AutomaticStartDeployment(cc.modId, RudderEventActor)
        Full("Policy update request sent")
      }
    } yield {
      save
    }) match {
      case Full(_) => onSuccess()
      case Empty   =>
        formTracker.addFormError(error("An error occurred while saving the Technique"))
        onFailure()
      case f: Failure =>
        formTracker.addFormError(error("An error occurred while saving the Technique: " + f.messageChain))
        onFailure()
    }
  }

  private def updateAndDisplayNotifications(formTracker: FormTracker): NodeSeq = {
    val notifications = formTracker.formErrors
    formTracker.cleanErrors
    if (notifications.isEmpty) {
      NodeSeq.Empty
    } else {
      val html = <div id="notifications">
        <ul class="text-danger">{notifications.map(n => <li>{n}</li>)}</ul></div>
      html
    }
  }

  ///////////// success pop-up ///////////////
  private def successPopup: JsCmd = {
    JsRaw("""createSuccessNotification()""") // JsRaw ok, const
  }

}
