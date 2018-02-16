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

package com.normation.rudder.web.snippet.configuration

import com.normation.rudder.domain.policies.{DirectiveId, Directive}
import com.normation.cfclerk.domain.Technique
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
import com.normation.cfclerk.domain.TechniqueVersion
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.rudder.web.services.DisplayDirectiveTree
import com.normation.rudder.web.model.CurrentUser
import org.joda.time.DateTime
import net.liftweb.http.js.JE.JsArray
import com.normation.rudder.web.model.JsInitContextLinkUtil
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.eventlog.ModificationId
import com.normation.rudder.web.services.AgentCompat

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
  private[this] val configService = RudderConfig.configService

  def dispatch = {
    configService.rudder_workflow_enabled match {
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
          directiveId = JSON.parse(decodeURI(window.location.hash.substring(1))).directiveId ;
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
      <div id={htmlId_activeTechniquesTree} class="col-xs-12">{
          (directiveLibrary,rules,configService.rudder_global_policy_mode()) match {
            case (Full(activeTechLib), Full(allRules), Full(globalMode)) =>
              val usedDirectives = allRules.flatMap { case r =>
                  r.directiveIds.map( id => (id -> r.id))
                }.groupBy( _._1 ).mapValues( _.size).toSeq

              <ul>{
                DisplayDirectiveTree.displayTree(
                    activeTechLib
                  , globalMode
                  , usedDirectives
                  , None
                  , Some(onClickActiveTechnique)
                  , Some(onClickDirective(workflowEnabled))
                  , false
                )
              }</ul>
            case (x, y, z) =>

              (x :: y :: z :: Nil).foreach {
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
          directiveId = "jsTree-" + JSON.parse(decodeURI(window.location.hash.substring(1))).directiveId ;
        } catch(e) {
          directiveId = '';
        }

        buildDirectiveTree('#${htmlId_activeTechniquesTree}', [ directiveId ], '${S.contextPath}', 1);
        $$(window).on('resize',function(){
          adjustHeight('#activeTechniquesTree');
        });
        adjustHeight('#activeTechniquesTree');
        $$('#activeTechniquesTree').on('scroll',function(){$$('.tooltip').hide();});
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
        "#info-title *" #> "Directives" &
        "#details *" #> {
            <div class="col-lg-12">
              <div class="col-lg-12 callout-fade callout-warning">
                <div class="marker">
                  <span class="glyphicon glyphicon-info-sign"></span>
                </div>
                <p>A Directive is an instance of a Technique, which allows to set values for the parameters of the latter.</p>
                <p>Each Directive can have a unique name, and should be completed with a short and a long description, and a collection of parameters for the variables defined by the Technique.</p>
                <p>Techniques are often available in several versions, numbered X.Y, X being the major version number and Y the minor version number:</p>
                <ol>
                  <li><b>Bugs</b> are fixed in all existing versions of Rudder techniques. Make sure you update your Rudder packages frequently.</li>
                  <li>A new <b>minor</b> technique version is created for any new features</li>
                  <li>A new <b>major</b> version is created for any <b>architectural change</b> (such as refactoring)</li>
                </ol>
                <p>You can find your own Techniques written in the Technique Editor in the <b>User Techniques</b> category.</p>
              </div>
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
             currentDirectiveSettingForm.get.map { piForm =>
               (".directive *" #> piForm.directive.name)
             }
           } &
           "#techniqueName *" #> technique.name &
           "#techniqueDescription *" #>  technique.description &
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
      val agentTypes = t.agentConfigs.map(_.agentType).toSet
      val (dscSupport,classicSupport) = AgentCompat(agentTypes) match {
        case AgentCompat.Dsc => (true,false)
        case AgentCompat.Classic => (false,true)
        case AgentCompat.All => (true,true)
        case AgentCompat.NoAgent => (false,false)
      }
      val action = {
        val ajax = SHtml.ajaxCall(JsNull, (_) => newDirective(t, activeTechnique, workflowEnabled))
        AnonFunc("",ajax)
      }
      JsObj(
          ( "version"            -> v.toString )
        , ( "isDeprecated"       -> isDeprecated)
        , ( "deprecationMessage" -> deprecationMessage)
        , ( "acceptationDate"    -> acceptationDate)
        , ( "dscSupport"         -> dscSupport)
        , ( "classicSupport"     -> classicSupport)
        , ( "action"             -> action)
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
    currentDirectiveSettingForm.get match {
      case Failure(m,ex,_) =>
        <div id={htmlId_policyConf} class="col-md-offset-2 col-md-8" style="margin-top:50px">
          <h4 class="text-warning">An error happened when trying to load Directive configuration.</h4>
          <div class="bs-callout bs-callout-danger">
            <strong>Error message was:</strong>
            <p>{m}</p>{
            ex match {
              case Full(MissingTechniqueException(directive)) =>
                // in that case, we bypass workflow because we don't have the information to create a valid one (technique is missing)
                val deleteButton = SHtml.ajaxButton("Delete", () => {
                    RudderConfig.woDirectiveRepository.delete(
                        directive.id
                      , ModificationId(RudderConfig.stringUuidGenerator.newUuid)
                      , CurrentUser.getActor
                      , Some(s"Deleting directive '${directive.name}' (${directive.id}) because its Technique isn't available anymore")
                    ) match {
                      case Full(diff)   =>
                        currentDirectiveSettingForm.set(Empty)
                        Replace(htmlId_policyConf, showDirectiveDetails) & JsRaw("""correctButtons(); createTooltip();""") & onRemoveSuccessCallBack(false)
                      case eb: EmptyBox =>
                        val msg = (eb ?~! s"Error when trying to delete directive '${directive.name}' (${directive.id})").messageChain
                        //redisplay this form with the new error
                        currentDirectiveSettingForm.set(Failure(msg))
                        Replace(htmlId_policyConf, showDirectiveDetails) & JsRaw("""correctButtons(); createTooltip();""")
                    }
                  }, ("class" ,"dangerButton")
                )

                <div><p>Do you want to <strong>delete directive</strong> '{directive.name}' ({directive.id.value})?</p>{
                  deleteButton
                }</div>

              case _ => NodeSeq.Empty
            }
          }
          </div>
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

  private[this] def newDirective(technique: Technique, activeTechnique: FullActiveTechnique, workflowEnabled: Boolean)  = {
    configService.rudder_global_policy_mode() match {
      case Full(globalMode) =>
        val allDefaults = techniqueRepository.getTechniquesInfo.directivesDefaultNames
        val directiveDefaultName = allDefaults.get(technique.id.toString).orElse(allDefaults.get(technique.id.name.value)).getOrElse(technique.name)
        val directive =
          Directive(
              DirectiveId(uuidGen.newUuid)
            , technique.id.version
            , Map()
            , directiveDefaultName
            , ""
            , None
            , ""
            , 5
            , true
          )
        updateDirectiveSettingForm(activeTechnique, directive,None, workflowEnabled, true, globalMode)
        //Update UI
        Replace(htmlId_policyConf, showDirectiveDetails) &
        SetHtml(html_techniqueDetails, NodeSeq.Empty) &
        JsRaw("""createTooltip();""")
      case eb:EmptyBox =>
        val fail = eb ?~! "Could not get global policy mode while creating new Directive"
        logger.error(fail.messageChain)
        val errorHtml =
              <div class="deca">
              <p class="error">{fail.messageChain}</p>
              </div>
        SetHtml(htmlId_policyConf, errorHtml)
    }
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
    configService.rudder_global_policy_mode() match {
      case Full(globalMode) =>
        directiveLibrary.flatMap( _.allDirectives.get(directiveId)) match {
          // The directive exists, update directive form
          case Full((activeTechnique, directive)) =>
            // In priority, use the directive passed as parameter
            val newDirective = directiveInfo match {
              case Left(updatedDirective) => updatedDirective
              case _ => directive // Only the id, get it from the library
            }
            updateDirectiveSettingForm(activeTechnique, newDirective, oldDirective, workflowEnabled, false, globalMode)
          case eb:EmptyBox =>
            currentDirectiveSettingForm.set(eb)
        }
      case eb:EmptyBox =>
        currentDirectiveSettingForm.set(eb)
    }

    SetHtml(html_techniqueDetails, NodeSeq.Empty) &
    Replace(htmlId_policyConf, showDirectiveDetails) &
    JsRaw(s"""this.window.location.hash = "#" + JSON.stringify({'directiveId':'${directiveId.value}'})""") &
    After(0,JsRaw("""createTooltip();""")) // OnLoad or JsRaw createTooltip does not work ...
  }


  private[this] final case class MissingTechniqueException(directive: Directive) extends
    Exception(s"Directive ${directive.name} (${directive.id.value}) is bound to a Technique without any valid version available")

  private[this] def updateDirectiveSettingForm(
      activeTechnique     : FullActiveTechnique
    , directive           : Directive
    , oldDirective        : Option[Directive]
    , workflowEnabled     : Boolean
    , isADirectiveCreation: Boolean
    , globalMode          : GlobalPolicyMode
  ) : Unit = {

    def createForm(dir: Directive, oldDir: Option[Directive], technique: Technique, errorMsg: Option[String]) = {
      new DirectiveEditForm(
            htmlId_policyConf
          , technique
          , activeTechnique.toActiveTechnique
          , activeTechnique
          , dir
          , oldDir
          , workflowEnabled
          , globalMode
          , onSuccessCallback = directiveEditFormSuccessCallBack(workflowEnabled)
          , onMigrationCallback = (dir,optDir) => updateDirectiveForm(workflowEnabled)(Left(dir),optDir) & displayFinishMigrationPopup
          , isADirectiveCreation = isADirectiveCreation
          , onRemoveSuccessCallBack = () => onRemoveSuccessCallBack(workflowEnabled)
        )
    }

    activeTechnique.techniques.get(directive.techniqueVersion) match {
      case Some(technique) =>
        val dirEditForm = createForm(directive, oldDirective, technique, None)
        currentDirectiveSettingForm.set(Full(dirEditForm))
      case None =>
        // do we have at least one version for that technique ? We can then try to migrate towards it
        activeTechnique.techniques.lastOption match {
          case Some((version, technique)) =>
            val dirEditForm = createForm(directive.copy(techniqueVersion = version), Some(directive), technique, None)
            currentDirectiveSettingForm.set(Full(dirEditForm))
            dirEditForm.addFormMsg(
              <div class="tw-bs" style="margin: 40px">
                <h4 class="text-danger"><u>Important information: directive migration towards version {version} of '{technique.name}'</u></h4>
                <div class="bs-callout bs-callout-danger text-left">
                <p>This directive was linked to version '{directive.techniqueVersion}' of the Technique which is not available anymore. It was automatically
                migrated to version '{version}' but the change is not commited yet.</p>
                <p>You can now delete the directive or save it to confirm migration. If you keep that directive without commiting changes, Rudder will not be
                able to generate policies for rules which use it.</p>
                </div>
              </div>
            )
          case None =>
            // no version ! propose deletion to the directive along with an error message.
            val msg = s"Can not display directive edit form: missing information about technique with name='${activeTechnique.techniqueName}' and version='${directive.techniqueVersion}'"
            logger.warn(msg)
            currentDirectiveSettingForm.set(Failure(msg, Full(MissingTechniqueException(directive)), Empty))
        }
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
        updateDirectiveLibrary(workflowEnabled) &
        updateDirectiveForm(workflowEnabled)(Left(dir),None) &
        After(0,JsRaw("""applyFilter('directiveFilter');"""))

      case Right(changeRequestId) => // oh, we have a change request, go to it
        JsInitContextLinkUtil.redirectToChangeRequestLink(changeRequestId)
    }
  }

  private[this] def onRemoveSuccessCallBack(workflowEnabled: Boolean): JsCmd = {
    updateDirectiveLibrary(workflowEnabled)&
        After(0,JsRaw("""applyFilter('directiveFilter');"""))
  }

  private[this] def updateDirectiveLibrary(workflowEnabled : Boolean) : JsCmd = {
    directiveLibrary = getDirectiveLib()
    rules = getRules()
    Replace(htmlId_activeTechniquesTree, displayDirectiveLibrary(workflowEnabled))
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
      JsRaw("""createTooltip();""")
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
