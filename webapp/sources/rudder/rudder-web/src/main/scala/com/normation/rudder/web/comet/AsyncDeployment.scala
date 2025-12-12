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

package com.normation.rudder.web.comet

import bootstrap.liftweb.FindCurrentUser
import bootstrap.liftweb.RudderConfig
import bootstrap.liftweb.RudderConfig.clearCacheService
import com.normation.inventory.domain.NodeId
import com.normation.rudder.AuthorizationType
import com.normation.rudder.batch.*
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.properties.FailedNodePropertyHierarchy
import com.normation.rudder.domain.properties.ResolvedNodePropertyHierarchy
import com.normation.rudder.domain.properties.SuccessNodePropertyHierarchy
import com.normation.rudder.facts.nodes.MinimalNodeFactInterface
import com.normation.rudder.ncf.CompilationStatus
import com.normation.rudder.ncf.CompilationStatusAllSuccess
import com.normation.rudder.ncf.CompilationStatusErrors
import com.normation.rudder.ncf.EditorTechniqueError
import com.normation.rudder.tenants.QueryContext
import com.normation.rudder.users.CurrentUser
import com.normation.rudder.users.RudderUserDetail
import com.normation.utils.DateFormaterService
import com.normation.zio.UnsafeRun
import net.liftweb.common.*
import net.liftweb.http.*
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import org.apache.commons.text.StringEscapeUtils
import org.joda.time.DateTime
import scala.util.matching.Regex
import scala.xml.*

class AsyncDeployment extends CometActor with CometListener with Loggable {
  import AsyncDeployment.*

  private val asyncDeploymentAgent = RudderConfig.asyncDeploymentAgent
  private val nodeFactRepo         = RudderConfig.nodeFactRepository
  private val nodeGroupRepo        = RudderConfig.roNodeGroupRepository
  private val linkUtil             = RudderConfig.linkUtil

  // current states of the deployment
  private var deploymentStatus = DeploymentStatus(NoStatus, IdleDeployer)
  private var compilationStatus:         CompilationStatus                                                                               = CompilationStatusAllSuccess
  private var nodeProperties:            Map[NodeId, ResolvedNodePropertyHierarchy]                                                      = Map.empty
  private var groupProperties:           Map[NodeGroupId, ResolvedNodePropertyHierarchy]                                                 = Map.empty
  private def globalStatus:              (CurrentDeploymentStatus, CompilationStatus, NodeConfigurationStatus, GroupConfigurationStatus) = {
    (
      deploymentStatus.current,
      compilationStatus,
      NodeConfigurationStatus.fromProperties(nodeProperties),
      GroupConfigurationStatus.fromProperties(groupProperties)
    )
  }
  // we need to get current user from SpringSecurity because it is not set in session anymore,
  // and comet doesn't know about requests
  private val currentUser:               Option[RudderUserDetail]                                                                        = FindCurrentUser.get()
  def havePerm(perm: AuthorizationType): Boolean                                                                                         = {
    currentUser match {
      case None    => false
      case Some(u) => u.checkRights(perm)
    }
  }

  override def registerWith: AsyncDeploymentActor = asyncDeploymentAgent

  override val defaultHtml = NodeSeq.Empty

  override def lowPriority: PartialFunction[Any, Unit] = {
    case msg: AsyncDeploymentActorCreateUpdate =>
      deploymentStatus = msg.deploymentStatus
      nodeProperties = msg.nodeProperties
      groupProperties = msg.groupProperties
      // we want to completely ignore rendering of disabled techniques, so we can directly adapt the received message
      compilationStatus = CompilationStatus.ignoreDisabledTechniques(msg.compilationStatus)
      reRender()
  }

