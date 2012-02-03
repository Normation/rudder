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

package com.normation.rudder.web.snippet.configuration

import com.normation.rudder.web.model._
import com.normation.rudder.domain.policies._
import com.normation.rudder.web.services.PolicyEditorService
import com.normation.cfclerk.domain._
import com.normation.cfclerk.services.PolicyPackageService
import com.normation.rudder.web.model.JsTreeNode
import net.liftweb.common._
import net.liftweb.http.{SHtml,S}
import scala.xml._
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.js._
import JsCmds._
import com.normation.rudder.web.components.popup.CreateUserPolicyTemplateCategoryPopup
import JE._
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers._
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.repository._
import com.normation.rudder.web.components.{
  PolicyTemplateEditForm, PolicyTemplateCategoryEditForm
}
import net.liftweb.http.LocalSnippet
import net.liftweb.json._
import com.normation.rudder.web.services.JsTreeUtilService
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import com.normation.cfclerk.services.UpdatePolicyTemplateLibrary
import net.liftweb.http.IdMemoizeTransform


/**
 * Snippet for managing the System and User PolicyTemplate libraries.
 * 
 * It allow to see what PolicyTemplates are available in the
 * system library, choose and configure which one to use in 
 * the user private library. 
 * 
 * PolicyTemplates are classify by categories in a tree. 
 *
 */
class PolicyTemplateLibraryManagement extends DispatchSnippet with Loggable {

  import PolicyTemplateLibraryManagement._
 
  //find policy template
  val policyPackageService = inject[PolicyPackageService]
  //find policy template
  val updatePTLibService = inject[UpdatePolicyTemplateLibrary]
  //find & create user categories
  val userPolicyTemplateCategoryRepository = inject[UserPolicyTemplateCategoryRepository]
  //find & create user policy templates
  val userPolicyTemplateRepository = inject[UserPolicyTemplateRepository]
  //generate new uuid
  val uuidGen = inject[StringUuidGenerator]
  //transform policy template variable to human viewable HTML fields
  val policyEditorService = inject[PolicyEditorService]
  val treeUtilService = inject[JsTreeUtilService]

  //the popup component to create user pt category
  private[this] val creationPopup = new LocalSnippet[CreateUserPolicyTemplateCategoryPopup]


  def dispatch = { 
    case "head" => { _ => head }
    case "systemLibrary" => { _ => systemLibrary }
    case "userLibrary" => { _ => userLibrary }
    case "bottomPanel" => { _ => showBottomPanel }
    case "userLibraryAction" => { _ => userLibraryAction }
    case "reloadPolicyTemplateLibrary" => reloadPolicyTemplateLibrary
  }

  
  //current states for the page - they will be kept only for the duration
  //of one request and its followng Ajax requests
  
  private[this] val rootCategoryId = userPolicyTemplateCategoryRepository.getUserPolicyTemplateLibrary.id
  
  private[this] val currentPolicyTemplateDetails = new LocalSnippet[PolicyTemplateEditForm]
  private[this] var currentPolicyTemplateCategoryDetails = new LocalSnippet[PolicyTemplateCategoryEditForm]
  
  private[this] val policyTemplateId: Box[String] = S.param("policyTemplateId")
    
  //create a new policy template edit form and update currentPolicyTemplateDetails
  private[this] def updateCurrentPolicyTemplateDetails(pt:PolicyPackage) = {
    currentPolicyTemplateDetails.set(Full(new PolicyTemplateEditForm(
        htmlId_bottomPanel,
        pt,
        currentPolicyTemplateCategoryDetails.is.map( _.getCategory ),
        { () => Replace(htmlId_userTree, userLibrary) } 
    )))
  }
  
  //create a new policy template edit form and update currentPolicyTemplateDetails
  private[this] def updateCurrentPolicyTemplateCategoryDetails(category:UserPolicyTemplateCategory) = {
    currentPolicyTemplateCategoryDetails.set(Full(new PolicyTemplateCategoryEditForm(
        htmlId_bottomPanel,
        category,
        rootCategoryId,
        { () => Replace(htmlId_userTree, userLibrary) } 
    )))
  }
  
