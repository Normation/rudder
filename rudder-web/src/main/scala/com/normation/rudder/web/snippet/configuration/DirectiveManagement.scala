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
import com.normation.rudder.domain.policies.{DirectiveId, Directive}
import com.normation.cfclerk.domain.{Technique,TechniqueId}
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.web.components.{DirectiveEditForm,DateFormaterService}
import com.normation.rudder.repository._
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
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.cfclerk.domain.TechniqueId
import com.normation.rudder.web.services.DisplayDirectiveTree

/**
 * Snippet for managing the System and Active Technique libraries.
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

  val techniqueRepository = RudderConfig.techniqueRepository
  val getDirectiveLib     = () => RudderConfig.roDirectiveRepository.getFullDirectiveLibrary
  val uuidGen             = RudderConfig.stringUuidGenerator
  val treeUtilService     = RudderConfig.jsTreeUtilService
  val workflowEnabled     = RudderConfig.RUDDER_ENABLE_APPROVAL_WORKFLOWS

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

  //we specify here what is the technique we are working on
  //technique version must be in the map of techniques in the fullActiveTechnique
  var currentTechnique = Option.empty[(FullActiveTechnique, TechniqueVersion)]

  //the state of the directive library.
  //must be reloaded "updateDirectiveLibrary()"
  //when information change (directive added/removed/modified, etc)
  var directiveLibrary = getDirectiveLib()

  private[this] val directiveId: Box[String] = S.param("directiveId")


  /**
   * Head information (JsTree dependencies,...)
   */
  def head() : NodeSeq = {
    DirectiveEditForm.staticInit ++
    (
      <head>
        <script type="text/javascript" src="/javascript/jstree/jquery.jstree.js" id="jstree">
        </script>
        <script type="text/javascript" src="/javascript/rudder/tree.js" id="tree">
        </script>
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
          directiveId = null;
        }
        if( directiveId != null && directiveId.length > 0) {
          %s;
        }
    """.format(SHtml.ajaxCall(JsVar("directiveId"), displayDetails _ )._2.toJsCmd)
    )
  }

  /**
   * Almost same as Technique/activeTechniquesTree
   * TODO : factor out that part
   */
  def userLibrary(): NodeSeq = {
    (
      <div id={htmlId_activeTechniquesTree} class="nodisplay">{
          directiveLibrary match {
            case eb:EmptyBox =>
              val f = eb ?~! "Error when trying to get the root category of Active Techniques"
              logger.error(f.messageChain)
              f.rootExceptionCause.foreach { ex =>
                logger.error("Exception causing the error was:" , ex)
              }
              <span class="error">An error occured when trying to get information from the database. Please contact your administrator of retry latter.</span>
            case Full(activeTechLib) =>
              <ul>{
                DisplayDirectiveTree.displayTree(activeTechLib, None, Some(onClickActiveTechnique), Some(onClickDirective))
              }</ul>
          }
      }</div>
    ) ++ Script(OnLoad(buildJsTree()))
  }


  private[this] def buildJsTree() : JsCmd = {

    def isDirectiveIdValid(directiveId: String): JsCmd = {
      directiveLibrary.flatMap( _.allDirectives.get(DirectiveId(directiveId))) match {
        case Full((activeTechnique, directive)) =>
          JsRaw(""" buildDirectiveTree('#%s', '%s', '%s') """
            .format(htmlId_activeTechniquesTree, "jsTree-" + directive.id.value, S.contextPath))
        case e:EmptyBox =>
          JsRaw(""" buildDirectiveTree('#%s', '', '%s') """.format(htmlId_activeTechniquesTree, S.contextPath))
      }
    }

    JsRaw("""
        var directiveId = null;
        try {
          directiveId = JSON.parse(window.location.hash.substring(1)).directiveId ;
        } catch(e) {
          directiveId = null;
        }

        %s;

    """.format(SHtml.ajaxCall(JsVar("directiveId"), isDirectiveIdValid _ )._2.toJsCmd,
         htmlId_activeTechniquesTree))
  }

  def initDirectiveDetails(): NodeSeq = directiveId match {
    case Full(id) => <div id={ htmlId_policyConf } /> ++
      //Here, we MUST add a Noop because of a Lift bug that add a comment on the last JsLine.
      Script(OnLoad(displayDirectiveDetails(DirectiveId(id)) & Noop))
    case _ =>  <div id={ htmlId_policyConf }></div>
  }



  def initTechniqueDetails : MemoizeTransform = SHtml.memoize {

    "#techniqueDetails *" #> ( currentTechnique match {
      case None => "*" #> {
        <div class="deca">
          <p><em>Directives</em> are displayed in the tree of
          <a href="/secure/administration/techniqueLibraryManagement">
            <em>Active Techniques</em>
          </a>,
          grouped by categories.</p>
          <ul>
            <li>Fold/unfold category folders;</li>
            <li>Click on the name of a <em>Technique</em> to see its description;</li>
            <li>
              Click on the name of a <em>Directive</em> to see its configuration items.
              Details of the <em>Technique</em> it's based on will also be displayed.
            </li>
          </ul>
          <p>Additional <em>Techniques</em> may be available through the
            <a href="/secure/administration/techniqueLibraryManagement">
              Techniques screen
            </a>.
          </p>
        </div>
      }
      case Some((fullActiveTechnique,version)) =>
        val technique = fullActiveTechnique.techniques(version)

        "#detailFieldsetId *" #> {
          if(currentDirectiveSettingForm.is.isDefined) {
            "Directive's template"
          } else {
            "Template details"
          }
        } &
        "#directiveIntro " #> {
          currentDirectiveSettingForm.is.map { piForm =>
            (".directive *" #> piForm.directive.name)
          }
        } &
        "#techniqueName" #> technique.name &
        "#compatibility" #> {
          if (!technique.compatible.isEmpty) technique.compatible.head.toHtml
          else NodeSeq.Empty
        } &
        "#techniqueDescription" #>  technique.description &
        "#techniqueLongDescription" #>  technique.longDescription &
        "#isSingle *" #> showIsSingle(technique) &
        "#techniqueVersions" #> showVersions(fullActiveTechnique) &
        "#migrate" #> showMigration(fullActiveTechnique) &
        "#addButton" #> SHtml.ajaxButton(
          { Text("Create a new Directive based on technique ") ++ <b>{technique.name}</b> },
          { () =>  SetHtml(CreateDirectivePopup.htmlId_popup,
                     newCreationPopup(technique, fullActiveTechnique)) &
                   JsRaw( s""" createPopup("${CreateDirectivePopup.htmlId_popup}") """) },
            ("class", "autoWidthButton")
          )
    })
  }

  private[this] def showIsSingle(technique:Technique) : NodeSeq = {
    <span>
      {
        if(technique.isMultiInstance) {
          {<b>Multi instance</b>} ++
          Text(": several Directives derived from that template can be deployed on a given server")
        } else {
          {<b>Unique</b>} ++
          Text(": an unique Directive derived from that template can be deployed on a given server")
        }
      }
    </span>
  }

  private[this] def showVersions(activeTechnique: FullActiveTechnique) : NodeSeq = {
    //if we are on a Policy Server, add a "in use" after the currently used version
    val directiveVersion = currentDirectiveSettingForm.is.map {
      form => form.directive.techniqueVersion
    }

    activeTechnique.techniques.map { case(v,t) =>

        activeTechnique.acceptationDatetimes.get(v) match {
          case Some(timeStamp) =>
            <li>
              <b>{v.toString}</b>, last accepted on:
                { DateFormaterService.getFormatedDate(timeStamp)} {
                  directiveVersion match {
                    case Full(x) if(x == v) => <i>(version used)</i>
                    case _ => NodeSeq.Empty
                  }
                }
             </li>

          case None =>
            logger.error("Inconsistent Technique version state for Technique with ID '%s' and its version '%s': ".format(activeTechnique.techniqueName, v.toString) +
                    "that version was not correctly registered into Rudder and can not be use for now.")
            logger.debug("A workaround is to remove that version manually from Rudder (move the directory for that version of the Technique out " +
                    "of your configuration-repository directory (for example in /tmp) and 'git commit' the modification), " +
                    "reload the Technique Library, then add back the version back (move it back at its place, 'git add' the directory, 'git commit' the" +
                    "modification), and reload again the Technique Library.")

            NodeSeq.Empty
        }
     }.toSeq.flatten
  }

  /**
   * If there is only zero or one version, display nothing.
   * Else, build an ajax form with the migration logic
   */
  private[this] def showMigration(activeTechnique: FullActiveTechnique) = {

    def onSubmitMigration(v:TechniqueVersion, directive:Directive, activeTechnique: FullActiveTechnique) = {
      currentTechnique = Some((activeTechnique,v))
      updateCf3PolicyDraftInstanceSettingFormComponent(activeTechnique, directive.copy(techniqueVersion = v), Some(directive))
    }

    "*" #> currentDirectiveSettingForm.is
      .filter { _ => //do not display form when only one version is available
        activeTechnique.techniques.size > 1
      }
      .map { form =>
        "form" #> { (xml:NodeSeq) =>
          val options = activeTechnique.techniques.keys
            .toSeq
            .filterNot( _ == form.directive.techniqueVersion)
            .map( v => (v,v.toString) )
            .reverse
          val onSubmit =  { (v:TechniqueVersion) =>
            onSubmitMigration(v, form.directive, activeTechnique)
          }
          val jsFunc = { () =>
            //update UI: Directive details
            Replace(html_techniqueDetails, techniqueDetails.applyAgain) &
            setRightPanelHeader(false) &
            Replace(htmlId_policyConf, showDirectiveDetails) &
            displayFinishMigrationPopup
          }
          (
            "select" #> (SHtml.selectObj(options, default = Empty, onSubmit) %
              ("style", "width:60px;")) &
            ":submit" #> SHtml.ajaxSubmit("Migrate", jsFunc)
          ) (SHtml.ajaxForm(xml))
        }
      }
  }

  private[this] def setRightPanelHeader(isDirective:Boolean) : JsCmd = {
    val title = if (isDirective) "About this Directive" else "About this Technique"
    SetHtml("detailsPortletTitle", Text(title))
  }

  ///////////// finish migration pop-up ///////////////
  private[this] def displayFinishMigrationPopup : JsCmd = {
    JsRaw(""" callPopupWithTimeout(200,"finishMigrationPopup") """)
  }

  /**
   * Configure a Rudder internal Technique to be usable in the
   * user Technique (private) library.
   */
  private[this] def showDirectiveDetails() : NodeSeq = {
    currentDirectiveSettingForm.is match {
      case Failure(m,_,_) =>
        <div id={htmlId_policyConf} class="error">
          An error happened when trying to load Directive configuration.
          Error message was: {m}
        </div>
      case Empty =>  <div id={htmlId_policyConf}></div>
      //here we CAN NOT USE <lift:DirectiveEditForm.showForm /> because lift seems to cache things
      //strangely, and if so, after an form save, clicking on tree node does nothing
      // (or more exactly, the call to "onclicknode" is correct, the currentDirectiveSettingForm
      // has the good Directive, but the <lift:DirectiveEditForm.showForm /> is called on
      // an other component (the one which did the submit). Strange.
      case Full(formComponent) => formComponent.showForm()
    }
  }

  private[this] def newCreationPopup(technique: Technique, activeTechnique: FullActiveTechnique) : NodeSeq = {
    new CreateDirectivePopup(
        technique.name, technique.description, technique.id.version,
        onSuccessCallback = { (directive : Directive) =>
          updateCf3PolicyDraftInstanceSettingFormComponent(activeTechnique, directive,None, true)
          //Update UI
          Replace(htmlId_policyConf, showDirectiveDetails) &
          JsRaw("""createTooltip(); scrollToElement('%s')""".format(htmlId_policyConf))
        }
    ).popupContent
  }

  private[this] def displayDirectiveDetails(directiveId: DirectiveId): JsCmd = {
    //Set current Directive edition component to the given value

    directiveLibrary.flatMap( _.allDirectives.get(directiveId)) match {
      case Full((fullActiveTechnique, directive)) =>
        Replace(htmlId_activeTechniquesTree, userLibrary)
        currentTechnique = Some((fullActiveTechnique, directive.techniqueVersion))
        updateCf3PolicyDraftInstanceSettingFormComponent(fullActiveTechnique,directive,None)
      case eb: EmptyBox => currentDirectiveSettingForm.set(eb)
    }

    //update UI: Directive details
    Replace(html_techniqueDetails, techniqueDetails.applyAgain) &
    setRightPanelHeader(true) &
    Replace(htmlId_policyConf, showDirectiveDetails) &
    JsRaw("""this.window.location.hash = "#" + JSON.stringify({'directiveId':'%s'})"""
        .format(directiveId.value)) &
    JsRaw("""createTooltip(); scrollToElement('%s')""".format(htmlId_policyConf))
  }

  private[this] def updateCf3PolicyDraftInstanceSettingFormComponent(
      activeTechnique     : FullActiveTechnique
    , directive           : Directive
    , oldDirective        : Option[Directive]
    , isADirectiveCreation: Boolean = false
  ) : Unit = {


    activeTechnique.techniques.get(directive.techniqueVersion) match {
      case Some(technique) =>
        val dirEditForm = new DirectiveEditForm(
            htmlId_policyConf
          , technique
          , activeTechnique.toActiveTechnique
          , directive
          , oldDirective
          , onSuccessCallback = directiveEditFormSuccessCallBack
          , isADirectiveCreation = isADirectiveCreation
          , onRemoveSuccessCallBack = onRemoveSuccessCallBack
        )

        currentDirectiveSettingForm.set(Full(dirEditForm))
      case None =>
        val msg = s"Can not display directive edit form: missing information about technique with name='${activeTechnique.techniqueName}' and version='${directive.techniqueVersion}'"
        logger.warn(msg)
        currentDirectiveSettingForm.set(Failure(msg))
    }
  }

  /**
   * Callback used to update the form when the edition of the directive have
   * been done
   * If it is given a directive, it updated the form, else goes to the changerequest page
   */
  private[this] def directiveEditFormSuccessCallBack(returns: Either[Directive,ChangeRequestId]): JsCmd = {
    returns match {
      case Left(dir) => // ok, we've received a directive, show it
        updateDirectiveLibrary()

        directiveLibrary.flatMap( _.allDirectives.get(dir.id)) match {
          case Full((activeTechnique, directive)) => {
            updateCf3PolicyDraftInstanceSettingFormComponent(activeTechnique, dir, None)
            Replace(htmlId_policyConf, showDirectiveDetails) &
            JsRaw("""this.window.location.hash = "#" + JSON.stringify({'directiveId':'%s'})"""
              .format(dir.id.value)) &
            Replace(htmlId_activeTechniquesTree, userLibrary)
          }
          case eb:EmptyBox => {
            val errMsg = "Error when trying to get directive'%s' [%s] info with its " +
                "technique context for displaying in Directive Management edit form."
            val e = eb ?~! errMsg.format(dir.name, dir.id)
            logger.error(e.messageChain)
            e.rootExceptionCause.foreach { ex =>
              logger.error("Root exception was: ", ex)
            }
            Alert("Error when trying to get display the page. Please, try again")
          }
        }
      case Right(changeRequest) => // oh, we have a change request, go to it
        RedirectTo(s"""/secure/utilities/changeRequest/${changeRequest.value}""")
    }
  }

  private[this] def onRemoveSuccessCallBack(): JsCmd = {
    Replace(htmlId_policyConf, showDirectiveDetails) &
    Replace(htmlId_activeTechniquesTree, userLibrary)
  }

  private[this] def updateDirectiveLibrary() : Unit = {
    directiveLibrary = getDirectiveLib()
  }

  //////////////// display trees ////////////////////////

  private[this] def onClickDirective(cat: FullActiveTechniqueCategory, at: FullActiveTechnique,directive: Directive) : JsCmd = {
    displayDirectiveDetails(directive.id)
  }

  private[this] def onClickActiveTechnique(cat: FullActiveTechniqueCategory, fullActiveTechnique : FullActiveTechnique) : JsCmd = {
      currentTechnique = Some((fullActiveTechnique, fullActiveTechnique.techniques.keys.toSeq.sorted.head))

      currentDirectiveSettingForm.set(Empty)

      //Update UI
      Replace(html_techniqueDetails, techniqueDetails.applyAgain) &
      setRightPanelHeader(false) &
      Replace(htmlId_policyConf, showDirectiveDetails) &
      JsRaw("""correctButtons();""")
  }

}


object DirectiveManagement {

  /*
   * HTML id for zones with Ajax / snippet output
   */
  val htmlId_activeTechniquesTree = "activeTechniquesTree"
  val htmlId_policyConf = "policyConfiguration"
  val htmlId_currentActiveTechniqueActions = "currentActiveTechniqueActions"
  val html_addPiInActiveTechnique = "addNewDirective"
  val html_techniqueDetails = "techniqueDetails"
}

