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

import com.normation.rudder.web.components.popup.CreatePolicyInstancePopup
import com.normation.rudder.web.model.JsTreeNode
import com.normation.rudder.domain.policies._
import com.normation.cfclerk.domain.{PolicyPackage,PolicyPackageId}
import com.normation.cfclerk.services.PolicyPackageService
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.web.components.{PolicyInstanceEditForm,DateFormaterService}
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
import com.normation.cfclerk.domain.PolicyVersion
import com.normation.rudder.web.services.JsTreeUtilService

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
class PolicyInstanceManagement extends DispatchSnippet with Loggable {  
  import PolicyInstanceManagement._
  
  val policyPackageService = inject[PolicyPackageService]
  val userPolicyTemplateCategoryRepository = inject[UserPolicyTemplateCategoryRepository]
  val policyInstanceRepository = inject[PolicyInstanceRepository]
  val uuidGen = inject[StringUuidGenerator]
  val treeUtilService = inject[JsTreeUtilService]
  
  
  def dispatch = { 
    case "head" => { _ => head }
    case "userLibrary" => { _ => userLibrary }
    case "showPolicyInstanceDetails" => { _ => initPolicyInstanceDetails }
    case "policyTemplateDetails" => policyTemplateDetails
  }
  
  //the current PolicyInstanceEditForm component
  val currentPolicyInstanceSettingForm = new LocalSnippet[PolicyInstanceEditForm]

  var currentPolicyTemplate = Option.empty[(PolicyPackage,UserPolicyTemplate)]
  
  private[this] val policyInstanceId: Box[String] = S.param("policyInstanceId")
  