  /**
   * Head information (JsTree dependencies,...)
   */
  def head() : NodeSeq = {
    PolicyTemplateEditForm.staticInit ++ 
    {
      <head>
        <script type="text/javascript" src="/javascript/jstree/jquery.jstree.js" id="jstree"></script>
        <script type="text/javascript" src="/javascript/rudder/tree.js" id="tree"></script>
      </head>
    }
  }
  
  private[this] def setCreationPopup : Unit = {
         creationPopup.set(Full(new CreateUserPolicyTemplateCategoryPopup(
            onSuccessCallback = { () => refreshTree })))
  }

  /**
   * Create the popup
   */
  private[this] def createPopup : NodeSeq = {
    creationPopup.is match {
      case Failure(m,_,_) =>  <span class="error">Error: {m}</span>
      case Empty => <div>The component is not set</div>
      case Full(popup) => popup.popupContent(NodeSeq.Empty)
    }
  }

  ////////////////////   
  /**
   * Display the PolicyTemplate system library, which is 
   * what policy template are known by the system. 
   * PolicyTemplate are classified by category, one policyTemplate
   * belonging at most to one category. 
   * Categories are ordered in trees of subcategories. 
   */
  def systemLibrary() : NodeSeq = {
    <div id={htmlId_referenceTree}>
      <ul>{jsTreeNodeOf_ptCategory(policyPackageService.getReferencePolicyTemplateLibrary).toXml}</ul>
      {Script(OnLoad(buildReferenceLibraryJsTree))}
    </div>  
  }

  /**
   * Display the actions bar of the user library
   */
  def userLibraryAction() : NodeSeq = {
    <div>{SHtml.ajaxButton("Create a new category", () => showCreateUserPTCategoryPopup(), ("class", "autoWidthButton"))}</div>
  }

  /**
   *  Display the PolicyTemplate user library, which is
   * what policy template are configurable as policy instance.
   * PolicyTemplate are classified by category, one policyTemplate
   * belonging at most to one category. 
   * Categories are ordered in trees of subcategories. 
   */
  def userLibrary() : NodeSeq = {
    <div id={htmlId_userTree}>
      <ul>{jsTreeNodeOf_uptCategory(userPolicyTemplateCategoryRepository.getUserPolicyTemplateLibrary).toXml}</ul>
      { 
        Script(OnLoad(
          buildUserLibraryJsTree &
          //init bind callback to move
          JsRaw("""
            // use global variables to store where the event come from to prevent infinite recursion
              var fromUser = false;
              var fromReference = false;

            $('#%1$s').bind("move_node.jstree", function (e,data) {
              var interTree = "%1$s" != data.rslt.ot.get_container().prop("id");
              var sourceCatId = $(data.rslt.o).prop("catId");
              var sourcePtId = $(data.rslt.o).prop("ptId");
              var destCatId = $(data.rslt.np).prop("catId");
              if( destCatId ) {
                if(sourcePtId) {
                  var arg = JSON.stringify({ 'sourcePtId' : sourcePtId, 'destCatId' : destCatId });
                  if(interTree) {
                    %2$s;
                  } else {
                    %3$s;
                  }
                } else if( sourceCatId ) {
                  var arg = JSON.stringify({ 'sourceCatId' : sourceCatId, 'destCatId' : destCatId });
                  %4$s;
                } else {  
                  alert("Can not move that kind of object");
                  $.jstree.rollback(data.rlbk);
                }
              } else {
                alert("Can not move to something else than a category");
                $.jstree.rollback(data.rlbk);
              }
            });
            $('#%1$s').bind("select_node.jstree", function (e,data) {
                var sourcePtId = data.rslt.obj.prop("ptid");
                var target = $('#%5$s  li[ptid|="'+sourcePtId+'"]');
                if (target.length>0) {
                  if (fromReference) {
                    fromReference = false;
                    return false;
                  }
                  fromUser = true;
                
                  $('#%5$s').jstree("select_node", target , true , null );
                }
            });
            $('#%5$s').bind("select_node.jstree", function (e,data) {
                var sourcePtId = data.rslt.obj.prop("ptid");
                var target = $('#%1$s  li[ptid|="'+sourcePtId+'"]');
                if (target.length>0) {
                   if (fromUser) {
                    fromUser = false;
                    return false;
                  }
                  fromReference = true;

                  $('#%1$s').jstree("select_node", target , true , null );
                }

            });
          """.format(
            // %1$s 
            htmlId_userTree , 
            // %2$s
            SHtml.ajaxCall(JsVar("arg"), bindPolicyTemplate _ )._2.toJsCmd,
            // %3$s
            SHtml.ajaxCall(JsVar("arg"), movePolicyTemplate _ )._2.toJsCmd,
            // %4$s
            SHtml.ajaxCall(JsVar("arg"), moveCategory _)._2.toJsCmd,
            
            htmlId_referenceTree
          )))
        )
      }
    </div>
  }
  