  private def displayTime(label: String, time: DateTime): NodeSeq = {
    // this is replaced by a dynamic JS timer, see updateDeploymentStart
    val d = DateFormaterService.getBroadlyFormatedPeriod(time, DateTime.now)
    <span>{label} <span id="deployment-start-interval">{d}</span> ago{displayHelpBlock(time)}</span>
  }
  private def displayDate(label: String, time: DateTime): NodeSeq = {
    val t = time.toString("yyyy-MM-dd HH:mm:ssZ")
    <span class="dropdown-header">{label + t}</span>
  }
  private def updateDeploymentStart = {
    val helpBlockDate = deploymentStatus.current match {
      case s: SuccessStatus => Some(s.started)
      case s: ErrorStatus   => Some(s.started)
      case _ => None
    }
    helpBlockDate match {
      case None       => Noop
      case Some(date) => // instead change the help block part + modify current date
        Replace("deployment-start-help-block", displayHelpBlock(date)) & Run(
          // time update should be done at 1s interval after a new deployment, and clean all other running intervals
          // in the global window variable
          s"""
          function updateTimeDiff(date) {
            var now = new Date();
            var diff = Math.floor((now - date) / 1000);
            var hours = Math.floor(diff / 3600);
            var minutes = Math.floor((diff % 3600) / 60);
            var seconds = diff % 60;
            var timeDiff = "";
            if (hours > 0) {
              timeDiff += hours + "h ";
            }
            if (minutes > 0) {
              timeDiff += minutes + "m ";
            }
            timeDiff += seconds + "s ";
            $$('#deployment-start-interval').text(timeDiff);
          }
          if (!("deploymentStatus" in window)) {
            window.deploymentStatus = {};
          }

          var mainHandler = document.getElementById('statusDropdownLink');
          var date = new Date('${date.toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")}');
          var toDelete = Object.entries(window.deploymentStatus).filter(([d, i]) => date.getTime() !== new Date(d).getTime())
          toDelete.forEach(([d, i]) => {
            clearInterval(i);
            delete window.deploymentStatus[d];
          });
          window.deploymentStatus[date] = setInterval(function () {
            updateTimeDiff(date)
          }, 1000)
         """
        )
    }
  }

  private def displayHelpBlock(time: DateTime): NodeSeq = {
    val t = time.toString("yyyy-MM-dd HH:mm:ssZ")
    <div id="deployment-start-help-block" class="help-block"><em>↳ at {t}</em></div>
  }

  override def render: RenderOut = {
    // we need to update the deployment time each time we render the page, and also when the status content is available
    partialUpdate(updateDeploymentStart)
    new RenderOut(layout)
  }

  val deployementErrorMessage: Regex = """(.*)!errormessage!(.*)""".r

  private def statusBackground: String = {
    deploymentStatus.processing match {
      case IdleDeployer =>
        globalStatus match {
          case (_: ErrorStatus, _, _, _)                 =>
            "bg-error"
          case (_, _: CompilationStatusErrors, _, _)     =>
            "bg-error"
          case (_, _, NodeConfigurationStatus.Error, _)  =>
            "bg-error"
          case (_, _, _, GroupConfigurationStatus.Error) =>
            "bg-error"
          case (NoStatus, _, _, _)                       =>
            "bg-neutral"
          case (
                _: SuccessStatus,
                CompilationStatusAllSuccess,
                NodeConfigurationStatus.Success,
                GroupConfigurationStatus.Success
              ) =>
            "bg-ok"
        }
      case _            =>
        "bg-refresh"
    }
  }

