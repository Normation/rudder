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

package com.normation.rudder.web.snippet.administration

import bootstrap.liftweb.RudderConfig
import com.normation.box.*
import com.normation.cfclerk.domain.*
import com.normation.eventlog.ModificationId
import com.normation.rudder.AuthorizationType
import com.normation.rudder.config.ReasonBehavior.*
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.domain.policies.*
import com.normation.rudder.users.CurrentUser
import com.normation.rudder.web.components.*
import com.normation.rudder.web.components.popup.CreateActiveTechniqueCategoryPopup
import com.normation.rudder.web.components.popup.GiveReasonPopup
import com.normation.rudder.web.model.JsTreeNode
import com.normation.rudder.web.services.AgentCompat
import com.normation.rudder.web.snippet.WithNonce
import net.liftweb.common.*
import net.liftweb.common.Box.box2Option
import net.liftweb.common.Box.option2Box
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.LocalSnippet
import net.liftweb.http.S
import net.liftweb.http.SHtml
import net.liftweb.http.SHtml.ElemAttr.pairToBasic
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.json.*
import net.liftweb.util.Helpers.*
import org.apache.commons.text.StringEscapeUtils
import scala.xml.*

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

  import TechniqueLibraryManagement.*

  private val techniqueRepository         = RudderConfig.techniqueRepository
  private val updatePTLibService          = RudderConfig.updateTechniqueLibrary
  private val roActiveTechniqueRepository = RudderConfig.roDirectiveRepository
  private val rwActiveTechniqueRepository = RudderConfig.woDirectiveRepository
  private val uuidGen                     = RudderConfig.stringUuidGenerator
  // transform Technique variable to human viewable HTML fields
  private val treeUtilService             = RudderConfig.jsTreeUtilService
  private val userPropertyService         = RudderConfig.userPropertyService
  private val updateTecLibInterval        = RudderConfig.RUDDER_BATCH_TECHNIQUELIBRARY_UPDATEINTERVAL

  // the popup component to create user technique category
  private val creationPopup = new LocalSnippet[CreateActiveTechniqueCategoryPopup]

  // the popup component to give reason when moving techniques from Reference
  // Technique Library to Active Technique Library
  private val giveReasonPopup = new LocalSnippet[GiveReasonPopup]

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = {
    case "head"                   => { _ => head() }
    case "systemLibrary"          => { _ => systemLibrary() }
    case "userLibrary"            => { _ => userLibrary() }
    case "bottomPanel"            => { _ => showBottomPanel }
    case "userLibraryAction"      => { _ => userLibraryAction() }
    case "reloadTechniqueButton"  => { _ => reloadTechniqueLibrary(false) }
    case "reloadTechniqueLibrary" => { _ => reloadTechniqueLibrary(true) }
  }

  // current states for the page - they will be kept only for the duration
  // of one request and its followng Ajax requests

  private val rootCategoryId = roActiveTechniqueRepository.getActiveTechniqueLibrary.map(_.id).toBox

  private val currentTechniqueDetails         = new LocalSnippet[TechniqueEditForm]
  private val currentTechniqueCategoryDetails = new LocalSnippet[TechniqueCategoryEditForm]

  private val techniqueId: Box[String] = S.param("techniqueId")

  // create a new Technique edit form and update currentTechniqueDetails
  private def updateCurrentTechniqueDetails(technique: Option[Technique], activeTechnique: Option[ActiveTechnique]) = {
    currentTechniqueDetails.set(
      Full(
        new TechniqueEditForm(
          htmlId_editForm,
          technique,
          activeTechnique,
          currentTechniqueCategoryDetails.get.map(_.getCategory),
          { () => Replace(htmlId_activeTechniquesTree, userLibrary()) }
          // we don't need/want an error callback here - the error is managed in the form.
        )
      )
    )
  }

  // create a new Technique edit form and update currentTechniqueDetails
  private def updateCurrentTechniqueCategoryDetails(category: ActiveTechniqueCategory) = {
    currentTechniqueCategoryDetails.set(
      rootCategoryId.map { rootCategoryId =>
        new TechniqueCategoryEditForm(
          htmlId_bottomPanel,
          category,
          rootCategoryId,
          { () => Replace(htmlId_activeTechniquesTree, userLibrary()) }
        )
      }
    )
  }

  /**
   * Head information (JsTree dependencies,...)
   */
  def head(): NodeSeq = {
    TechniqueEditForm.staticInit
  }

  private def setCreationPopup: Unit = {
    creationPopup.set(Full(new CreateActiveTechniqueCategoryPopup(onSuccessCallback = { () => refreshTree() })))
  }

  private def setGiveReasonPopup(s: ActiveTechniqueId, d: ActiveTechniqueCategoryId): Unit = {
    giveReasonPopup.set(
      Full(
        new GiveReasonPopup(
          onSuccessCallback = { onSuccessReasonPopup },
          onFailureCallback = { onFailureReasonPopup },
          refreshActiveTreeLibrary = { () => refreshActiveTreeLibrary() },
          sourceActiveTechniqueId = s,
          destCatId = d
        )
      )
    )
  }

  /**
   * Create the popup
   */
  private def createPopup: NodeSeq = {
    creationPopup.get match {
      case Failure(m, _, _) => <span class="error">Error: {m}</span>
      case Empty            => <div>The component is not set</div>
      case Full(popup)      => popup.popupContent(NodeSeq.Empty)
    }
  }

  /**
   * Create the reason popup
   */
  private def createReasonPopup: NodeSeq = {
    giveReasonPopup.get match {
      case Failure(m, _, _) => <span class="error">Error: {m}</span>
      case Empty            => <div>The component is not set</div>
      case Full(popup)      => popup.popupContent(NodeSeq.Empty)
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
  def systemLibrary(): NodeSeq = {
    <div id={htmlId_techniqueLibraryTree} class="row">
      <ul>{jsTreeNodeOf_ptCategory(techniqueRepository.getTechniqueLibrary).toXml}</ul>
      {WithNonce.scriptWithNonce(Script(OnLoad(buildReferenceLibraryJsTree)))}
    </div>
  }

  /**
   * Display the actions bar of the user library
   */
  def userLibraryAction(): NodeSeq = {
    SHtml.ajaxButton("Add category", () => showCreateActiveTechniqueCategoryPopup(), ("class", "btn btn-default pull-right"))
  }

  /**
   *  Display the Technique user library, which is
   * what Technique are configurable as Directive.
   * Technique are classified by category, one technique
   * belonging at most to one category.
   * Categories are ordered in trees of subcategories.
   */
  def userLibrary(): NodeSeq = {
    <div id={htmlId_activeTechniquesTree} class="row">{
      val xml = {
        roActiveTechniqueRepository.getActiveTechniqueLibrary.toBox match {
          case eb: EmptyBox =>
            val f = eb ?~! "Error when trying to get the root category of active techniques"
            logger.error(f.messageChain)
            f.rootExceptionCause.foreach(ex => logger.error("Exception causing the error was:", ex))
            <div class="col-sm-12">
              <span class="error">An error occured when trying to get information from the database. Please contact your administrator of retry latter.</span>
            </div>
          case Full(activeTechLib) =>
            <ul>{jsTreeNodeOf_uptCategory(activeTechLib).toXml}</ul>
        }
      }

      (xml: NodeSeq) ++ {
        WithNonce.scriptWithNonce(
          Script(
            OnLoad(
              buildUserLibraryJsTree &
              // init bind callback to move
              JsRaw(
                """
            // use global variables to store where the event come from to prevent infinite recursion
              var fromUser = false;
              var fromReference = false;

            $('#%1$s').bind("copy_node.jstree", function (e,data) {
              var parent = data.new_instance.get_node(data.parent);
              var sourceactiveTechniqueId = data.node.li_attr.activetechniqueid;
              var destCatId = parent.li_attr.catid;
              if( destCatId ) {
                if(sourceactiveTechniqueId) {
                  var arg = JSON.stringify({ 'sourceactiveTechniqueId' : sourceactiveTechniqueId, 'destCatId' : destCatId });
                    %2$s;
                } else {
                  alert("Trying to add a technique that has no technique id");
                  $.jstree.rollback(data.rlbk);
                }
              } else {
                alert("Can not move to something else than a category");
                $.jstree.rollback(data.rlbk);
              }
            });

            $('#%1$s').bind("move_node.jstree", function (e,data) {
              var parent = data.new_instance.get_node(data.parent);
              var sourceactiveTechniqueId = data.node.li_attr.activetechniqueid;
              var sourceCatId = data.node.li_attr.catid;
              var destCatId = parent.li_attr.catid;
              if( destCatId ) {
                if(sourceactiveTechniqueId) {
                  var arg = JSON.stringify({ 'sourceactiveTechniqueId' : sourceactiveTechniqueId, 'destCatId' : destCatId });
                  %3$s;
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
                if (fromReference) {
                  fromReference = false;
                  return false;
                }
                var sourceactiveTechniqueId = data.node.li_attr.activetechniqueid;
                var target = $('#%5$s  li[activeTechniqueId|="'+sourceactiveTechniqueId+'"]');
                var refTree = $('#%5$s').jstree()
                refTree.deselect_all()
                if (target.length>0) {
                  fromUser = true;
                  refTree.select_node(target);
                }
            });
            $('#%5$s').bind("select_node.jstree", function (e,data) {
                if (fromUser) {
                  fromUser = false;
                  return false;
                }
                var sourceactiveTechniqueId = data.node.li_attr.activetechniqueid;
                var target = $('#%1$s  li[activeTechniqueId|="'+sourceactiveTechniqueId+'"]');
                var userTree = $('#%1$s').jstree()
                userTree.deselect_all();
                if (target.length>0) {
                  fromReference = true;
                  userTree.select_node(target);
                }

            });
            //Check if url anchor is a technique name, if so open its details.
            var anch = window.location.hash.substr(1);
            if(anch!==""){
              var selector = '#ref-technique-'+anch+'_anchor'
              if($(selector).length){
                $(selector).click();
              }
            }
          """.format(
                  // %1$s
                  htmlId_activeTechniquesTree,
                  // %2$s
                  SHtml.ajaxCall(JsVar("arg"), bindTechnique)._2.toJsCmd,
                  // %3$s
                  SHtml.ajaxCall(JsVar("arg"), moveTechnique)._2.toJsCmd,
                  // %4$s
                  SHtml.ajaxCall(JsVar("arg"), moveCategory)._2.toJsCmd,
                  htmlId_techniqueLibraryTree
                )
              ) // JsRaw ok, escaped
            )
          )
        )
      }
    }</div>
  }

  def showBottomPanel: NodeSeq = {
    (for {
      ptName    <- techniqueId
      technique <- techniqueRepository.getLastTechniqueByName(TechniqueName(ptName))
    } yield {
      technique
    }) match {
      case Full(technique) =>
        updateCurrentTechniqueDetails(Some(technique), None)
        showTechniqueDetails()
      case _               =>
        <div id={htmlId_bottomPanel} class="portlet pt-3 pb-4">
          Click on a technique or a category from user library to
          display its details.
        </div>
    }
  }

  /**
   * Configure a Rudder internal Technique to be usable in the
   * user Technique (private) library.
   */
  def showTechniqueDetails(): NodeSeq = {
    currentTechniqueDetails.get match {
      case e: EmptyBox =>
        <div id={htmlId_bottomPanel}>
          <h3>Technique details</h3>
          <p>Click on a technique to display its details</p>
        </div>
      case Full(form) => form.showForm()
    }
  }

  def showUserCategoryDetails(): NodeSeq = {
    currentTechniqueCategoryDetails.get match {
      case e: EmptyBox =>
        <div id={
          htmlId_bottomPanel
        }><p>Click on a category from the user library to display its details and edit its properties</p></div>
      case Full(form) => form.showForm()
    }
  }

  ///////////////////// Callback function for Drag'n'drop in the tree /////////////////////

  private def moveTechnique(arg: String): JsCmd = {
    // parse arg, which have to  be json object with sourceactiveTechniqueId, destCatId
    try {
      (for {
        case JObject(child) <- JsonParser.parse(arg)
        case JField("sourceactiveTechniqueId", JString(sourceactiveTechniqueId)) <- child
        case JField("destCatId", JString(destCatId)) <- child
      } yield {
        (sourceactiveTechniqueId, destCatId)
      }) match {
        case (sourceactiveTechniqueId, destCatId) :: Nil =>
          (for {
            activeTechnique <- roActiveTechniqueRepository
                                 .getActiveTechnique(TechniqueName(sourceactiveTechniqueId))
                                 .toBox
                                 .flatMap(Box(_)) ?~! "Error while trying to find active technique with requested id %s".format(
                                 sourceactiveTechniqueId
                               )
            result          <- rwActiveTechniqueRepository
                                 .move(
                                   activeTechnique.id,
                                   ActiveTechniqueCategoryId(destCatId),
                                   ModificationId(uuidGen.newUuid),
                                   CurrentUser.actor,
                                   Some("User moved active technique from UI")
                                 )
                                 .toBox ?~! "Error while trying to move active technique with requested id '%s' to category id '%s'"
                                 .format(sourceactiveTechniqueId, destCatId)
          } yield {
            result
          }) match {
            case Full(res) =>
              refreshTree() & JsRaw(
                """setTimeout(function() { $("[activeTechniqueId=%s]").attempt("highlight", {}, 2000)}, 100)""".format(
                  sourceactiveTechniqueId
                )
              ) & refreshBottomPanel(res) // JsRaw ok, inputs comes from json
            case f: Failure => Alert(f.messageChain + "\nPlease reload the page")
            case Empty     =>
              Alert(
                "Error while trying to move active technique with requested id '%s' to category id '%s'\nPlease reload the page."
                  .format(sourceactiveTechniqueId, destCatId)
              )
          }
        case _                                           => Alert("Error while trying to move active technique: bad client parameters")
      }
    } catch {
      case e: Exception => Alert("Error while trying to move active technique")
    }
  }

  private def moveCategory(arg: String): JsCmd = {
    // parse arg, which have to  be json object with sourceactiveTechniqueId, destCatId
    try {
      (for {
        case JObject(child) <- JsonParser.parse(arg)
        case JField("sourceCatId", JString(sourceCatId)) <- child
        case JField("destCatId", JString(destCatId)) <- child
      } yield {
        (sourceCatId, destCatId)
      }) match {
        case (sourceCatId, destCatId) :: Nil =>
          (for {
            result <- rwActiveTechniqueRepository
                        .move(
                          ActiveTechniqueCategoryId(sourceCatId),
                          ActiveTechniqueCategoryId(destCatId),
                          None,
                          ModificationId(uuidGen.newUuid),
                          CurrentUser.actor,
                          Some("User moved active technique category from UI")
                        )
                        .toBox ?~! "Error while trying to move category with requested id %s into new parent: %s".format(
                        sourceCatId,
                        destCatId
                      )
          } yield {
            result
          }) match {
            case Full(res) =>
              refreshTree() &
              OnLoad(
                JsRaw(
                  """setTimeout(function() { $("[catid=%s]").attempt("highlight", {}, 2000);}, 100)"""
                    .format(sourceCatId)
                ) // JsRaw ok, input comes from json
              )
            case f: Failure => Alert(f.messageChain + "\nPlease reload the page")
            case Empty     =>
              Alert(
                "Error while trying to move category with requested id '%s' to category id '%s'\nPlease reload the page.".format(
                  sourceCatId,
                  destCatId
                )
              )
          }
        case _                               => Alert("Error while trying to move category: bad client parameters")
      }
    } catch {
      case e: Exception => Alert("Error while trying to move category")
    }
  }

  private def bindTechnique(arg: String): JsCmd = {
    // parse arg, which have to be json object with sourceactiveTechniqueId, destCatId
    try {
      (for {
        case JObject(child) <- JsonParser.parse(arg)
        case JField("sourceactiveTechniqueId", JString(sourceactiveTechniqueId)) <- child
        case JField("destCatId", JString(destCatId)) <- child
      } yield {
        (sourceactiveTechniqueId, destCatId)
      }) match {
        case (sourceactiveTechniqueId, destCatId) :: Nil =>
          if (userPropertyService.reasonsFieldBehavior != Disabled) {
            showGiveReasonPopup(ActiveTechniqueId(sourceactiveTechniqueId), ActiveTechniqueCategoryId(destCatId))
          } else {
            val ptName    = TechniqueName(sourceactiveTechniqueId)
            val errorMess = "Error while trying to add Rudder internal " +
              "Technique with requested id '%s' in user library category '%s'"
            (for {
              result <- (rwActiveTechniqueRepository
                          .addTechniqueInUserLibrary(
                            ActiveTechniqueCategoryId(destCatId),
                            ptName,
                            techniqueRepository.getTechniqueVersions(ptName).toSeq,
                            policyTypes = PolicyTypes.rudderBase,
                            modId = ModificationId(uuidGen.newUuid),
                            actor = CurrentUser.actor,
                            reason = Some("Active technique added by user from UI")
                          )
                          .toBox
                        ?~! errorMess.format(sourceactiveTechniqueId, destCatId))
            } yield {
              result
            }) match {
              case Full(res) =>
                val jsString = """setTimeout(function() { $("[activeTechniqueId=%s]")
                    .attempt("highlight", {}, 2000)}, 100)"""
                refreshTree() & JsRaw(
                  jsString
                    .format(sourceactiveTechniqueId) // JsRaw ok, comes from json
                ) &
                refreshBottomPanel(res.id)
              case f: Failure => Alert(f.messageChain + "\nPlease reload the page")
              case Empty     =>
                val errorMess = "Error while trying to move active technique with " +
                  "requested id '%s' to category id '%s'\nPlease reload the page."
                Alert(errorMess.format(sourceactiveTechniqueId, destCatId))
            }
          }
        case _                                           =>
          Alert("Error while trying to move active technique: bad client parameters")
      }
    } catch {
      case e: Exception => Alert("Error while trying to move active technique")
    }
  }

  private def onSuccessReasonPopup(id: ActiveTechniqueId): JsCmd = {
    val jsStr = """setTimeout(function() { $("[activeTechniqueId=%s]")
      .attempt("highlight", {}, 2000)}, 100)"""
    refreshTree() &
    JsRaw(jsStr) & refreshBottomPanel(id) // JsRaw ok, const
  }

  def onFailureReasonPopup(srcActiveTechId: String, destCatId: String): JsCmd = {
    val errorMessage = "Error while trying to move active technique with " +
      "requested id '%s' to category id '%s'\nPlease reload the page."
    Alert(errorMessage.format(srcActiveTechId, destCatId))
  }

  private def refreshTree(): JsCmd = {
    Replace(htmlId_techniqueLibraryTree, systemLibrary()) &
    Replace(htmlId_activeTechniquesTree, userLibrary()) &
    OnLoad(After(TimeSpan(100), JsRaw("""initBsTooltips();"""))) // JsRaw ok, const
  }

  private def refreshActiveTreeLibrary(): JsCmd = {
    Replace(htmlId_activeTechniquesTree, userLibrary()) &
    OnLoad(After(TimeSpan(100), JsRaw("""initBsTooltips();"""))) // JsRaw ok, const
  }

  private def refreshBottomPanel(id: ActiveTechniqueId): JsCmd = {
    for {
      activeTechnique <- roActiveTechniqueRepository.getActiveTechniqueByActiveTechnique(id).toBox.flatMap(Box(_))
      technique       <- techniqueRepository.getLastTechniqueByName(activeTechnique.techniqueName)
    } { // TODO : check errors
      updateCurrentTechniqueDetails(Some(technique), Some(activeTechnique))
    }
    SetHtml(htmlId_bottomPanel, showTechniqueDetails())
  }

  //////////////// display trees ////////////////////////

  /**
   * Javascript to initialize the reference library tree
   */
  private def buildReferenceLibraryJsTree: JsExp = JsRaw(
    """buildReferenceTechniqueTree('#%s','%s','%s')""".format(
      htmlId_techniqueLibraryTree, {
        techniqueId match {
          case Full(activeTechniqueId) => "ref-technique-" + StringEscapeUtils.escapeEcmaScript(activeTechniqueId)
          case _                       => ""
        }
      },
      S.contextPath
    )
  ) // JsRaw ok, escaped

  /**
   * Javascript to initialize the user library tree
   */
  private def buildUserLibraryJsTree: JsExp = JsRaw(
    """buildActiveTechniqueTree('#%s', '%s', %s ,'%s')""".format(
      htmlId_activeTechniquesTree,
      htmlId_techniqueLibraryTree,
      CurrentUser.checkRights(AuthorizationType.Technique.Write),
      S.contextPath
    )
  ) // JsRaw ok, escaped

  // ajax function that update the bottom of the page when a Technique is clicked
  private def onClickTemplateNode(technique: Option[Technique], activeTechnique: Option[ActiveTechnique]): JsCmd = {
    updateCurrentTechniqueDetails(technique, activeTechnique)

    // update UI
    SetHtml(htmlId_bottomPanel, showTechniqueDetails())
  }

  /**
   * Transform a WBTechniqueCategory into category JsTree node in reference library:
   * - contains:
   *   - other categories
   *   - Techniques
   * - no action can be done with such node.
   */
  private def jsTreeNodeOf_ptCategory(category: TechniqueCategory): JsTreeNode = {

    def jsTreeNodeOf_pt(technique: Technique): JsTreeNode = new JsTreeNode {

      val agentTypes  = technique.agentConfigs.map(_.agentType).toSet
      val agentCompat = AgentCompat(agentTypes)

      override def body: NodeSeq = {

        val tooltipContent = s"""
          <h3>${technique.name}</h3>
          <div>
            <p>${technique.escapedDescription}</p>
            ${agentCompat.techniqueText}
          </div>
        """
        SHtml.a(
          () => onClickTemplateNode(Some(technique), None),
          <span class="treeTechniqueName" data-bs-toggle="tooltip" data-bs-placement="top" title={
            tooltipContent
          }>{agentCompat.icon}{technique.name}</span>
        )
      }
      override def children: List[JsTreeNode] = Nil
      override val attrs = {
        ("data-jstree"       -> """{ "type" : "template" }""") ::
        ("id"                -> ("ref-technique-" + technique.id.name.value)) ::
        ("class"             -> "techniqueNode") ::
        ("activeTechniqueId" -> technique.id.name.value) ::
        Nil
      }
    }

    new JsTreeNode {
      // actually transform a technique category to jsTree nodes:
      override def body: NodeSeq = {
        val tooltipContent = s"<h3>${category.name}</h3\n<div>${category.description}</div>"
        <a href="#">
          <span class="treeActiveTechniqueCategoryName" data-bs-toggle="tooltip" title={tooltipContent}>{
          category.name
        }</span>
        </a>
      }
      override def children = {
        category.subCategoryIds
          .flatMap(x => treeUtilService.getPtCategory(x, logger))
          .toList
          .sortWith(treeUtilService.sortPtCategory(_, _))
          .map(jsTreeNodeOf_ptCategory(_)) ++
        category.techniqueIds.unsorted
          .map(_.name)
          .flatMap(x => treeUtilService.getPt(x, logger))
          .toList
          .sortWith((x, y) => treeUtilService.sortPt(x.id.name, y.id.name))
          .map(jsTreeNodeOf_pt(_))
      }

      override val attrs = {
        ("data-jstree" -> """{ "type" : "category" }""") ::
        Nil
      }
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
  private def jsTreeNodeOf_uptCategory(category: ActiveTechniqueCategory): JsTreeNode = {
    /*
     * Transform a ActiveTechnique into a JsTree leaf
     */
    def jsTreeNodeOf_upt(activeTechnique: ActiveTechnique, optTechnique: Option[Technique]): JsTreeNode = {

      // there is two case: the normal one, and the case where the technique is missing and
      // we want to inform the user of the problem
      val agentTypes  = optTechnique.toList.flatMap(_.agentConfigs).map(_.agentType).toSet
      val agentCompat = AgentCompat(agentTypes)
      optTechnique match {
        case Some(technique) =>
          new JsTreeNode {
            override def body:     NodeSeq          = {
              val tooltipContent = s"""<h3>${technique.name}</h3><div>
                <p>${technique.escapedDescription}</p>
                ${agentCompat.techniqueText}
                ${if (!activeTechnique.isEnabled) { <div>This Technique is currently <b>disabled</b>.</div> }
                else { NodeSeq.Empty }}
              </div>
              """
              val badgeDisabled  = if (activeTechnique.isEnabled) NodeSeq.Empty else <span class="badge-disabled"></span>

              val numberDirectives = s"${activeTechnique.directives.size} directive(s) are based on that technique"
              SHtml.a(
                () => onClickTemplateNode(Some(technique), Some(activeTechnique)),
                (
                  <span class="treeTechniqueName" data-bs-toggle="tooltip" data-bs-placement="top" title={
                    tooltipContent
                  }><span class="item-name">{agentCompat.icon} {technique.name}</span>{badgeDisabled}</span>
                  <span data-bs-toggle="tooltip" title={numberDirectives}>
                    {s" (${activeTechnique.directives.size})"}
                  </span>
                )
              )
            }
            override def children: List[JsTreeNode] = Nil
            override val attrs = {
              ("data-jstree"       -> """{ "type" : "template" }""") ::
              ("activeTechniqueId" -> technique.id.name.value) ::
              ("class"             -> ("techniqueNode" + (if (!activeTechnique.isEnabled) " disableTreeNode" else ""))) :: Nil
            }
          }
        case None            =>
          if (activeTechnique.isEnabled) {
            val msg =
              s"Disabling active technique '${activeTechnique.id.value}' because its technique '${activeTechnique.techniqueName.value}' was not found in the repository"
            rwActiveTechniqueRepository
              .changeStatus(
                activeTechnique.id,
                status = false,
                modId = ModificationId(uuidGen.newUuid),
                actor = RudderEventActor,
                reason = Some(msg)
              )
              .toBox match {
              case eb: EmptyBox =>
                val e = eb ?~! s"Error when trying to disable active technique '${activeTechnique.id.value}'"
                logger.debug(e.messageChain)
              case Full(x) =>
                logger.warn(msg)
            }
          }

          new JsTreeNode {
            override def body:     NodeSeq          = {
              val tooltipContent =
                s"<h3>Missing technique ${activeTechnique.techniqueName.value}</h3>\n<div>The technique is missing on the repository. Active technique based on it are disable until the technique is putted back on the repository</div>"
              SHtml.a(
                () => onClickTemplateNode(None, Some(activeTechnique)),
                <span class="error treeTechniqueName" data-bs-toggle="tooltip" title={tooltipContent}>{
                  activeTechnique.techniqueName.value
                }</span>
              )
            }
            override def children: List[JsTreeNode] = Nil
            override val attrs = {
              ("data-jstree"       -> """{ "type" : "template" }""") ::
              ("activeTechniqueId" -> activeTechnique.techniqueName.value) ::
              ("class"             -> ("techniqueNode" + (if (!activeTechnique.isEnabled) " disableTreeNode" else ""))) :: Nil
            }
          }
      }
    }

    def onClickUserCategory(): JsCmd = {
      updateCurrentTechniqueCategoryDetails(category)
      // update UI
      // update template details only if it is open
      (
        currentTechniqueDetails.get match {
          case Full(form) =>
            updateCurrentTechniqueDetails(form.technique, form.activeTechnique)

            // update UI
            SetHtml(htmlId_bottomPanel, showTechniqueDetails())
          case _          => Noop
        }
      ) &
      SetHtml(htmlId_bottomPanel, showUserCategoryDetails())
    }

    // the actual mapping activeTechnique category to jsTree nodes:
    new JsTreeNode {
      override val attrs    = {
        ("data-jstree" -> s"""{ "type" : "${if (Full(category.id) == rootCategoryId) "root-category" else "category"}" }""") ::
        ("catId"       -> category.id.value) ::
        Nil
      }
      override def body: NodeSeq = {
        val tooltipContent = s"<h3>${category.name}</h3>\n<div>${category.description}</div>"
        SHtml.a(
          () => onClickUserCategory(),
          <span class="treeActiveTechniqueCategoryName" data-bs-toggle="tooltip" title={tooltipContent}>{
            category.name
          }</span>
        )
      }
      override def children = {
        category.children
          .flatMap(x => treeUtilService.getActiveTechniqueCategory(x, logger))
          .toList
          .sortWith(treeUtilService.sortActiveTechniqueCategory(_, _))
          .map(jsTreeNodeOf_uptCategory(_)) ++
        category.items
          .flatMap(x => treeUtilService.getActiveTechnique(x, logger))
          .toList
          .sortWith((x, y) => treeUtilService.sortPt(x._1.techniqueName, y._1.techniqueName))
          .map { case (activeTechnique, technique) => jsTreeNodeOf_upt(activeTechnique, technique) }
      }
    }
  }

  private def showCreateActiveTechniqueCategoryPopup(): JsCmd = {
    setCreationPopup

    // update UI
    SetHtml("createActiveTechniquesCategoryContainer", createPopup) & JsRaw(
      """initBsModal("createActiveTechniqueCategoryPopup")""" // JsRaw ok, const
    )

  }

  private def showGiveReasonPopup(
      sourceActiveTechniqueId: ActiveTechniqueId,
      destCatId:               ActiveTechniqueCategoryId
  ): JsCmd = {

    setGiveReasonPopup(sourceActiveTechniqueId, destCatId)
    // update UI

    SetHtml("createActiveTechniquesContainer", createReasonPopup) &
    JsRaw("""initBsModal("createActiveTechniquePopup")""") // JsRaw ok, const
  }

  private def reloadTechniqueLibrary(isTechniqueLibraryPage: Boolean): NodeSeq = {

    def initJs    = SetHtml("techniqueLibraryUpdateInterval", <span>{updateTecLibInterval}</span>)
    def process() = {
      val createNotification = updatePTLibService.update(
        ModificationId(uuidGen.newUuid),
        CurrentUser.actor,
        Some("Technique library reloaded by user")
      ) match {
        case Full(x) =>
          JsRaw("""createSuccessNotification("The technique library was successfully reloaded")""") // JsRaw ok, const
        case e: EmptyBox =>
          val error = e ?~! "An error occurred when updating the technique library from file system"
          logger.debug(error.messageChain, e)
          JsRaw(s"""createErrorNotification(s"${error.msg}}")""") // JsRaw ok, no user input
      }

      val processAction = {
        if (isTechniqueLibraryPage) {
          refreshTree()
        } else {
          Noop
        }
      }
      processAction & createNotification
    }

    (WithNonce.scriptWithNonce(Script(OnLoad(initJs))): NodeSeq) ++
    SHtml.ajaxButton("Reload techniques", () => process(), ("class", "btn btn-primary"))
  }

}

object TechniqueLibraryManagement {

  /*
   * HTML id for zones with Ajax / snippet output
   */
  val htmlId_techniqueLibraryTree  = "techniqueLibraryTree"
  val htmlId_activeTechniquesTree  = "activeTechniquesTree"
  val htmlId_addPopup              = "addPopup"
  val htmlId_addToActiveTechniques = "addToActiveTechniques"
  val htmlId_bottomPanel           = "bottomPanel"
  val htmlId_editForm              = "editForm"
}
