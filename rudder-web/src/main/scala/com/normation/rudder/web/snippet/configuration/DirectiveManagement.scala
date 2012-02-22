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

import com.normation.rudder.web.components.popup.CreateDirectivePopup
import com.normation.rudder.web.model.JsTreeNode
import com.normation.rudder.domain.policies._
import com.normation.cfclerk.domain.{Technique,TechniqueId}
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.web.components.{DirectiveEditForm,DateFormaterService}
import com.normation.rudder.repository._
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import scala.xml._
import net.liftweb.common._
import Box._
import net.liftweb.http._
import net.liftweb.http.js._
import JsCmds._
import JE._
import net.liftweb.util.Helpers._
import net.liftweb.util._
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.rudder.web.services.JsTreeUtilService

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
class DirectiveManagement extends DispatchSnippet with Loggable {  
  import DirectiveManagement._
  
  val techniqueRepository = inject[TechniqueRepository]
  val activeTechniqueCategoryRepository = inject[ActiveTechniqueCategoryRepository]
  val directiveRepository = inject[DirectiveRepository]
  val uuidGen = inject[StringUuidGenerator]
  val treeUtilService = inject[JsTreeUtilService]
  
  
  def dispatch = { 
    case "head" => { _ => head }
    case "userLibrary" => { _ => userLibrary }
    case "showDirectiveDetails" => { _ => initDirectiveDetails }
    case "techniqueDetails" => { xml =>
                                      techniqueDetails = initTechniqueDetails
                                      techniqueDetails.apply(xml)
                                    }
  }
  
  //must be initialized on first call of "techniqueDetails"
  private[this] var techniqueDetails: MemoizeTransform = null
  
  //the current DirectiveEditForm component
  val currentDirectiveSettingForm = new LocalSnippet[DirectiveEditForm]

  var currentTechnique = Option.empty[(Technique,ActiveTechnique)]
  
  private[this] val directiveId: Box[String] = S.param("directiveId")
  
  /**
   * Head information (JsTree dependencies,...)
   */
  def head() : NodeSeq = {
    DirectiveEditForm.staticInit ++ 
    (
      <head>
        <script type="text/javascript" src="/javascript/jstree/jquery.jstree.js" id="jstree"></script>
        <script type="text/javascript" src="/javascript/rudder/tree.js" id="tree"></script>
        {Script(OnLoad(parseJsArg))}
      </head>
    )
  }
    
  /**
   * If a query is passed as argument, try to dejoniffy-it, in a best effort
   * way - just don't take of errors. 
   * 
   * We want to look for #{ "directiveId":"XXXXXXXXXXXX" }
   */
  private[this] def parseJsArg(): JsCmd = {
    def displayDetails(directiveId:String) = displayDirectiveDetails(DirectiveId(directiveId))
    
    JsRaw("""
        var directiveId = null;
        try {
          directiveId = JSON.parse(window.location.hash.substring(1)).directiveId ;
        } catch(e) {
          directiveId = null
        }
        if( directiveId != null && directiveId.length > 0) { 
          %s;
        }
    """.format(SHtml.ajaxCall(JsVar("directiveId"), displayDetails _ )._2.toJsCmd)
    )
  }
  
  
  /**
   * Almost same as Technique/userTree
   * TODO : factor out that part
   */
  def userLibrary() : NodeSeq = {
    (
        <div id={htmlId_userTree}>
          <ul>{jsTreeNodeOf_uptCategory(activeTechniqueCategoryRepository.getActiveTechniqueLibrary, "jstn_0").toXml}</ul>
        </div>
    ) ++ Script(OnLoad(buildJsTree))
  }
  

  private[this] def buildJsTree() : JsCmd = {
    JsRaw("""buildDirectiveTree('#%s', '%s')""".format(htmlId_userTree, {
      directiveId match {
        case Full(id) => "jsTree-" + id
        case _ => ""
      }
    })) & OnLoad(After(TimeSpan(50), JsRaw("""createTooltip();""")))
  }
  
  
  def initDirectiveDetails(): NodeSeq = directiveId match {
    case Full(id) => <div id={ htmlId_policyConf } /> ++ Script(OnLoad(displayDirectiveDetails(DirectiveId(id)) & 
        //Here, we MUST add a Noop because of a Lift bug that add a comment on the last JsLine. 
        Noop ))
    case _ =>  <div id={ htmlId_policyConf }>Click on a policy instance in the tree above to show and modify its settings.</div>
  }
  
  
  