  def showBottomPanel : NodeSeq = {
    (for {
      ptName <- policyTemplateId
      pt <- policyPackageService.getLastPolicyByName(PolicyPackageName(ptName))
    } yield {
      pt
    }) match {
      case Full(pt) => 
        updateCurrentPolicyTemplateDetails(pt)
        showPolicyTemplateDetails()
      case _ => 
        <div id={htmlId_bottomPanel} class="centertext">
          Click on a policy template or a category from user library to
          display its details.
        </div>
    }
  }
  
  /**
   * Configure a system policyTemplate to be usable in the
   * user PolicyTemplate (private) library. 
   */
  def showPolicyTemplateDetails() : NodeSeq = {
    currentPolicyTemplateDetails.is match {
      case e:EmptyBox => 
        <div id={htmlId_bottomPanel}>
        <fieldset class="policyTemplateDetailsFieldset"><legend>Policy template details</legend>
          <p>Click on a policy template to display its details</p>
        </fieldset></div>
      case Full(form) => form.showForm
    }
  }  

  

  def showUserCategoryDetails() : NodeSeq = {
    currentPolicyTemplateCategoryDetails.is  match {
      case e:EmptyBox => <div id={htmlId_bottomPanel}><p>Click on a category from the user library to display its details and edit its properties</p></div>
      case Full(form) => form.showForm
    }
  }

  
  ///////////////////// Callback function for Drag'n'drop in the tree /////////////////////
  
  private[this] def movePolicyTemplate(arg: String) : JsCmd = {
    //parse arg, which have to  be json object with sourcePtId, destCatId
    try {
      (for { 
         JObject(child) <- JsonParser.parse(arg)
         JField("sourcePtId", JString(sourcePtId)) <- child 
         JField("destCatId", JString(destCatId)) <- child 
       } yield {
         (sourcePtId, destCatId)
       }) match {
        case (sourcePtId, destCatId) :: Nil =>
          (for {
            upt <- userPolicyTemplateRepository.getUserPolicyTemplate(PolicyPackageName(sourcePtId)) ?~! "Error while trying to find user policy template with requested id %s".format(sourcePtId)
            result <- userPolicyTemplateRepository.move(upt.id, UserPolicyTemplateCategoryId(destCatId), CurrentUser.getActor)?~! "Error while trying to move user policy template with requested id '%s' to category id '%s'".format(sourcePtId,destCatId)
          } yield {
            result
          }) match {
            case Full(res) => 
              refreshTree() & JsRaw("""setTimeout(function() { $("[ptId=%s]").effect("highlight", {}, 2000)}, 100)""".format(sourcePtId)) & refreshBottomPanel(res) 
            case f:Failure => Alert(f.messageChain + "\nPlease reload the page")
            case Empty => Alert("Error while trying to move user policy template with requested id '%s' to category id '%s'\nPlease reload the page.".format(sourcePtId,destCatId))
          }
        case _ => Alert("Error while trying to move user policy template: bad client parameters")
      }      
    } catch {
      case e:Exception => Alert("Error while trying to move user policy template")
    }
  }
   
