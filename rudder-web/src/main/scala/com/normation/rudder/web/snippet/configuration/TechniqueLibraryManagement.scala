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
import com.normation.rudder.web.services.DirectiveEditorService
import com.normation.cfclerk.domain._
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.rudder.web.model.JsTreeNode
import net.liftweb.common._
import net.liftweb.http.{SHtml,S}
import scala.xml._
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.js._
import JsCmds._
import com.normation.rudder.web.components.popup.CreateActiveTechniqueCategoryPopup
import JE._
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers._
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.repository._
import com.normation.rudder.web.components.{
  TechniqueEditForm, TechniqueCategoryEditForm
}
import net.liftweb.http.LocalSnippet
import net.liftweb.json._
import com.normation.rudder.web.services.JsTreeUtilService
import com.normation.cfclerk.services.UpdateTechniqueLibrary
import net.liftweb.http.IdMemoizeTransform
import com.normation.rudder.web.components.popup.GiveReasonPopup
import com.normation.rudder.web.services.UserPropertyService
import com.normation.rudder.web.services.ReasonBehavior._
import com.normation.rudder.authorization.Write
import com.normation.eventlog.ModificationId
import bootstrap.liftweb.RudderConfig


/**
 * Snippet for managing the System and User Technique libraries.
 *
 * It allow to see what Techniques are available in the
 * system library, choose and configure which one to use in
 * the user private library.
 *
 * Techniques are classify by categories in a tree.
 *
 */
class TechniqueLibraryManagement extends DispatchSnippet with Loggable {

  import TechniqueLibraryManagement._

  private[this] val techniqueRepository         = RudderConfig.techniqueRepository
  private[this] val updatePTLibService          = RudderConfig.updateTechniqueLibrary
  private[this] val roActiveTechniqueRepository = RudderConfig.roDirectiveRepository
  private[this] val rwActiveTechniqueRepository = RudderConfig.woDirectiveRepository
  private[this] val uuidGen                     = RudderConfig.stringUuidGenerator
  //transform Technique variable to human viewable HTML fields
  private[this] val directiveEditorService      = RudderConfig.directiveEditorService
  private[this] val treeUtilService             = RudderConfig.jsTreeUtilService
  private[this] val userPropertyService         = RudderConfig.userPropertyService

  //the popup component to create user technique category
  private[this] val creationPopup = new LocalSnippet[CreateActiveTechniqueCategoryPopup]

  // the popup component to give reason when moving techniques from Reference
  // Technique Library to Active Technique Library
  private[this] val giveReasonPopup = new LocalSnippet[GiveReasonPopup]



  def dispatch = {
    case "head" => { _ => head }
    case "systemLibrary" => { _ => systemLibrary }
    case "userLibrary" => { _ => userLibrary }
    case "bottomPanel" => { _ => showBottomPanel }
    case "userLibraryAction" => { _ => userLibraryAction }
    case "reloadTechniqueButton" =>  reloadTechniqueLibrary(false)
    case "reloadTechniqueLibrary" => reloadTechniqueLibrary(true)
  }


  //current states for the page - they will be kept only for the duration
  //of one request and its followng Ajax requests

  private[this] val rootCategoryId = roActiveTechniqueRepository.getActiveTechniqueLibrary.map( _.id )

  private[this] val currentTechniqueDetails = new LocalSnippet[TechniqueEditForm]
  private[this] var currentTechniqueCategoryDetails = new LocalSnippet[TechniqueCategoryEditForm]

  private[this] val techniqueId: Box[String] = S.param("techniqueId")

  //create a new Technique edit form and update currentTechniqueDetails
  private[this] def updateCurrentTechniqueDetails(technique:Technique) = {
    currentTechniqueDetails.set(Full(new TechniqueEditForm(
        htmlId_editForm,
        technique,
        currentTechniqueCategoryDetails.is.map( _.getCategory ),
        { () => Replace(htmlId_activeTechniquesTree, userLibrary) }
        //we don't need/want an error callback here - the error is managed in the form.
    )))
  }

  //create a new Technique edit form and update currentTechniqueDetails
  private[this] def updateCurrentTechniqueCategoryDetails(category:ActiveTechniqueCategory) = {
    currentTechniqueCategoryDetails.set(
        rootCategoryId.map { rootCategoryId =>
          new TechniqueCategoryEditForm(
              htmlId_bottomPanel,
              category,
              rootCategoryId,
              { () => Replace(htmlId_activeTechniquesTree, userLibrary) }
          )
        }
      )
  }