  def initTechniqueDetails : MemoizeTransform = SHtml.memoize {

    "#polityTemplateDetails *" #> ( currentTechnique match { 
      case None => "*" #> <span class="greenscala">Click on a Policy or on a Policy Template...</span>
      case Some((technique, activeTechnique)) =>
        "#detailFieldsetId *" #> (if(currentDirectiveSettingForm.is.isDefined) {
                                  "Policy instance's template"
                                } else {
                                  "Template details"
                                })&
        "#directiveIntro " #> (currentDirectiveSettingForm.is.map { piForm =>
                                    (".directive *" #> piForm.directive.name)
                                  }) &
        "#techniqueName" #> technique.name &
        "#compatibility" #> (if (!technique.compatible.isEmpty) technique.compatible.head.toHtml else NodeSeq.Empty) &
        "#techniqueDescription" #>  technique.description &
        "#techniqueLongDescription" #>  technique.longDescription &
        "#isSingle *" #> showIsSingle(technique) &
        "#ptVersions" #> showVersions(activeTechnique) &
        "#migrate" #> showMigration(technique, activeTechnique) &
        "#addButton" #> SHtml.ajaxButton( 
            { Text("Create a new policy based on template ") ++ <b>{technique.name}</b> },
            { () =>  SetHtml(CreateDirectivePopup.htmlId_popup, newCreationPopup(technique,activeTechnique) ) &
                     JsRaw( """ createPopup("%s",300,400) """.format(CreateDirectivePopup.htmlId_popup) ) },
            ("class", "autoWidthButton")
          )
    })
  }

  private[this] def showIsSingle(technique:Technique) : NodeSeq = {
    <span>
      {
        if(technique.isMultiInstance) {
          {<b>Multi instance</b>} ++ Text(": several policies derived from that template can be deployed on a given server")
        } else {
          {<b>Unique</b>} ++ Text(": an unique policy derived from that template can be deployed on a given server")
        }
      }
    </span>
  }
    
  private[this] def showVersions(activeTechnique:ActiveTechnique) : NodeSeq = {
    //if we are on a directive node, add a "in use" after the currently used version
    val piVersion = currentDirectiveSettingForm.is.map { form => form.directive.techniqueVersion }
    
    techniqueRepository.getTechniqueVersions(activeTechnique.techniqueName).toSeq.map { v =>
          <li><b>{v.toString}</b>, last accepted on: {DateFormaterService.getFormatedDate(activeTechnique.acceptationDatetimes(v))} {
            piVersion match {
              case Full(x) if(x == v) => <i>(version used)</i>
              case _ => NodeSeq.Empty
            }
          }</li>
        }
  }
  
  /**
   * If there is only zero or one version, display nothing. 
   * Else, build an ajax form with the migration logic
   */
  private[this] def showMigration(technique:Technique, activeTechnique:ActiveTechnique) = {
    def onSubmitMigration(v:TechniqueVersion, directive:Directive, activeTechnique:ActiveTechnique) = {
      val id = TechniqueId(activeTechnique.techniqueName, v)
      (for {
        technique <- techniqueRepository.get(id) ?~!
                          "No policy template with ID=%s found in reference library.".format(id)
      } yield {
        technique
      }) match {
        case Full(technique) => 
          currentTechnique = Some((technique,activeTechnique))
          updateCf3PolicyDraftInstanceSettingFormComponent(technique,activeTechnique,directive.copy(techniqueVersion = v))
        case e:EmptyBox => currentDirectiveSettingForm.set(e)
      }
    }

    
    "*" #> currentDirectiveSettingForm.is.filter { x => 
      techniqueRepository.getTechniqueVersions(activeTechnique.techniqueName).toSeq.size > 1 }.map { form =>
          "form" #> { xml => (
              "select" #> ( SHtml.selectObj(
                  options = techniqueRepository.getTechniqueVersions(activeTechnique.techniqueName).toSeq.
                    filterNot( _ == form.directive.techniqueVersion).map { v => (v,v.toString) }
                , default = Empty
                , onSubmit = { (v:TechniqueVersion) =>
                    onSubmitMigration(v,form.directive,activeTechnique)
                } ) % ( "style", "width:60px;") ) &
              ":submit" #> SHtml.ajaxSubmit("Migrate", { () => 
                  //update UI: policy instance details
                  Replace(html_ptDetails, techniqueDetails.applyAgain) &
                  setRightPanelHeader(false) &
                  Replace(htmlId_policyConf, showDirectiveDetails) & 
                  displayFinishMigrationPopup
              } )
              ) (SHtml.ajaxForm(xml))
          }
      }
  }
  
  private[this] def setRightPanelHeader(isDirective:Boolean) : JsCmd = {
    val title = 
      if(isDirective) "Directive general information"
      else "Technique summary"
      
      SetHtml("detailsPortletTitle", Text(title))
  }
  
  ///////////// finish migration pop-up ///////////////
  private[this] def displayFinishMigrationPopup : JsCmd = {
    JsRaw("""
      callPopupWithTimeout(200,"finishMigrationPopup",100,350)
    """)
  }
  
  /**
   * Configure a system technique to be usable in the
   * user Technique (private) library. 
   */
  private[this] def showDirectiveDetails() : NodeSeq = {
    currentDirectiveSettingForm.is match {
      case Failure(m,_,_) => <div id={htmlId_policyConf} class="error">An error happened when trying to load policy configuration. Error message was: {m}</div>
      case Empty => <div id={htmlId_policyConf}>Click on a policy instance to show and modify its settings</div>
      //here we CAN NOT USE <lift:DirectiveEditForm.showForm /> because lift seems to cache things
      //strangely, and if so, after an form save, clicking on tree node does nothing
      // (or more exactly, the call to "onclicknode" is correct, the currentDirectiveSettingForm
      // has the good Directive, but the <lift:DirectiveEditForm.showForm /> is called on
      // an other component (the one which did the submit). Strange. 
      case Full(formComponent) => formComponent.showForm()
    }
  }
  
  private[this] def newCreationPopup(technique:Technique, activeTechnique:ActiveTechnique) : NodeSeq = {
    new CreateDirectivePopup(
        technique.name, technique.description, technique.id.version, 
        onSuccessCallback = { (directive : Directive) =>
          updateCf3PolicyDraftInstanceSettingFormComponent(technique,activeTechnique,directive, true)
          //Update UI
          Replace(htmlId_policyConf, showDirectiveDetails)
        }
    ).popupContent
  }
   
  private[this] def displayDirectiveDetails(directiveId: DirectiveId): JsCmd = {
    //Set current policy instance edition component to the given value
    (for {
      directive <- directiveRepository.getDirective(directiveId) ?~! 
            "No user policy instance with ID=%s.".format(directiveId)
      activeTechnique <- directiveRepository.getActiveTechnique(directiveId) ?~! 
                            "Can not find the user policy template for policy instance %s".format(directiveId)
      activeTechniqueId = TechniqueId(activeTechnique.techniqueName, directive.techniqueVersion)
      technique <- techniqueRepository.get(activeTechniqueId) ?~!
                        "No policy template with ID=%s found in reference library.".format(activeTechniqueId)
    } yield {
      (technique, activeTechnique,directive)
    }) match {
      case Full((technique,activeTechnique,directive)) => 
        currentTechnique = Some((technique,activeTechnique))
        updateCf3PolicyDraftInstanceSettingFormComponent(technique,activeTechnique,directive)
      case e:EmptyBox => currentDirectiveSettingForm.set(e)
    }
    
    //update UI: policy instance details
    Replace(html_ptDetails, techniqueDetails.applyAgain) &
    setRightPanelHeader(true) &
    Replace(htmlId_policyConf, showDirectiveDetails) &
    JsRaw("""this.window.location.hash = "#" + JSON.stringify({'directiveId':'%s'})""".format(directiveId.value))
  }
  
  private[this] def updateCf3PolicyDraftInstanceSettingFormComponent(technique:Technique,activeTechnique:ActiveTechnique,directive:Directive, piCreation : Boolean = false) : Unit = {
    currentDirectiveSettingForm.set(Full(
        new DirectiveEditForm(htmlId_policyConf,technique, activeTechnique,directive,
            onSuccessCallback = { () => Replace(htmlId_userTree, userLibrary()) },
            piCreation = piCreation
        )))
  }
   
  //////////////// display trees ////////////////////////    
  
  /**
   * Transform ActiveTechniqueCategory into category JsTree nodes in User Library:
   * - contains 
   *   - other user categories
   *   - user policy templates
   */
  private[this] def jsTreeNodeOf_uptCategory(category:ActiveTechniqueCategory, nodeId:String) : JsTreeNode = {
    /*
     * Transform a Directive into a JsTree node
     */
    def jsTreeNodeOf_upt(activeTechnique : ActiveTechnique, technique:Technique) : JsTreeNode = {    
      // now, actually map activeTechnique to JsTreeNode
      new JsTreeNode {
        def onClickNode() : JsCmd = {
            currentTechnique = Some((technique,activeTechnique))
              
            currentDirectiveSettingForm.set(Empty)
              
            //Update UI
            Replace(html_ptDetails, techniqueDetails.applyAgain) &
            setRightPanelHeader(false) &
            Replace(htmlId_policyConf, showDirectiveDetails) & JsRaw("""correctButtons();""")
        }
            
        override val attrs = ( "rel" -> "template") :: Nil ::: (if(!activeTechnique.isEnabled) ("class" -> "disableTreeNode") :: Nil else Nil )
        override def body = {
          val tooltipid = Helpers.nextFuncName
          SHtml.a(
            onClickNode _,  
            <span class="treeActiveTechniqueName tooltipable" tooltipid={tooltipid}>{technique.name}</span>
            <div class="tooltipContent" id={tooltipid}>{technique.description}</div>
        )} 
            
        override def children = activeTechnique.directives.flatMap( treeUtilService.getPi(_,logger) ).toList.
            sortWith( treeUtilService.sortPi( _, _ ) ).map { directive => jsTreeNodeOf_directive(activeTechnique,directive) }
      }
    }
    
    /*
     * Transform a Directive into a JsTree leaf
     */
    def jsTreeNodeOf_directive(activeTechnique : ActiveTechnique, directive : Directive) : JsTreeNode = {
      new JsTreeNode {
         //ajax function that update the bottom
        def onClickNode() : JsCmd = {
          displayDirectiveDetails(directive.id)
        }
        
        override def body = {
          val tooltipid = Helpers.nextFuncName
          SHtml.a(
            onClickNode _,  <span class="treeDirective tooltipable" tooltipid={tooltipid}>{directive.name}</span>
            <div class="tooltipContent" id={tooltipid}>{directive.shortDescription}</div>
        )} 
        override def children = Nil
        override val attrs = ( "rel" -> "policy") :: ( "id" -> ("jsTree-"+directive.id.value)) :: Nil ::: (if(!directive.isEnabled) ("class" -> "disableTreeNode") :: Nil else Nil )
      }
    }
    
    /*
     * Transform a Policy template category into a JsTree node
     */
    new JsTreeNode {
      override def body = { 
        val tooltipid = Helpers.nextFuncName
        ( <a><span class="treeActiveTechniqueCategoryName tooltipable" tooltipid={tooltipid}>{Text(category.name)}</span></a>
          <div class="tooltipContent" id={tooltipid}>{category.description}</div>  
        )
      }
      override def children = 
          category.children.flatMap(treeUtilService.getActiveTechniqueCategory( _,logger )).toList.
            sortWith( treeUtilService.sortActiveTechniqueCategory( _,_ ) ).zipWithIndex.map{ case (node, i) =>
              jsTreeNodeOf_uptCategory(node, nodeId + "_" + i)
            } ++ category.items.flatMap( treeUtilService.getActiveTechnique(_,logger)).toList.sortWith( _._2.name < _._2.name).map { case(activeTechnique,technique) => jsTreeNodeOf_upt(activeTechnique,technique) }
      override val attrs = ( "rel" -> "category") :: ( "id" -> nodeId) :: Nil
    }
  }
}


object DirectiveManagement {
  
  /*
   * HTML id for zones with Ajax / snippet output
   */
  val htmlId_userTree = "userTree"
  val htmlId_policyConf = "policyConfiguration"
  val htmlId_currentActiveTechniqueActions = "currentActiveTechniqueActions"
  val html_addPiInActiveTechnique = "addNewDirective" 
  val html_ptDetails = "polityTemplateDetails"

}