  private[this] def moveCategory(arg: String) : JsCmd = {
    //parse arg, which have to  be json object with sourcePtId, destCatId
    try {
      (for { 
         JObject(child) <- JsonParser.parse(arg)
         JField("sourceCatId", JString(sourceCatId)) <- child 
         JField("destCatId", JString(destCatId)) <- child 
       } yield {
         (sourceCatId, destCatId)
       }) match {
        case (sourceCatId, destCatId) :: Nil =>
          (for {
            result <- userPolicyTemplateCategoryRepository.move(UserPolicyTemplateCategoryId(sourceCatId), UserPolicyTemplateCategoryId(destCatId), CurrentUser.getActor) ?~! "Error while trying to move category with requested id %s into new parent: %s".format(sourceCatId,destCatId)
          } yield {
            result
          }) match {
            case Full(res) => 
              refreshTree() & OnLoad(JsRaw("""setTimeout(function() { $("[catid=%s]").effect("highlight", {}, 2000);}, 100)""".format(sourceCatId)))  
            case f:Failure => Alert(f.messageChain + "\nPlease reload the page")
            case Empty => Alert("Error while trying to move category with requested id '%s' to category id '%s'\nPlease reload the page.".format(sourceCatId,destCatId))
          }
        case _ => Alert("Error while trying to move category: bad client parameters")
      }      
    } catch {
      case e:Exception => Alert("Error while trying to move category")
    } 
  }

  
  private[this] def bindPolicyTemplate(arg: String) : JsCmd = {
    //parse arg, which have to  be json object with sourcePtId, destCatId
    try {
      (for { 
         JObject(child) <- JsonParser.parse(arg)
         JField("sourcePtId", JString(sourcePtId)) <- child 
         JField("destCatId", JString(destCatId)) <- child 
       } yield {
         (sourcePtId, destCatId)
       }) match {
        case (sourcePtId, destCatId) :: Nil =>
          val ptName = PolicyPackageName(sourcePtId)
          (for {
            result <- userPolicyTemplateRepository.addPolicyTemplateInUserLibrary(UserPolicyTemplateCategoryId(destCatId), ptName, policyPackageService.getVersions(ptName).toSeq, CurrentUser.getActor) ?~! "Error while trying to add system policy template with requested id '%s' in user library category '%s'".format(sourcePtId,destCatId)
          } yield {
            result
          }) match {
            case Full(res) => 
              refreshTree() & JsRaw("""setTimeout(function() { $("[ptId=%s]").effect("highlight", {}, 2000)}, 100)""".format(sourcePtId)) & refreshBottomPanel(res.id) 
            case f:Failure => Alert(f.messageChain + "\nPlease reload the page")
            case Empty => Alert("Error while trying to move user policy template with requested id '%s' to category id '%s'\nPlease reload the page.".format(sourcePtId,destCatId))
          }
        case _ => Alert("Error while trying to move user policy template: bad client parameters")
      }      
    } catch {
      case e:Exception => Alert("Error while trying to move user policy template")
    }
  }

  private[this] def refreshTree() : JsCmd =  {
    Replace(htmlId_referenceTree, systemLibrary) &
    Replace(htmlId_userTree, userLibrary) &
    OnLoad(After(TimeSpan(100), JsRaw("""createTooltip();""")))
  }
  
  private[this] def refreshBottomPanel(id:UserPolicyTemplateId) : JsCmd = {
    for {
      upt <- userPolicyTemplateRepository.getUserPolicyTemplate(id)
      pt <- policyPackageService.getLastPolicyByName(upt.referencePolicyTemplateName)
    } { //TODO : check errors
      updateCurrentPolicyTemplateDetails(pt)
    }        
    Replace(htmlId_bottomPanel, showPolicyTemplateDetails() )
  }


  

  
  //////////////// display trees ////////////////////////  