  /**
   * Head information (JsTree dependencies,...)
   */
  def head() : NodeSeq = {
    TechniqueEditForm.staticInit ++
    {
      <head>
        <script type="text/javascript" src="/javascript/jstree/jquery.jstree.js" id="jstree"></script>
        <script type="text/javascript" src="/javascript/rudder/tree.js" id="tree"></script>
      </head>
    }
  }

  private[this] def setCreationPopup : Unit = {
         creationPopup.set(Full(new CreateActiveTechniqueCategoryPopup(
            onSuccessCallback = { () => refreshTree })))
  }

  private[this] def setGiveReasonPopup(s : ActiveTechniqueId, d : ActiveTechniqueCategoryId) : Unit = {
    giveReasonPopup.set(Full(new GiveReasonPopup(
        onSuccessCallback = { onSuccessReasonPopup }
      , onFailureCallback = { onFailureReasonPopup }
      , refreshActiveTreeLibrary = { refreshActiveTreeLibrary }
      , sourceActiveTechniqueId = s
      , destCatId = d)
    ))
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

  /**
   * Create the reason popup
   */
  private[this] def createReasonPopup : NodeSeq = {
    giveReasonPopup.is match {
      case Failure(m,_,_) =>  <span class="error">Error: {m}</span>
      case Empty => <div>The component is not set</div>
      case Full(popup) => popup.popupContent(NodeSeq.Empty)
    }
  }

  ////////////////////
  /**
   * Display the Technique system library, which is
   * what Technique are known by the system.
   * Technique are classified by category, one technique
   * belonging at most to one category.
   * Categories are ordered in trees of subcategories.
   */
  def systemLibrary() : NodeSeq = {
    <div id={htmlId_techniqueLibraryTree}>
      <ul>{jsTreeNodeOf_ptCategory(techniqueRepository.getTechniqueLibrary).toXml}</ul>
      {Script(OnLoad(buildReferenceLibraryJsTree))}
    </div>
  }

  /**
   * Display the actions bar of the user library
   */
  def userLibraryAction() : NodeSeq = {
    <div>{SHtml.ajaxButton("Create a new category", () => showCreateActiveTechniqueCategoryPopup(), ("class", "autoWidthButton"))}</div>
  }

  /**
   *  Display the Technique user library, which is
   * what Technique are configurable as Directive.
   * Technique are classified by category, one technique
   * belonging at most to one category.
   * Categories are ordered in trees of subcategories.
   */
  def userLibrary() : NodeSeq = {
    <div id={htmlId_activeTechniquesTree}>{
      val xml = {
        roActiveTechniqueRepository.getActiveTechniqueLibrary match {
          case eb:EmptyBox =>
            val f = eb ?~! "Error when trying to get the root category of Active Techniques"
            logger.error(f.messageChain)
            f.rootExceptionCause.foreach { ex =>
              logger.error("Exception causing the error was:" , ex)
            }
            <span class="error">An error occured when trying to get information from the database. Please contact your administrator of retry latter.</span>
          case Full(activeTechLib) =>
            <ul>{ jsTreeNodeOf_uptCategory(activeTechLib).toXml }</ul>
        }
      }

      xml ++ {
        Script(OnLoad(
          buildUserLibraryJsTree &
          //init bind callback to move
          JsRaw("""
            // use global variables to store where the event come from to prevent infinite recursion
              var fromUser = false;
              var fromReference = false;

            $('#%1$s').bind("move_node.jstree", function (e,data) {
              var interTree = "%1$s" != data.rslt.ot.get_container().attr("id");
              var sourceCatId = $(data.rslt.o).attr("catId");
              var sourceactiveTechniqueId = $(data.rslt.o).attr("activeTechniqueId");
              var destCatId = $(data.rslt.np).attr("catId");
              if( destCatId ) {
                if(sourceactiveTechniqueId) {
                  var arg = JSON.stringify({ 'sourceactiveTechniqueId' : sourceactiveTechniqueId, 'destCatId' : destCatId });
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
                var sourceactiveTechniqueId = data.rslt.obj.attr("activeTechniqueId");
                var target = $('#%5$s  li[activeTechniqueId|="'+sourceactiveTechniqueId+'"]');
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
                var sourceactiveTechniqueId = data.rslt.obj.attr("activeTechniqueId");
                var target = $('#%1$s  li[activeTechniqueId|="'+sourceactiveTechniqueId+'"]');
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
            htmlId_activeTechniquesTree ,
            // %2$s
            SHtml.ajaxCall(JsVar("arg"), bindTechnique _ )._2.toJsCmd,
            // %3$s
            SHtml.ajaxCall(JsVar("arg"), moveTechnique _ )._2.toJsCmd,
            // %4$s
            SHtml.ajaxCall(JsVar("arg"), moveCategory _)._2.toJsCmd,

            htmlId_techniqueLibraryTree
          )))
        )
      }
    }</div>
  }

  def showBottomPanel : NodeSeq = {
    (for {
      ptName <- techniqueId
      technique <- techniqueRepository.getLastTechniqueByName(TechniqueName(ptName))
    } yield {
      technique
    }) match {
      case Full(technique) =>
        updateCurrentTechniqueDetails(technique)
        showTechniqueDetails()
      case _ =>
        <div id={htmlId_bottomPanel}>
          <div  class="centertext">
          Click on a Technique or a category from user library to
          display its details.</div>
        </div>
    }
  }

  /**
   * Configure a Rudder internal Technique to be usable in the
   * user Technique (private) library.
   */
  def showTechniqueDetails() : NodeSeq = {
    currentTechniqueDetails.is match {
      case e:EmptyBox =>
        <div id={htmlId_bottomPanel}>
        <fieldset class="techniqueDetailsFieldset"><legend>Technique details</legend>
          <p>Click on a Technique to display its details</p>
        </fieldset></div>
      case Full(form) => form.showForm
    }
  }

  def showUserCategoryDetails() : NodeSeq = {
    currentTechniqueCategoryDetails.is  match {
      case e:EmptyBox => <div id={htmlId_bottomPanel}><p>Click on a category from the user library to display its details and edit its properties</p></div>
      case Full(form) => form.showForm
    }
  }


  ///////////////////// Callback function for Drag'n'drop in the tree /////////////////////

  private[this] def moveTechnique(arg: String) : JsCmd = {
    //parse arg, which have to  be json object with sourceactiveTechniqueId, destCatId
    try {
      (for {
         JObject(child) <- JsonParser.parse(arg)
         JField("sourceactiveTechniqueId", JString(sourceactiveTechniqueId)) <- child
         JField("destCatId", JString(destCatId)) <- child
       } yield {
         (sourceactiveTechniqueId, destCatId)
       }) match {
        case (sourceactiveTechniqueId, destCatId) :: Nil =>
          (for {
            activeTechnique <- roActiveTechniqueRepository.getActiveTechnique(TechniqueName(sourceactiveTechniqueId)) ?~! "Error while trying to find Active Technique with requested id %s".format(sourceactiveTechniqueId)
            result <- rwActiveTechniqueRepository.move(activeTechnique.id, ActiveTechniqueCategoryId(destCatId), ModificationId(uuidGen.newUuid), CurrentUser.getActor, Some("User moved active technique from UI"))?~! "Error while trying to move Active Technique with requested id '%s' to category id '%s'".format(sourceactiveTechniqueId,destCatId)
          } yield {
            result
          }) match {
            case Full(res) =>
              refreshTree() & JsRaw("""setTimeout(function() { $("[activeTechniqueId=%s]").effect("highlight", {}, 2000)}, 100)""".format(sourceactiveTechniqueId)) & refreshBottomPanel(res)
            case f:Failure => Alert(f.messageChain + "\nPlease reload the page")
            case Empty => Alert("Error while trying to move Active Technique with requested id '%s' to category id '%s'\nPlease reload the page.".format(sourceactiveTechniqueId,destCatId))
          }
        case _ => Alert("Error while trying to move Active Technique: bad client parameters")
      }
    } catch {
      case e:Exception => Alert("Error while trying to move Active Technique")
    }
  }

  private[this] def moveCategory(arg: String) : JsCmd = {
    //parse arg, which have to  be json object with sourceactiveTechniqueId, destCatId
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
            result <- rwActiveTechniqueRepository.move(
                          ActiveTechniqueCategoryId(sourceCatId)
                        , ActiveTechniqueCategoryId(destCatId)
                        , ModificationId(uuidGen.newUuid)
                        , CurrentUser.getActor
                        , Some("User moved Active Technique Category from UI")) ?~! "Error while trying to move category with requested id %s into new parent: %s".format(sourceCatId,destCatId)
          } yield {
            result
          }) match {
            case Full(res) =>
              refreshTree() &
              OnLoad(JsRaw("""setTimeout(function() { $("[catid=%s]").effect("highlight", {}, 2000);}, 100)"""
                  .format(sourceCatId)))
            case f:Failure => Alert(f.messageChain + "\nPlease reload the page")
            case Empty => Alert("Error while trying to move category with requested id '%s' to category id '%s'\nPlease reload the page.".format(sourceCatId,destCatId))
          }
        case _ => Alert("Error while trying to move category: bad client parameters")
      }
    } catch {
      case e:Exception => Alert("Error while trying to move category")
    }
  }


  private[this] def bindTechnique(arg: String) : JsCmd = {
    //parse arg, which have to be json object with sourceactiveTechniqueId, destCatId
    try {
      (for {
         JObject(child) <- JsonParser.parse(arg)
         JField("sourceactiveTechniqueId", JString(sourceactiveTechniqueId)) <- child
         JField("destCatId", JString(destCatId)) <- child
       } yield {
         (sourceactiveTechniqueId, destCatId)
       }) match {
        case (sourceactiveTechniqueId, destCatId) :: Nil =>
          if(userPropertyService.reasonsFieldBehavior != Disabled) {
            showGiveReasonPopup(ActiveTechniqueId(sourceactiveTechniqueId),
                ActiveTechniqueCategoryId(destCatId))
          } else {
            val ptName = TechniqueName(sourceactiveTechniqueId)
            val errorMess= "Error while trying to add Rudder internal " +
            "Technique with requested id '%s' in user library category '%s'"
                (for {
                  result <- (rwActiveTechniqueRepository
                      .addTechniqueInUserLibrary(
                          ActiveTechniqueCategoryId(destCatId),
                          ptName,
                          techniqueRepository.getTechniqueVersions(ptName).toSeq,
                          ModificationId(uuidGen.newUuid),
                          CurrentUser.getActor,
                          Some("Active Technique added by user from UI")
                       )
                      ?~! errorMess.format(sourceactiveTechniqueId,destCatId)
                   )
                } yield {
                  result
                }) match {
                case Full(res) =>
                  val jsString = """setTimeout(function() { $("[activeTechniqueId=%s]")
                    .effect("highlight", {}, 2000)}, 100)"""
                refreshTree() & JsRaw(jsString
                    .format(sourceactiveTechniqueId)) &
                    refreshBottomPanel(res.id)
                case f:Failure => Alert(f.messageChain + "\nPlease reload the page")
                case Empty =>
                  val errorMess = "Error while trying to move Active Technique with " +
                  "requested id '%s' to category id '%s'\nPlease reload the page."
                  Alert(errorMess.format(sourceactiveTechniqueId, destCatId))
            }
          }
        case _ =>
          Alert("Error while trying to move Active Technique: bad client parameters")
      }
    } catch {
      case e:Exception => Alert("Error while trying to move Active Technique")
    }
  }

  private[this] def onSuccessReasonPopup(id : ActiveTechniqueId) : JsCmd =  {
    val jsStr = """setTimeout(function() { $("[activeTechniqueId=%s]")
      .effect("highlight", {}, 2000)}, 100)"""
    refreshTree() &
    JsRaw(jsStr) & refreshBottomPanel(id)
  }

 def onFailureReasonPopup(srcActiveTechId : String, destCatId : String) : JsCmd = {
   val errorMessage = "Error while trying to move Active Technique with " +
   		"requested id '%s' to category id '%s'\nPlease reload the page."
   Alert(errorMessage.format(srcActiveTechId, destCatId))
 }

  private[this] def refreshTree() : JsCmd =  {
    Replace(htmlId_techniqueLibraryTree, systemLibrary) &
    Replace(htmlId_activeTechniquesTree, userLibrary) &
    OnLoad(After(TimeSpan(100), JsRaw("""createTooltip();""")))
  }

  private[this] def refreshActiveTreeLibrary() : JsCmd =  {
    Replace(htmlId_activeTechniquesTree, userLibrary)
  }

  private[this] def refreshBottomPanel(id:ActiveTechniqueId) : JsCmd = {
    for {
      activeTechnique <- roActiveTechniqueRepository.getActiveTechnique(id)
      technique <- techniqueRepository.getLastTechniqueByName(activeTechnique.techniqueName)
    } { //TODO : check errors
      updateCurrentTechniqueDetails(technique)
    }
    SetHtml(htmlId_bottomPanel, showTechniqueDetails() )
  }


  //////////////// display trees ////////////////////////

  /**
   * Javascript to initialize the reference library tree
   */
  private[this] def buildReferenceLibraryJsTree : JsExp = JsRaw(
    """buildReferenceTechniqueTree('#%s','%s','%s')""".format(htmlId_techniqueLibraryTree,{
      techniqueId match {
        case Full(activeTechniqueId) => "ref-technique-"+activeTechniqueId
        case _ => ""
      }
    }, S.contextPath)
  )

  /**
   * Javascript to initialize the user library tree
   */
  private[this] def buildUserLibraryJsTree : JsExp = JsRaw(
    """buildActiveTechniqueTree('#%s', '%s', %s ,'%s')""".format(htmlId_activeTechniquesTree, htmlId_techniqueLibraryTree, CurrentUser.checkRights(Write("technique")), S.contextPath)
  )




  //ajax function that update the bottom of the page when a Technique is clicked
  private[this] def onClickTemplateNode(technique : Technique): JsCmd = {
    updateCurrentTechniqueDetails(technique)

    //update UI
    SetHtml(htmlId_bottomPanel, showTechniqueDetails() )
  }




  /**
   * Transform a WBTechniqueCategory into category JsTree node in reference library:
   * - contains:
   *   - other categories
   *   - Techniques
   * - no action can be done with such node.
   */
  private[this] def jsTreeNodeOf_ptCategory(category:TechniqueCategory) : JsTreeNode = {


    def jsTreeNodeOf_pt(technique : Technique) : JsTreeNode = new JsTreeNode {
      override def body = {
        val tooltipid = Helpers.nextFuncName
        SHtml.a(
            { () => onClickTemplateNode(technique) },
            <span class="treeTechniqueName tooltipable" tooltipid={tooltipid} title={technique.description}>{technique.name}</span>
            <div class="tooltipContent" id={tooltipid}><h3>{technique.name}</h3><div>{technique.description}</div></div>
        )
      }
      override def children = Nil
      override val attrs = ( "rel" -> "template") :: ( "id" -> ("ref-technique-"+technique.id.name.value) ) :: ( "activeTechniqueId" -> technique.id.name.value ) :: Nil
    }

    new JsTreeNode {
      //actually transform a technique category to jsTree nodes:
      override def body = {
        val tooltipid = Helpers.nextFuncName
        <a href="#">
          <span class="treeActiveTechniqueCategoryName tooltipable" tooltipid={tooltipid} title={category.description}>{category.name}</span>
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
   * Transform ActiveTechniqueCategory into category JsTree nodes in User Library:
   * - contains
   *   - other user categories
   *   - Active Techniques
   * - are clickable
   *   - on the left (triangle) : open contents
   *   - on the name : update zones
   *     - "add a subcategory here"
   *     - "add the current Technique which is not yet in the User Library here"
   *
   * @param category
   * @return
   */
  private[this] def jsTreeNodeOf_uptCategory(category:ActiveTechniqueCategory) : JsTreeNode = {
    /*
     * Transform a ActiveTechnique into a JsTree leaf
     */
    def jsTreeNodeOf_upt(activeTechnique : ActiveTechnique, technique:Technique) : JsTreeNode = new JsTreeNode {
      override def body = {
        val tooltipid = Helpers.nextFuncName
        SHtml.a(
          { () => onClickTemplateNode(technique) },
            <span class="treeTechniqueName tooltipable" tooltipid={tooltipid} title={technique.description}>{technique.name}</span>
            <div class="tooltipContent" id={tooltipid}><h3>{technique.name}</h3><div>{technique.description}</div></div>
          )
      }
      override def children = Nil
      override val attrs = ( "rel" -> "template") :: ( "activeTechniqueId" -> technique.id.name.value ) :: Nil ::: (if(!activeTechnique.isEnabled) ("class" -> "disableTreeNode") :: Nil else Nil )
    }

    def onClickUserCategory() : JsCmd = {
      updateCurrentTechniqueCategoryDetails(category)
      //update UI
      //update template details only if it is open
      (
        currentTechniqueDetails.is match {
          case Full(form) =>
            updateCurrentTechniqueDetails(form.technique)

            //update UI
            SetHtml(htmlId_bottomPanel, showTechniqueDetails() )
          case _ => Noop
        }
      ) &
      SetHtml(htmlId_bottomPanel, showUserCategoryDetails() )
    }

    //the actual mapping activeTechnique category to jsTree nodes:
    new JsTreeNode {
      override val attrs =
        ( "rel" -> { if(Full(category.id) == rootCategoryId) "root-category" else "category" } ) ::
        ( "catId" -> category.id.value ) ::
        Nil
      override def body = {
        val tooltipid = Helpers.nextFuncName
        SHtml.a(onClickUserCategory _,
          <span class="treeActiveTechniqueCategoryName tooltipable" tooltipid={tooltipid}  title={category.description}>{category.name}</span>
          <div class="tooltipContent" id={tooltipid}><h3>{category.name}</h3><div>{category.description}</div></div>
        )
      }
      override def children =
        category.children.flatMap(x => treeUtilService.getActiveTechniqueCategory(x,logger)).
          toList.sortWith { treeUtilService.sortActiveTechniqueCategory( _,_ ) }.
          map(jsTreeNodeOf_uptCategory(_) ) ++
        category.items.flatMap(x => treeUtilService.getActiveTechnique(x,logger)).
          toList.sortWith( (x,y) => treeUtilService.sortPt( x._2 , y._2) ).
          map { case (activeTechnique,technique) => jsTreeNodeOf_upt(activeTechnique,technique) }
    }
  }

  ///////////// success pop-up ///////////////
    private[this] def successPopup : JsCmd = {
    JsRaw(""" callPopupWithTimeout(200, "successConfirmationDialog")
    """)
  }

  private[this] def showCreateActiveTechniqueCategoryPopup() : JsCmd = {
    setCreationPopup

    //update UI
    SetHtml("createActiveTechniquesCategoryContainer", createPopup) &
    JsRaw( """createPopup("createActiveTechniqueCategoryPopup")
     """)

  }

  private[this] def showGiveReasonPopup(
      sourceActiveTechniqueId : ActiveTechniqueId, destCatId : ActiveTechniqueCategoryId) : JsCmd = {

    setGiveReasonPopup(sourceActiveTechniqueId, destCatId)
    //update UI

    SetHtml("createActiveTechniquesContainer", createReasonPopup) &
    JsRaw( """createPopup("createActiveTechniquePopup")
     """)
  }
  
  private[this] def reloadTechniqueLibrary(isTechniqueLibraryPage : Boolean) : IdMemoizeTransform = SHtml.idMemoize { outerXml =>
      def process = {
        updatePTLibService.update(ModificationId(uuidGen.newUuid), CurrentUser.getActor, Some("Technique library reloaded by user")) match {
          case Full(x) =>
            S.notice("updateLib", "The Technique library was successfully reloaded")
          case e:EmptyBox =>
            val error = e ?~! "An error occured when updating the Technique library from file system"
            logger.debug(error.messageChain, e)
            S.error("updateLib", error.msg)
        }

        Replace("reloadTechniqueLibForm",outerXml.applyAgain) &
        (if (isTechniqueLibraryPage) {
          refreshTree
        } else {
          Noop
        } )
      }


      //fill the template
      // Add a style to display correctly the button in both page : policyServer and technique library
      ":submit" #> ( SHtml.ajaxSubmit("Reload Techniques", process _, ("style","min-width:150px")) ++
                     Script(OnLoad(JsRaw(""" correctButtons(); """)))
                   )
  }

}




object TechniqueLibraryManagement {

  /*
   * HTML id for zones with Ajax / snippet output
   */
  val htmlId_techniqueLibraryTree = "techniqueLibraryTree"
  val htmlId_activeTechniquesTree = "activeTechniquesTree"
  val htmlId_addPopup = "addPopup"
  val htmlId_addToActiveTechniques = "addToActiveTechniques"
  val htmlId_bottomPanel = "bottomPanel"
  val htmlId_editForm = "editForm"
}
