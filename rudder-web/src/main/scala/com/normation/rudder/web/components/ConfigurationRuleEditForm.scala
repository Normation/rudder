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

import com.normation.rudder.domain.policies._
import com.normation.rudder.services.policies.PolicyInstanceTargetService
import com.normation.rudder.batch.{AsyncDeploymentAgent,StartDeployment}
import com.normation.rudder.repository._
import com.normation.rudder.domain.policies._
import net.liftweb.http.js._
import JsCmds._
import com.normation.rudder.domain.nodes.{NodeGroup, NodeGroupCategoryId, NodeGroupCategory, NodeGroupId}
import com.normation.cfclerk.services.PolicyPackageService
import com.normation.cfclerk.domain.PolicyPackage
import JE._
import net.liftweb.common._
import net.liftweb.http._
import scala.xml._
import net.liftweb.util._
import net.liftweb.util.Helpers._
import com.normation.rudder.web.model._
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.domain.log.RudderEventActor
import com.normation.plugins.{SpringExtendableSnippet,SnippetExtensionKey}
import net.liftweb.json._
import com.normation.rudder.domain.log.{
  DeleteConfigurationRule,
  ModifyConfigurationRule
}
import com.normation.rudder.web.services.JsTreeUtilService


object ConfigurationRuleEditForm {
  
  /**
   * This is part of component static initialization.
   * Any page which contains (or may contains after an ajax request)
   * that component have to add the result of that method in it.
   */
  def staticInit:NodeSeq = 
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentRuleEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "staticInit", xml)
    }) openOr Nil
    
  private def body = 
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentRuleEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "body", xml) ++ Script(OnLoad(JsRaw("""$( "#editCrZone" ).tabs()""") ))
    }) openOr Nil

  private def crForm = 
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentRuleEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "form", xml)
    }) openOr Nil

    
  val htmlId_groupTree = "groupTree"
  val htmlId_userTree = "userPiTree"
}

/**
 * The form that handles policy instance edition
 * (not creation)
 * - update name, description, etc
 * - update parameters 
 *
 * It handle save itself (TODO: see how to interact with the component parent)
 *
 * Parameters can not be null. 
 * 
 * Injection should not be used in components
 * ( WHY ? I will try to see...)
 * 
 */