  /**
   * Javascript to initialize the reference library tree
   */
  private[this] def buildReferenceLibraryJsTree : JsExp = JsRaw(
    """buildReferencePolicyTemplateTree('#%s','%s')""".format(htmlId_referenceTree,{
      policyTemplateId match {
        case Full(ptId) => "ref-pt-"+ptId
        case _ => ""
      }
    })
  )
    
  /**
   * Javascript to initialize the user library tree
   */
  private[this] def buildUserLibraryJsTree : JsExp = JsRaw(
    """buildUserPolicyTemplateTree('#%s', '%s')""".format(htmlId_userTree, htmlId_referenceTree)
  )

    
  
    
  //ajax function that update the bottom of the page when a policy template is clicked
  private[this] def onClickTemplateNode(pt : PolicyPackage): JsCmd = {
    updateCurrentPolicyTemplateDetails(pt)
      
    //update UI
    Replace(htmlId_bottomPanel, showPolicyTemplateDetails() )
  }

  

  
  /**
   * Transform a WBPolicyTemplateCategory into category JsTree node in reference library:
   * - contains:
   *   - other categories
   *   - policy templates
   * - no action can be done with such node. 
   */
  private[this] def jsTreeNodeOf_ptCategory(category:PolicyPackageCategory) : JsTreeNode = {


    def jsTreeNodeOf_pt(pt : PolicyPackage) : JsTreeNode = new JsTreeNode {
      override def body = {
        val tooltipid = Helpers.nextFuncName
        SHtml.a(
            { () => onClickTemplateNode(pt) },
            <span class="treePolicyTemplateName tooltipable" tooltipid={tooltipid} title={pt.description}>{pt.name}</span> 
            <div class="tooltipContent" id={tooltipid}><h3>{pt.name}</h3><div>{pt.description}</div></div>
        )
      }
      override def children = Nil
      override val attrs = ( "rel" -> "template") :: ( "id" -> ("ref-pt-"+pt.id.name.value) ) :: ( "ptId" -> pt.id.name.value ) :: Nil
    }
    
    new JsTreeNode {
      //actually transform a pt category to jsTree nodes:
      override def body = {
        val tooltipid = Helpers.nextFuncName
        <a href="#">
          <span class="treeUptCategoryName tooltipable" tooltipid={tooltipid} title={category.description}>{category.name}</span>
          <div class="tooltipContent" id={tooltipid}>
            <h3>{category.name}</h3>
            <div>{category.description}</div>
            </div>
        </a>
      }
      override def children = 
        category.subCategoryIds.flatMap(x => treeUtilService.getPtCategory(x,logger) ).
          toList.sortWith( treeUtilService.sortPtCategory( _ , _ ) ).
          map(jsTreeNodeOf_ptCategory(_)
        ) ++ 
        category.packageIds.map( _.name ).
          flatMap(x => treeUtilService.getPt(x,logger)).toList.
          sortWith( treeUtilService.sortPt( _ , _ ) ).map(jsTreeNodeOf_pt( _ ) )
      
      override val attrs = ( "rel" -> "category" ) :: Nil   
    }
  }  
  