  private def showGeneration(hasLastBottomBorder: Boolean): NodeSeq = {
    def showDetailsPopup(details: NodeSeq): NodeSeq = {
      val callback =
        () => JsRaw("initBsModal('errorDetailsDialog');") & SetHtml("errorDetailsMessage", details) // JsRaw ok, const
      (<li class="list-group-item p-0">
      {SHtml.ajaxButton(Text("Details"), callback, ("class", "generationDetails btn py-2 rounded-0"))}
      </li>)
    }
    def showGeneratePoliciesPopup:          NodeSeq = {
      val callback = () => Run("initBsModal('generatePoliciesDialog')") // JsRaw ok, const
      if (havePerm(AuthorizationType.Deployer.Write)) {
        <li class="list-group-item p-0">{
          SHtml.ajaxButton(
            Text("Regenerate all policies"),
            callback,
            ("class", "regeneratePolicies btn py-2 rounded-top-0") // fills the li item
          )
        }
        </li>
      } else NodeSeq.Empty
    }

    def commonStatement(
        start:        DateTime,
        end:          DateTime,
        durationText: Option[String],
        headText:     String,
        iconClass:    String,
        statusClass:  String
    ): NodeSeq = {
      <li class="list-group-item"><a href="#" class={statusClass + " no-click"}><span class={iconClass}></span>{headText}</a></li>
      <li class="list-group-item"><a href="#" class="no-click">
      <span class={statusClass + " fa fa-hourglass-start"}></span><span id="deployment-start">{
        displayTime("Started", start)
      }</span>
      </a>
      </li> ++ {
        durationText
          .map(dur => {
            <li class="list-group-item"><a href="#" class="no-click"><span class={statusClass + " fa fa-clock-o"}></span>{
              dur
            } {
              DateFormaterService.getBroadlyFormatedPeriod(start, end)
            }</a></li>
          })
          .getOrElse(NodeSeq.Empty)
      }
    }
    def loadingStatement(start: DateTime):  NodeSeq = {
      <li class="list-group-item">Policies building...</li>
      <li class="list-group-item" >{displayDate("Started at ", start)}</li>
    }
    val (titleIconClass: String, items: NodeSeq) = deploymentStatus.processing match {
      case IdleDeployer                                        =>
        deploymentStatus.current match {
          case NoStatus                                          => "fa-minus-circle" -> <li class="list-group-item">Policy update status unavailable</li>
          case SuccessStatus(id, start, end, configurationNodes) =>
            "fa-check-circle" -> commonStatement(
              start,
              end,
              Some("Update took"),
              "Policies updated",
              "text-success fa fa-check",
              "text-success"
            )
          case ErrorStatus(id, start, end, failure)              =>
            "fa-times-circle" -> (commonStatement(
              start,
              end,
              None,
              "Error during policy update",
              "text-danger fa fa-times",
              "text-danger"
            ) ++ showDetailsPopup(detailsPopup(failure)))
        }
      case Processing(id, start)                               => "fa-arrows-rotate" -> loadingStatement(start)
      case ProcessingAndPendingAuto(asked, current, a, e)      => "fa-arrows-rotate" -> loadingStatement(current.started)
      case ProcessingAndPendingManual(asked, current, a, e, r) => "fa-arrows-rotate" -> loadingStatement(current.started)
    }
    val titleIconColorClass                      = deploymentStatus.processing match {
      case IdleDeployer =>
        deploymentStatus.current match {
          case _: ErrorStatus   => "text-danger"
          case _: SuccessStatus => "text-success"
          case NoStatus => "text-muted"
        }
      case _            => "text-muted"
    }
    <li class={"card border-start-0 border-end-0 border-top-0 p-0" + (if (hasLastBottomBorder) "" else " rounded-0")}>
      <div class="card-body status-card">
        <button class="card-title btn status-card-title" data-bs-toggle="collapse" data-bs-target="#policy-generation-collapse" aria-expanded="true" aria-controls="policy-generation-collapse">
          <div class="status-card-title-caret"/>
          Policy generation <i class={"ps-1 fa " + titleIconClass + " " + titleIconColorClass}/>
        </button>
        <div id="policy-generation-collapse" class="collapse show">
          <ul class="list-group policy-generation">
          {items}
          {showGeneratePoliciesPopup}
          </ul>
        </div>
      </div>
    </li>
  }

  private def detailsPopup(failure: Failure): NodeSeq = {
    failure.messageChain match {
      case deployementErrorMessage(chain, error) =>
        <div class="alert alert-danger" role="alert">
          {chain.split("<-").map(x => { <b>⇨&nbsp;</b> } ++ Text(x) ++ { <br/> })}
        </div>
        <br/>
        <div class="panel-group" role="tablist">
          <div class="panel panel-default">
            <a class="" id="showTechnicalErrorDetails" role="button" data-bs-toggle="collapse" href="#collapseListGroup1" aria-expanded="true" aria-controls="collapseListGroup1" onclick="reverseErrorDetails()">
              <div class="panel-heading" role="tab" id="collapseListGroupHeading1">
                <h4 class="panel-title">
                  Show technical details
                  <span id="showhidetechnicalerrors" class="fa fa-chevron-up up"></span>
                </h4>
              </div>
            </a>
            <div id="collapseListGroup1" class="panel-collapse collapse in" role="tabpanel" aria-labelledby="collapseListGroupHeading1" aria-expanded="true">
              <ul id="deploymentErrorMsg" class="list-group">
                {error.split("<-").map(x => <li class="list-group-item">{"⇨ " + x}</li>)}
              </ul>
            </div>
          </div>
        </div>

      case _ =>
        <pre class="code">{
          failure.messageChain
            .split("<- ")
            .map(x => Text("⇨ " + x.replace("cause was:", "\n    cause was:")) ++ { <br/> })
        }</pre>
    }
  }

  private def closePopup(): JsCmd = {
    JsRaw("""hideBsModal('generatePoliciesDialog')""") // JsRaw ok, const
  }

