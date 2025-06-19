/*
 *************************************************************************************
 * Copyright 2019 Normation SAS
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
package com.normation.rudder.web.services

import com.normation.cfclerk.domain.TechniqueName
import com.normation.eventlog.EventLog
import com.normation.inventory.domain.NodeId
import com.normation.rudder.api.*
import com.normation.rudder.batch.ErrorStatus
import com.normation.rudder.batch.SuccessStatus
import com.normation.rudder.domain.eventlog.*
import com.normation.rudder.domain.eventlog.WorkflowStepChanged
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.domain.secret.Secret
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.domain.workflows.WorkflowStepChange
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.git.GitArchiveId
import com.normation.rudder.git.GitCommitId
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.HeartbeatConfiguration
import com.normation.rudder.repository.*
import com.normation.rudder.rule.category.RoRuleCategoryRepository
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.services.eventlog.EventLogDetailsService
import com.normation.rudder.services.eventlog.RollbackInfo
import com.normation.rudder.services.modification.ModificationService
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.user.PersonIdentService
import com.normation.rudder.web.model.LinkUtil
import com.normation.utils.DateFormaterService
import com.normation.zio.UnsafeRun
import net.liftweb.common.*
import net.liftweb.http.S
import net.liftweb.http.SHtml
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.json.JsonAST.JObject
import net.liftweb.util.Helpers.*
import org.eclipse.jgit.lib.PersonIdent
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import scala.util.Failure as Catch
import scala.util.Success
import scala.util.Try
import scala.xml.*

class EventLogDetailsGenerator(
    logDetailsService:   EventLogDetailsService,
    repos:               EventLogRepository,
    nodeGroupRepository: RoNodeGroupRepository,
    directiveRepository: RoDirectiveRepository,
    nodeInfoService:     NodeInfoService,
    ruleCatRepository:   RoRuleCategoryRepository,
    modificationService: ModificationService,
    personIdentService:  PersonIdentService,
    linkUtil:            LinkUtil,
    diffDisplayer:       DiffDisplayer
) extends Loggable {

  private val xmlPretty = new scala.xml.PrettyPrinter(80, 2)

  // convention: "X" means "ignore"

  def displayDescription(event: EventLog): NodeSeq = {
    import linkUtil.*
    def crDesc(x: EventLog, actionName: NodeSeq) = {
      val id   = RuleId.parse((x.details \ "rule" \ "id").text).getOrElse(RuleId(RuleUid("")))
      val name = (x.details \ "rule" \ "displayName").text
      Text("Rule ") ++ {
        if (id.serialize.length < 1) Text(name)
        else <a href={ruleLink(id)}>{name}</a> ++ actionName
      }
    }

    def piDesc(x: EventLog, actionName: NodeSeq) = {
      val id   = (x.details \ "directive" \ "id").text
      val name = (x.details \ "directive" \ "displayName").text
      Text("Directive ") ++ {
        if (id.size < 1) Text(name)
        else <a href={directiveLink(DirectiveUid(id))}>{name}</a> ++ actionName
      }
    }

    def groupDesc(x: EventLog, actionName: NodeSeq) = {
      val id   = (x.details \ "nodeGroup" \ "id").text
      val name = (x.details \ "nodeGroup" \ "displayName").text
      Text("Group ") ++ {
        if (id.size < 1) Text(name)
        else <a href={groupLink(NodeGroupId(NodeGroupUid(id)))}>{name}</a> ++ actionName
      }
    }

    def nodeDesc(x: EventLog, actionName: NodeSeq) = {
      val id   = (x.details \\ "node" \ "id").text
      val name = (x.details \\ "node" \ "hostname").text.strip() match {
        case "" => id
        case x  => x
      }
      Text("Node ") ++ {
        if ((id.size < 1) || (actionName == Text(" deleted"))) Text(name)
        else <a href={nodeLink(NodeId(id))}>{name}</a> ++ actionName
      }
    }

    def techniqueDesc(x: EventLog, actionName: NodeSeq) = {
      val name = (x.details \ "activeTechnique" \ "techniqueName").text
      Text("Technique %s".format(name)) ++ actionName
    }

    def globalParamDesc(x: EventLog, actionName: NodeSeq) = {
      val name = (x.details \ "globalParameter" \ "name").text
      Text(s"Global Parameter ${name} ${actionName}")
    }

    def changeRequestDesc(x: EventLog, actionName: NodeSeq) = {
      val name   = (x.details \ "changeRequest" \ "name").text
      val idNode = (x.details \ "changeRequest" \ "id").text.trim
      val xml    = Try(idNode.toInt) match {
        case Success(id) =>
          if (actionName == Text(" deleted"))
            Text(name)
          else
            <a href={changeRequestLink(ChangeRequestId(id))}>{name}</a>

        case Catch(e) =>
          logger.error(s"could not translate ${idNode} to a correct chage request identifier: ${e.getMessage()}")
          Text(name)
      }
      Text("Change request ") ++ xml ++ actionName
    }

    def workflowStepDesc(x: EventLog) = {
      logDetailsService.getWorkflotStepChange(x.details) match {
        case Full(WorkflowStepChange(crId, from, to)) =>
          Text("Change request #") ++
          <a href={changeRequestLink(crId)}>{crId}</a> ++
          Text(s" status modified from '${from.value}' to '${to.value}''")

        case eb: EmptyBox =>
          val fail = eb ?~! "could not display workflow step event log"
          logger.error(fail.msg)
          Text("Change request status modified")
      }
    }

    def apiAccountDesc(x: EventLog, actionName: NodeSeq) = {
      val name = (x.details \ "apiAccount" \ "name").text
      Text(s"API Account ${name} ${actionName}")
    }

    def secretDesc(x: EventLog, actionName: NodeSeq) = {
      val name = (x.details \ "secret" \ "name").text
      Text(s"Secret ${name} ${actionName}")
    }

    event match {
      case x: ActivateRedButton             => Text("Stop Rudder agents on all nodes")
      case x: ReleaseRedButton              => Text("Start again Rudder agents on all nodes")
      case x: AcceptNodeEventLog            => nodeDesc(x, Text(" accepted"))
      case x: RefuseNodeEventLog            => nodeDesc(x, Text(" refused"))
      case x: DeleteNodeEventLog            => nodeDesc(x, Text(" deleted"))
      case x: LoginEventLog                 => Text("User '%s' login".format(x.principal.name))
      case x: LogoutEventLog                => Text("User '%s' logout".format(x.principal.name))
      case x: BadCredentialsEventLog        => Text("User '%s' failed to login: bad credentials".format(x.principal.name))
      case x: AutomaticStartDeployement     => Text("Automatically update policies")
      case x: ManualStartDeployement        => Text("Manually update policies")
      case x: ApplicationStarted            => Text("Rudder starts")
      case x: ModifyRule                    => crDesc(x, Text(" modified"))
      case x: DeleteRule                    => crDesc(x, Text(" deleted"))
      case x: AddRule                       => crDesc(x, Text(" added"))
      case x: ModifyDirective               => piDesc(x, Text(" modified"))
      case x: DeleteDirective               => piDesc(x, Text(" deleted"))
      case x: AddDirective                  => piDesc(x, Text(" added"))
      case x: ModifyNodeGroup               => groupDesc(x, Text(" modified"))
      case x: DeleteNodeGroup               => groupDesc(x, Text(" deleted"))
      case x: AddNodeGroup                  => groupDesc(x, Text(" added"))
      case x: ClearCacheEventLog            => Text("Clear caches")
      case x: UpdatePolicyServer            => Text("Change Policy Server authorized network")
      case x: ReloadTechniqueLibrary        => Text("Technique library updated")
      case x: ModifyTechnique               => techniqueDesc(x, Text(" modified"))
      case x: DeleteTechnique               => techniqueDesc(x, Text(" deleted"))
      case x: SuccessfulDeployment          => Text("Successful policy update")
      case x: FailedDeployment              => Text("Failed policy update")
      case x: ExportGroupsArchive           => Text("New groups archive")
      case x: ExportTechniqueLibraryArchive => Text("New Directive library archive")
      case x: ExportRulesArchive            => Text("New Rules archives")
      case x: ExportParametersArchive       => Text("New Parameters archives")
      case x: ExportFullArchive             => Text("New full archive")
      case x: ImportGroupsArchive           => Text("Restoring group archive")
      case x: ImportTechniqueLibraryArchive => Text("Restoring Directive library archive")
      case x: ImportRulesArchive            => Text("Restoring Rules archive")
      case x: ImportParametersArchive       => Text("Restoring Parameters archive")
      case x: ImportFullArchive             => Text("Restoring full archive")
      case _: AddChangeRequest              => changeRequestDesc(event, Text(" created"))
      case _: DeleteChangeRequest           => changeRequestDesc(event, Text(" deleted"))
      case _: ModifyChangeRequest           => changeRequestDesc(event, Text(" modified"))
      case _: Rollback                      => Text("Restore a previous state of configuration policy")
      case x: WorkflowStepChanged           => workflowStepDesc(x)
      case x: AddGlobalParameter            => globalParamDesc(x, Text(" added"))
      case x: ModifyGlobalParameter         => globalParamDesc(x, Text(" modified"))
      case x: DeleteGlobalParameter         => globalParamDesc(x, Text(" deleted"))
      case x: CreateAPIAccountEventLog      => apiAccountDesc(x, Text(" added"))
      case x: ModifyAPIAccountEventLog      => apiAccountDesc(x, Text(" modified"))
      case x: DeleteAPIAccountEventLog      => apiAccountDesc(x, Text(" deleted"))
      case x: ModifyGlobalProperty          => Text(s"Modify '${x.propertyName}' global property")
      case x: ModifyNode                    => nodeDesc(x, Text(" modified"))
      case x: PromoteNode                   => nodeDesc(x, Text(" promoted to relay"))
      case x: DemoteRelay                   => nodeDesc(x, Text(" demoted to node"))
      case x: AddSecret                     => secretDesc(x, Text(" added"))
      case x: ModifySecret                  => secretDesc(x, Text(" modified"))
      case x: DeleteSecret                  => secretDesc(x, Text(" deleted"))
      case _ => Text("Unknow event type")
    }
  }

  def displayDetails(event: EventLog, changeRequestId: Option[ChangeRequestId])(implicit qc: QueryContext): NodeSeq = {

    (for {
      groupLib         <- nodeGroupRepository
                            .getFullGroupLibrary()
                            .orElseFail(<div class="error">System error when trying to get the group library</div>)
      rootRuleCategory <- ruleCatRepository
                            .getRootCategory()
                            .orElseFail(<div class="error">System error when trying to get the rule categories</div>)
    } yield {
      val generatedByChangeRequest = {
        changeRequestId match {
          case None     => NodeSeq.Empty
          case Some(id) =>
            NodeSeq.Empty
          //          <h5 style="padding:5px"> This change was introduced by change request {SHtml.a(() => S.redirectTo(linkUtil.changeRequestLink(id)),Text(s"#${id}"))}</h5>
        }
      }

      def xmlParameters(eventId: Option[Int]) = {
        eventId match {
          case None     => NodeSeq.Empty
          case Some(id) =>
            val btnId = s"showParameters${id}"
            val preId = s"showParametersInfo${id}"
            <button id={btnId} class="btn btn-default showParameters">
              <b class="action">Show</b>
              raw technical details
            </button>
            <pre id={preId} style="display:none;" class="technical-details">{
              event.details.map(n => xmlPretty.format(n) + "\n")
            }</pre>
        }
      }

      val reasonHtml = {
        val r = event.eventDetails.reason.getOrElse("")
        if (r == "") NodeSeq.Empty
        else <div style="margin-top:2px;">
          <b>Reason: </b>{r}
        </div>
      }

      def errorMessage(e: EmptyBox) = {
        logger.debug(e ?~! "Error when parsing details.", e)
        <xml:group>
          <div class="evloglmargin">
            <h5>Details for that node were not in a recognized format.
              Raw data are displayed next:</h5>{xmlParameters(event.id)}
          </div>
        </xml:group>
      }

      <div class="py-2 flex-fill">
        {
        (event match {
          /*
           * bug in scalac : https://issues.scala-lang.org/browse/SI-6897
           * A workaround is to type with a Nodeseq
           */
          case add: AddRule =>
            "*" #> {
              val xml: NodeSeq = logDetailsService.getRuleAddDetails(add.details) match {
                case Full(addDiff)    =>
                  <div class="evloglmargin">
                    {generatedByChangeRequest}{ruleDetails(crDetailsXML, addDiff.rule, groupLib, rootRuleCategory)}{reasonHtml}{
                    xmlParameters(event.id)
                  }
                  </div>
                case Failure(m, _, _) =>
                  <p>
                    {m}
                  </p>
                case e: EmptyBox => errorMessage(e)
              }
              xml
            }

          case del: DeleteRule =>
            "*" #> {
              val xml: NodeSeq = logDetailsService.getRuleDeleteDetails(del.details) match {
                case Full(delDiff) =>
                  <div class="evloglmargin">

                    {generatedByChangeRequest}{ruleDetails(crDetailsXML, delDiff.rule, groupLib, rootRuleCategory)}{reasonHtml}{
                    xmlParameters(event.id)
                  }
                  </div>
                case e: EmptyBox => errorMessage(e)
              }
              xml
            }

          case mod: ModifyRule =>
            "*" #> {
              val xml: NodeSeq = logDetailsService.getRuleModifyDetails(mod.details) match {
                case Full(modDiff) =>
                  <div class="evloglmargin">

                    {generatedByChangeRequest}<h5>Rule overview:</h5>
                    <ul class="evlogviewpad">
                      <li>
                        <b>Rule ID: </b>{modDiff.id.serialize}
                      </li>
                      <li>
                        <b>Name: </b>{modDiff.modName.map(diff => diff.newValue).getOrElse(modDiff.name)}
                      </li>
                    </ul>{
                    val modCategory = modDiff.modCategory.map {
                      case SimpleDiff(oldOne, newOne) =>
                        <li>
                          <b>Rule category changed: </b>
                        </li> ++
                        diffDisplayer.displayRuleCategory(rootRuleCategory, oldOne, Some(newOne))
                    }

                    (
                      "#name" #> mapSimpleDiff(modDiff.modName) &
                      "#category" #> modCategory &
                      "#isEnabled *" #> mapSimpleDiff(modDiff.modIsActivatedStatus) &
                      "#isSystem *" #> mapSimpleDiff(modDiff.modIsSystem) &
                      "#shortDescription *" #> mapSimpleDiff(modDiff.modShortDescription) &
                      "#longDescription *" #> mapSimpleDiff(modDiff.modLongDescription) &
                      "#target" #> (modDiff.modTarget.map {
                        case SimpleDiff(oldOnes, newOnes) =>
                          <li>
                              <b>Group Targets changed: </b>
                            </li> ++
                          diffDisplayer.displayRuleTargets(oldOnes.toSeq, newOnes.toSeq, groupLib)
                      }) &
                      "#policies" #> (modDiff.modDirectiveIds.map {
                        case SimpleDiff(oldOnes, newOnes) =>
                          <li>
                              <b>Directives changed: </b>
                            </li> ++
                          diffDisplayer.displayDirectiveChangeList(oldOnes.toSeq, newOnes.toSeq)
                      })
                    )(crModDetailsXML)
                  }{reasonHtml}{xmlParameters(event.id)}
                  </div>
                case e: EmptyBox => errorMessage(e)
              }
              xml
            }

          ///////// Directive /////////

          case x: ModifyDirective =>
            "*" #> {
              val xml: NodeSeq = logDetailsService.getDirectiveModifyDetails(x.details) match {
                case Full(modDiff)    =>
                  <div class="evloglmargin">

                    {generatedByChangeRequest}<h5>Directive overview:</h5>
                    <ul class="evlogviewpad">
                      <li>
                        <b>Directive ID: </b>{modDiff.id.serialize}
                      </li>
                      <li>
                        <b>Name: </b>{modDiff.modName.map(diff => diff.newValue.toString).getOrElse(modDiff.name)}
                      </li>
                    </ul>{
                    (
                      "#name" #> mapSimpleDiff(modDiff.modName, modDiff.id) &
                      "#priority *" #> mapSimpleDiff(modDiff.modPriority) &
                      "#isEnabled *" #> mapSimpleDiff(modDiff.modIsActivated) &
                      "#isSystem *" #> mapSimpleDiff(modDiff.modIsSystem) &
                      "#shortDescription *" #> mapSimpleDiff(modDiff.modShortDescription) &
                      "#longDescription *" #> mapSimpleDiff(modDiff.modLongDescription) &
                      "#ptVersion *" #> mapSimpleDiff(modDiff.modTechniqueVersion) &
                      "#policyMode *" #> mapSimpleDiffT[Option[PolicyMode]](
                        modDiff.modPolicyMode,
                        _.fold(PolicyMode.defaultValue)(_.name)
                      ) &
                      "#tags" #> tagsDiff(modDiff.modTags) &
                      "#parameters" #> (
                        modDiff.modParameters.map(diff => "#diff" #> displaydirectiveInnerFormDiff(diff, event.id))
                      )
                    )(piModDirectiveDetailsXML)
                  }{reasonHtml}{xmlParameters(event.id)}
                  </div>
                case Failure(m, _, _) =>
                  <p>
                    {m}
                  </p>
                case e: EmptyBox => errorMessage(e)
              }
              xml
            }

          case x: AddDirective =>
            "*" #> {
              val xml: NodeSeq = logDetailsService.getDirectiveAddDetails(x.details) match {
                case Full((diff, sectionVal)) =>
                  <div class="evloglmargin">

                    {generatedByChangeRequest}{directiveDetails(piDetailsXML, diff.techniqueName, diff.directive, sectionVal)}{
                    reasonHtml
                  }{xmlParameters(event.id)}
                  </div>
                case e: EmptyBox => errorMessage(e)
              }
              xml
            }

          case x: DeleteDirective =>
            "*" #> {
              val xml: NodeSeq = logDetailsService.getDirectiveDeleteDetails(x.details) match {
                case Full((diff, sectionVal)) =>
                  <div class="evloglmargin">

                    {generatedByChangeRequest}{directiveDetails(piDetailsXML, diff.techniqueName, diff.directive, sectionVal)}{
                    reasonHtml
                  }{xmlParameters(event.id)}
                  </div>
                case e: EmptyBox => errorMessage(e)
              }
              xml
            }

          ///////// Node Group /////////

          case x: ModifyNodeGroup =>
            "*" #> {
              val xml: NodeSeq = logDetailsService.getNodeGroupModifyDetails(x.details) match {
                case Full(modDiff) =>
                  <div class="evloglmargin">

                    {generatedByChangeRequest}<h5>Group overview:</h5>
                    <ul class="evlogviewpad">
                      <li>
                        <b>Node Group ID: </b>{modDiff.id.withDefaultRev.serialize}
                      </li>
                      <li>
                        <b>Name: </b>{modDiff.modName.map(diff => diff.newValue.toString).getOrElse(modDiff.name)}
                      </li>
                    </ul>{
                    (
                      "#name" #> mapSimpleDiff(modDiff.modName) &
                      "#isEnabled *" #> mapSimpleDiff(modDiff.modIsActivated) &
                      "#isSystem *" #> mapSimpleDiff(modDiff.modIsSystem) &
                      "#isDynamic *" #> mapSimpleDiff(modDiff.modIsDynamic) &
                      "#shortDescription *" #> mapSimpleDiff(modDiff.modDescription) &
                      "#query" #> (
                        modDiff.modQuery.map { diff =>
                          val mapOptionQuery = (opt: Option[Query]) => {
                            opt match {
                              case None    => Text("None")
                              case Some(q) => Text(q.toJSONString)
                            }
                          }

                          ".diffOldValue *" #> mapOptionQuery(diff.oldValue) &
                          ".diffNewValue *" #> mapOptionQuery(diff.newValue)
                        }
                      ) &
                      "#nodes" #> (
                        modDiff.modNodeList.map { diff =>
                          ".diffOldValue *" #> nodeGroupDetails(diff.oldValue) &
                          ".diffNewValue *" #> nodeGroupDetails(diff.newValue)
                        }
                      )
                    )(groupModDetailsXML)
                  }{reasonHtml}{xmlParameters(event.id)}
                  </div>
                case e: EmptyBox => errorMessage(e)
              }
              xml
            }

          case x: AddNodeGroup =>
            "*" #> {
              val xml: NodeSeq = logDetailsService.getNodeGroupAddDetails(x.details) match {
                case Full(diff) =>
                  <div class="evloglmargin">

                    {generatedByChangeRequest}{groupDetails(groupDetailsXML, diff.group)}{reasonHtml}{xmlParameters(event.id)}
                  </div>
                case e: EmptyBox => errorMessage(e)
              }
              xml
            }

          case x: DeleteNodeGroup =>
            "*" #> {
              val xml: NodeSeq = logDetailsService.getNodeGroupDeleteDetails(x.details) match {
                case Full(diff) =>
                  <div class="evloglmargin">

                    {generatedByChangeRequest}{groupDetails(groupDetailsXML, diff.group)}{reasonHtml}{xmlParameters(event.id)}
                  </div>
                case e: EmptyBox => errorMessage(e)
              }
              xml
            }

          ///////// Node Group /////////

          case x: AcceptNodeEventLog =>
            "*" #> {
              val xml: NodeSeq = logDetailsService.getAcceptNodeLogDetails(x.details) match {
                case Full(details) =>
                  <div class="evloglmargin">

                    <h5>Node accepted overview:</h5>{nodeDetails(details)}{reasonHtml}{xmlParameters(event.id)}
                  </div>
                case e: EmptyBox => errorMessage(e)
              }
              xml
            }

          case x: RefuseNodeEventLog =>
            "*" #> {
              val xml: NodeSeq = logDetailsService.getRefuseNodeLogDetails(x.details) match {
                case Full(details) =>
                  <div class="evloglmargin">

                    <h5>Node refused overview:</h5>{nodeDetails(details)}{reasonHtml}{xmlParameters(event.id)}
                  </div>
                case e: EmptyBox => errorMessage(e)
              }
              xml
            }

          case x: DeleteNodeEventLog   =>
            "*" #> {
              val xml: NodeSeq = logDetailsService.getDeleteNodeLogDetails(x.details) match {
                case Full(details) =>
                  <div class="evloglmargin">

                    <h5>Node deleted overview:</h5>{nodeDetails(details)}{reasonHtml}{xmlParameters(event.id)}
                  </div>
                case e: EmptyBox => errorMessage(e)
              }
              xml
            }

          ////////// deployment //////////
          case x: SuccessfulDeployment =>
            "*" #> {
              val xml: NodeSeq = logDetailsService.getDeploymentStatusDetails(x.details) match {
                case Full(SuccessStatus(id, started, ended, _)) =>
                  <div class="evloglmargin">

                    <h5>Successful policy update:</h5>
                    <ul class="evlogviewpad">
                      <li>
                        <b>ID: </b> &nbsp;{id}
                      </li>
                      <li>
                        <b>Start time: </b> &nbsp;{DateFormaterService.getDisplayDate(started)}
                      </li>
                      <li>
                        <b>End Time: </b> &nbsp;{DateFormaterService.getDisplayDate(ended)}
                      </li>
                    </ul>{reasonHtml}{xmlParameters(event.id)}
                  </div>
                case Full(_)                                    => errorMessage(Failure("Unconsistant policy update status"))
                case e: EmptyBox => errorMessage(e)
              }
              xml
            }

          case x: FailedDeployment =>
            "*" #> {
              val xml: NodeSeq = logDetailsService.getDeploymentStatusDetails(x.details) match {
                case Full(ErrorStatus(id, started, ended, failure)) =>
                  <div class="evloglmargin">
                    <h5>Failed policy update:</h5>
                    <ul class="evlogviewpad">
                      <li>
                        <b>ID: </b> &nbsp;{id}
                      </li>
                      <li>
                        <b>Start time: </b> &nbsp;{DateFormaterService.getDisplayDate(started)}
                      </li>
                      <li>
                        <b>End Time: </b> &nbsp;{DateFormaterService.getDisplayDate(ended)}
                      </li>
                      <li>
                        <b>Error stack trace: </b> &nbsp;{failure.messageChain}
                      </li>
                    </ul>{reasonHtml}{xmlParameters(event.id)}
                  </div>
                case Full(_)                                        => errorMessage(Failure("Unconsistant policy update status"))
                case e: EmptyBox => errorMessage(e)
              }
              xml
            }

          case x: AutomaticStartDeployement =>
            "*" #>
            <div class="evloglmargin">

                {xmlParameters(event.id)}
              </div>

          ////////// change authorized networks //////////

          case x: UpdatePolicyServer =>
            "*" #> {
              val xml: NodeSeq = logDetailsService.getUpdatePolicyServerDetails(x.details) match {
                case Full(details) =>
                  def networksToXML(nets: Seq[String]) = {
                    <ul>
                      {
                      nets.map(n => <li class="eventLogUpdatePolicy">
                        {n}
                      </li>)
                    }
                    </ul>
                  }

                  <div class="evloglmargin">
                    {
                    (
                      ".diffOldValue *" #> networksToXML(details.oldNetworks) &
                      ".diffNewValue *" #> networksToXML(details.newNetworks)
                    )(authorizedNetworksXML())
                  }{reasonHtml}{xmlParameters(event.id)}
                  </div>
                case e: EmptyBox => errorMessage(e)
              }
              xml
            }

          // Technique library reloaded

          case x: ReloadTechniqueLibrary =>
            "*" #> {
              val xml: NodeSeq = logDetailsService.getTechniqueLibraryReloadDetails(x.details) match {
                case Full(details) =>
                  <div class="evloglmargin">

                    <b>The Technique library was reloaded and following Techniques were updated: </b>
                    <ul>
                      {
                    details.map { technique =>
                      <li class="eventLogUpdatePolicy">
                          {s"${technique.name.value} (version ${technique.version.debugString})"}
                        </li>
                    }
                  }
                    </ul>{reasonHtml}{xmlParameters(event.id)}
                  </div>

                case e: EmptyBox => errorMessage(e)
              }
              xml
            }

          // Technique modified
          case x: ModifyTechnique        =>
            "*" #> {
              val xml: NodeSeq = logDetailsService.getTechniqueModifyDetails(x.details) match {
                case Full(modDiff) =>
                  <div class="evloglmargin">

                    <h5>Technique overview:</h5>
                    <ul class="evlogviewpad">
                      <li>
                        <b>Technique ID: </b>{modDiff.id.value}
                      </li>
                      <li>
                        <b>Name: </b>{modDiff.name}
                      </li>
                    </ul>{
                    (
                      "#isEnabled *" #> mapSimpleDiff(modDiff.modIsEnabled)
                    ).apply(liModDetailsXML("isEnabled", "Activation status"))
                  }{reasonHtml}{xmlParameters(event.id)}
                  </div>
                case e: EmptyBox => errorMessage(e)
              }
              xml
            }

          // Technique modified
          case x: DeleteTechnique        =>
            "*" #> {
              val xml: NodeSeq = logDetailsService.getTechniqueDeleteDetails(x.details) match {
                case Full(techDiff) =>
                  <div class="evloglmargin">

                    {techniqueDetails(techniqueDetailsXML, techDiff.technique)}{reasonHtml}{xmlParameters(event.id)}
                  </div>
                case e: EmptyBox => errorMessage(e)
              }
              xml
            }

          // archiving & restoring

          case x: ExportEventLog =>
            "*" #> {
              val xml: NodeSeq = logDetailsService.getNewArchiveDetails(x.details, x) match {
                case Full(gitArchiveId) =>
                  displayExportArchiveDetails(gitArchiveId, xmlParameters(event.id))
                case e: EmptyBox => errorMessage(e)
              }
              xml
            }

          case x: Rollback =>
            "*" #> {
              val xml: NodeSeq = logDetailsService.getRollbackDetails(x.details) match {
                case Full(eventLogs) =>
                  displayRollbackDetails(eventLogs, event.id.get)
                case e: EmptyBox =>
                  logger.warn(e)
                  errorMessage(e)
              }
              xml
            }

          case x: ImportEventLog =>
            "*" #> {
              val xml: NodeSeq = logDetailsService.getRestoreArchiveDetails(x.details, x) match {
                case Full(gitArchiveId) =>
                  displayImportArchiveDetails(gitArchiveId, xmlParameters(event.id))
                case e: EmptyBox => errorMessage(e)
              }
              xml
            }

          case x: ChangeRequestEventLog =>
            "*" #> {
              logDetailsService.getChangeRequestDetails(x.details) match {
                case Full(diff) =>
                  val (name, desc) = x.id match {
                    case None     => (Text(diff.changeRequest.info.name), Text(diff.changeRequest.info.description))
                    case Some(id) =>
                      val modName = displaySimpleDiff(diff.diffName, s"name${id}", diff.changeRequest.info.name)
                      val modDesc =
                        displaySimpleDiff(diff.diffDescription, s"description${id}", diff.changeRequest.info.description)
                      (modName, modDesc)
                  }
                  <div class="evloglmargin">
                    <h5>Change request details:</h5>
                    <ul class="evlogviewpad">
                      <li>
                        <b>Id: </b>{diff.changeRequest.id}
                      </li>
                      <li>
                        <b>Name: </b>{name}
                      </li>
                      <li>
                        <b>Description: </b>{desc}
                      </li>
                    </ul>
                  </div>
                case e: EmptyBox =>
                  logger.warn(e)
                  errorMessage(e)
              }
            }

          case x: WorkflowStepChanged =>
            "*" #> {
              logDetailsService.getWorkflotStepChange(x.details) match {
                case Full(step) =>
                  <div class="evloglmargin">
                    <h5>Change request status modified:</h5>
                    <ul class="evlogviewpad">
                      <li>
                        <b>Id: </b>{step.id}
                      </li>
                      <li>
                        <b>From status: </b>{step.from}
                      </li>
                      <li>
                        <b>To status: </b>{step.to}
                      </li>{reasonHtml}
                    </ul>
                  </div>
                case e: EmptyBox =>
                  logger.warn(e)
                  errorMessage(e)
              }
            }

          // Global parameters

          case x: AddGlobalParameter =>
            "*" #> {
              logDetailsService.getGlobalParameterAddDetails(x.details) match {
                case Full(globalParamDiff) =>
                  <div class="evloglmargin">

                    {generatedByChangeRequest}{globalParameterDetails(globalParamDetailsXML, globalParamDiff.parameter)}{
                    reasonHtml
                  }{xmlParameters(event.id)}
                  </div>
                case e: EmptyBox =>
                  logger.warn(e)
                  errorMessage(e)
              }
            }

          case x: DeleteGlobalParameter =>
            "*" #> {
              logDetailsService.getGlobalParameterDeleteDetails(x.details) match {
                case Full(globalParamDiff) =>
                  <div class="evloglmargin">

                    {generatedByChangeRequest}{globalParameterDetails(globalParamDetailsXML, globalParamDiff.parameter)}{
                    reasonHtml
                  }{xmlParameters(event.id)}
                  </div>
                case e: EmptyBox =>
                  logger.warn(e)
                  errorMessage(e)
              }
            }

          case mod: ModifyGlobalParameter =>
            "*" #> {
              logDetailsService.getGlobalParameterModifyDetails(mod.details) match {
                case Full(modDiff) =>
                  <div class="evloglmargin">

                    {generatedByChangeRequest}<h5>Global Parameter overview:</h5>
                    <ul class="evlogviewpad">
                      <li>
                        <b>Global Parameter name: </b>{modDiff.name}
                      </li>
                    </ul>{
                    (
                      "#name" #> modDiff.name &
                      "#value" #> mapSimpleDiff(modDiff.modValue) &
                      "#description *" #> mapSimpleDiff(modDiff.modDescription)
                    )(globalParamModDetailsXML)
                  }{reasonHtml}{xmlParameters(event.id)}
                  </div>
                case e: EmptyBox =>
                  logger.warn(e)
                  errorMessage(e)
              }
            }

          case x: CreateAPIAccountEventLog =>
            "*" #> {
              logDetailsService.getApiAccountAddDetails(x.details) match {
                case Full(apiAccountDiff) =>
                  <div class="evloglmargin">

                    {generatedByChangeRequest}{apiAccountDetails(apiAccountDetailsXML, apiAccountDiff.apiAccount)}{reasonHtml}{
                    xmlParameters(event.id)
                  }
                  </div>
                case e: EmptyBox =>
                  logger.warn(e)
                  errorMessage(e)
              }
            }

          case x: DeleteAPIAccountEventLog =>
            "*" #> {
              logDetailsService.getApiAccountDeleteDetails(x.details) match {
                case Full(apiAccountDiff) =>
                  <div class="evloglmargin">

                    {generatedByChangeRequest}{apiAccountDetails(apiAccountDetailsXML, apiAccountDiff.apiAccount)}{reasonHtml}{
                    xmlParameters(event.id)
                  }
                  </div>
                case e: EmptyBox =>
                  logger.warn(e)
                  errorMessage(e)
              }
            }

          case mod: ModifyAPIAccountEventLog =>
            "*" #> {
              logDetailsService.getApiAccountModifyDetails(mod.details) match {
                case Full(apiAccountDiff) =>
                  <div class="evloglmargin">

                    {generatedByChangeRequest}<h5>API account overview:</h5>
                    <ul class="evlogviewpad">
                      <li>
                        <b>Account ID: </b>{apiAccountDiff.id.value}
                      </li>
                    </ul>{
                    (
                      "#name" #> mapSimpleDiff(apiAccountDiff.modName) &
                      "#token" #> mapSimpleDiff(apiAccountDiff.modToken) &
                      "#description *" #> mapSimpleDiff(apiAccountDiff.modDescription) &
                      "#isEnabled *" #> mapSimpleDiff(apiAccountDiff.modIsEnabled) &
                      "#tokenGenerationDate *" #> mapSimpleDiff(apiAccountDiff.modTokenGenerationDate) &
                      "#expirationDate *" #> mapSimpleDiffT[Option[DateTime]](
                        apiAccountDiff.modExpirationDate,
                        _.fold("")(_.toString(ISODateTimeFormat.dateTime()))
                      ) &
                      "#accountKind *" #> mapSimpleDiff(apiAccountDiff.modAccountKind) &
                      // make list of ACL unsderstandable
                      "#acls *" #> mapSimpleDiff(apiAccountDiff.modAccountAcl.map(o => {
                        val f = (l: List[ApiAclElement]) => {
                          l.sortBy(_.path.value)
                            .map(x => s"[${x.actions.map(_.name.toUpperCase()).mkString(",")}] ${x.path.value}")
                            .mkString(" | ")
                        }
                        SimpleDiff(f(o.oldValue), f(o.newValue))
                      }))
                    )(apiAccountModDetailsXML)
                  }{reasonHtml}{xmlParameters(event.id)}
                  </div>
                case e: EmptyBox =>
                  logger.warn(e)
                  errorMessage(e)
              }
            }

          case mod: ModifyGlobalProperty =>
            "*" #> {
              logDetailsService.getModifyGlobalPropertyDetails(mod.details) match {
                case Full((oldProp, newProp)) =>
                  val diff = SimpleDiff(oldProp.value, newProp.value)
                  <div class="evloglmargin">
                    <h5>Global property overview:</h5>
                    <ul class="evlogviewpad">
                      <li>
                        <b>Name: </b>{mod.propertyName}
                      </li>
                      <li>
                        <b>Old Value: </b>
                        <span class="diffOldValue">
                          {diff.oldValue}
                        </span>
                      </li>
                      <li>
                        <b>New Value: </b>
                        <span class="diffNewValue">
                          {diff.newValue}
                        </span>
                      </li>
                    </ul>{reasonHtml}{xmlParameters(event.id)}
                  </div>
                case e: EmptyBox =>
                  logger.warn(e)
                  errorMessage(e)
              }
            }

          // Node modification
          case mod: ModifyNode           =>
            "*" #> {
              logDetailsService.getModifyNodeDetails(mod.details) match {
                case Full(modDiff) =>
                  <div class="evloglmargin">

                    {generatedByChangeRequest}<h5>Node '
                    {modDiff.id.value}
                    ' modified:</h5>

                    <ul class="evlogviewpad">
                      <li>
                        <b>Node ID: </b>{modDiff.id.value}
                      </li>
                    </ul>{
                    mapComplexDiff(modDiff.modAgentRun, <b>Agent Run</b>) { (optAr: Option[AgentRunInterval]) =>
                      optAr match {
                        case None     =>
                          <span>No value</span>
                        case Some(ar) => agentRunDetails(ar)
                      }
                    }
                  }{
                    mapComplexDiff(modDiff.modHeartbeat, <b>Heartbeat</b>) { (optHb: Option[HeartbeatConfiguration]) =>
                      optHb match {
                        case None     =>
                          <span>No value</span>
                        case Some(hb) => heartbeatDetails(hb)
                      }
                    }
                  }<div id={"nodepropertiesdiff-" + event.id.getOrElse("unknown")}></div>{
                    mapComplexDiff(modDiff.modPolicyMode, <b>Policy Mode</b>) { (optMode: Option[PolicyMode]) =>
                      optMode match {
                        case None       =>
                          <span>Use global policy mode</span>
                        case Some(mode) =>
                          <span>
                            {mode.name}
                          </span>
                      }
                    }
                  }{mapComplexDiff(modDiff.modNodeState, <b>Node state</b>)(x => Text(x.name))}{
                    mapComplexDiff(modDiff.modKeyStatus, <b>Key status</b>)(x => Text(x.value))
                  }{mapComplexDiff(modDiff.modKeyValue, <b>Key value</b>)(x => Text(x.key))}{reasonHtml}{
                    xmlParameters(event.id)
                  }
                  </div>
                case e: EmptyBox =>
                  logger.warn(e)
                  errorMessage(e)
              }
            }
          case x:   PromoteNode          =>
            "*" #> {
              logDetailsService.getPromotedNodeToRelayDetails(x.details) match {
                case Full(details) =>
                  <div class="evloglmargin">

                    <h5>Node promoted to relay overview:</h5>{promotedNodeDetails(details._1, details._2)}{reasonHtml}{
                    xmlParameters(event.id)
                  }
                  </div>
                case e: EmptyBox => errorMessage(e)
              }
            }

          // Secret
          case x:   AddSecret            =>
            "*" #> {
              logDetailsService.getSecretAddDetails(x.details) match {
                case Full(secretDiff) =>
                  <div class="evloglmargin">

                    {secretDetails(secretXML, secretDiff.secret)}{reasonHtml}{xmlParameters(event.id)}
                  </div>
                case e: EmptyBox =>
                  logger.warn(e)
                  errorMessage(e)
              }
            }

          case x: DeleteSecret =>
            "*" #> {
              logDetailsService.getSecretDeleteDetails(x.details) match {
                case Full(secretDiff) =>
                  <div class="evloglmargin">

                    {secretDetails(secretXML, secretDiff.secret)}{reasonHtml}{xmlParameters(event.id)}
                  </div>
                case e: EmptyBox =>
                  logger.warn(e)
                  errorMessage(e)
              }
            }

          case mod: ModifySecret =>
            "*" #> {
              logDetailsService.getSecretModifyDetails(mod.details) match {
                case Full(modDiff) =>
                  val hasChanged = {
                    if (modDiff.modValue) {
                      Some(<div id="value">
                        <br/> <b>The value has been changed</b>
                      </div> <br/>)
                    } else {
                      None
                    }
                  }
                  <div class="evloglmargin">

                    <h5>Secret overview:</h5>
                    <ul class="evlogviewpad">
                      <li>
                        <b>Secret name: </b>{modDiff.name}
                      </li>
                      <li>
                        <b>Secret description: </b>{modDiff.description}
                      </li>
                    </ul>{
                    (
                      "#name" #> modDiff.name &
                      "#value" #> hasChanged &
                      "#description" #> mapSimpleDiff(modDiff.modDescription)
                    )(secretModDetailsXML)
                  }{reasonHtml}{xmlParameters(event.id)}
                  </div>
                case e: EmptyBox =>
                  logger.warn(e)
                  errorMessage(e)
              }
            }
          // other case: do not display details at all
          case _ => "*" #> ""

        })(event.details)
      }
      </div>

    }).merge.runNow

  }

  def nodePropertiesDiff(event: EventLog): Option[SimpleDiff[JObject]] = {
    event match {
      case m: ModifyNode =>
        logDetailsService
          .getModifyNodeDetails(event.details)
          .toOption
          .flatMap(_.modProperties)
          .map(_.map(_.toDataJson))
      case _ => None
    }
  }

  private def agentRunDetails(ar: AgentRunInterval): NodeSeq = {
    (
      "#override" #> ar.overrides.map(_.toString()).getOrElse("false")
      & "#interval" #> ar.interval
      & "#startMinute" #> ar.startMinute
      & "#startHour" #> ar.startHour
      & "#splaytime" #> ar.splaytime
    ).apply(
      <ul class="evlogviewpad">
        <li><b>Override global value: </b><value id="override"/></li>
        <li><b>Period: </b><value id="interval"/></li>
        <li><b>Start at minute: </b><value id="startMinute"/></li>
        <li><b>Start at hour: </b><value id="startHour"/></li>
        <li><b>Splay time: </b><value id="splaytime"/></li>
      </ul>
    )
  }

  private def heartbeatDetails(hb: HeartbeatConfiguration):                                      NodeSeq = {
    (
      "#override" #> hb.overrides
      & "#interval" #> hb.heartbeatPeriod
    ).apply(
      <ul class="evlogviewpad">
        <li><b>Override global value: </b><value id="override"/></li>
        <li><b>Period: </b><value id="interval"/></li>
      </ul>
    )
  }

  private def displaySimpleDiff(
      diff:    Option[SimpleDiff[String]],
      name:    String,
      default: String
  ): NodeSeq = displaySimpleDiff(diff, name).getOrElse(Text(default))

  private def displaySimpleDiff(
      diff: Option[SimpleDiff[String]],
      name: String
  ): Option[NodeSeq] = diff.map(value => displayFormDiff(value, name))

  private def displayFormDiff(
      diff: SimpleDiff[String],
      name: String
  ): NodeSeq = {
    <pre id={s"result${name}"} style="white-space: pre-line; word-break: break-word; overflow: auto;">
        <del>-{diff.oldValue}</del>
        <ins>+{diff.newValue}</ins>
      </pre>
  }
  private def displaydirectiveInnerFormDiff(diff: SimpleDiff[SectionVal], eventId: Option[Int]): NodeSeq = {
    eventId match {
      case None     => NodeSeq.Empty
      case Some(id) => displayFormDiff(diff.map(s => SectionVal.toXml(s).toString), id.toString)
    }
  }

  private def displayExportArchiveDetails(gitArchiveId: GitArchiveId, rawData: NodeSeq) = {
    <div class="evloglmargin">
      <h5>Details of the new archive:</h5>
      <ul class="evlogviewpad">
        <li><b>Git path of the archive: </b>{gitArchiveId.path.value}</li>
        <li><b>Commit ID (hash): </b>{gitArchiveId.commit.value}</li>
        <li><b>Commiter name: </b>{gitArchiveId.commiter.getName}</li>
        <li><b>Commiter email: </b>{gitArchiveId.commiter.getEmailAddress}</li>
      </ul>
      {rawData}
    </div>
  }

  private def displayImportArchiveDetails(gitCommitId: GitCommitId, rawData: NodeSeq) = {
    <div class="evloglmargin">
      <h5>Details of the restored archive:</h5>
      <ul class="evlogviewpad">
        <li><b>Commit ID (hash): </b>{gitCommitId.value}</li>
      </ul>
      {rawData}
    </div>
  }

  private def nodeGroupDetails(nodes: Set[NodeId]): NodeSeq = {
    val res = nodes.toSeq match {
      case Seq() => NodeSeq.Empty
      case t     =>
        nodes.toSeq
          .map(id => linkUtil.createNodeLink(id))
          .reduceLeft[NodeSeq]((a, b) => a ++ <span class="groupSeparator" /> ++ b)
    }
    (
      ".groupSeparator" #> ", "
    ).apply(res)

  }

  private def ruleDetails(xml: NodeSeq, rule: Rule, groupLib: FullNodeGroupCategory, rootRuleCategory: RuleCategory)(implicit
      qc: QueryContext
  ) = {
    (
      "#ruleID" #> rule.id.serialize &
      "#ruleName" #> rule.name &
      "#category" #> diffDisplayer.displayRuleCategory(rootRuleCategory, rule.categoryId, None) &
      "#target" #> diffDisplayer.displayRuleTargets(rule.targets.toSeq, rule.targets.toSeq, groupLib) &
      "#policy" #> diffDisplayer.displayDirectiveChangeList(rule.directiveIds.toSeq, rule.directiveIds.toSeq) &
      "#isEnabled" #> rule.isEnabled &
      "#isSystem" #> rule.isSystem &
      "#shortDescription" #> rule.shortDescription &
      "#longDescription" #> rule.longDescription
    )(xml)
  }

  private def directiveDetails(xml: NodeSeq, ptName: TechniqueName, directive: Directive, sectionVal: SectionVal) = (
    "#directiveID" #> directive.id.serialize &
      "#directiveName" #> directive.name &
      "#ptVersion" #> directive.techniqueVersion.debugString &
      "#ptName" #> ptName.value &
      "#ptVersion" #> directive.techniqueVersion.debugString &
      "#ptName" #> ptName.value &
      "#priority" #> directive.priority &
      "#isEnabled" #> directive.isEnabled &
      "#isSystem" #> directive.isSystem &
      "#shortDescription" #> directive.shortDescription &
      "#longDescription" #> directive.longDescription
  )(xml)

  private def groupDetails(xml: NodeSeq, group: NodeGroup) = (
    "#groupID" #> group.id.withDefaultRev.serialize &
      "#groupName" #> group.name &
      "#shortDescription" #> group.description &
      "#shortDescription" #> group.description &
      "#query" #> (group.query match {
        case None    => Text("None")
        case Some(q) => Text(q.toJSONString)
      }) &
      "#isDynamic" #> group.isDynamic &
      "#nodes" #> ({
        val l = group.serverList.toList
        l match {
          case Nil => Text("None")
          case _   =>
            l
              .map(id => <a href={linkUtil.nodeLink(id)}>{id.value}</a>)
              .reduceLeft[NodeSeq]((a, b) => a ++ <span>,&nbsp;</span> ++ b)
        }
      }) &
      "#isEnabled" #> group.isEnabled &
      "#isSystem" #> group.isSystem
  )(xml)

  private def techniqueDetails(xml: NodeSeq, technique: ActiveTechnique) = (
    "#techniqueID" #> technique.id.value &
      "#techniqueName" #> technique.techniqueName.value &
      "#isEnabled" #> technique.isEnabled &
      "#isSystem" #> technique.policyTypes.types.toList.map(_.value).sorted.mkString(",")
  )(xml)

  private def globalParameterDetails(xml: NodeSeq, globalParameter: GlobalParameter) = (
    "#name" #> globalParameter.name &
      "#value" #> globalParameter.valueAsString &
      "#description" #> globalParameter.description
  )(xml)

  private def apiAccountDetails(xml: NodeSeq, apiAccount: ApiAccount) = {
    val (expiration, kind, authz) = apiAccount.kind match {
      case ApiAccountKind.System                                    => ("N/A", "system", Text("N/A"))
      case ApiAccountKind.User                                      => ("N/A", "user", Text("N/A"))
      case ApiAccountKind.PublicApi(authorizations, expirationDate) =>
        (
          expirationDate.map(DateFormaterService.getDisplayDate).getOrElse("N/A"),
          "API",
          authorizations match {
            case ApiAuthorization.None     => Text("none")
            case ApiAuthorization.RW       => Text("read/write")
            case ApiAuthorization.RO       => Text("read only")
            case ApiAuthorization.ACL(acl) => <div> ACL <ul>{acl.map(x => <li>{x.display}</li>)}</ul></div>
          }
        )
    }

    ("#id" #> apiAccount.id.value &
    "#name" #> apiAccount.name.value &
    "#token" #> apiAccount.token.value &
    "#description" #> apiAccount.description &
    "#isEnabled" #> apiAccount.isEnabled &
    "#creationDate" #> DateFormaterService.getDisplayDate(apiAccount.creationDate) &
    "#tokenGenerationDate" #> DateFormaterService.getDisplayDate(apiAccount.tokenGenerationDate) &
    "#expirationDate" #> expiration &
    "#accountKind" #> kind &
    "#authz" #> authz)(xml)
  }

  private def mapSimpleDiffT[T](opt: Option[SimpleDiff[T]], t: T => String) = opt.map { diff =>
    ".diffOldValue *" #> t(diff.oldValue) &
    ".diffNewValue *" #> t(diff.newValue)
  }

  private def mapSimpleDiff[T](opt: Option[SimpleDiff[T]]) = mapSimpleDiffT(opt, (x: T) => x.toString)

  private def mapSimpleDiff[T](opt: Option[SimpleDiff[T]], id: DirectiveId) = opt.map { diff =>
    ".diffOldValue *" #> diff.oldValue.toString &
    ".diffNewValue *" #> diff.newValue.toString &
    "#directiveID" #> id.serialize
  }

  private def mapComplexDiff[T](opt: Option[SimpleDiff[T]], title: NodeSeq)(display: T => NodeSeq) = {
    opt match {
      case None       => NodeSeq.Empty
      case Some(diff) =>
        <div class="diffElem">
          {title}
          <ul class="evlogviewpad">
            <li><b>Old value:&nbsp;</b><span class="diffOldValue">{display(diff.oldValue)}</span></li>
            <li><b>New value:&nbsp;</b><span class="diffNewValue">{display(diff.newValue)}</span></li>
          </ul>
        </div>
    }
  }

  /*
   * Special diff for tags as a list of key-value pair using a simple line diff against "key=value" tag format
   */
  private def tagsDiff(opt: Option[SimpleDiff[Tags]]) = {
    def tagsToLines(tags: Tags): String = {
      tags.tags.toList.sortBy(_.name.value).map(t => s" ${t.name.value}=${t.value.value} ").mkString("\n")
    }

    val linesDiff = opt.map(diff => SimpleDiff(tagsToLines(diff.oldValue), tagsToLines(diff.newValue)))
    displaySimpleDiff(linesDiff, "tags")
  }

  private def promotedNodeDetails(id: NodeId, name: String) = (
    "#nodeID" #> id.value &
      "#nodeName" #> name
  )(
    <ul class="evlogviewpad">
      <li><b>Node ID: </b><value id="nodeID"/></li>
      <li><b>Hostname: </b><value id="nodeName"/></li>
    </ul>
  )
  private def nodeDetails(details: InventoryLogDetails)     = (
    "#nodeID" #> details.nodeId.value &
      "#nodeName" #> details.hostname &
      "#os" #> details.fullOsName &
      "#version" #> DateFormaterService.getDisplayDate(details.inventoryVersion)
  )(
    <ul class="evlogviewpad">
      <li><b>Node ID: </b><value id="nodeID"/></li>
      <li><b>Name: </b><value id="nodeName"/></li>
      <li><b>Operating System: </b><value id="os"/></li>
      <li><b>Date inventory last received: </b><value id="version"/></li>
    </ul>
  )

  private def secretDetails(xml: NodeSeq, secret: Secret) = (
    "#name" #> secret.name &
      "#description" #> secret.description
  )(xml)

  private val crDetailsXML = {
    <div>
      <h5>Rule overview:</h5>
      <ul class="evlogviewpad">
        <li><b>ID:&nbsp;</b><value id="ruleID"/></li>
        <li><b>Name:&nbsp;</b><value id="ruleName"/></li>
        <li><b>Category:&nbsp;</b><value id="category"/></li>
        <li><b>Description:&nbsp;</b><value id="shortDescription"/></li>
        <li><b>Target:&nbsp;</b><value id="target"/></li>
        <li><b>Directives:&nbsp;</b><value id="policy"/></li>
        <li><b>Enabled:&nbsp;</b><value id="isEnabled"/></li>
        <li><b>System:&nbsp;</b><value id="isSystem"/></li>
        <li><b>Details:&nbsp;</b><value id="longDescription"/></li>
      </ul>
    </div>
  }

  private val piDetailsXML = {
    <div>
      <h5>Directive overview:</h5>
      <ul class="evlogviewpad">
        <li><b>ID:&nbsp;</b><value id="directiveID"/></li>
        <li><b>Name:&nbsp;</b><value id="directiveName"/></li>
        <li><b>Description:&nbsp;</b><value id="shortDescription"/></li>
        <li><b>Technique name:&nbsp;</b><value id="ptName"/></li>
        <li><b>Technique version:&nbsp;</b><value id="ptVersion"/></li>
        <li><b>Priority:&nbsp;</b><value id="priority"/></li>
        <li><b>Enabled:&nbsp;</b><value id="isEnabled"/></li>
        <li><b>System:&nbsp;</b><value id="isSystem"/></li>
        <li><b>Details:&nbsp;</b><value id="longDescription"/></li>
      </ul>
    </div>
  }

  private val groupDetailsXML = {
    <div>
      <h5>Group overview:</h5>
      <ul class="evlogviewpad">
        <li><b>ID: </b><value id="groupID"/></li>
        <li><b>Name: </b><value id="groupName"/></li>
        <li><b>Description: </b><value id="shortDescription"/></li>
        <li><b>Enabled: </b><value id="isEnabled"/></li>
        <li><b>Dynamic: </b><value id="isDynamic"/></li>
        <li><b>System: </b><value id="isSystem"/></li>
        <li><b>Query: </b><value id="query"/></li>
        <li><b>Node list: </b><value id="nodes"/></li>
      </ul>
    </div>
  }

  private val techniqueDetailsXML = {
    <div>
      <h5>Technique overview:</h5>
      <ul class="evlogviewpad">
        <li><b>ID:&nbsp;</b><value id="techniqueID"/></li>
        <li><b>Name:&nbsp;</b><value id="techniqueName"/></li>
        <li><b>Enabled:&nbsp;</b><value id="isEnabled"/></li>
        <li><b>System:&nbsp;</b><value id="isSystem"/></li>
      </ul>
    </div>
  }

  private val globalParamDetailsXML = {
    <div>
      <h5>Global Parameter overview:</h5>
      <ul class="evlogviewpad">
        <li><b>Name:&nbsp;</b><value id="name"/></li>
        <li><b>Value:&nbsp;</b><value id="value"/></li>
        <li><b>Description:&nbsp;</b><value id="description"/></li>
        <li><b>Overridable:&nbsp;</b><value id="overridable"/></li>
      </ul>
    </div>
  }

  private val apiAccountDetailsXML = {
    <div>
      <h5>API account overview:</h5>
      <ul class="evlogviewpad">
        <li><b>Rudder ID: </b><value id="id"/></li>
        <li><b>Name:&nbsp;</b><value id="name"/></li>
        <li><b>Token:&nbsp;</b><value id="token"/></li>
        <li><b>Description:&nbsp;</b><value id="description"/></li>
        <li><b>Enabled:&nbsp;</b><value id="isEnabled"/></li>
        <li><b>Creation date:&nbsp;</b><value id="creationDate"/></li>
        <li><b>Token Generation date:&nbsp;</b><value id="tokenGenerationDate"/></li>
        <li><b>Token Expiration date:&nbsp;</b><value id="expirationDate"/></li>
        <li><b>Account Kind:&nbsp;</b><value id="accountKind"/></li>
        <li><b>Authorization:&nbsp;</b><value id="authz"/></li>
      </ul>
    </div>
  }

  private val apiAccountModDetailsXML = {
    <xml:group>
      {liModDetailsXML("name", "Name")}
      {liModDetailsXML("token", "Token")}
      {liModDetailsXML("description", "Description")}
      {liModDetailsXML("isEnabled", "Enabled")}
      {liModDetailsXML("tokenGenerationDate", "Token Generation Date")}
      {liModDetailsXML("expirationDate", "Token Expiration Date")}
      {liModDetailsXML("accountKind", "Account Kind")}
      {liModDetailsXML("acls", "ACL list")}
    </xml:group>
  }

  private def liModDetailsXML(id: String, name: String) = (
    <div id={id}>
      <b>{name} changed: </b>
      <ul class="evlogviewpad">
        <li><b>Old value:&nbsp;</b><span class="diffOldValue">old value</span></li>
        <li><b>New value:&nbsp;</b><span class="diffNewValue">new value</span></li>
      </ul>
    </div>
  )

  private def liModDirectiveDetailsXML(id: String, name: String) = (
    <div id={id}>
      <b>{name} changed: </b>
      <ul class="evlogviewpad">
        <li><b>Differences: </b><div id="diff" /></li>
      </ul>
    </div>
  )

  private val groupModDetailsXML = {
    <xml:group>
      {liModDetailsXML("name", "Name")}
      {liModDetailsXML("shortDescription", "Description")}
      {liModDetailsXML("nodes", "Node list")}
      {liModDetailsXML("query", "Query")}
      {liModDetailsXML("isDynamic", "Dynamic group")}
      {liModDetailsXML("isEnabled", "Activation status")}
      {liModDetailsXML("isSystem", "System")}
    </xml:group>
  }

  private val crModDetailsXML = {
    <xml:group>
      {liModDetailsXML("name", "Name")}
      {liModDetailsXML("category", "Category")}
      {liModDetailsXML("shortDescription", "Description")}
      {liModDetailsXML("longDescription", "Details")}
      {liModDetailsXML("target", "Target")}
      {liModDetailsXML("policies", "Directives")}
      {liModDetailsXML("isEnabled", "Activation status")}
      {liModDetailsXML("isSystem", "System")}
    </xml:group>
  }

  private val piModDirectiveDetailsXML = {
    <xml:group>
      {liModDetailsXML("name", "Name")}
      {liModDetailsXML("shortDescription", "Description")}
      {liModDetailsXML("longDescription", "Details")}
      {liModDetailsXML("ptVersion", "Target")}
      {liModDetailsXML("policyMode", "Policy mode")}
      {liModDetailsXML("tags", "Tags")}
      {liModDirectiveDetailsXML("parameters", "Policy parameters")}
      {liModDetailsXML("priority", "Priority")}
      {liModDetailsXML("isEnabled", "Activation status")}
      {liModDetailsXML("isSystem", "System")}
    </xml:group>
  }

  private val globalParamModDetailsXML = {
    <xml:group>
      {liModDetailsXML("name", "Name")}
      {liModDetailsXML("value", "Value")}
      {liModDetailsXML("description", "Description")}
      {liModDetailsXML("overridable", "Overridable")}
    </xml:group>
  }

  private val secretXML = {
    <div>
      <h5>Secret overview:</h5>
      <ul class="evlogviewpad">
        <li><b>Name:&nbsp;</b><value id="name"/></li>
        <li><b>Description:&nbsp;</b><value id="description"/></li>
      </ul>
    </div>
  }

  private val secretModDetailsXML = {
    <xml:group>
      {liModDetailsXML("name", "Name")}
      {liModDetailsXML("value", "Value")}
      {liModDetailsXML("description", "Description")}
    </xml:group>
  }

  private def displayRollbackDetails(rollbackInfo: RollbackInfo, id: Int) = {
    val rollbackedEvents = rollbackInfo.rollbacked
    <div class="evloglmargin">
      <div style="width:50%; float:left;">
        <br/>
        <h5>Details of the rollback:</h5>
        <br/>
        <span>A rollback to {rollbackInfo.rollbackType} event
          {
      SHtml.a(
        () =>
          SetHtml("currentId%s".format(id), Text(rollbackInfo.target.id.toString)) &
          JsRaw("""$('#%1$s').dataTable().fnFilter("%2$s|%3$s",0,true,false);
                $("#cancel%3$s").show();
                scrollToElement('%2$s', ".rudder_col");
                if($('#%2$s').prop('open') != "opened")
                $('#%2$s').click();""".format("gridName", rollbackInfo.target.id, id)), // JsRaw OK, id is int
        Text(rollbackInfo.target.id.toString)
      )
    } has been completed.
        </span>
        <br/><br/>
        <b>Events that were rollbacked can be consulted in the table below.</b>
        <br/>
        <span>Those events are no longer applied by the configuration policy.</span>

        <br/>
        <table id={"rollbackTable%s".format(id)} class="display" cellspacing="0">
          <thead>
            <tr class="head">
              <th>ID</th>
              <th>Date</th>
              <th>Actor</th>
              <th>Event type</th>
            </tr>
          </thead>
          <tbody>
            {
      rollbackedEvents.map { ev =>
        <tr>
              <td>
                {
          SHtml.a(
            () =>
              SetHtml("currentId%s".format(id), Text(ev.id.toString)) &
              JsRaw("""$('#%1$s').dataTable().fnFilter("%2$s|%3$s",0,true,false);
                $("#cancel%3$s").show();
                scrollToElement('%2$s', ".rudder_col");
                if($('#%2$s').prop('open') != "opened")
                $('#%2$s').click();""".format("gridName", ev.id, id)),
            Text(ev.id.toString)
          )
        }
              </td>
              <td>{ev.date} </td>
              <td>{ev.author} </td>
              <td>{S.?("rudder.log.eventType.names." + ev.eventType)} </td>
            </tr>
      }
    }
          </tbody>
        </table>

        <br/>
        <div id={"cancel%s".format(id)} style="display:none"> the event <span id={
      "currentId%s".format(id)
    }/>  is displayed in the table below
          {
      SHtml.ajaxButton(
        "Clear display",
        () => Run("""$('#%s').dataTable().fnFilter("",0,true,false);
            $("#cancel%s").hide();""".format("gridName", id))
      )
    }
        </div>
        <br/>
      </div>
    </div> ++ Script(JsRaw(s"""
        $$('#rollbackTable${id}').dataTable({
            "asStripeClasses": [ 'color1', 'color2' ],
            "bAutoWidth": false,
            "bFilter" :true,
            "bPaginate" :true,
            "bLengthChange": true,
            "bStateSave": true,
                    "fnStateSave": function (oSettings, oData) {
                      localStorage.setItem( 'DataTables_rollbackTable${id}', JSON.stringify(oData) );
                    },
                    "fnStateLoad": function (oSettings) {
                      return JSON.parse( localStorage.getItem('DataTables_rollbackTable${id}') );
                    },
            "sPaginationType": "full_numbers",
            "bJQueryUI": false,
            "oLanguage": {
              "sSearch": ""
            },
            "aaSorting":[],
            "aoColumns": [
                { "sWidth": "100px" }
              , { "sWidth": "100px" }
              , { "sWidth": "100px" }
              , { "sWidth": "100px" }
            ],
            "sDom": '<"dataTables_wrapper_top"f>rt<"dataTables_wrapper_bottom"lip>'
          });
        """)) // JsRaw OK, id is int.
  }

  private def authorizedNetworksXML() = (
    <div>
      <b>Networks authorized on policy server were updated: </b>
      <table class="eventLogUpdatePolicy">
        <thead><tr><th>from:</th><th>to:</th></tr></thead>
        <tbody>
          <tr>
            <td><span class="diffOldValue">old value</span></td>
            <td><span class="diffNewValue">new value</span></td>
          </tr>
        </tbody>
      </table>
    </div>
  )

  trait RollBackAction {
    def name:   String
    def op:     String
    def action: (EventLog, PersonIdent, Seq[EventLog], EventLog) => Box[GitCommitId]
    def selectRollbackedEventsRequest(id: Int): String = s" id ${op} ${id} and modificationid IS NOT NULL"
  }

  case object RollbackTo extends RollBackAction {
    val name = "after"
    val op   = ">"
    def action: (EventLog, PersonIdent, Seq[EventLog], EventLog) => Box[GitCommitId] = modificationService.restoreToEventLog _
  }

  case object RollbackBefore extends RollBackAction {
    val name = "before"
    val op   = ">="
    def action: (EventLog, PersonIdent, Seq[EventLog], EventLog) => Box[GitCommitId] = modificationService.restoreBeforeEventLog _
  }

}
