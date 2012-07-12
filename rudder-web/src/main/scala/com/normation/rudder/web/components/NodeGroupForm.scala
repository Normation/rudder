/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.rudder.web.components

import com.normation.rudder.domain.nodes._
import com.normation.inventory.domain.NodeId
import org.slf4j.LoggerFactory
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.queries.Query
import net.liftweb.http.LocalSnippet
import com.normation.rudder.services.policies.DependencyAndDeletionService
import com.normation.rudder.batch.{AsyncDeploymentAgent,AutomaticStartDeployment}
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.authorization._

import net.liftweb.http.js._
import JsCmds._ // For implicits
import JE._
import net.liftweb.common._
import net.liftweb.http._
import scala.xml._
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers._

import com.normation.rudder.domain.policies.GroupTarget

import com.normation.rudder.web.model.{
  WBTextField, FormTracker, WBTextAreaField,WBSelectField,WBRadioField
}
import com.normation.rudder.repository._
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import com.normation.rudder.services.nodes.NodeInfoService
import NodeGroupForm._
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.web.services.UserPropertyService
import com.normation.rudder.web.components.popup.CreateCloneGroupPopup
import com.normation.utils.HashcodeCaching

object NodeGroupForm {
  
 /**
  * Add that in the calling page, NOT in the <head> tag 
  */
  def staticInit:NodeSeq =
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "NodeGroupForm" :: Nil)
    } yield {
      chooseTemplate("component", "staticInit", xml)
    }) openOr Nil

 /**
  * Add that in the calling page somewhere in the body
  */
  def staticBody:NodeSeq =
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "NodeGroupForm" :: Nil)
    } yield {
      chooseTemplate("component", "staticBody", xml)
    }) openOr Nil

    
    
    
    
  private def body =
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "NodeGroupForm" :: Nil)
    } yield {
      chooseTemplate("component", "body", xml)
    }) openOr Nil

  
  private val saveButtonId = "groupSaveButtonId"
     
  private sealed trait RightPanel
  private case object NoPanel extends RightPanel
  private case class GroupForm(group:NodeGroup) extends RightPanel with HashcodeCaching
  private case class CategoryForm(category:NodeGroupCategory) extends RightPanel with HashcodeCaching
  
  val htmlId_groupTree = "groupTree"
  val htmlId_item = "ajaxItemContainer"
  val htmlId_updateContainerForm = "updateContainerForm"
}


/**
 * The form that deals with updating the server group
 * 
 * @author Nicolas CHARLES
 *
 */