  private def fullPolicyGeneration: NodeSeq = {
    if (havePerm(AuthorizationType.Deployment.Write)) {
      SHtml.ajaxButton(
        "Regenerate",
        () => {
          clearCacheService.clearNodeConfigurationCache(storeEvent = true, CurrentUser.actor) match {
            case Full(_) => // ok
            case eb: EmptyBox =>
              val err = eb ?~! "Error when trying to start policy generation"
              logger.warn(err.messageChain)
          }
          closePopup()
        },
        ("class", "btn btn-danger")
      )
    } else NodeSeq.Empty
  }

  private def showGroups(hasLastBottomBorder: Boolean): NodeSeq = {
    def tooltipContent(error: String):       String = {
      s"<h4 class='tags-tooltip-title'>Properties error</h4><div class='tooltip-inner-content'><pre class=\"code\">${StringEscapeUtils
          .escapeHtml4(error)}</pre></div>"
    }
    def groupBtnId(group: NodeGroupId):      String = {
      StringEscapeUtils.escapeHtml4(s"status-group-${group.serialize}")
    }
    def groupLinkScript(group: NodeGroupId): JsCmd  = {
      val link  = linkUtil.groupLink(group)
      val btnId = groupBtnId(group)
      scriptLinkButton(btnId, link)
    }