class ConfigurationRuleEditForm(
  htmlId_configurationRule:String, //HTML id for the div around the form
  val configurationRule:ConfigurationRule, //the configuration rule to edit
  //JS to execute on form success (update UI parts)
  //there are call by name to have the context matching their execution when called
  onSuccessCallback : () => JsCmd = { () => Noop },
  onFailureCallback : () => JsCmd = { () => Noop }
) extends DispatchSnippet with SpringExtendableSnippet[ConfigurationRuleEditForm] with Loggable {
  import ConfigurationRuleEditForm._
  
  private[this] val htmlId_save = htmlId_configurationRule + "Save"

  private[this] val configurationRuleRepository = inject[ConfigurationRuleRepository]
  private[this] val targetInfoService = inject[PolicyInstanceTargetService]
  private[this] val policyInstanceRepository = inject[PolicyInstanceRepository]
  private[this] val userPolicyTemplateCategoryRepository = inject[UserPolicyTemplateCategoryRepository]
  private[this] val userPolicyTemplateRepository = inject[UserPolicyTemplateRepository]
  private[this] val policyPackageService = inject[PolicyPackageService]
  private[this] val uuidGen = inject[StringUuidGenerator]
  private[this] val asyncDeploymentAgent = inject[AsyncDeploymentAgent]
  
  private[this] var crCurrentStatusIsActivated = configurationRule.isActivatedStatus

  private[this] var selectedTarget = configurationRule.target
  private[this] var selectedPis = configurationRule.policyInstanceIds

  private[this] val groupCategoryRepository = inject[GroupCategoryRepository]
  private[this] val nodeGroupRepository = inject[NodeGroupRepository]

  private[this] val rootCategoryId = groupCategoryRepository.getRootCategory.id
  private[this] val treeUtilService = inject[JsTreeUtilService]

  //////////////////////////// public methods ////////////////////////////
  val extendsAt = SnippetExtensionKey(classOf[ConfigurationRuleEditForm].getSimpleName)
  
  def mainDispatch = Map( 
    "showForm" -> { _:NodeSeq => showForm }
  )

  private[this] def showForm() : NodeSeq = {
    (
        "#editForm" #> showCrForm()
    )(body)
        
    
  }
  private[this] def showCrForm() : NodeSeq = {    
    (
      "#editForm *" #> { (n:NodeSeq) => SHtml.ajaxForm(n) } andThen
      ClearClearable &
      //activation button: show disactivate if activated
      "#disactivateButtonLabel" #> { if(crCurrentStatusIsActivated) "Disable" else "Enable" } &
      "#dialogDisactivateButton" #> { disactivateButton % ("id", "disactivateButton") } &
      "#dialogDeactivateTitle" #> { if(crCurrentStatusIsActivated) "Disable" else "Enable" } &
      "#dialogDisactivateLabel" #> { if(crCurrentStatusIsActivated) "disable" else "enable" } &
      "#dialogRemoveButton" #> { removeButton % ("id", "removeButton") } &
      "#nameField" #> crName.toForm_! &
      "#shortDescriptionField" #> crShortDescription.toForm_! &
      "#longDescriptionField" #> crLongDescription.toForm_! &
      "#selectPiField" #> {<div id={htmlId_userTree}>
            <ul>{userPolicyTemplateCategoryToJsTreeNode(userPolicyTemplateCategoryRepository.getUserPolicyTemplateLibrary).toXml}</ul>
           </div> } &
      "#selectGroupField" #> { <div id={htmlId_groupTree}>
            <ul>{nodeGroupCategoryToJsTreeNode(groupCategoryRepository.getRootCategory).toXml}</ul>
          </div> } &
      "#save" #> saveButton &
      "#notification *" #>  updateAndDisplayNotifications() &
      "#editForm [id]" #> htmlId_configurationRule
    )(crForm) ++ 
    Script(OnLoad(JsRaw("""
    	correctButtons();
      $('#removeButton').click(function() {
        $('#removeActionDialog').modal({
          minHeight:140,
          minWidth: 850,
      		maxWidth: 850
        });
        $('#simplemodal-container').css('height', 'auto');
        return false;
      });

      $('#disactivateButton').click(function() {
        $('#desactivateActionDialog').modal({
          minHeight:140,
          minWidth: 850,
      		maxWidth: 850
        });
        $('#simplemodal-container').css('height', 'auto');
        return false;
      });
    """)))++ Script(
        //a function to update the list of currently selected PI in the tree
        //and put the json string of ids in the hidden field. 
        JsCrVar("updateSelectedPis", AnonFunc(JsRaw("""
          $('#selectedPis').val(JSON.stringify(
            $.jstree._reference('#%s').get_selected().map(function(){
              return this.id;
            }).get()));""".format(htmlId_userTree) 
        ))) &
      OnLoad(
        //build jstree and
        //init bind callback to move
        JsRaw("buildGroupTree('#%1$s', '%2$s');".format(  htmlId_groupTree,
          selectedTarget.collect { case GroupTarget(groupId) => 
            "jsTree-" + groupId.value
          }.mkString(",")
        )) &
        //function to update list of PIs before submiting form
        JsRaw("buildConfigurationRulePIdepTree('#%1$s', %2$s);".format(  
            htmlId_userTree,
            serializePiIds(selectedPis.toSeq)
        )) &
        After(TimeSpan(50), JsRaw("""createTooltip();"""))
      )
    )
  }

  
  
  /*
   * from a list of PI ids, get a string.
   * the format is a JSON array: [ "id1", "id2", ...] 
   */
  private[this] def serializePiIds(ids:Seq[PolicyInstanceId]) : String = {
    implicit val formats = Serialization.formats(NoTypeHints)
    Serialization.write(ids.map( "jsTree-" + _.value ))
  }
  
  /*
   * from a JSON array: [ "id1", "id2", ...], get the list of
   * policy instance Ids. 
   * Never fails, but returned an empty list. 
   */
  private[this] def unserializePiIds(ids:String) : Seq[PolicyInstanceId] = {
    implicit val formats = DefaultFormats 
    parse(ids).extract[List[String]].map( x => PolicyInstanceId(x.replace("jsTree-","")) )
  }
    
  ////////////// Callbacks //////////////
  
  
  private[this] def onSuccess() : JsCmd = {
    //MUST BE THIS WAY, because the parent may change some reference to JsNode
    //and so, our AJAX could be broken
    onSuccessCallback() & updateFormClientSide() & 
    //show success popup
    successPopup
  }
  
  private[this] def onFailure() : JsCmd = {
    formTracker.addFormError(error("The form contains some errors, please correct them"))
    onFailureCallback() & updateFormClientSide() & JsRaw("""scrollToElement("errorNotification");""")
  }
  
  ///////////// Remove ///////////// 
    
  private[this] def removeButton : Elem = {
    def removeCr() : JsCmd = {
      JsRaw("$.modal.close();") & 
      { 
        (for {
          save <- configurationRuleRepository.delete(configurationRule.id, CurrentUser.getActor)
          deploy <- {
            asyncDeploymentAgent ! StartDeployment(RudderEventActor)
            Full("Deployment request sent")
          }
        } yield {
          save 
        }) match {
          case Full(x) => 
            onSuccessCallback() & 
            SetHtml(htmlId_configurationRule, <div id={htmlId_configurationRule}>Configuration rule successfully deleted</div> ) & 
            //show success popup
            successPopup 
          case Empty => //arg. 
            formTracker.addFormError(error("An error occurred while deleting the configuration rule"))
            onFailure
          case Failure(m,_,_) =>
            formTracker.addFormError(error("An error occurred while saving the configuration rule: " + m))
            onFailure
        }
      }
    }
    
    SHtml.ajaxButton(<span class="red">Delete</span>, removeCr _ )
  }
  
  
  ///////////// Activation / disactivation ///////////// 
  
  
  private[this] def disactivateButton : Elem = {
    def switchActivation(status:Boolean)() : JsCmd = {
      crCurrentStatusIsActivated = status
      JsRaw("$.modal.close();") & 
      saveAndDeployConfigurationRule(configurationRule.copy(isActivatedStatus = status))
    }
    
    if(crCurrentStatusIsActivated) {
      SHtml.ajaxButton(<span class="red">Disable</span>, switchActivation(false) _ )
    } else {
      SHtml.ajaxButton(<span class="red">Enable</span>, switchActivation(true) _ )
    }
  }
  
  /*
   * Create the ajax save button
   */
  private[this] def saveButton : NodeSeq = { 
      //add an hidden field to hold the list of selected pis
      val save = SHtml.ajaxSubmit("Save", onSubmit _) % ("id" -> htmlId_save) 
      //update onclick to get the list of pi in the hidden field before submitting

      val newOnclick = "updateSelectedPis();" + save.attributes.asAttrMap("onclick")


      SHtml.hidden( { ids => 
          selectedPis = unserializePiIds(ids).toSet
        }, serializePiIds(selectedPis.toSeq) 
      ) % ( "id" -> "selectedPis") ++ 
      save % ( "onclick" -> newOnclick)
  }
  
  
  /////////////////////////////////////////////////////////////////////////
  /////////////////////////////// Edit form ///////////////////////////////
  /////////////////////////////////////////////////////////////////////////

  ///////////// fields for Configuration Rule settings ///////////////////
  
  private[this] val crName = new WBTextField("Name: ", configurationRule.name) {
    override def displayNameHtml = Some(<b>{displayName}</b>)
    override def setFilter = notNull _ :: trim _ :: Nil
    override def validations = 
      valMinLen(3, "The name must have at least 3 characters") _ :: Nil
  }
  
  private[this] val crShortDescription = new WBTextField("Short description: ", configurationRule.shortDescription) {
    override def displayNameHtml = Some(<b>{displayName}</b>)
    override def setFilter = notNull _ :: trim _ :: Nil
    override val maxLen = 255
    override def validations =  Nil
  }
  
  private[this] val crLongDescription = new WBTextAreaField("Description: ", configurationRule.longDescription.toString) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField = super.inputField  % ("style" -> "width:50em;height:15em")
  }

  private[this] def setCurrentNodeGroup(group : NodeGroup) = {
    selectedTarget = Some(GroupTarget(group.id))
  }

  private[this] val formTracker = new FormTracker(crName,crShortDescription,crLongDescription)
  
  private[this] var notifications = List.empty[NodeSeq]
  
  
  private[this] def activateButtonOnChange() : JsCmd = {
    JsRaw("""activateButtonOnFormChange("%s", "%s");  """.format(htmlId_configurationRule,htmlId_save) )
  }
  
  private[this] def updateFormClientSide() : JsCmd = {
    Replace(htmlId_configurationRule, this.showCrForm )
  }
  
  private[this] def error(msg:String) = <span class="error">{msg}</span>
  
    
  private[this] def onSubmit() : JsCmd = {
    if(formTracker.hasErrors) {
      onFailure
    } else { //try to save the cr
      val newCr = configurationRule.copy(
        name = crName.is,
        shortDescription = crShortDescription.is,
        longDescription = crLongDescription.is,
        target = selectedTarget,
        policyInstanceIds = selectedPis,
        isActivatedStatus = crCurrentStatusIsActivated
      )
      
      saveAndDeployConfigurationRule(newCr)
    }
  }
  
  private[this] def saveAndDeployConfigurationRule(cr:ConfigurationRule) : JsCmd = {
      (for {
        save <- configurationRuleRepository.update(cr, CurrentUser.getActor)
        deploy <- {
          asyncDeploymentAgent ! StartDeployment(RudderEventActor)
          Full("Deployment request sent")
        }
      } yield {
        save 
      }) match {
        case Full(x) => 
          onSuccess
        case Empty => //arg. 
          formTracker.addFormError(error("An error occurred while saving the configuration rule"))
          onFailure
        case f:Failure =>
          formTracker.addFormError(error("An error occurred while saving the configuration rule: " + f.messageChain))
          onFailure
      }      
  }
  
  private[this] def updateAndDisplayNotifications() : NodeSeq = {
    notifications :::= formTracker.formErrors
    formTracker.cleanErrors
   
    if(notifications.isEmpty) NodeSeq.Empty
    else {
      val html = <div id="errorNotification" class="notify"><ul>{notifications.map( n => <li>{n}</li>) }</ul></div>
      notifications = Nil
      html
    }
  }
  
  ///////////// success pop-up ///////////////
  private[this] def successPopup : JsCmd = {
    JsRaw(""" callPopupWithTimeout(200, "successConfirmationDialog", 100, 350)     
    """)
  }
    
 /********************************************
  * Utilitary methods for JS
  ********************************************/

  /**
   * Transform a NodeGroupCategory into category JsTree node :
   * - contains:
   *   - other categories
   *   - groups
   * -
   */

  private def nodeGroupCategoryToJsTreeNode(category:NodeGroupCategory) : JsTreeNode = new JsTreeNode {

    override def body = {
      val tooltipid = Helpers.nextFuncName
        <a href="#"><span class="treeGroupCategoryName tooltipable" tooltipid={tooltipid} title={category.description}>{category.name}</span></a>
        <div class="tooltipContent" id={tooltipid}><h3>{category.name}</h3><div>{category.description}</div></div>
      }

    override def children = category.children.flatMap(x => nodeGroupCategoryIdToJsTreeNode(x)) ++ category.items.map(x => policyTargetInfoToJsTreeNode(x))
    override val attrs =
      ( "rel" -> { if(category.id == rootCategoryId) "root-category" else "category" } ) ::
      ( "catId" -> category.id.value ) ::
      ( "class" -> "" ) ::
      Nil
  }


  //fetch server group category id and transform it to a tree node
  private def nodeGroupCategoryIdToJsTreeNode(id:NodeGroupCategoryId) : Box[JsTreeNode] = {
    groupCategoryRepository.getGroupCategory(id) match {
      //remove sytem category
      case Full(category) => category.isSystem match {
        case true => Empty
        case false => Full(nodeGroupCategoryToJsTreeNode(category))
      }
      case e:EmptyBox =>
        val f = e ?~! "Error while fetching policy template category %s".format(id)
        logger.error(f.messageChain)
        f
    }
  }

  //fetch server group id and transform it to a tree node
  private def policyTargetInfoToJsTreeNode(targetInfo:PolicyInstanceTargetInfo) : JsTreeNode = {
    targetInfo.target match {
      case GroupTarget(id) =>
        nodeGroupRepository.getNodeGroup(id) match {
          case Full(group) => nodeGroupToJsTreeNode(group)
          case _ => new JsTreeNode {
            override def body = <span class="error">Can not find node {id.value}</span>
            override def children = Nil
          }
        }
      case x => new JsTreeNode {
         override def body =  {
           val tooltipid = Helpers.nextFuncName
           <span class="treeGroupName tooltipable" tooltipid={tooltipid} >{targetInfo.name} <span title={targetInfo.description} class="greyscala">(special)</span>
           <div class="tooltipContent" id={tooltipid}><h3>{targetInfo.name}</h3><div>{targetInfo.description}</div></div>
           </span>
         }
         											
         override def children = Nil
         override val attrs = ( "rel" -> "special_target" ) :: Nil
      }
    }
  }


  /**
   * Transform a WBNodeGroup into a JsTree leaf.
   */
  private def nodeGroupToJsTreeNode(group : NodeGroup) : JsTreeNode = new JsTreeNode {
    //ajax function that update the bottom
    def onClickNode() : JsCmd = {
      setCurrentNodeGroup(group)
      Noop
    }

    override def body = {
        val tooltipid = Helpers.nextFuncName
        SHtml.a(onClickNode _,
        <span class="treeGroupName tooltipable" tooltipid={tooltipid} title={group.description}>{List(group.name,group.isDynamic?"dynamic"|"static").mkString(": ")}</span>
        <div class="tooltipContent" id={tooltipid}><h3>{group.name}</h3><div>{group.description}</div></div>)    
    }
    override def children = Nil
    override val attrs =
      ( "rel" -> "group" ) ::
      ( "groupId" -> group.id.value ) ::
      ( "id" -> ("jsTree-" + group.id.value) ) ::
      Nil
  }


  /**
   * Transform UserPolicyTemplateCategory into category JsTree nodes in User Library:
   * - contains
   *   - other user categories
   *   - user policy templates

   */
  private def userPolicyTemplateCategoryToJsTreeNode(category:UserPolicyTemplateCategory) : JsTreeNode = {
    def userPolicyTemplateIdToJsTreeNode(id : UserPolicyTemplateId) : (JsTreeNode, Option[PolicyPackage]) = {
      def userPolicyTemplateToJsTreeNode(upt : UserPolicyTemplate, pt:PolicyPackage) : JsTreeNode = {
        
        //check policy instance existence and transform it to a tree node
        def policyInstanceIdToJsTreeNode(piId : PolicyInstanceId) : (JsTreeNode,Option[PolicyInstance]) = {
          
          policyInstanceRepository.getPolicyInstance(piId) match {
            case Full(pi) =>  (
              new JsTreeNode {
                override def body = {
                  val tooltipid = Helpers.nextFuncName
                  <a>
                    <span class="treePolicyInstance tooltipable" tooltipid={tooltipid} title={pi.shortDescription}>{pi.name}</span>
                    <div class="tooltipContent" id={tooltipid}><h3>{pi.name}</h3><div>{pi.shortDescription}</div></div>
                  </a>
                }
                override def children = Nil
                override val attrs = ( "rel" -> "policy") :: ( "id" -> ("jsTree-" + pi.id.value) ) :: (if(!pi.isActivated) ("class" -> "disableTreeNode") :: Nil else Nil )
              },
              Some(pi)
            )
            case x =>
              logger.error("Error while fetching node %s: %s".format(piId, x.toString))
              (new JsTreeNode {
                override def body = <span class="error">Can not find node {piId.value}</span>
                override def children = Nil
              }, 
              None)
          }
        }
        
        new JsTreeNode {      
          override val attrs = ( "rel" -> "template") :: Nil ::: (if(!upt.isActivated) ("class" -> "disableTreeNode") :: Nil else Nil )
          override def body = {
              val tooltipid = Helpers.nextFuncName            
              <a href="#">
                  <span class="treeUptName tooltipable" tooltipid={tooltipid} title={pt.description}>{pt.name}</span>
                  <div class="tooltipContent" id={tooltipid}><h3>{pt.name}</h3><div>{pt.description}</div></div>
              </a>
          }
      
          override def children = upt.policyInstances.map(x => policyInstanceIdToJsTreeNode(x)).toList.
              sortWith { 
                case ( (_, None) , _  ) => true
                case (  _ ,  (_, None)) => false
                case ( (node1, Some(pi1)), (node2, Some(pi2)) ) => treeUtilService.sortPi(pi1,pi2)
              }.map { case (node, _) => node }
        }
      }
      
      userPolicyTemplateRepository.getUserPolicyTemplate(id) match {
        case Full(upt) => 
          policyPackageService.getLastPolicyByName(upt.referencePolicyTemplateName) match {
            case Some(refPt) => 
              ( userPolicyTemplateToJsTreeNode(upt,refPt), Some(refPt))

            case None => 
              (new JsTreeNode {
                override def body = <span class="error">Can not find node {upt.referencePolicyTemplateName}</span>
                override def children = Nil
              },None)
          }

        case x =>
          logger.error("Error while fetching node %s: %s".format(id, x.toString))
          (new JsTreeNode {
            override def body = <span class="error">Can not find node {id.value}</span>
            override def children = Nil
          },None)
        }
    }

    
    //chech user policy template category id and transform it to a tree node
    def userPolicyTemplateCategoryIdToJsTreeNode(id:UserPolicyTemplateCategoryId) : Box[(JsTreeNode,UserPolicyTemplateCategory)] = {
      userPolicyTemplateCategoryRepository.getUserPolicyTemplateCategory(id) match {
        //remove sytem category
        case Full(cat) => cat.isSystem match {
          case true => Empty
          case false => Full((userPolicyTemplateCategoryToJsTreeNode(cat),cat))
        }
        case e:EmptyBox =>
          val f = e ?~! "Error while fetching for policy template category %s".format(id)
          logger.error(f.messageChain)
          f
      }
    }

    new JsTreeNode {
      override def body = {
        val tooltipid = Helpers.nextFuncName
        <a>
        <span class="treeUptCategoryName tooltipable"  tooltipid={tooltipid} title={category.description}>{Text(category.name)}</span>
        <div class="tooltipContent" id={tooltipid}><h3>{category.name}</h3><div>{category.description}</div></div>
      </a>
      }
      override def children = {
        val sortedUpt = category.items.map(x => userPolicyTemplateIdToJsTreeNode(x)).toList.
            sortWith {
              case ( (_, None) , _ ) => true
              case ( _ , (_, None) ) => false
              case ( (node1, Some(refPt1)) , (node2, Some(refPt2)) ) => treeUtilService.sortPt(refPt1,refPt2)
            }.map { case (node,_) => node }
      
        val sortedCat = category.children.flatMap(x => userPolicyTemplateCategoryIdToJsTreeNode(x)).toList.
            sortWith {
              case ( (node1, cat1) , (node2, cat2) ) => treeUtilService.sortUptCategory(cat1,cat2)
            }.map { case (node,_) => node }
                              
        val res = sortedUpt ++ sortedCat
        res
      }
      override val attrs = ( "rel" -> "category") :: Nil
    }
  }
}
  
  
  
