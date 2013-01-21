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

import net.liftweb.http.js._
import JsCmds._ // For implicits
import JE._
import net.liftweb.common._
import net.liftweb.http.{SHtml,S,DispatchSnippet}
import scala.xml._
import net.liftweb.util._
import net.liftweb.util.Helpers._
import com.normation.rudder.domain.nodes.{NodeGroupCategory,NodeGroupCategoryId}

import com.normation.rudder.web.model.{
  WBTextField, FormTracker, WBTextAreaField,WBSelectField
}
import com.normation.rudder.repository._
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import com.normation.rudder.web.services.CategoryHierarchyDisplayer

/**
 * The form that deals with updating the server group category
 * 
 * @author Nicolas CHARLES
 *
 */
class NodeGroupCategoryForm(
	htmlIdCategory : String,
	nodeGroupCategory : NodeGroupCategory,
  onSuccessCallback : () => JsCmd = { () => Noop },
  onFailureCallback : () => JsCmd = { () => Noop }
) extends DispatchSnippet with Loggable {


  var _nodeGroupCategory = nodeGroupCategory.copy()

	val groupCategoryRepository = inject[GroupCategoryRepository]
	
	private[this] val categoryHierarchyDisplayer = inject[CategoryHierarchyDisplayer]
  
	val parentCategory = groupCategoryRepository.getParentGroupCategory(nodeGroupCategory.id )
	
	val parentCategoryId = parentCategory match {
		case Full(x) =>  x.id.value
		case _ => ""
	}
	
	def dispatch = { 
    case "showForm" => { _ => showForm }
  }
	
	def initJs : JsCmd = {
    JsRaw("correctButtons();")
  }
	   
	def showForm() : NodeSeq = {
		 val html = SHtml.ajaxForm(<fieldset class="groupCategoryUpdateComponent"><legend>Category: {nodeGroupCategory.name}</legend>
		 <pi:notifications />
		 <hr class="spacer"/>
		 <pi:name/>
		 <hr class="spacer"/>
		 <pi:description/>
		 <hr class="spacer"/>
		 <pi:container/>
		 <hr class="spacer"/>
		 <div style="margin:10px" align="right"><pi:save/> <pi:delete/></div>
		 </fieldset>) ++ Script(JsRaw("correctButtons();"))

    if (_nodeGroupCategory.isSystem) {
      ("input" #> {(n:NodeSeq) => n match {
        case input: Elem => input % ("disabled", "true")
      } } &
        "textarea" #> {(n:NodeSeq) => n match {
        case input: Elem => input % ("disabled", "true")
      } }) (bind("pi", html,
        "name" -> piName.toForm_! ,
        "description" -> piDescription.toForm_!,
        "container" -> piContainer.toForm_!,
        "save" -> SHtml.ajaxSubmit("Update", onSubmit _),
        "delete" -> deleteButton,
        "notifications" -> updateAndDisplayNotifications()
      ))
    } else {
       bind("pi", html,
        "name" -> piName.toForm_!,
        "description" -> piDescription.toForm_!,
        "container" -> piContainer.toForm_!,
        "save" -> SHtml.ajaxSubmit("Update", onSubmit _) ,
        "delete" -> deleteButton,
        "notifications" -> updateAndDisplayNotifications()
      )
    }
	 }
	 

	///////////// delete management /////////////
	
	/**
	 * Delete button is only enabled is that category
	 * has zero child
	 */
	private[this] def deleteButton : NodeSeq = {

	  if(parentCategory.isDefined && _nodeGroupCategory.children.isEmpty && _nodeGroupCategory.items.isEmpty) {
	    (
	      <button id="removeButton">Delete</button>
        <div id="removeActionDialog" style="display:none">
          <div class="simplemodal-title">
            <h1>Delete a group category</h1>
            <hr/>
          </div>
          <div class="simplemodal-content">    
	    			<div>
	    				<img src="/img/icWarn.png" alt="Warning!" height="32" width="32" style="float:left; margin-right:20px;"/>
            	<h3>Are you sure that you want to completely delete this category ?</h3>
	    			</div>
           	<hr class="spacer" />
        	</div>
					<div class="simplemodal-bottom">
	    			<hr/>
						<div class="popupButton">
				 			<span>
	    					<button class="simplemodal-close" onClick="return false;">Cancel</button>
              	{SHtml.ajaxButton("Delete", onDelete _)}
              </span>
            </div>
          </div>
        </div>
      ) ++
      Script(JsRaw("""
        $('#removeButton').click(function() {
          $('#removeActionDialog').modal({
            minHeight:140,
          	minWidth: 850,
      			maxWidth: 850
          });
          $('#simplemodal-container').css('height', 'auto');
          return false;
        });
        """))
	  } else {
	    <button disabled="disabled">Delete</button><br/>
	    <span style="font-size:0.9em; margin-left: 5px;">Only empty and non root categories can be deleted</span>
	  }
	}
	
	private[this] def onDelete() : JsCmd = {
	  groupCategoryRepository.delete(_nodeGroupCategory.id) match {
	    case Full(id) => 
	      JsRaw("""$.modal.close();""") &
	      SetHtml(htmlIdCategory, NodeSeq.Empty) &
	      onSuccessCallback() &
	      successPopup()
	    case e:EmptyBox =>    
	      val m = (e ?~! "Error when trying to delete the category").messageChain
	      formTracker.addFormError(error(m))
	      updateFormClientSide & onFailureCallback()
	  } 
	}
	
	
  ///////////// fields for category settings ///////////////////
  private[this] val piName = new WBTextField("Category name: ", _nodeGroupCategory.name) {
    override def displayNameHtml = Some(<b>{displayName}</b>)
    override def setFilter = notNull _ :: trim _ :: Nil
    override def validations = 
      valMinLen(3, "The name must have at least 3 characters") _ :: Nil
  }
  
	private[this] val piDescription = new WBTextAreaField("Category description: ", _nodeGroupCategory.description.toString) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField = super.inputField  % ("style" -> "height:10em")

    override def toForm_! = bind("field", 
    <div class="wbBaseField">
    	<field:errors />
      <label for={id} class="wbBaseFieldLabel threeCol" style="text-align:right"><b><field:label /></b></label>
      <field:input />
      <field:infos />
      
    </div>,
    "label" -> displayHtml,
    "input" -> inputField % ( "id" -> id) % ("class" -> "largeCol"),
    "infos" -> (helpAsHtml openOr NodeSeq.Empty),
    "errors" -> {
      errors match {
        case Nil => NodeSeq.Empty
        case l => 
          <span><ul class="field_errors" style="padding:0px 0px 0px 15px;">{
            l.map(e => <li class="field_error" style="padding:0px;">{e.msg}</li>)
          }</ul></span><hr class="spacer"/>
      }
    }
  )
  }
	
	/**
	 * If there is no parent, it is its own parent
	 */
	private[this] val piContainer = parentCategory match {
		case x:EmptyBox => new WBSelectField("Parent category: ", Seq(_nodeGroupCategory.id.value -> _nodeGroupCategory.name), _nodeGroupCategory.id .value, Seq("disabled"->"true")) {
			}
		case Full(category) => new WBSelectField("Parent category: "
		    , categoryHierarchyDisplayer.getCategoriesHierarchy().
		        map { case (id, name) => (id.value -> name)}, parentCategoryId) {
			}    
  }
	
	
	private[this] val formTracker = new FormTracker(piName,piDescription,piContainer)
  
  private[this] var notifications = List.empty[NodeSeq]
  
	private[this] def updateFormClientSide : JsCmd = {
    SetHtml(htmlIdCategory, showForm()) &
    initJs
  }
  
  private[this] def error(msg:String) = <span class="error">{msg}</span>
  
  
  private[this] def onSuccess : JsCmd = {
      
    notifications ::=  <span style="color:green;">Category was correctly updated</span>
    updateFormClientSide
  }
  
  private[this] def onFailure : JsCmd = {
    formTracker.addFormError(error("The form contains some errors, please correct them"))
    updateFormClientSide & JsRaw("""scrollToElement("errorNotification");""")
  }
  
  private[this] def onSubmit() : JsCmd = {
    if(formTracker.hasErrors) {
      onFailure & onFailureCallback()
    } else {

      // create the new NodeGroupCategory
      val newNodeGroup = new NodeGroupCategory(
        _nodeGroupCategory.id,
        piName.is,
        piDescription.is,
        _nodeGroupCategory.children,
        _nodeGroupCategory.items,
        _nodeGroupCategory.isSystem
      )

      groupCategoryRepository.saveGroupCategory(newNodeGroup,
            NodeGroupCategoryId(piContainer.is)) match {
        case Full(x) =>
    			_nodeGroupCategory = x
    			onSuccess & onSuccessCallback() & successPopup
    		case Empty =>
    			logger.error("An error occurred while saving the GroupCategory")
    		 	formTracker.addFormError(error("An error occurred while saving the GroupCategory"))
          onFailure & onFailureCallback()
        case Failure(m,_,_) =>
    			logger.error("An error occurred while saving the GroupCategory:" + m)
          formTracker.addFormError(error("An error occurred while saving the GroupCategory: " + m))
          onFailure & onFailureCallback()
      }

    }
  }
  
  private[this] def updateAndDisplayNotifications() : NodeSeq = {
    notifications :::= formTracker.formErrors
    formTracker.cleanErrors
   
    if(notifications.isEmpty) NodeSeq.Empty
    else {
      val html = <div  id="errorNotification" style="text-align:center;margin:10px;"><ul>{notifications.map( n => <li>{n}</li>) }</ul></div>
      notifications = Nil
      html
    }
  }

  ///////////// success pop-up ///////////////
  private[this] def successPopup() : JsCmd = {
    JsRaw("""
      setTimeout(function() { $("#succesConfirmationDialog").modal({
          minHeight:100,
          minWidth: 350
        });
        $('#simplemodal-container').css('height', 'auto');
      }, 200);
    """)
  }

}