  /**
   * Head information (JsTree dependencies,...)
   */
  def head() : NodeSeq = {
    PolicyInstanceEditForm.staticInit ++ 
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
   * We want to look for #{ "piId":"XXXXXXXXXXXX" }
   */
  private[this] def parseJsArg(): JsCmd = {
    def displayDetails(piId:String) = displayPolicyInstanceDetails(PolicyInstanceId(piId))
    
    JsRaw("""
        var piId = null;
        try {
          piId = JSON.parse(window.location.hash.substring(1)).piId ;
        } catch(e) {
          piId = null
        }
        if( piId != null && piId.length > 0) { 
          %s;
        }
    """.format(SHtml.ajaxCall(JsVar("piId"), displayDetails _ )._2.toJsCmd)
    )
  }
  
  
  /**
   * Almost same as PolicyTemplate/userTree
   * TODO : factor out that part
   */
  def userLibrary() : NodeSeq = {
    (
      <div id={htmlId_userTree}>
        <ul>{jsTreeNodeOf_uptCategory(userPolicyTemplateCategoryRepository.getUserPolicyTemplateLibrary).toXml}</ul>
      </div>
    ) ++ Script(OnLoad(buildJsTree))
  }
  

  private[this] def buildJsTree() : JsCmd = {
    JsRaw("""buildPolicyInstanceTree('#%s', '%s')""".format(htmlId_userTree, {
      policyInstanceId match {
        case Full(id) => "jsTree-" + id
        case _ => ""
      }
    })) & OnLoad(After(TimeSpan(50), JsRaw("""createTooltip();""")))
  }
  
  
  def initPolicyInstanceDetails(): NodeSeq = policyInstanceId match {
    case Full(id) => <div id={ htmlId_policyConf } /> ++ Script(OnLoad(displayPolicyInstanceDetails(PolicyInstanceId(id)) & 
        //Here, we MUST add a Noop because of a Lift bug that add a comment on the last JsLine. 
        Noop ))
    case _ =>  <div id={ htmlId_policyConf }>Click on a policy instance in the tree above to show and modify its settings.</div>
  }
  
  
  
  def policyTemplateDetails : MemoizeTransform = SHtml.memoize {

    "#polityTemplateDetails *" #> ( currentPolicyTemplate match { 
      case None => "*" #> <span class="greenscala">Click on a Policy or on a Policy Template...</span>
      case Some((pt, upt)) =>
        "#detailFieldsetId *" #> (if(currentPolicyInstanceSettingForm.is.isDefined) {
                                  "Policy instance's template"
                                } else {
                                  "Template details"
                                })&
        "#policyInstanceIntro " #> (currentPolicyInstanceSettingForm.is.map { piForm =>
                                    (".pi *" #> piForm.pi.name)
                                  }) &
        "#policyTemplateName" #> pt.name &
        "#compatibility" #> (if (!pt.compatible.isEmpty) pt.compatible.head.toHtml else NodeSeq.Empty) &
        "#policyTemplateDescription" #>  pt.description &
        "#policyTemplateLongDescription" #>  pt.longDescription &
        "#isSingle *" #> showIsSingle(pt) &
        "#ptVersions" #> showVersions(upt) &
        "#migrate" #> showMigration(pt, upt) &
        "#addButton" #> SHtml.ajaxButton( 
            { Text("Create a new policy based on template ") ++ <b>{pt.name}</b> },
            { () =>  SetHtml(CreatePolicyInstancePopup.htmlId_popup, newCreationPopup(pt,upt) ) &
                     JsRaw( """ $("#%s").modal({
                       minHeight:300,
                       minWidth: 400
                   });
                   $('#simplemodal-container').css('height', 'auto');
                   """.format(CreatePolicyInstancePopup.htmlId_popup) ) },
            ("class", "autoWidthButton")
          )
    })
  }

  private[this] def showIsSingle(pt:PolicyPackage) : NodeSeq = {
    <span>
      {
        if(pt.isMultiInstance) {
          {<b>Multi instance</b>} ++ Text(": several policies derived from that template can be deployed on a given server")
        } else {
          {<b>Unique</b>} ++ Text(": an unique policy derived from that template can be deployed on a given server")
        }
      }
    </span>
  }
    
  private[this] def showVersions(upt:UserPolicyTemplate) : NodeSeq = {
    //if we are on a pi node, add a "in use" after the currently used version
    val piVersion = currentPolicyInstanceSettingForm.is.map { form => form.pi.policyTemplateVersion }
    
    policyPackageService.getVersions(upt.referencePolicyTemplateName).toSeq.map { v =>
          <li><b>{v.toString}</b>, last accepted on: {DateFormaterService.getFormatedDate(upt.acceptationDatetimes(v))} {
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
  private[this] def showMigration(pt:PolicyPackage, upt:UserPolicyTemplate) = {
    def onSubmitMigration(v:PolicyVersion, pi:PolicyInstance, upt:UserPolicyTemplate) = {
      val id = PolicyPackageId(upt.referencePolicyTemplateName, v)
      (for {
        policyTemplate <- policyPackageService.getPolicy(id) ?~!
                          "No policy template with ID=%s found in reference library.".format(id)
      } yield {
        policyTemplate
      }) match {
        case Full(policyTemplate) => 
          currentPolicyTemplate = Some((policyTemplate,upt))
          updatePolicyInstanceSettingFormComponent(policyTemplate,upt,pi.copy(policyTemplateVersion = v))
        case e:EmptyBox => currentPolicyInstanceSettingForm.set(e)
      }
    }

    
    "*" #> currentPolicyInstanceSettingForm.is.filter { x => 
      policyPackageService.getVersions(upt.referencePolicyTemplateName).toSeq.size > 1 }.map { form =>
          "form" #> { xml => (
              "select" #> ( SHtml.selectObj(
                  options = policyPackageService.getVersions(upt.referencePolicyTemplateName).toSeq.
                    filterNot( _ == form.pi.policyTemplateVersion).map { v => (v,v.toString) }
                , default = Empty
                , onSubmit = { (v:PolicyVersion) =>
                    onSubmitMigration(v,form.pi,upt)
                } ) % ( "style", "width:60px;") ) &
              ":submit" #> SHtml.ajaxSubmit("Migrate", { () => 
                  //update UI: policy instance details
                  Replace(html_ptDetails, policyTemplateDetails.applyAgain) &
                  setRightPanelHeader(false) &
                  Replace(htmlId_policyConf, showPolicyInstanceDetails) & 
                  displayFinishMigrationPopup
              } )
              ) (SHtml.ajaxForm(xml))
          }
      }
  }
  
  private[this] def setRightPanelHeader(isPolicyInstance:Boolean) : JsCmd = {
    val title = 
      if(isPolicyInstance) "Policy general information"
      else "Policy Template summary"
      
      SetHtml("detailsPortletTitle", Text(title))
  }
  
  ///////////// finish migration pop-up ///////////////
  private[this] def displayFinishMigrationPopup : JsCmd = {
    JsRaw("""
      setTimeout(function() { $("#finishMigrationPopup").modal({
          minHeight:100,
          minWidth: 350
        });
        $('#simplemodal-container').css('height', 'auto');
      }, 200);
    """)
  }
  
  /**
   * Configure a system policyTemplate to be usable in the
   * user PolicyTemplate (private) library. 
   */
  private[this] def showPolicyInstanceDetails() : NodeSeq = {
    currentPolicyInstanceSettingForm.is match {
      case Failure(m,_,_) => <div id={htmlId_policyConf} class="error">An error happened when trying to load policy configuration. Error message was: {m}</div>
      case Empty => <div id={htmlId_policyConf}>Click on a policy instance to show and modify its settings</div>
      //here we CAN NOT USE <lift:PolicyInstanceEditForm.showForm /> because lift seems to cache things
      //strangely, and if so, after an form save, clicking on tree node does nothing
      // (or more exactly, the call to "onclicknode" is correct, the currentPolicyInstanceSettingForm
      // has the good PolicyInstance, but the <lift:PolicyInstanceEditForm.showForm /> is called on
      // an other component (the one which did the submit). Strange. 
      case Full(formComponent) => formComponent.showForm()
    }
  }
  
  private[this] def newCreationPopup(pt:PolicyPackage, upt:UserPolicyTemplate) : NodeSeq = {
    new CreatePolicyInstancePopup(
        pt.name, pt.description, pt.id.version, 
        onSuccessCallback = { (pi : PolicyInstance) =>
          updatePolicyInstanceSettingFormComponent(pt,upt,pi, true)
          //Update UI
          Replace(htmlId_policyConf, showPolicyInstanceDetails)
        }
    ).popupContent
  }
   
  private[this] def displayPolicyInstanceDetails(piId: PolicyInstanceId): JsCmd = {
    //Set current policy instance edition component to the given value
    (for {
      pi <- policyInstanceRepository.getPolicyInstance(piId) ?~! 
            "No user policy instance with ID=%s.".format(piId)
      userPolicyTemplate <- policyInstanceRepository.getUserPolicyTemplate(piId) ?~! 
                            "Can not find the user policy template for policy instance %s".format(piId)
      ptId = PolicyPackageId(userPolicyTemplate.referencePolicyTemplateName, pi.policyTemplateVersion)
      policyTemplate <- policyPackageService.getPolicy(ptId) ?~!
                        "No policy template with ID=%s found in reference library.".format(ptId)
    } yield {
      (policyTemplate, userPolicyTemplate,pi)
    }) match {
      case Full((policyTemplate,userPolicyTemplate,pi)) => 
        currentPolicyTemplate = Some((policyTemplate,userPolicyTemplate))
        updatePolicyInstanceSettingFormComponent(policyTemplate,userPolicyTemplate,pi)
      case e:EmptyBox => currentPolicyInstanceSettingForm.set(e)
    }
    
    //update UI: policy instance details
    Replace(html_ptDetails, policyTemplateDetails.applyAgain) &
    setRightPanelHeader(true) &
    Replace(htmlId_policyConf, showPolicyInstanceDetails) &
    JsRaw("""this.window.location.hash = "#" + JSON.stringify({'piId':'%s'})""".format(piId.value))
  }
  
  private[this] def updatePolicyInstanceSettingFormComponent(policyTemplate:PolicyPackage,userPolicyTemplate:UserPolicyTemplate,pi:PolicyInstance, piCreation : Boolean = false) : Unit = {
    currentPolicyInstanceSettingForm.set(Full(
        new PolicyInstanceEditForm(htmlId_policyConf,policyTemplate, userPolicyTemplate,pi,
            onSuccessCallback = { () => Replace(htmlId_userTree, userLibrary()) },
            piCreation = piCreation
        )))
  }
   
  //////////////// display trees ////////////////////////    
  
  /**
   * Transform UserPolicyTemplateCategory into category JsTree nodes in User Library:
   * - contains 
   *   - other user categories
   *   - user policy templates
   */
  private[this] def jsTreeNodeOf_uptCategory(category:UserPolicyTemplateCategory) : JsTreeNode = {
    /*
     * Transform a PolicyInstance into a JsTree node
     */
    def jsTreeNodeOf_upt(upt : UserPolicyTemplate, pt:PolicyPackage) : JsTreeNode = {    
      // now, actually map upt to JsTreeNode
      new JsTreeNode {
        def onClickNode() : JsCmd = {
            currentPolicyTemplate = Some((pt,upt))
              
            currentPolicyInstanceSettingForm.set(Empty)
              
            //Update UI
            Replace(html_ptDetails, policyTemplateDetails.applyAgain) &
            setRightPanelHeader(false) &
            Replace(htmlId_policyConf, showPolicyInstanceDetails) & JsRaw("""correctButtons();""")
        }
            
        override val attrs = ( "rel" -> "template") :: Nil ::: (if(!upt.isActivated) ("class" -> "disableTreeNode") :: Nil else Nil )
        override def body = {
          val tooltipid = Helpers.nextFuncName
          SHtml.a(
            onClickNode _,  
            <span class="treeUptName tooltipable" tooltipid={tooltipid}>{pt.name}</span>
            <div class="tooltipContent" id={tooltipid}>{pt.description}</div>
        )} 
            
        override def children = upt.policyInstances.flatMap( treeUtilService.getPi(_,logger) ).toList.
            sortWith( treeUtilService.sortPi( _, _ ) ).map { pi => jsTreeNodeOf_policyInstance(upt,pi) }
      }
    }
    
    /*
     * Transform a PolicyInstance into a JsTree leaf
     */
    def jsTreeNodeOf_policyInstance(upt : UserPolicyTemplate, pi : PolicyInstance) : JsTreeNode = {
      new JsTreeNode {
         //ajax function that update the bottom
        def onClickNode() : JsCmd = {
          displayPolicyInstanceDetails(pi.id)
        }
        
        override def body = {
          val tooltipid = Helpers.nextFuncName
          SHtml.a(
            onClickNode _,  <span class="treePolicyInstance tooltipable" tooltipid={tooltipid}>{pi.name}</span>
            <div class="tooltipContent" id={tooltipid}>{pi.shortDescription}</div>
        )} 
        override def children = Nil
        override val attrs = ( "rel" -> "policy") :: ( "id" -> ("jsTree-"+pi.id.value)) :: Nil ::: (if(!pi.isActivated) ("class" -> "disableTreeNode") :: Nil else Nil )
      }
    }
    
    /*
     * Transform a Policy template category into a JsTree node
     */
    new JsTreeNode {
      override def body = { 
        val tooltipid = Helpers.nextFuncName
        ( <a><span class="treeUptCategoryName tooltipable" tooltipid={tooltipid}>{Text(category.name)}</span></a>
      	  <div class="tooltipContent" id={tooltipid}>{category.description}</div>  
      	)
      }
      override def children = 
          category.children.flatMap(treeUtilService.getUptCategory( _,logger )).toList.
            sortWith( treeUtilService.sortUptCategory( _,_ ) ).map(jsTreeNodeOf_uptCategory(_)) ++ 
          category.items.flatMap( treeUtilService.getUpt(_,logger)).toList.sortWith( _._2.name < _._2.name).map { case(upt,pt) => jsTreeNodeOf_upt(upt,pt) }
      override val attrs = ( "rel" -> "category") :: Nil
    }
  }
}


object PolicyInstanceManagement {
  
  /*
   * HTML id for zones with Ajax / snippet output
   */
  val htmlId_userTree = "userTree"
  val htmlId_policyConf = "policyConfiguration"
  val htmlId_currentUptActions = "currentUptActions"
  val html_addPiInUpt = "addNewPolicyInstance" 
  val html_ptDetails = "polityTemplateDetails"

}