    val failures = groupProperties.collect { case (id, f: FailedNodePropertyHierarchy) => id -> f }
    val groupsErrors: Map[NodeGroup, String] = failures.toList match {
      case Nil      => Map.empty
      case statuses =>
        val groups = nodeGroupRepo.getAllByIds(statuses.map { case (id, _) => id }).runNow
        groups.flatMap(g => failures.get(g.id).map(f => g -> f.getMessage)).toMap
    }
    groupsErrors.toList match {
      case Nil    => NodeSeq.Empty
      case errors =>
        val buttonSetup: JsCmd = errors.map { case (g, _) => groupLinkScript(g.id) }.foldLeft(Noop)(_ & _)
        partialUpdate(buttonSetup)
        <li class={"card border-start-0 border-end-0 border-top-0" + (if (hasLastBottomBorder) "" else " rounded-0")}>
          <div class="card-body status-card">
            <button class="card-title btn status-card-title" data-bs-toggle="collapse" data-bs-target="#group-configuration-collapse" aria-expanded="true" aria-controls="group-configuration-collapse">
              <div class="status-card-title-caret"/>
              Group configuration errors <span class="badge bg-danger">{errors.size}</span>
            </button>
            <div id="group-configuration-collapse" class="collapse show">
              <ul class="list-group">
            {
          NodeSeq.fromSeq(
            errors.map {
              case (g, err) =>
                <li class="list-group-item d-flex justify-content-between align-items-center">
                  <button id={groupBtnId(g.id)} class="btn btn-link text-start">
                    {StringEscapeUtils.escapeHtml4(g.name)} <i class="fa fa-external-link"/>
                  </button>
                  <span>
                    <i class="fa fa-question-circle info" data-bs-toggle="tooltip" data-bs-placement="top" data-bs-html="true" data-bs-original-title={
                  tooltipContent(err)
                }></i>
                  </span>
                </li>
            }
          )
        }
              </ul>
            </div>
          </div>
        </li>
    }
  }
  private def showNodes(hasLastBottomBorder: Boolean):  NodeSeq = {
    def tooltipContent(error: String): String                                = {
      s"<h4 class='tags-tooltip-title'>Properties error</h4><div class='tooltip-inner-content'><pre class=\"code\">${StringEscapeUtils
          .escapeHtml4(error)}</pre></div>"
    }
    def nodeBtnId(node: NodeId):       String                                = {
      s"status-node-${StringEscapeUtils.escapeHtml4(node.value)}"
    }
    // The "a" tag has special display in the content, we need a button that opens link in new tab
    def nodeLinkScript(node: NodeId):  JsCmd                                 = {
      val link  = linkUtil.nodeLink(node)
      val btnId = nodeBtnId(node)
      scriptLinkButton(btnId, link)
    }
    val allNodes = nodeFactRepo.getAll()(using QueryContext.systemQC).runNow
    val nodesErrors:                   Map[MinimalNodeFactInterface, String] = nodeProperties.flatMap {
      case (_, _: SuccessNodePropertyHierarchy)     => None
      case (nodeId, f: FailedNodePropertyHierarchy) => allNodes.get(nodeId).map(_ -> f.getMessage)
    }
    nodesErrors.toList match {
      case Nil    => NodeSeq.Empty
      case errors =>
        // prevent the same error to repeat many times for nodes
        // 3 seems to be a reasonable size for a sample of nodes in error
        val maxNumber = 3
        val buttonSetup: JsCmd = errors.take(maxNumber).map { case (n, _) => nodeLinkScript(n.id) }.foldLeft(Noop)(_ & _)
        partialUpdate(buttonSetup)
        <li class={"card border-start-0 border-end-0 border-top-0" + (if (hasLastBottomBorder) "" else " rounded-0")}>
          <div class="card-body status-card">
            <button class="card-title btn status-card-title" data-bs-toggle="collapse" data-bs-target="#node-configuration-collapse" aria-expanded="true" aria-controls="node-configuration-collapse">
              <div class="status-card-title-caret"/>
              Node configuration errors <span class="badge bg-danger">{errors.size}</span>
            </button>
            <div id="node-configuration-collapse" class="collapse show">
              <ul class="list-group">
            {
          if (errors.size > maxNumber) {
            <em class="help-block d-flex align-items-baseline justify-content-center mb-2"><i class="fa fa-info-circle text-secondary pe-2"></i>only displaying the first {
              maxNumber
            } nodes in errors</em>
          } else NodeSeq.Empty
        }
            {
          NodeSeq.fromSeq(
            errors
              .take(maxNumber)
              .map {
                case (n, err) =>
                  <li class="list-group-item d-flex justify-content-between align-items-center">
                  <button id={nodeBtnId(n.id)} class="btn btn-link text-start">
                    {StringEscapeUtils.escapeHtml4(n.fqdn)} <i class="fa fa-external-link"/>
                  </button>
                  <span>
                    <i class="fa fa-question-circle info" data-bs-toggle="tooltip" data-bs-placement="top" data-bs-html="true" data-bs-original-title={
                    tooltipContent(err)
                  }></i>
                  </span>
                </li>
              }
          )
        }
              </ul>
            </div>
          </div>
        </li>
    }
  }
  private def showCompilation:                          NodeSeq = {
    def tooltipContent(error: EditorTechniqueError):      String = {
      s"<h4 class='tags-tooltip-title'>Technique compilation output in ${StringEscapeUtils.escapeHtml4(error.id.value)}/${StringEscapeUtils
          .escapeHtml4(error.version.value)}</h4><div class='tooltip-inner-content'><pre class=\"code\">${error.errorMessage}</pre></div>"
    }
    def techniqueBtnId(error: EditorTechniqueError):      String = {
      s"status-compilation-${StringEscapeUtils.escapeHtml4(error.id.value)}-${StringEscapeUtils.escapeHtml4(error.version.value)}"
    }
    // The "a" tag has special display in the content, we need a button that opens link in new tab
    def techniqueLinkScript(error: EditorTechniqueError): JsCmd  = {
      val link  = linkUtil.techniqueLink(error.id.value)
      val btnId = techniqueBtnId(error)
      scriptLinkButton(btnId, link)
    }
    compilationStatus match {
      case CompilationStatusAllSuccess                =>
        NodeSeq.Empty
      case CompilationStatusErrors(techniquesInError) =>
        val buttonSetup: JsCmd = techniquesInError.map(techniqueLinkScript).foldLeft(Noop)(_ & _)
        partialUpdate(buttonSetup)
        <li class="card border-start-0 border-end-0 border-top-0">
          <div class="card-body status-card">
            <button class="card-title btn status-card-title" data-bs-toggle="collapse" data-bs-target="#technique-compilation-collapse" aria-expanded="true" aria-controls="technique-compilation-collapse">
              <div class="status-card-title-caret"/>
              Technique compilation errors <span class="badge bg-danger float h-25">{techniquesInError.size}</span>
            </button>
            <div id="technique-compilation-collapse" class="collapse show">
              <ul class="list-group">
            {
          NodeSeq.fromSeq(
            techniquesInError
              .map(t => {
                <li class="list-group-item d-flex justify-content-between align-items-center">
                  <button id={techniqueBtnId(t)} class="btn btn-link text-start">
                    {StringEscapeUtils.escapeHtml4(t.name)} <i class="fa fa-external-link"/>
                  </button>
                  <span>
                    <i class="fa fa-question-circle info" data-bs-toggle="tooltip" data-bs-placement="top" data-bs-html="true" data-bs-original-title={
                  tooltipContent(t)
                }></i>
                  </span>
                </li>
              })
          )
        }
              </ul>
            </div>
          </div>
        </li>
    }
  }

  private def layout = {
    if (havePerm(AuthorizationType.Deployment.Read)) {
      // We have to compute in reverse order to know if the bottom border should be specialized
      val compilation = showCompilation
      val nodes       = showNodes(hasLastBottomBorder = compilation.isEmpty)
      val groups      = showGroups(hasLastBottomBorder = compilation.isEmpty && nodes.isEmpty)
      val generation  =
        showGeneration(hasLastBottomBorder = compilation.isEmpty && nodes.isEmpty && groups.isEmpty)

      (<li class={"nav-item dropdown notifications-menu " ++ statusBackground}>
        <a href="#" class="dropdown-toggle status-dropdown" id="statusDropdownLink" onclick="event.preventDefault();event.stopPropagation();" role="button" data-bs-toggle="dropdown" aria-expanded="false">
          Status
          <i class="fa fa-heartbeat"></i>
          <span id="generation-status" class="label"><span></span></span>
        </a>
        <ul class="dropdown-menu" aria-labelledby="statusDropdownLink">
          {generation}
          {groups}
          {nodes}
          {compilation}
        </ul>
      </li> ++ errorPopup ++ generatePoliciesPopup)

    } else NodeSeq.Empty
  }

  private def errorPopup = {
    <div class="modal fade" tabindex="-1" id="errorDetailsDialog" data-bs-backdrop="false">
        <div class="modal-dialog modal-lg">
            <div class="modal-content">
                <div class="modal-header">
                    <h5 class="modal-title">Error</h5>
                    <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
                </div>
                <div class="modal-body">
                    <div class="row space-bottom">
                        <div class="col-xl-12">
                            <h4 class="text-center">
                                Policy update process was stopped due to an error:
                            </h4>
                        </div>
                    </div>
                    <div id="errorDetailsMessage" class="space-bottom space-top">
                        [Here come the error message]
                    </div>
                </div>
                <div class="modal-footer">
                    <button type="button" class="btn btn-default" data-bs-dismiss="modal">Close</button>
                </div>
            </div>
        </div>
      </div>
  }

  private def generatePoliciesPopup = {
    <div class="modal fade" tabindex="-1" id="generatePoliciesDialog" aria-hidden="true" data-bs-backdrop="false" data-bs-dismiss="modal">
      <div class="modal-dialog">
        <div class="modal-content">
          <div class="modal-header">
            <h5 class="modal-title">Regenerate Policies</h5>
            <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
          </div>
          <div class="modal-body">
            <div class="row space-bottom">
              <div class="col-xl-12">
                <h4 class="text-center">
                  Are you sure that you want to regenerate all policies ?
                </h4>
              </div>
            </div>
          </div>
          <div class="modal-footer">
            <button type="button" class="btn btn-default" data-bs-dismiss="modal">Close</button>
            {fullPolicyGeneration}
          </div>
        </div>
      </div>
    </div>
  }

  private def scriptLinkButton(btnId: String, link: String) = {
    OnLoad(JsRaw(s"""
            document.getElementById("${StringEscapeUtils.escapeEcmaScript(btnId)}").onclick = function () {
              window.open("${StringEscapeUtils.escapeEcmaScript(link)}", "_blank");
            };
            """))
  }
}

private object AsyncDeployment {
  sealed trait NodeConfigurationStatus

  object NodeConfigurationStatus {

    case object Error   extends NodeConfigurationStatus
    case object Success extends NodeConfigurationStatus

    def fromProperties(properties: Map[NodeId, ResolvedNodePropertyHierarchy]): NodeConfigurationStatus = {
      val hasError = properties.collectFirst { case (_, _: FailedNodePropertyHierarchy) => () }.isDefined
      if (hasError) Error
      else Success
    }
  }

  sealed trait GroupConfigurationStatus

  object GroupConfigurationStatus {

    case object Error   extends GroupConfigurationStatus
    case object Success extends GroupConfigurationStatus

    def fromProperties(properties: Map[NodeGroupId, ResolvedNodePropertyHierarchy]): GroupConfigurationStatus = {
      val hasError = properties.collectFirst { case (_, _: FailedNodePropertyHierarchy) => () }.isDefined
      if (hasError) Error
      else Success
    }
  }
}
