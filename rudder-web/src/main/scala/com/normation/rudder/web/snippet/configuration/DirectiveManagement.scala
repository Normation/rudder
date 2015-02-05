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
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.authorization.AuthzToRights
import com.normation.rudder.authorization.NoRights
import org.joda.time.DateTime
import net.liftweb.http.js.JE.JsArray
import com.normation.rudder.web.model.JsInitContextLinkUtil

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
  val getRules            = () => RudderConfig.roRuleRepository.getAll()
  val uuidGen             = RudderConfig.stringUuidGenerator
  val treeUtilService     = RudderConfig.jsTreeUtilService

  def dispatch = {
    RudderConfig.configService.rudder_workflow_enabled match {
      case Full(workflowEnabled) =>
        {
          case "head" => { _ => head(workflowEnabled) }
          case "userLibrary" => { _ => displayDirectiveLibrary(workflowEnabled) }
          case "showDirectiveDetails" => { _ => initDirectiveDetails(workflowEnabled) }
          case "techniqueDetails" => { xml =>
            techniqueDetails = initTechniqueDetails(workflowEnabled)
            techniqueDetails.apply(xml)
          }
        }
      case eb:EmptyBox =>
        val e = eb ?~! "Error when trying to read Rudder configuration for workflow activation"
        logger.error(s"Error when displaying Directives : ${e.messageChain}")

        {
          case "userLibrary" => { _ => <div class="error">{e.msg}</div> }
          case _ => { _ => NodeSeq.Empty }
        }
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
  var rules = getRules()

  private[this] val directiveId: Box[String] = S.param("directiveId")


  /**
   * Head information (JsTree dependencies,...)
   */
  def head(workflowEnabled: Boolean) : NodeSeq = {
    DirectiveEditForm.staticInit ++
    (
      <head>
        {Script(OnLoad(parseJsArg(workflowEnabled)))}
      </head>
    )
  }

  /**
   * If a query is passed as argument, try to dejoniffy-it, in a best effort
   * way - just don't take of errors.
   *
   * We want to look for #{ "directiveId":"XXXXXXXXXXXX" }
   */
  private[this] def parseJsArg(workflowEnabled: Boolean): JsCmd = {

    def displayDetails(directiveId:String) = updateDirectiveForm(workflowEnabled)(Right(DirectiveId(directiveId)), None)

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
  def displayDirectiveLibrary(workflowEnabled: Boolean): NodeSeq = {
    (
      <div id={htmlId_activeTechniquesTree}>{
          (directiveLibrary,rules) match {
            case (Full(activeTechLib), Full(allRules)) =>
              val usedDirectives = allRules.flatMap { case r =>
                  r.directiveIds.map( id => (id -> r.id))
                }.groupBy( _._1 ).mapValues( _.size).toSeq

              <ul>{
                DisplayDirectiveTree.displayTree(
                    activeTechLib
                  , usedDirectives
                  , None
                  , Some(onClickActiveTechnique)
                  , Some(onClickDirective(workflowEnabled))
                  , false
                )
              }</ul>
            case (x, y) =>

              (x :: y :: Nil).foreach {
                case eb: EmptyBox =>
                  val f = eb ?~! "Error when trying to get the root category of Active Techniques"
                  logger.error(f.messageChain)
                  f.rootExceptionCause.foreach { ex =>
                    logger.error("Exception causing the error was:" , ex)
                  }

                case _ => //
              }

              <span class="error">An error occured when trying to get information from the database. Please contact your administrator of retry latter.</span>
          }
      }</div>
    ) ++ Script(OnLoad(buildJsTree()))
  }


  private[this] def buildJsTree() : JsCmd = {

    JsRaw(s"""
        var directiveId = null;
        try {
          directiveId = "jsTree-" + JSON.parse(window.location.hash.substring(1)).directiveId ;
        } catch(e) {
          directiveId = '';
        }

        buildDirectiveTree('#${htmlId_activeTechniquesTree}', [ directiveId ], '${S.contextPath}', 1);
        createTooltip();
    """)
  }

  def initDirectiveDetails(workflowEnabled: Boolean): NodeSeq = directiveId match {
    case Full(id) => <div id={ htmlId_policyConf } /> ++
      //Here, we MUST add a Noop because of a Lift bug that add a comment on the last JsLine.
      Script(OnLoad(updateDirectiveForm(workflowEnabled)(Right(DirectiveId(id)),None)))
    case _ =>  <div id={ htmlId_policyConf }></div>
  }



  def initTechniqueDetails(workflowEnabled: Boolean) : MemoizeTransform = SHtml.memoize {
    "#techniqueDetails *" #> ( currentTechnique match {
      case None =>
        ".page-title *" #> "Usage" &
        "#details *" #> {
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
        fullActiveTechnique.techniques.get(version) match {
          case None =>
            val m = s"There was an error when trying to read version ${version.toString} of the Technique." +
                 "This is bad. Please check if that version exists on the filesystem and is correctly registered in the Technique Library."

            logger.error(m)

            "*" #> {
              <div id="techniqueDetails">
              <div class="deca">
              <p class="error">{m}</p>
              </div>
              </div>
            }

          case Some(technique) =>
            /*
             * We want to filter technique to only show the one
             * with registered acceptation date time.
             * Also sort by version, reverse
             */
            val validTechniqueVersions = fullActiveTechnique.techniques.map { case(v, t) =>
              fullActiveTechnique.acceptationDatetimes.get(v) match {
                case Some(timeStamp) => Some((v, t, timeStamp))
                case None =>
                  logger.error("Inconsistent Technique version state for Technique with ID '%s' and its version '%s': ".format(fullActiveTechnique.techniqueName, v.toString) +
                          "that version was not correctly registered into Rudder and can not be use for now.")
                  logger.info("A workaround is to remove that version manually from Rudder (move the directory for that version of the Technique out " +
                          "of your configuration-repository directory (for example in /tmp) and 'git commit' the modification), " +
                          "reload the Technique Library, then add back the version back (move it back at its place, 'git add' the directory, 'git commit' the" +
                          "modification), and reload again the Technique Library.")

                  None
              }
            }.toSeq.flatten.sortBy( _._1 )


            "#directiveIntro " #> {
              currentDirectiveSettingForm.is.map { piForm =>
                (".directive *" #> piForm.directive.name)
              }
            } &
            "#techniqueName" #> technique.name &
            "#compatibility" #> technique.compatible.map { comp =>
              { if(comp.os.isEmpty) {
                NodeSeq.Empty
              } else {
                <p><b>Supported operating systems: </b>{comp.os.mkString(", ")}</p>
              } } ++ {
              if (comp.agents.isEmpty) {
                NodeSeq.Empty
              } else {
                <p><b>Supported agents: </b>{comp.agents.mkString(", ")}</p>
              } }
            } &
            "#techniqueDescription" #>  technique.description &
            "#techniqueLongDescription" #>  technique.longDescription &
            "#isSingle *" #> showIsSingle(technique) &
            "#techniqueVersion *+" #> showVersions(fullActiveTechnique, validTechniqueVersions, workflowEnabled)
        }
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

  private[this] def showVersions(activeTechnique: FullActiveTechnique, validTechniques: Seq[(TechniqueVersion, Technique, DateTime)], workflowEnabled: Boolean) = {

    val techniqueVersionInfo = validTechniques.map { case(v,t, timeStamp) =>
      val isDeprecated = t.deprecrationInfo.isDefined
      val deprecationMessage = t.deprecrationInfo.map(_.message).getOrElse("")
      val acceptationDate = DateFormaterService.getFormatedDate(timeStamp)
      val action = {
        val callback = SetHtml(CreateDirectivePopup.htmlId_popup, newCreationPopup(t, activeTechnique, workflowEnabled) ) &
                       JsRaw( s""" createPopup("${CreateDirectivePopup.htmlId_popup}") """)
        val ajax = SHtml.ajaxCall(JsNull, (s) => callback)
        AnonFunc("",ajax)
      }
      JsObj(
          ( "version"           -> v.toString )
        , ( "isDeprecated"      -> isDeprecated)
        , ("deprecationMessage" -> deprecationMessage)
        , ("acceptationDate"    -> acceptationDate)
        , ( "action"            -> action)
      )
    }
    val dataArray = JsArray(techniqueVersionInfo.toList)

    Script(
      OnLoad(
        JsRaw(s"""
          angular.bootstrap('#techniqueDetails', ['techniqueDetails']);
          var scope = angular.element($$("#techniqueVersion")).scope();
          scope.$$apply(function(){
            scope.init(${dataArray.toJsCmd});
          } );
          createTooltip();"""
    ) ) )
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

  private[this] def newCreationPopup(technique: Technique, activeTechnique: FullActiveTechnique, workflowEnabled: Boolean) : NodeSeq = {
    new CreateDirectivePopup(
        technique.name, technique.description, technique.id.version,
        onSuccessCallback = { (directive : Directive) =>
          updateDirectiveSettingForm(activeTechnique, directive,None, workflowEnabled, true)
          //Update UI
          Replace(htmlId_policyConf, showDirectiveDetails) &
          SetHtml(html_techniqueDetails, NodeSeq.Empty) &
          JsRaw("""createTooltip(); scrollToElement('%s')""".format(htmlId_policyConf))
        }
    ).popupContent
  }

  /* Update Directive form,
   * either from the directive Id (when it comes from the url )
   * or from the full directive info ( callbacks, click on directive tree ...)
   */
  def updateDirectiveForm(workflowEnabled : Boolean)(
      directiveInfo: Either[Directive,DirectiveId]
    , oldDirective: Option[Directive]
  ) = {
    val directiveId = directiveInfo match {
      case Left(directive) => directive.id
      case Right(directiveId) => directiveId
    }
    directiveLibrary.flatMap( _.allDirectives.get(directiveId)) match {
      // The directive exists, update directive form
      case Full((activeTechnique, directive)) =>
        // In priority, use the directive passed as parameter
        val newDirective = directiveInfo match {
          case Left(updatedDirective) => updatedDirective
          case _ => directive // Only the id, get it from the library
        }
        updateDirectiveSettingForm(activeTechnique, newDirective, oldDirective, workflowEnabled, false)
      case eb:EmptyBox =>
        currentDirectiveSettingForm.set(eb)
    }

    SetHtml(html_techniqueDetails, NodeSeq.Empty) &
    Replace(htmlId_policyConf, showDirectiveDetails) &
    JsRaw(s"""this.window.location.hash = "#" + JSON.stringify({'directiveId':'${directiveId.value}'})""") &
    Replace(htmlId_activeTechniquesTree, displayDirectiveLibrary(workflowEnabled)) &
    After(0,JsRaw("""correctButtons(); createTooltip();""")) // OnLoad or JsRaw createTooltip does not work ...
  }

  private[this] def updateDirectiveSettingForm(
      activeTechnique     : FullActiveTechnique
    , directive           : Directive
    , oldDirective        : Option[Directive]
    , workflowEnabled     : Boolean
    , isADirectiveCreation: Boolean
  ) : Unit = {


    activeTechnique.techniques.get(directive.techniqueVersion) match {
      case Some(technique) =>
        val dirEditForm = new DirectiveEditForm(
            htmlId_policyConf
          , technique
          , activeTechnique.toActiveTechnique
          , activeTechnique
          , directive
          , oldDirective
          , workflowEnabled
          , onSuccessCallback = directiveEditFormSuccessCallBack(workflowEnabled)
          , onMigrationCallback = (dir,optDir) => updateDirectiveForm(workflowEnabled)(Left(dir),optDir) & displayFinishMigrationPopup
          , isADirectiveCreation = isADirectiveCreation
          , onRemoveSuccessCallBack = () => onRemoveSuccessCallBack(workflowEnabled)
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
  private[this] def directiveEditFormSuccessCallBack(workflowEnabled: Boolean)(returns: Either[Directive,ChangeRequestId]): JsCmd = {

    returns match {
      case Left(dir) => // ok, we've received a directive, show it
        updateDirectiveLibrary()
        updateDirectiveForm(workflowEnabled)(Left(dir),None)

      case Right(changeRequestId) => // oh, we have a change request, go to it
        JsInitContextLinkUtil.redirectToChangeRequestLink(changeRequestId)
    }
  }

  private[this] def onRemoveSuccessCallBack(workflowEnabled: Boolean): JsCmd = {
    updateDirectiveLibrary
    Replace(htmlId_activeTechniquesTree, displayDirectiveLibrary(workflowEnabled))
  }

  private[this] def updateDirectiveLibrary() : Unit = {
    directiveLibrary = getDirectiveLib()
    rules = getRules()
  }

  //////////////// display trees ////////////////////////

  private[this] def onClickDirective(workflowEnabled: Boolean)(cat: FullActiveTechniqueCategory, at: FullActiveTechnique, directive: Directive) : JsCmd = {
    updateDirectiveForm( workflowEnabled)(Left(directive),None)
  }

  private[this] def onClickActiveTechnique(cat: FullActiveTechniqueCategory, fullActiveTechnique : FullActiveTechnique) : JsCmd = {
      currentTechnique = fullActiveTechnique.newestAvailableTechnique.map( fat => (fullActiveTechnique, fat.id.version) )

      currentDirectiveSettingForm.set(Empty)

      //Update UI
      Replace(html_techniqueDetails, techniqueDetails.applyAgain) &
      Replace(htmlId_policyConf, showDirectiveDetails) &
      JsRaw("""correctButtons(); createTooltip();""")
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

