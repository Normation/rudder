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

import bootstrap.liftweb.RudderConfig
import com.normation.GitVersion
import com.normation.GitVersion.ParseRev
import com.normation.box.*
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueGenerationMode.*
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.errors
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.services.policies.DontCare
import com.normation.rudder.users.CurrentUser
import com.normation.rudder.web.components.DirectiveEditForm
import com.normation.rudder.web.components.DisplayColumn
import com.normation.rudder.web.components.RuleGrid
import com.normation.rudder.web.services.AgentCompat
import com.normation.rudder.web.services.DisplayDirectiveTree
import com.normation.rudder.web.snippet.WithNonce
import com.normation.utils.DateFormaterService
import com.normation.zio.*
import enumeratum.Enum
import enumeratum.EnumEntry
import net.liftweb.common.*
import net.liftweb.common.Box.*
import net.liftweb.http.*
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.Helpers.*
import org.apache.commons.text.StringEscapeUtils
import org.joda.time.DateTime
import scala.xml.*
import zio.json.*

final case class JsonDirectiveRId(directiveId: String, rev: Option[String])

object JsonDirectiveRId {
  implicit val decoder: JsonDecoder[JsonDirectiveRId] = DeriveJsonDecoder.gen[JsonDirectiveRId]
}

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
  import DirectiveManagement.*

  private val techniqueRepository = RudderConfig.techniqueRepository
  private val getDirectiveLib     = () => RudderConfig.roDirectiveRepository.getFullDirectiveLibrary()
  private val getRules            = () => RudderConfig.roRuleRepository.getAll()
  private val getGroups           = () => RudderConfig.roNodeGroupRepository.getFullGroupLibrary()
  private val uuidGen             = RudderConfig.stringUuidGenerator
  private val linkUtil            = RudderConfig.linkUtil
  private val configService       = RudderConfig.configService
  private val configRepo          = RudderConfig.configurationRepository
  private val dependencyService   = RudderConfig.dependencyAndDeletionService

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = {
    implicit val qc: QueryContext = CurrentUser.queryContext // bug https://issues.rudder.io/issues/26605

    {
      case "head"                 => { _ => head() }
      case "userLibrary"          => { _ => displayDirectiveLibrary() }
      case "showDirectiveDetails" => { _ => initDirectiveDetails() } // Used in directiveManagement.html
      case "techniqueDetails"     => { xml =>
        techniqueDetails = initTechniqueDetails()
        techniqueDetails.apply(xml)
      }
    }
  }

  // must be initialized on first call of "techniqueDetails"
  private var techniqueDetails: MemoizeTransform = null

  // the current DirectiveEditForm component
  val currentDirectiveSettingForm = new LocalSnippet[DirectiveEditForm]

  // we specify here what is the technique we are working on
  // technique version must be in the map of techniques in the fullActiveTechnique
  var currentTechnique: Option[(FullActiveTechnique, TechniqueVersion)] = None

  // the state of the directive library.
  // must be reloaded "updateDirectiveLibrary()"
  // when information change (directive added/removed/modified, etc)
  var directiveLibrary: errors.IOResult[FullActiveTechniqueCategory] = getDirectiveLib()
  var rules:            errors.IOResult[Seq[Rule]]                   = getRules()

  private val directiveId: Box[String] = S.param("directiveId")

  /**
   * Head information (JsTree dependencies,...)
   */
  def head(): NodeSeq = {
    implicit val qc: QueryContext = CurrentUser.queryContext
    (
      <head>
        {WithNonce.scriptWithNonce(Script(OnLoad(parseJsArg())))}
      </head>
    )
  }

  /**
   * If a query is passed as argument, try to dejoniffy-it, in a best effort
   * way - just don't take of errors.
   *
   * We want to look for #{ "directiveId":"XXXXXXXXXXXX" , "rev":"XXXX" }
   */
  private def parseJsArg()(implicit qc: QueryContext): JsCmd = {

    def displayDetails(jsonId: String)(implicit qc: QueryContext) = {
      jsonId.fromJson[JsonDirectiveRId].toOption match {
        case None     =>
          Noop
        case Some(id) =>
          updateDirectiveForm(Right(DirectiveId(DirectiveUid(id.directiveId), ParseRev(id.rev))), None)
      }
    }

    JsRaw(s"""
        // support loading another directive from change in URL hash while staying in page (e.g. from quicksearch result)
        window.addEventListener('hashchange', function (e) {
          var newHash = e.target.location.hash;
          var splitHash = newHash.split("#");
          if (splitHash.length > 0) {
            var hashJson = decodeURIComponent(splitHash[1]);
            try {
              var hashJsonObj = JSON.parse(hashJson);
              if ("directiveId" in hashJsonObj) {
                // displayDetails needs stringified object
                ${SHtml.ajaxCall(JsVar("hashJson"), displayDetails)._2.toJsCmd};
              }
            } catch {}
          }
        });

        var directiveId = null;
        try {
          var directiveId = decodeURI(window.location.hash.substring(1)) ;
        } catch(e) {
          directiveId = null;
        }
        if( directiveId != null && directiveId.length > 0) {
          ${SHtml.ajaxCall(JsVar("directiveId"), displayDetails)._2.toJsCmd};
        }
        removeBsTooltips();
    """) // JsRaw ok, escaped
  }

  /**
   * Almost same as Technique/activeTechniquesTree
   * TODO : factor out that part
   */
  def displayDirectiveLibrary()(implicit qc: QueryContext): NodeSeq = {
    (
      <div id={htmlId_activeTechniquesTree} class="col-sm-12">{
        (directiveLibrary.toBox, rules.toBox, configService.rudder_global_policy_mode().toBox) match {
          case (Full(activeTechLib), Full(allRules), Full(globalMode)) =>
            val usedDirectives = allRules.flatMap {
              case r =>
                r.directiveIds.map(id => (id.uid -> r.id))
            }.groupMapReduce(_._1)(_ => 1)(_ + _).toSeq

            <ul>{
              DisplayDirectiveTree.displayTree(
                activeTechLib,
                globalMode,
                usedDirectives,
                None,
                Some(onClickActiveTechnique),
                Some(onClickDirective),
                Some(newDirective),
                addEditLink = false,
                addActionBtns = false
              )
            }</ul>

          case (x, y, z) =>
            (x :: y :: z :: Nil).foreach {
              case eb: EmptyBox =>
                val f = eb ?~! "Error when trying to get the root category of active techniques"
                logger.error(f.messageChain)
                f.rootExceptionCause.foreach(ex => logger.error("Exception causing the error was:", ex))

              case _ => //
            }

            <span class="error">An error occured when trying to get information from the database. Please contact your administrator or retry latter.</span>
        }
      }</div>: NodeSeq
    ) ++ WithNonce.scriptWithNonce(Script(OnLoad(buildJsTree())))
  }

  private def buildJsTree(): JsCmd = {

    JsRaw(s"""
        var directiveId = null;
        try {
          directiveId = "jsTree-" + JSON.parse(decodeURI(window.location.hash.substring(1))).directiveId ;
        } catch(e) {
          directiveId = '';
        }
        buildDirectiveTree('#${htmlId_activeTechniquesTree}', [ directiveId ], '${S.contextPath}', 1);
        removeBsTooltips();
        initBsTooltips();
        $$('.sidebar-body').on('scroll', function(){
          removeBsTooltips();
        });
    """) // JsRaw ok, const
  }

  def initDirectiveDetails()(implicit qc: QueryContext): NodeSeq = directiveId match {
    case Full(id) =>
      (<div id={htmlId_policyConf} />: NodeSeq) ++
      // Here, we MUST add a Noop because of a Lift bug that add a comment on the last JsLine.
      Script(OnLoad(updateDirectiveForm(Right(DirectiveId(DirectiveUid(id))), None)))
    case _        => <div id={htmlId_policyConf}></div>
  }

  def initTechniqueDetails()(implicit qc: QueryContext): MemoizeTransform = {
    SHtml.memoize {
      "#techniqueDetails *" #> (
        currentTechnique match {
          case None =>
            ".main-header [class+]" #> "no-header" &
            "#details *" #> {
              <div>
                  <style>
                    #policyConfiguration{{
                    display:none;
                    }}
                  </style>
                  <div class="jumbotron">
                    <h1>Directives</h1>
                    <p>A directive is an instance of a technique, which allows to set values for the parameters of the latter.</p>
                    <p>Each directive can have a unique name, and should be completed with a short and a long description, and a collection of parameters for the variables defined by the technique.</p>
                    <p>Techniques are often available in several versions, numbered X.Y, X being the major version number and Y the minor version number:</p>
                    <ol>
                      <li>
                        <b>Bugs</b>
                        are fixed in all existing versions of Rudder techniques. Make sure you update your Rudder packages frequently.</li>
                      <li>A new
                        <b>minor</b>
                        technique version is created for any new features</li>
                      <li>A new
                        <b>major</b>
                        version is created for any
                        <b>architectural change</b>
                        (such as refactoring)</li>
                    </ol>
                    <p>You can find your own techniques written in the technique editor in the
                      <b>User Techniques</b>
                      category.</p>
                  </div>
                </div>
            }

          case Some((fullActiveTechnique, version)) =>
            fullActiveTechnique.techniques.get(version) match {
              case None =>
                val m = s"There was an error when trying to read version ${version.debugString} of the technique." +
                  "Please check if that version exists on the filesystem and is correctly registered in the technique library."

                logger.error(m)

                "*" #> {
                  <div id="techniqueDetails">
                    <div class="deca p-2">
                      <p class="error">
                        {m}
                      </p>
                    </div>
                  </div>
                }

              case Some(technique) =>
                /*
                 * We want to filter technique to only show the one
                 * with registered acceptation date time.
                 * Also sort by version, reverse
                 */
                def showPopup(nextStatus: NextStatus)(implicit qc: QueryContext): JsCmd = {
                  SetHtml(
                    "showTechniqueValidationPopup",
                    showTechniquePopup("showTechniqueValidationPopup", fullActiveTechnique, nextStatus)
                  )
                }

                val validTechniqueVersions = fullActiveTechnique.techniques.map {
                  case (v, t) =>
                    fullActiveTechnique.acceptationDatetimes.get(v) match {
                      case Some(timeStamp) => Some((v, t, timeStamp))
                      case None            =>
                        logger.error(
                          "Inconsistent technique version state for technique with ID '%s' and its version '%s': ".format(
                            fullActiveTechnique.techniqueName,
                            v.debugString
                          ) +
                          "that version was not correctly registered into Rudder and can not be use for now."
                        )
                        logger.info(
                          "A workaround is to remove that version manually from Rudder (move the directory for that version of the technique out " +
                          "of your configuration-repository directory (for example in /tmp) and 'git commit' the modification), " +
                          "reload the technique library, then add back the version back (move it back at its place, 'git add' the directory, 'git commit' the" +
                          "modification), and reload the technique library again."
                        )

                        None
                    }
                }.toSeq.flatten.sortBy(_._1)
                ".main-container [class-]" #> "no-header" &
                "#directiveIntro " #> {
                  currentDirectiveSettingForm.get.map(piForm => (".directive *" #> piForm.directive.name))
                } &
                "#techniqueName" #> <span>
                    {technique.name}
                  </span> &
                ".header-buttons *" #> {
                  if (!fullActiveTechnique.isEnabled) {
                    SHtml.ajaxButton(
                      <span>
                      Enable
                      <i class="fa fa-check-circle"></i>
                    </span>,
                      () => showPopup(NextStatus.Enabled),
                      ("class", "btn btn-default")
                    )
                  } else {
                    SHtml.ajaxButton(
                      <span>
                      Disable
                      <i class="fa fa-ban"></i>
                    </span>,
                      () => showPopup(NextStatus.Disabled),
                      ("class", "btn btn-default")
                    )
                  }
                } &
                "#techniqueID *" #> technique.id.name.value &
                "#techniqueDocumentation [class]" #> (if (technique.longDescription.isEmpty) "d-none" else "") &
                "#techniqueLongDescription *" #> Script(
                  JsRaw(
                    s"""generateMarkdown(${Str(technique.longDescription).toJsCmd}, "#techniqueLongDescription");"""
                  ) // JsRaw ok, escaped
                ) &
                "#techniqueDescription" #> technique.description &
                "#isSingle *" #> showIsSingle(technique) &
                "#isDisabled" #> {
                  if (!fullActiveTechnique.isEnabled) {
                    <div class="main-alert alert alert-warning">
                        <i class="fa fa-exclamation-triangle" aria-hidden="true"></i>
                        This Technique is disabled.
                        {
                      SHtml.ajaxButton(
                        <span>
                        Enable
                        <i class="fa fa-check-circle ms-2"></i>
                      </span>,
                        () => showPopup(NextStatus.Enabled),
                        ("class", "btn btn-sm btn-default ms-2")
                      )
                    }
                    </div>
                  } else {
                    NodeSeq.Empty
                  }
                } &
                "#techniqueversion-app *+" #> showVersions(fullActiveTechnique, validTechniqueVersions)
            }
        }
      )
    }
  }

  private val (monoMessage, multiMessage, limitedMessage) = {
    (
      "A unique directive derived from that technique can be deployed on a given server.",
      """Several directives derived from that technique can be deployed on a given server.
      Those directives can be deployed at the same time even if they have a different policy mode.
      Directives from this technique version can also be used with other directives from a single limited multi instance technique version.
      """,
      "Several directives derived from that technique can be deployed on a given server if they are based on the same technique version and have the same policy mode."
    )
  }

  private def showIsSingle(technique: Technique): NodeSeq = {
    <span>
      {
      if (technique.isMultiInstance) {
        { <b>Multi instance</b> } ++
        Text(s": ${multiMessage}")
      } else {
        { <b>Mono instance</b> } ++
        Text(s": ${monoMessage}")
      }
      (technique.generationMode, technique.isMultiInstance) match {
        case (_, false)           =>
          { <b>Mono instance</b> } ++
          Text(s": ${monoMessage}")
        case (MergeDirectives, _) =>
          { <b>Limited Multi instance</b> } ++
          Text(s": ${limitedMessage}")
        case (_, _)               =>
          { <b>Multi instance</b> } ++
          Text(s": ${multiMessage}")
      }

    }
    </span>
  }

  private def showVersions(
      activeTechnique: FullActiveTechnique,
      validTechniques: Seq[(TechniqueVersion, Technique, DateTime)]
  )(implicit qc: QueryContext): NodeSeq = {

    val techniqueVersionInfo    = validTechniques.map {
      case (v, t, timeStamp) =>
        val isDeprecated                      = t.deprecrationInfo.isDefined
        val deprecationMessage                = t.deprecrationInfo.map(_.message).getOrElse("")
        val acceptationDate                   = DateFormaterService.getDisplayDate(timeStamp)
        val agentTypes                        = t.agentConfigs.map(_.agentType).toSet
        val (dscSupport, classicSupport)      = AgentCompat(agentTypes) match {
          case AgentCompat.Windows => (true, false)
          case AgentCompat.Linux   => (false, true)
          case AgentCompat.All     => (true, true)
          case AgentCompat.NoAgent => (false, false)
        }
        val (multiVersionSupport, mvsMessage) = (t.generationMode, t.isMultiInstance) match {
          case (_, false)           => ("Mono instance", monoMessage)
          case (MergeDirectives, _) => ("Limited multi instance", limitedMessage)
          case (_, _)               => ("Multi instance", multiMessage)
        }
        JsObj(
          ("version" -> v.serialize),
          ("isDeprecated"        -> isDeprecated),
          ("deprecationMessage"  -> deprecationMessage),
          ("acceptationDate"     -> acceptationDate),
          ("dscSupport"          -> dscSupport),
          ("classicSupport"      -> classicSupport),
          ("multiVersionSupport" -> multiVersionSupport),
          ("mvsMessage"          -> mvsMessage)
        )
    }
    val techniqueVersionActions = validTechniques.map {
      case (v, t, timeStamp) =>
        val action = {
          val ajax = SHtml.ajaxCall(JsNull, (_) => newDirective(t, activeTechnique))
          AnonFunc("", ajax)
        }
        JsObj(
          ("version" -> v.serialize),
          ("action" -> action)
        )
    }
    val dataArray               = JsArray(techniqueVersionInfo.toList)
    val actionsArray            = JsArray(techniqueVersionActions.toList)

    Script(
      OnLoad(
        JsRaw(
          s"""
             |var main = document.getElementById("techniqueversion-app");
             |var initValues = {
             |    contextPath    : "${S.contextPath}"
             |  , hasWriteRights : hasWriteRights
             |  , versions       : ${dataArray.toJsCmd}
             |};
             |var app = Elm.Techniqueversion.init({node: main , flags: initValues});
             |// Initialize tooltips
             |app.ports.initTooltips.subscribe(function(msg) {
             |  setTimeout(function(){
             |    initBsTooltips();
             |  }, 800);
             |});
             |app.ports.errorNotification.subscribe(function(msg) {
             |  createErrorNotification(msg);
             |});
             |app.ports.createDirective.subscribe(function(version) {
             |  var versions = ${actionsArray.toJsCmd}
             |  var getVersion = versions.find(v => v.version === version)
             |  if (getVersion === undefined) {
             |    createErrorNotification("Error while creating directive based on technique version '"+version+"'. Reason: Unknown version")
             |  }else{
             |    getVersion.action();
             |  }
             |  removeBsTooltips();
             |});
             |removeBsTooltips();
          """.stripMargin
        )
      ) // JsRaw ok, escaped
    )
  }

  // validation / warning pop-up when enabling a technique

  def showTechniquePopup(popupId: String, technique: FullActiveTechnique, nextStatus: NextStatus)(implicit
      qc: QueryContext
  ): NodeSeq = {
    def closePopup(): JsCmd = SetHtml(popupId, NodeSeq.Empty)

    dependencyService
      .techniqueDependencies(technique.id, getGroups().toBox, DontCare)
      .map(_.rules.values) match {
      case box: EmptyBox =>
        box match {
          case Empty => // no more log
          case f: Failure => ApplicationLogger.error(f.msg)
        }
        <div class="main-alert alert alert-error">
          Error when trying to retrieve information about technique status change. Please contact your administrator.
        </div>

      case Full(rules) =>
        val showDependentRules = {
          if (rules.size <= 0) NodeSeq.Empty
          else {
            val noDisplay = DisplayColumn.Force(display = false)
            val cmp       = new RuleGrid(
              "technique_status_popup_grid",
              None,
              showCheckboxColumn = false,
              directiveApplication = None,
              columnCompliance = noDisplay,
              graphRecentChanges = noDisplay
            )
            cmp.rulesGridWithUpdatedInfo(Some(rules.toSeq), showActionsColumn = false, isPopup = true)
          }
        }

        (
          "#validationForm" #> { (xml: NodeSeq) => SHtml.ajaxForm(xml) } andThen
          "#changeStatus" #> (SHtml.ajaxSubmit(
            nextStatus.action,
            () =>
              DirectiveManagement.setEnabled(
                technique.id,
                technique.techniqueName.value,
                nextStatus.booleanValue,
                () => {
                  (closePopup() & updateDirectiveLibrary() & onClickActiveTechnique(
                    technique.copy(isEnabled = nextStatus.booleanValue)
                  ))
                }
              ),
            ("class" -> "btn-danger")
          ) % ("id" -> "createDirectiveSaveButton") % ("tabindex" -> "3"))
        )(<div>
          <div class="modal-backdrop fade show"></div>
          <div id="validationContainer"  class="modal modal-lg fade show" style="display: block">
            <div id="validationForm">
              <div class="modal-dialog">
                  <div class="modal-content">
                      <div class="modal-header">
                          <h5 id="dialogTitle" class="modal-title">
                              {s"${nextStatus.action} technique '${technique.techniqueName.value}'"}
                          </h5>
                          <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close" onclick={
          closePopup()
        }></button>
                      </div>
                      <div class="modal-body">
                          <div id="explanationMessageZone">
                            {
          s"Technique '${technique.techniqueName.value}' will be ${nextStatus.entryName.toLowerCase}. Any item related to it will be impacted."
        }
                          </div>
                          <div id="disableItemDependencies">
                              {showDependentRules}
                          </div>
                      </div>
                      <div class="modal-footer">
                          <button type="button" class="btn btn-default" data-bs-dismiss="modal" onclick={
          closePopup()
        }>Cancel</button>
                          <button id="changeStatus" class="btn">[Submit Draft]</button>
                      </div>
                  </div>
              </div>
          </div>
      </div>
    </div>)
    }
  }

  ///////////// finish migration pop-up ///////////////
  private def displayFinishMigrationPopup: JsCmd = {
    JsRaw(""" callPopupWithTimeout(200,"finishMigrationPopup") """) // JsRaw ok, const
  }

  /**
   * Configure a Rudder internal Technique to be usable in the
   * user Technique (private) library.
   */
  private def showDirectiveDetails()(implicit qc: QueryContext): NodeSeq = {
    currentDirectiveSettingForm.get match {
      case Failure(m, ex, _) =>
        <div id={htmlId_policyConf} class="col-lg-offset-2 col-lg-8" style="margin-top:50px">
          <h4 class="text-warning">An error happened when trying to load directive configuration.</h4>
          <div class="bs-callout bs-callout-danger">
            <strong>Error message was:</strong>
            <p>{m}</p>{
          ex match {
            case Full(MissingTechniqueException(directive)) =>
              // in that case, we bypass workflow because we don't have the information to create a valid one (technique is missing)
              val deleteButton = SHtml.ajaxButton(
                "Delete",
                () => {
                  RudderConfig.woDirectiveRepository
                    .delete(
                      directive.id.uid,
                      ModificationId(RudderConfig.stringUuidGenerator.newUuid),
                      CurrentUser.actor,
                      Some(
                        s"Deleting directive '${directive.name}' (${directive.id.debugString}) because its technique isn't available anymore"
                      ).toBox
                    )
                    .toBox match {
                    case Full(diff) =>
                      currentDirectiveSettingForm.set(Empty)
                      Replace(htmlId_policyConf, showDirectiveDetails()) & JsRaw(
                        """initBsTooltips();"""
                      ) & onRemoveSuccessCallBack() // JsRaw ok, const
                    case eb: EmptyBox =>
                      val msg =
                        (eb ?~! s"Error when trying to delete directive '${directive.name}' (${directive.id.debugString})").messageChain
                      // redisplay this form with the new error
                      currentDirectiveSettingForm.set(Failure(msg))
                      Replace(htmlId_policyConf, showDirectiveDetails()) & JsRaw("""initBsTooltips();""") // JsRaw ok, const
                  }
                },
                ("class", "dangerButton")
              )

              <div><p>Do you want to <strong>delete directive</strong> '{directive.name}' ({directive.id.uid.value})?</p>{
                deleteButton
              }</div>

            case _ => NodeSeq.Empty
          }
        }
          </div>
        </div>

      case Empty               => <div id={htmlId_policyConf}></div>
      // here we CAN NOT USE <lift:DirectiveEditForm.showForm /> because lift seems to cache things
      // strangely, and if so, after an form save, clicking on tree node does nothing
      // (or more exactly, the call to "onclicknode" is correct, the currentDirectiveSettingForm
      // has the good Directive, but the <lift:DirectiveEditForm.showForm /> is called on
      // an other component (the one which did the submit). Strange.
      case Full(formComponent) => formComponent.showForm()
    }
  }

  private def newDirective(technique: Technique, activeTechnique: FullActiveTechnique)(implicit qc: QueryContext) = {
    configService.rudder_global_policy_mode().toBox match {
      case Full(globalMode) =>
        val allDefaults          = techniqueRepository.getTechniquesInfo().directivesDefaultNames
        val directiveDefaultName =
          allDefaults.get(technique.id.serialize).orElse(allDefaults.get(technique.id.name.value)).getOrElse(technique.name)
        val directive            = {
          Directive(
            DirectiveId(DirectiveUid(uuidGen.newUuid), GitVersion.DEFAULT_REV),
            technique.id.version,
            Map(),
            directiveDefaultName,
            "",
            None,
            "",
            5,
            _isEnabled = true
          )
        }
        updateDirectiveSettingForm(
          activeTechnique.techniques.toMap,
          activeTechnique.toActiveTechnique(),
          directive,
          None,
          isADirectiveCreation = true,
          globalMode = globalMode
        )
        // Update UI
        Replace(htmlId_policyConf, showDirectiveDetails()) &
        SetHtml(html_techniqueDetails, NodeSeq.Empty) &
        JsRaw("""initBsTooltips();""") // JsRaw ok, const
      case eb: EmptyBox =>
        val fail      = eb ?~! "Could not get global policy mode while creating new directive"
        logger.error(fail.messageChain)
        val errorHtml = {
          <div class="p-2">
              <p class="error">{fail.messageChain}</p>
              </div>
        }
        SetHtml(htmlId_policyConf, errorHtml)
    }
  }

  /* Update Directive form,
   * either from the directive Id (when it comes from the url )
   * or from the full directive info ( callbacks, click on directive tree ...)
   */
  def updateDirectiveForm(
      directiveInfo: Either[Directive, DirectiveId],
      oldDirective:  Option[Directive]
  )(implicit qc: QueryContext): JsCmd = {
    val directiveId = directiveInfo match {
      case Left(directive)    => directive.id
      case Right(directiveId) => directiveId
    }
    (for {
      globalMode <- configService.rudder_global_policy_mode()
      ad         <- RudderConfig.configurationRepository
                      .getDirective(directiveId)
                      .notOptional(s"Directive with id '${directiveId.debugString}' was not found")
      techniques  = RudderConfig.techniqueRepository.getByName(ad.activeTechnique.techniqueName)
    } yield {
      (globalMode, techniques, ad.activeTechnique, ad.directive)
    }).toBox match {
      case Full((globalMode, techniques, activeTechnique, directive)) =>
        // In priority, use the directive passed as parameter
        val newDirective = directiveInfo match {
          case Left(updatedDirective) => updatedDirective
          case _                      => directive // Only the id, get it from the library
        }
        updateDirectiveSettingForm(
          techniques,
          activeTechnique,
          newDirective,
          oldDirective,
          isADirectiveCreation = false,
          globalMode = globalMode
        )
      case eb: EmptyBox =>
        currentDirectiveSettingForm.set(eb)
    }

    SetHtml(html_techniqueDetails, NodeSeq.Empty) &
    Replace(htmlId_policyConf, showDirectiveDetails()) &
    JsRaw(s"""
        removeBsTooltips();
        sessionStorage.removeItem('tags-${StringEscapeUtils.escapeEcmaScript(directiveId.uid.value)}');
      """.stripMargin) &
    After(TimeSpan(0), JsRaw("""removeBsTooltips();initBsTooltips();"""))
  }

  private case class MissingTechniqueException(directive: Directive) extends Exception(
        s"Directive ${directive.name} (${directive.id.uid.value}) is bound to a technique without any valid version available"
      )

  private def updateDirectiveSettingForm(
      techniques:           Map[TechniqueVersion, Technique],
      activeTechnique:      ActiveTechnique,
      directive:            Directive,
      oldDirective:         Option[Directive],
      isADirectiveCreation: Boolean,
      globalMode:           GlobalPolicyMode
  )(implicit qc: QueryContext): Unit = {

    def createForm(dir: Directive, oldDir: Option[Directive], technique: Technique, errorMsg: Option[String]) = {
      new DirectiveEditForm(
        htmlId_policyConf,
        technique,
        activeTechnique,
        techniques,
        dir,
        oldDir,
        globalMode,
        isADirectiveCreation = isADirectiveCreation,
        onSuccessCallback = directiveEditFormSuccessCallBack(),
        onMigrationCallback = (dir, optDir) => updateDirectiveForm(Left(dir), optDir) & displayFinishMigrationPopup,
        onRemoveSuccessCallBack = () => onRemoveSuccessCallBack(),
        displayTechniqueDetails = onClickTechnique
      )
    }

    configRepo.getTechnique(TechniqueId(activeTechnique.techniqueName, directive.techniqueVersion)).runNow match {
      case Some(technique) =>
        val dirEditForm = createForm(directive, oldDirective, technique._2, None)
        currentDirectiveSettingForm.set(Full(dirEditForm))
      case None            =>
        // do we have at least one version for that technique ? We can then try to migrate towards it
        techniques.toSeq.sortBy(_._1).lastOption match {
          case Some((version, technique)) =>
            val dirEditForm = createForm(directive.copy(techniqueVersion = version), Some(directive), technique, None)
            currentDirectiveSettingForm.set(Full(dirEditForm))
            dirEditForm.addFormMsg(
              <div style="margin: 40px">
                <h4 class="text-danger"><u>Important information: directive migration towards version {version} of '{
                technique.name
              }'</u></h4>
                <div class="bs-callout bs-callout-danger text-left">
                <p>This directive was linked to version '{
                directive.techniqueVersion.debugString
              }' of the technique which is not available anymore. It was automatically
                migrated to version '{version}' but the change is not commited yet.</p>
                <p>You can now delete the directive or save it to confirm migration. If you keep that directive without commiting changes, Rudder will not be
                able to generate policies for rules which use it.</p>
                </div>
              </div>
            )
          case None                       =>
            // no version ! propose deletion to the directive along with an error message.
            val msg = s"Can not display directive edit form: missing information about technique with " +
              s"name='${activeTechnique.techniqueName.value}' and version='${directive.techniqueVersion.debugString}'"
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

  private def directiveEditFormSuccessCallBack()(
      returns: Either[Directive, ChangeRequestId]
  )(implicit qc: QueryContext): JsCmd = {

    returns match {
      case Left(dir) => // ok, we've received a directive, show it
        updateDirectiveLibrary() &
        updateDirectiveForm(Left(dir), None)

      case Right(changeRequestId) => // oh, we have a change request, go to it
        linkUtil.redirectToChangeRequestLink(changeRequestId)
    }
  }

  private def onRemoveSuccessCallBack()(implicit qc: QueryContext): JsCmd = {
    updateDirectiveLibrary()
  }

  private def updateDirectiveLibrary()(implicit qc: QueryContext): JsCmd = {
    directiveLibrary = getDirectiveLib()
    rules = getRules()
    Replace(htmlId_activeTechniquesTree, displayDirectiveLibrary())
  }

  //////////////// display trees ////////////////////////

  private def onClickDirective(cat: FullActiveTechniqueCategory, at: FullActiveTechnique, directive: Directive): JsCmd = {
    // since https://issues.rudder.io/issues/25046, we need to only change windows hash location, else it creates
    // duplicate request, see: https://issues.rudder.io/issues/26002
    // See `parseJsArg` above, especially the `window.addEventListener('hashchange'` part
    val json = directive.id.rev match {
      case GitVersion.DEFAULT_REV => s"""{"directiveId":"${StringEscapeUtils.escapeEcmaScript(directive.id.uid.value)}"}"""
      case r                      =>
        s"""{"directiveId":"${StringEscapeUtils.escapeEcmaScript(directive.id.uid.value)}", "rev":"${StringEscapeUtils
            .escapeEcmaScript(r.value)}"}"""
    }
    JsRaw(s"""this.window.location.hash = "#" + JSON.stringify(${json})""".stripMargin)
  }

  private def onClickActiveTechnique(fullActiveTechnique: FullActiveTechnique)(implicit qc: QueryContext): JsCmd = {
    currentTechnique = fullActiveTechnique.newestAvailableTechnique.map(fat => (fullActiveTechnique, fat.id.version))
    currentDirectiveSettingForm.set(Empty)
    // Update UI and reset hash location : we do not have hash for techniques, see https://issues.rudder.io/issues/26206
    Replace(html_techniqueDetails, techniqueDetails.applyAgain()) &
    Replace(htmlId_policyConf, showDirectiveDetails()) &
    JsRaw("""this.window.location.hash = ""; initBsTooltips();""".stripMargin) // JsRaw ok, const
  }

  private def onClickTechnique(id: ActiveTechniqueId)(implicit qc: QueryContext): JsCmd = {
    onClickActiveTechnique(directiveLibrary.runNow.allActiveTechniques(id))
  }

}