class NodeGroupForm(
  htmlIdCategory : String,
  nodeGroup : Option[NodeGroup],
  onSuccessCallback : () => JsCmd = { () => Noop },
  onFailureCallback : () => JsCmd = { () => Noop }
) extends DispatchSnippet with Loggable {

  // I use a copy and a var to really isolate what is happening in this compenent from 
  // the argument, and I need to change the object when it is created (from None to Some(x)
  private[this] var _nodeGroup = nodeGroup.map(x => x.copy())
  
  private[this] val nodeGroupRepository = inject[NodeGroupRepository]
  private[this] val groupCategoryRepository = inject[NodeGroupCategoryRepository]
  private[this] val nodeInfoService = inject[NodeInfoService]
  private[this] val dependencyService = inject[DependencyAndDeletionService]
  private[this] val asyncDeploymentAgent = inject[AsyncDeploymentAgent]
  private[this] val userPropertyService = inject[UserPropertyService]
  
  val categories = groupCategoryRepository.getAllNonSystemCategories
  
  //the current nodeGroupCategoryForm component
  private[this] val nodeGroupCategoryForm = new LocalSnippet[NodeGroupCategoryForm] 
  
  var parentCategory = Option.empty[Box[NodeGroupCategory]]

  var parentCategoryId = ""

  //the current nodeGroupForm component
  private[this] val nodeGroupForm = new LocalSnippet[NodeGroupForm] 
  
  // Import the search server component
  val searchNodeComponent = new LocalSnippet[SearchNodeComponent] 
  
  var query : Option[Query] = _nodeGroup.flatMap(x => x.query)
  var srvList : Box[Seq[NodeInfo]] = _nodeGroup.map( x => nodeInfoService.find(x.serverList.toSeq) ).getOrElse(None)
  
  private def setNodeGroupCategoryForm : Unit = {
    updateLocalParentCategory()
    searchNodeComponent.set(Full(new SearchNodeComponent(
        htmlIdCategory
      , query
      , srvList
      , onSearchCallback = saveButtonCallBack
      , onClickCallback = { id => onClickCallBack(id) }
      , saveButtonId = saveButtonId
    )))
  }
  
  private[this] def saveButtonCallBack(searchStatus : Boolean) : JsCmd = {
    JsRaw("""$('#%s').prop("disabled", %s);""".format(saveButtonId, searchStatus))
  }
  
  private[this] def onClickCallBack(s:String) : JsCmd = {
    s.split("\\|").toList match {
      case _ :: id :: _ =>
        SetHtml("serverDetails", (new ShowNodeDetailsFromNode(new NodeId(id))).display(true)) &
        JsRaw( """ createPopup("nodeDetailsPopup",500,1000)
        """)
        
      case _ => Alert("Error when trying to display node details: received bad parameter for node ID: %s".format(s))
    }    
  }
  
  setNodeGroupCategoryForm
  
  
  def dispatch = { 
    case "showForm" => { _ => showForm }
    case "showGroup" => searchNodeComponent.is match {
      case Full(component) => { _ => component.buildQuery }
      case _ => x : NodeSeq => <div>The component is not set</div><div></div>
    }
  }
  
  def initJs : JsCmd = {
    JsRaw("correctButtons();")
  }
     
  def showForm() : NodeSeq = {
     val html = SHtml.ajaxForm(<fieldset class="groupUpdateComponent"><legend>Group: {_nodeGroup.map( x => x.name).getOrElse("Create a new group")}</legend>
     <directive:notifications />
     <hr class="spacer"/>
     <directive:name/>
     <hr class="spacer"/>
     <directive:description/>
     <hr class="spacer"/>
     <directive:container/>
     <hr class="spacer"/>
     <directive:static/>
     <hr class="spacer"/>
     <fieldset class="searchNodes"><legend>Group criteria</legend>
       <div id="SearchNodes">
       <directive:showGroup />
      </div>
     </fieldset>
     <lift:authz role="group_edit">
     <directive:reason />
     </lift:authz>
     <div class="margins" align="right">
       <lift:authz role="group_write"><directive:group/></lift:authz>
       <directive:save/>
       <lift:authz role="group_write"><directive:delete/></lift:authz>
     </div>
     </fieldset>)

     bind("directive", html,
      "name" -> piName.toForm_!,
      "description" -> piDescription.toForm_!,
      "container" -> piContainer.toForm_!,
      "static" -> piStatic.toForm_!,
      "showGroup" -> searchNodeComponent.is.open_!.buildQuery,
      "explanation" -> crReasons.map {
        f => <div>{userPropertyService.reasonsFieldExplanation}</div>
      },       
      "reason" -> crReasons.map {f =>
        <fieldset class="reasonNode"><legend>Reason</legend>
          <div style="margin-bottom:5px">
            {userPropertyService.reasonsFieldExplanation}
          </div>
          {f.toForm_!}
        </fieldset>},
      "group" -> cloneButton() ,
      "save" ->   {_nodeGroup match {
            case Some(x) => 
              if (CurrentUser.checkRights(Edit("group"))) 
                SHtml.ajaxSubmit("Update", onSubmit _)  %  ("id", saveButtonId) 
              else NodeSeq.Empty
            case None =>   
              if (CurrentUser.checkRights(Write("group")))  
                SHtml.ajaxSubmit("Save", onSubmit _) % ("id", saveButtonId)  
              else NodeSeq.Empty
          }},
      "delete" -> deleteButton()
//      "notifications" -> updateAndDisplayNotifications()
    ) 
   }
  
  
  ///////////// fields for category settings ///////////////////

  private[this] def cloneButton() : NodeSeq = {
    _nodeGroup match {
      case None => NodeSeq.Empty
      case Some(x) => SHtml.ajaxButton("Clone", () => showCloneGroupPopup()) % 
        ("id", "groupCloneButtonId")
    }
  }
  
  private[this] def deleteButton() : NodeSeq = {
    _nodeGroup match {
      case None => NodeSeq.Empty //we are creating a group, no need to show delete
      case Some(group) => 
        //pop-up: their content should be retrieve lazily
        val target = GroupTarget(group.id)
        val removePopupGridXml = dependencyService.targetDependencies(target).map( _.rules ) match {
          case e:EmptyBox => <div class="error">An error occurred while trying to find dependent item</div>
          case Full(rules) => {
            val cmp = new RuleGrid("remove_popup_grid", rules, None, false)
            cmp.rulesGrid(linkCompliancePopup=false)
          }
        }
        
        ( 
          <button id="removeButton">Delete</button> 
          <div id="removeActionDialog" class="nodisplay">
            <div class="simplemodal-title">
              <h1>Delete a group</h1>
              <hr/>
            </div>
            <div class="simplemodal-content">
              <div>
                <img src="/images/icWarn.png" alt="Warning!" height="32" width="32" class="warnicon"/>
                <h2>
                  Deleting this group will also remove it as a target for
                  the following Rules which depend on it.
                </h2>
              </div>
              <hr class="spacer" />
              <div id="removeItemDependencies">
                {removePopupGridXml}
              </div>
              <br />
              <br />
              <h3>Are you sure that you want to delete this item?</h3>
              <br />
              <hr class="spacer" />
            </div>
            <div class="simplemodal-bottom">
              <hr/>
              <div class="popupButton">
                 <span>
                  <button class="simplemodal-close" onClick="return false;">Cancel</button>
                  {removeButton(target)}
                </span>
              </div>
            </div>
          </div>        
        ) ++ {
          Script(JsRaw("""
            correctButtons();
            $('#removeButton').click(function() {
              createPopup("removeActionDialog",140,850);
              return false;
            });        
        """))
      }
    }
  }
  
  private[this] def removeButton(target:GroupTarget) : Elem = {
    def removeCr() : JsCmd = {
      JsRaw("$.modal.close();") &
      {
        (for {
          deleted <- dependencyService.cascadeDeleteTarget(target, CurrentUser.getActor, Some("Group removed by user"))
          deploy <- {
            asyncDeploymentAgent ! AutomaticStartDeployment(RudderEventActor)
            Full("Deployment request sent")
          }
        } yield {
          deploy
        }) match {
          case Full(x) =>
            onSuccessCallback() &
            SetHtml(htmlIdCategory, NodeSeq.Empty ) &
            //show success popup
            successPopup &
            initJs
          case Empty => //arg.
            formTracker.addFormError(error("An error occurred while deleting the group (no more information)"))
            onFailure
          case f@Failure(m,_,_) =>
            val msg = "An error occurred while saving the group: "
            logger.debug( f ?~! "An error occurred while saving the group: " , f)
            formTracker.addFormError(error(msg + m))
            onFailure
        }
      }
    }

    SHtml.ajaxButton(<span class="red">Remove</span>, removeCr _ )
  }  
  
  ///////////// fields for category settings ///////////////////
  private[this] val piName = {
    new WBTextField("Group name", _nodeGroup.map( x => x.name).getOrElse("")) {
      override def setFilter = notNull _ :: trim _ :: Nil
      override def className = "twoCol"
      override def validations = 
        valMinLen(3, "The name must have at least 3 characters") _ :: Nil
    }
  }
  
  private[this] val crReasons = {
    import com.normation.rudder.web.services.ReasonBehavior._
    userPropertyService.reasonsFieldBehavior match {
      case Disabled => None
      case Mandatory => Some(buildReasonField(true, "subContainerReasonField"))
      case Optionnal => Some(buildReasonField(false, "subContainerReasonField"))
    }
  }
  
  def buildReasonField(mandatory:Boolean, containerClass:String = "twoCol") = {
    new WBTextAreaField("Message", "") {
      override def setFilter = notNull _ :: trim _ :: Nil
      override def inputField = super.inputField  % 
        ("style" -> "height:8em;")
      override def subContainerClassName = containerClass
      override def validations() = {
        if(mandatory){
          valMinLen(5, "The reasons must have at least 5 characters.") _ :: Nil
        } else {
          Nil
        }
      }
    }
  }
  
  private[this] val piDescription = {
    new WBTextAreaField("Group description", _nodeGroup.map( x => x.description).getOrElse("")) {
      override def setFilter = notNull _ :: trim _ :: Nil
      override def inputField = super.inputField  % ("style" -> "height:10em")
      override def validations =  Nil
      override def errorClassName = "field_errors paddscala"
    }
  }
  
  private[this] val piStatic = {
    new WBRadioField(
        "Group type", 
        Seq("static", "dynamic"), 
        ((_nodeGroup.map( x => x.isDynamic).getOrElse(false)) ? "dynamic" | "static")) {
      override def setFilter = notNull _ :: trim _ :: Nil
    }
  }
  
  private[this] val piContainer = new WBSelectField("Group container", 
      (categories.open_!.map(x => (x.id.value -> x.name))),
      parentCategoryId) {
  }
  
  private[this] val formTracker = {
    val fields = List(piName, piDescription, piContainer, piStatic) ++ crReasons.toList
    new FormTracker(fields)
  }
  
  private[this] var notifications = List.empty[NodeSeq]
  
  private[this] def updateFormClientSide() : JsCmd = {
    SetHtml(htmlIdCategory, showForm()) & initJs
  }
  
  private[this] def error(msg:String) = <span class="error">{msg}</span>
  
  
  private[this] def onCreateSuccess : JsCmd = {
    notifications ::=  <span class="greenscala">The group was successfully created</span>
    updateFormClientSide
  }
  private[this] def onUpdateSuccess : JsCmd = {
    //notifications ::=  <span class="greenscala">The group was successfully updated</span>
    updateFormClientSide
  }
  
  private[this] def onFailure : JsCmd = {
    
    formTracker.addFormError(error("The form contains some errors, please correct them"))
    updateFormClientSide() & JsRaw("""scrollToElement("errorNotification");""")
  }
  
  private[this] def onSubmit() : JsCmd = {
    // Since we are doing the submit from the component, it ought to exist 
    query = searchNodeComponent.is.open_!.getQuery
    srvList = searchNodeComponent.is.open_!.getSrvList
    if(formTracker.hasErrors) {
      
      onFailure & onFailureCallback()
    } else {
      val name = piName.is
      val description = piDescription.is
      val container = piContainer.is
      val isDynamic = piStatic.is match { case "dynamic" => true ; case _ => false }
      
      _nodeGroup match {
        case None =>
          // we are only creating new group now
          srvList match {
            case Full(list) =>
              createGroup(name, description, query.get, isDynamic, list.map(x => x.id).toList, container)
            case Empty =>
              createGroup(name, description, query.get, isDynamic, Nil, container)
            case Failure(m, _, _) =>
              logger.error("Could not retrieve the server list from the search component: %s".format(m))
              formTracker.addFormError(error("An error occurred while trying to fetch the server list of the group: " + m))
              onFailure& onFailureCallback()
          }
        case Some(nodeGroup) =>
          //  we are updating a nodeGroup
          srvList match {
            case Full(list) =>
              updateGroup(nodeGroup, name, description, query.get, isDynamic, list.map(x => x.id).toList, container)
            case Empty =>
              updateGroup(nodeGroup, name, description, query.get, isDynamic, Nil, container)
            case Failure(m, _, _) =>
              logger.error("Could not retrieve the server list from the search component : %s".format(m))
              formTracker.addFormError(error("An error occurred while trying to fetch the server list of the group: " + m))
              onFailure& onFailureCallback()
          }
      }
    }
  }
  
  private[this] def showCloneGroupPopup() : JsCmd = {
    val popupSnippet = new LocalSnippet[CreateCloneGroupPopup]
             popupSnippet.set(Full(new CreateCloneGroupPopup(
            nodeGroup,
            onSuccessCategory = displayACategory,
            onSuccessGroup = showGroupSection,
            onSuccessCallback = { onSuccessCallback })))
    val nodeSeqPopup = popupSnippet.is match {
      case Failure(m, _, _) =>  <span class="error">Error: {m}</span>
      case Empty => <div>The component is not set</div>
      case Full(popup) => popup.popupContent()
    }
    SetHtml("createCloneGroupContainer", nodeSeqPopup) &
    JsRaw("""createPopup("createCloneGroupPopup", 300, 400)""")
  }
   
  private[this] def htmlTreeNodeId(id:String) = "jsTree-" + id
    
  private[this] def displayACategory(category : NodeGroupCategory) : JsCmd = {
    //update UI
    refreshRightPanel(CategoryForm(category))
  }
  
  private[this] def refreshRightPanel(panel:RightPanel) : JsCmd = SetHtml(htmlId_item, setAndShowRightPanel(panel))
  
  /**
   *  Manage the state of what should be displayed on the right panel.
   * It could be nothing, a group edit form, or a category edit form.
   */
  private[this] def setAndShowRightPanel(panel:RightPanel) : NodeSeq = {
    panel match {
      case NoPanel => NodeSeq.Empty
      case GroupForm(group) =>
        val form = new NodeGroupForm(htmlId_item, Some(group), () => refreshTree(htmlTreeNodeId(group.id.value)))
        nodeGroupForm.set(Full(form))
        form.showForm()

      case CategoryForm(category) =>
        val form = new NodeGroupCategoryForm(htmlId_item, category, () => refreshTree(htmlTreeNodeId(category.id.value)))
        nodeGroupCategoryForm.set(Full(form))
        form.showForm()
    }
  }  

  private[this] def showGroupSection(sg : NodeGroup) : JsCmd = {
    //update UI
    refreshRightPanel(GroupForm(sg))&
    JsRaw("""this.window.location.hash = "#" + JSON.stringify({'groupId':'%s'})""".format(sg.id.value))
  }  
  
  private[this] def refreshTree(selectedNode:String) : JsCmd =  {
//    Replace(htmlId_groupTree, buildGroupTree(selectedNode:String)) &
    OnLoad(After(TimeSpan(50), JsRaw("""createTooltip();""")))
  }
  
  /**
   * Create a group from the given parameter
   * Do not deploy on create (nobody can already be using that group)
   * @param name
   * @param description
   * @param query
   * @param isDynamic 
   * @param nodeList
   * @param container
   * @return
   */
  private def createGroup(name : String, description : String, query : Query, isDynamic : Boolean, nodeList : List[NodeId], container: String ) : JsCmd = {
    nodeGroupRepository.createNodeGroup(name, description, Some(query), isDynamic, nodeList.toSet, new NodeGroupCategoryId(container), true, CurrentUser.getActor, Some("Group created by user")) match {
        case Full(x) => 
          _nodeGroup = Some(x.group)
          
          setNodeGroupCategoryForm
          onCreateSuccess  & onSuccessCallback()
        case Empty =>
          setNodeGroupCategoryForm
          logger.error("An error occurred while saving the Group")
           formTracker.addFormError(error("An error occurred while saving the Group"))
          onFailure & onFailureCallback()
        case Failure(m,_,_) =>
          setNodeGroupCategoryForm
          logger.error("An error occurred while saving the Group:" + m)
          formTracker.addFormError(error("An error occurred while saving the Group: " + m))
          onFailure & onFailureCallback()
      }
  }

  /**
   * Update a group from the given parameter
   * Redeploy on update (perhaps the group is in use)
   * @param name
   * @param description
   * @param query
   * @param isDynamic
   * @param nodeList
   * @param container
   * @return
   */
  private def updateGroup(originalNodeGroup : NodeGroup, name : String, description : String, query : Query, isDynamic : Boolean, nodeList : List[NodeId], container:String, isEnabled : Boolean = true ) : JsCmd = {
    val newNodeGroup = new NodeGroup(originalNodeGroup.id, name, description, Some(query), isDynamic, nodeList.toSet, isEnabled, originalNodeGroup.isSystem)
    (for {
      moved <- nodeGroupRepository.move(originalNodeGroup, NodeGroupCategoryId(container), CurrentUser.getActor, crReasons.map(_.is)) ?~! "Error when moving NodeGroup %s ('%s') to '%s'".format(originalNodeGroup.id, originalNodeGroup.name, container)
      saved <- nodeGroupRepository.update(newNodeGroup, CurrentUser.getActor, crReasons.map(_.is)) ?~! "Error when updating the group %s".format(originalNodeGroup.id)
      deploy <- {
        asyncDeploymentAgent ! AutomaticStartDeployment(RudderEventActor)
        Full("Deployment request sent")
      }
    } yield {
      saved
    }) match {
        case Full(x) =>
          _nodeGroup = Some(newNodeGroup)

          setNodeGroupCategoryForm
          onUpdateSuccess  & onSuccessCallback()& successPopup
        case Empty =>
          setNodeGroupCategoryForm
          logger.error("An error occurred while updating the group")
           formTracker.addFormError(error("An error occurred while updating the group"))
          onFailure & onFailureCallback()
        case f:Failure =>
          setNodeGroupCategoryForm
          logger.error("An error occurred while updating the group:" + f.messageChain)
          formTracker.addFormError(error("An error occurred while updating the group: " + f.msg))
          onFailure & onFailureCallback()
      }
  }
  
  private[this] def updateAndDisplayNotifications() : NodeSeq = {
    notifications :::= formTracker.formErrors
    formTracker.cleanErrors
   
    if(notifications.isEmpty) NodeSeq.Empty
    else {
      val html = 
        <div id="errorNotification" class="notify">
          <ul class="field_errors">{notifications.map( n => <li>{n}</li>) }</ul>
        </div>
      notifications = Nil
      html
    }
  }


  private[this] def updateLocalParentCategory() : Unit = {
    parentCategory = _nodeGroup.map(x => nodeGroupRepository.getParentGroupCategory(x.id))

    parentCategoryId = parentCategory match {
      case Some(x) =>  x match {
        case Full(y) =>  y.id.value
        case _ => ""
      }
      case None => ""
    }
  }

  ///////////// success pop-up ///////////////
    private[this] def successPopup : JsCmd = {
    JsRaw(""" callPopupWithTimeout(200, "successConfirmationDialog", 100, 350)     
    """)
  }
}

