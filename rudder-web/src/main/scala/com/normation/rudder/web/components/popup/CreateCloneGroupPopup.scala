package com.normation.rudder.web.components.popup

import com.normation.rudder.domain.nodes._
import net.liftweb.http.DispatchSnippet
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.domain.policies.{ Rule, RuleId }
import net.liftweb.http.Templates
import org.slf4j.LoggerFactory
import net.liftweb.http.js._
import JsCmds._
import com.normation.utils.StringUuidGenerator
import com.normation.inventory.domain.NodeId
import JE._
import net.liftweb.common._
import net.liftweb.http.{ SHtml, DispatchSnippet, Templates }
import scala.xml._
import net.liftweb.util.Helpers._
import com.normation.rudder.web.model.{
  WBTextField, FormTracker, WBTextAreaField, WBSelectField, WBRadioField
}
import com.normation.rudder.repository._
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import CreateRulePopup._
import com.normation.rudder.domain.eventlog.AddRule
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.repository._
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.domain.queries.Query

class CreateCloneGroupPopup(
  nodeGroup : Option[NodeGroup],
  groupGenerator : Option[NodeGroup] = None,
  onSuccessCategory : (NodeGroupCategory) => JsCmd,
  onSuccessGroup : (NodeGroup) => JsCmd,
  onSuccessCallback : () => JsCmd = { () => Noop },
  onFailureCallback : () => JsCmd = { () => Noop } ) 
  extends DispatchSnippet with Loggable {
  
  private[this] val nodeGroupRepository = inject[NodeGroupRepository]
  private[this] val groupCategoryRepository = inject[NodeGroupCategoryRepository]
  private[this] val nodeInfoService = inject[NodeInfoService]
  private[this] val categories = groupCategoryRepository.getAllNonSystemCategories
  private[this] val uuidGen = inject[StringUuidGenerator]
  var createContainer = false 
  
  def dispatch = {
    case "popupContent" => { _ => popupContent }
  }

  def popupContent() : NodeSeq = {
    SHtml.ajaxForm( bind( "item", popupTemplate,
      "itemName" -> piName.toForm_!,
      "itemContainer" -> piContainer.toForm_!,
      "itemDescription" -> piDescription.toForm_!,
      "groupType" -> piStatic.toForm_!,
      "notifications" -> updateAndDisplayNotifications(formTracker),
      "cancel" -> SHtml.ajaxButton( "Cancel", { () => closePopup() } ) % ( "tabindex", "6" ),
      "save" -> SHtml.ajaxSubmit( "Save", onSubmit _ ) % ( "id", "createCOGSaveButton" ) % ( "tabindex", "5" )
    ) ) ++ Script( OnLoad( initJs ) )
  }
  
  def templatePath = List("templates-hidden", "Popup", "createCloneGroupPopup")
  
  def template() =  Templates(templatePath) match {
     case Empty | Failure(_,_,_) =>
       error("Template for creation popup not found. I was looking for %s.html"
         .format(templatePath.mkString("/")))
     case Full(n) => n
  }
    
  def popupTemplate = chooseTemplate("groups", "createCloneGroupPopup", template)
  
  private[this] def closePopup() : JsCmd = {
    JsRaw(""" $.modal.close();""")
  }
  
  private[this] def onFailure() : JsCmd = {
    onFailureCallback() & 
    updateFormClientSide()
  }
//    private[this] def onFailure : JsCmd = {
//    formTracker.addFormError(error("The form contains some errors, please correct them"))
//    updateFormClientSide()
//  }
  
  private[this] def error(msg:String) = <span class="error">{msg}</span>
  
  private[this] def onSubmit() : JsCmd = {
    if(formTracker.hasErrors) {
      onFailure()
    } else {
      // get the type of query :
      if (createContainer) {
        groupCategoryRepository.addGroupCategorytoCategory(
            new NodeGroupCategory(
              NodeGroupCategoryId(uuidGen.newUuid),
              piName.is,
              piDescription.is,
              Nil,
              Nil
            )
          , NodeGroupCategoryId(piContainer.is)
          , CurrentUser.getActor
          , Some("Node Group Category created by user from UI")
        ) match {
          case Full(x) => closePopup() & onSuccessCallback() & onSuccessCategory(x)
          case Empty =>
            logger.error("An error occurred while saving the category")
            formTracker.addFormError(error("An error occurred while saving the category"))
            onFailure
          case Failure(m,_,_) =>
            logger.error("An error occurred while saving the category:" + m)
            formTracker.addFormError(error(m))
            onFailure
        }
      } else {
        // we are creating a group
        nodeGroupRepository.createNodeGroup(
          piName.is,
          piDescription.is,
          nodeGroup.map(x => x.query).getOrElse(groupGenerator.flatMap(_.query)),
          {piStatic.is match { case "dynamic" => true ; case _ => false } },
          nodeGroup.map(x => x.serverList).getOrElse(Set[NodeId]()),
          NodeGroupCategoryId(piContainer.is),
          nodeGroup.map(x => x.isEnabled).getOrElse(true),
          CurrentUser.getActor,
          Some("Group created by user")
        ) match {
          case Full(x) => 
            closePopup() & 
            onSuccessCallback() & 
            onSuccessGroup(x.group)
          case Empty =>
            logger.error("An error occurred while saving the group")
            formTracker.addFormError(error("An error occurred while saving the group"))
            onFailure
          case Failure(m,_,_) =>
            logger.error("An error occurred while saving the group:" + m)
            formTracker.addFormError(error(m))
            onFailure 
        }
      }
    }
  }
  
  private[this] def updateAndDisplayNotifications(formTracker : FormTracker) : NodeSeq = {
    
    val notifications = formTracker.formErrors
    formTracker.cleanErrors
   
    if(notifications.isEmpty) {
      NodeSeq.Empty
    }
    else {
      val html = 
        <div id="notifications" class="notify">
          <ul class="field_errors">{notifications.map( n => <li>{n}</li>) }</ul>
        </div>
      html
    }
  }
  
  ///////////// fields for category settings ///////////////////
  
  private[this] val piName = {
      new WBTextField("Name", 
        nodeGroup.map(x => "Copy of <%s>".format(x.name)).getOrElse("")) {
      override def setFilter = notNull _ :: trim _ :: Nil
      override def errorClassName = ""
      override def inputField = super.inputField %("onkeydown" , "return processKey(event , 'createCOGSaveButton')") % ("tabindex","1")
      override def validations =
        valMinLen(3, "The name must have at least 3 characters") _ :: Nil
    }
  }

  private[this] val piDescription = new WBTextAreaField("Description", 
      nodeGroup.map(x => x.description).getOrElse("") ) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField = super.inputField  % ("style" -> "height:10em") % ("tabindex","3")
    override def errorClassName = ""
    override def validations =  Nil
  }

  private[this] val piStatic = 
    new WBRadioField("Group type", Seq("static", "dynamic"),  
        ((nodeGroup.map(x => x.isDynamic).getOrElse(false)) ? "dynamic" | "static"), {
    case "static" => 
      <span title="The list of member nodes is defined at creation and will not change automatically.">Static</span>
    case "dynamic" => 
      <span title="Nodes will be automatically added and removed so that the list of members always matches this group's search criteria.">Dynamic</span>
  }, Some(4)) {
    override def className = "rudderBaseFieldSelectClassName"
    override def errorClassName = "threeColErrors"
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField = super.inputField %("onkeydown" , "return processKey(event , 'createCOGSaveButton')")
  }

  private[this] val piContainer = new WBSelectField("Parent category",
      (categories.open_!.map(x => (x.id.value -> x.name))),
      "") {
    override def inputField = super.inputField %("onkeydown" , "return processKey(event , 'createCOGSaveButton')") % ("tabindex","2")
    override def className = "rudderBaseFieldSelectClassName"
  }
  
  private[this] def initJs : JsCmd = {
    JsRaw("correctButtons();") & 
    JsShowId("createGroupHiddable") & 
    {
      if (groupGenerator != None) {
        JsHideId("itemCreation")
      } else {
        Noop
      }
    }
  }
  
  private[this] val formTracker = {
    new FormTracker(piName, piDescription, piContainer, piStatic)
  }
    
  
  private[this] def updateFormClientSide() : JsCmd = {
    SetHtml("createCloneGroupContainer", popupContent())
  }
}