object DirectiveManagement {

  /*
   * HTML id for zones with Ajax / snippet output
   */
  val htmlId_activeTechniquesTree          = "activeTechniquesTree"
  val htmlId_policyConf                    = "policyConfiguration"
  val htmlId_currentActiveTechniqueActions = "currentActiveTechniqueActions"
  val html_addPiInActiveTechnique          = "addNewDirective"
  val html_techniqueDetails                = "techniqueDetails"

  def setEnabled(activeTechniqueId: ActiveTechniqueId, name: String, status: Boolean, successCallback: () => JsCmd): JsCmd = {
    val msg = (if (status) "Enable" else "Disable") ++ "technique from directive library screen"
    RudderConfig.woDirectiveRepository
      .changeStatus(
        activeTechniqueId,
        status,
        ModificationId(RudderConfig.stringUuidGenerator.newUuid),
        CurrentUser.actor,
        Some(msg)
      )
      .either
      .runNow match {
      case Left(err) =>
        JsRaw(
          s"""createErrorNotification("Error when changing status of technique ${name}: ${err.fullMsg}")"""
        ).cmd // JsRaw ok, no user inputs
      case Right(_)  =>
        val msg = s"Technique '${name} was correctly ${if (status) "enabled" else "disabled"}"
        JsRaw(
          s"""createSuccessNotification("${msg}")"""
        ).cmd & // JsRaw ok, no user inputs
        successCallback()
    }
  }
}

sealed trait NextStatus extends EnumEntry        {
  def action:       String
  def booleanValue: Boolean
}
object NextStatus       extends Enum[NextStatus] {
  case object Enabled  extends NextStatus {
    val action       = "Enable"
    val booleanValue = true
  }
  case object Disabled extends NextStatus {
    val action       = "Disable"
    val booleanValue = false
  }

  override def values: IndexedSeq[NextStatus] = findValues
}