  /**
   * Transform UserPolicyTemplateCategory into category JsTree nodes in User Library:
   * - contains 
   *   - other user categories
   *   - user policy templates
   * - are clickable
   *   - on the left (triangle) : open contents
   *   - on the name : update zones
   *     - "add a subcategory here"
   *     - "add the current policy template which is not yet in the User Library here" 
   *   
   * @param category
   * @return
   */
  private[this] def jsTreeNodeOf_uptCategory(category:UserPolicyTemplateCategory) : JsTreeNode = {
    /*
     * Transform a UserPolicyTemplate into a JsTree leaf
     */
    def jsTreeNodeOf_upt(upt : UserPolicyTemplate, pt:PolicyPackage) : JsTreeNode = new JsTreeNode {
      override def body = {
        val tooltipid = Helpers.nextFuncName
        SHtml.a(
          { () => onClickTemplateNode(pt) },
            <span class="treePolicyTemplateName tooltipable" tooltipid={tooltipid} title={pt.description}>{pt.name}</span>
            <div class="tooltipContent" id={tooltipid}><h3>{pt.name}</h3><div>{pt.description}</div></div>
          )
      }
      override def children = Nil
      override val attrs = ( "rel" -> "template") :: ( "ptId" -> pt.id.name.value ) :: Nil ::: (if(!upt.isActivated) ("class" -> "disableTreeNode") :: Nil else Nil )
    }
    
    def onClickUserCategory() : JsCmd = {
      updateCurrentPolicyTemplateCategoryDetails(category)
      //update UI
      //update template details only if it is open
      (
        currentPolicyTemplateDetails.is match {
          case Full(form) =>
            updateCurrentPolicyTemplateDetails(form.policyTemplate)
            
            //update UI
            Replace(htmlId_bottomPanel, showPolicyTemplateDetails() )   
          case _ => Noop
        }
      ) &
      Replace(htmlId_bottomPanel, showUserCategoryDetails() )
    }
    
    //the actual mapping upt category to jsTree nodes:
    new JsTreeNode {
      override val attrs = 
        ( "rel" -> { if(category.id == rootCategoryId) "root-category" else "category" } ) ::
        ( "catId" -> category.id.value ) ::      
        Nil   
      override def body = {
        val tooltipid = Helpers.nextFuncName
        SHtml.a(onClickUserCategory _, 
          <span class="treeUptCategoryName tooltipable" tooltipid={tooltipid}  title={category.description}>{category.name}</span>
          <div class="tooltipContent" id={tooltipid}><h3>{category.name}</h3><div>{category.description}</div></div>
        )
      }
      override def children = 
        category.children.flatMap(x => treeUtilService.getUptCategory(x,logger)).
          toList.sortWith { treeUtilService.sortUptCategory( _,_ ) }.
          map(jsTreeNodeOf_uptCategory(_) ) ++ 
        category.items.flatMap(x => treeUtilService.getUpt(x,logger)).
          toList.sortWith( (x,y) => treeUtilService.sortPt( x._2 , y._2) ).
          map { case (upt,pt) => jsTreeNodeOf_upt(upt,pt) }
    } 
  }

  ///////////// success pop-up ///////////////
    private[this] def successPopup : JsCmd = {
    JsRaw(""" callPopupWithTimeout(200, "successConfirmationDialog", 100, 350)     
    """)
  }

  private[this] def showCreateUserPTCategoryPopup() : JsCmd = {
    setCreationPopup

    //update UI
    SetHtml("createPTCategoryContainer", createPopup) &
    JsRaw( """ createPopup("createPTCategoryPopup",300,400)
     """)

  }
  
  private[this] def reloadPolicyTemplateLibrary : IdMemoizeTransform = SHtml.idMemoize { outerXml =>
      def process = {
        updatePTLibService.update(CurrentUser.getActor) match {
          case Full(x) => 
            S.notice("updateOk", "The policy template library was successfully reloaded")
          case e:EmptyBox =>
            val error = e ?~! "An error occured when updating the policy template library from file system"
            logger.debug(error.messageChain, e)
            S.error("updateKO", error.msg)
        }
        Replace("reloadPTLibForm",outerXml.applyAgain) & refreshTree
      }
      
      
      //fill the template
      ":submit" #> (SHtml.ajaxSubmit("Reload", process _) ++ Script(OnLoad(JsRaw(""" correctButtons(); """))))      
  }
  
}




object PolicyTemplateLibraryManagement {

  /*
   * HTML id for zones with Ajax / snippet output
   */
  val htmlId_referenceTree = "referenceTree"
  val htmlId_userTree = "userTree"
  val htmlId_addPopup = "addPopup"
  val htmlId_addToUserLib = "addToUserLib"
  val htmlId_bottomPanel = "bottomPanel"
}
