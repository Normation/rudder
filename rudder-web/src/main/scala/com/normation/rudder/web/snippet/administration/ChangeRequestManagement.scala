/*
*************************************************************************************
* Copyright 2011-2013 Normation SAS
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

package com.normation.rudder.web.snippet.administration

import net.liftweb.common._
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.DispatchSnippet
import bootstrap.liftweb.RudderConfig
import net.liftweb.http._
import net.liftweb.http.js._
import JE._
import com.normation.rudder.domain.workflows._
import net.liftweb.util._
import net.liftweb.util.Helpers._
import net.liftweb.http.SHtml
import scala.xml.Text
import scala.xml.NodeSeq
import net.liftweb.http.SHtml
import com.normation.rudder.web.model.CurrentUser
import org.joda.time.DateTime
import com.normation.rudder.services.workflows.ChangeRequestService
import com.normation.rudder.web.components.DateFormaterService
import scala.xml.Node
import scala.xml.Elem
import com.normation.rudder.authorization.Edit
import com.normation.rudder.authorization.Read
import com.normation.rudder.web.services.JsTableData
import com.normation.rudder.web.services.JsTableLine
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.domain.workflows.WorkflowNodeId
import com.normation.rudder.domain.eventlog.ChangeRequestLogsFilter
import com.normation.eventlog.EventLog
import net.liftweb.http.SHtml.SelectableOption

class ChangeRequestManagement extends DispatchSnippet with Loggable {

  private[this] val uuidGen = RudderConfig.stringUuidGenerator
  private[this] val roCrRepo = RudderConfig.roChangeRequestRepository
  private[this] val workflowService = RudderConfig.workflowService
  private[this] val changeRequestEventLogService = RudderConfig.changeRequestEventLogService
  private[this] val workflowLoggerService = RudderConfig.workflowEventLogService
  private[this] val changeRequestTableId = "changeRequestTable"
  private[this] val currentUser = CurrentUser.checkRights(Read("validator")) || CurrentUser.checkRights(Read("deployer"))

  private[this] val initFilter : Box[String] = S.param("filter").map(_.replace("_", " "))

  /*
   *  { "name" : Change request name [String]
   *   , "id" : Change request id [String]
   *   , "step" : Change request validation step [String]
   *   , "creator" : Name of the user that has created the change Request [String]
   *   , "lastModification" : date of last modification [ String ]
   *   }
   */
  case class ChangeRequestLine (
      changeRequest : ChangeRequest
    , workflowStateMap: Map[ChangeRequestId,WorkflowNodeId]
    , eventsMap : Map[ChangeRequestId, EventLog]
  ) extends JsTableLine {
    val date = eventsMap.get(changeRequest.id).map(event => DateFormaterService.getFormatedDate(event.creationDate)).getOrElse("Unknown")

    val json = {
      JsObj(
          "id" -> changeRequest.id.value
        , "name" -> changeRequest.info.name
        , "creator" -> changeRequest.owner
        , "step" -> workflowStateMap.get(changeRequest.id).map(_.value).getOrElse("Unknown")
        , "lastModification" -> date
      )
    }
  }

  def getLines() = {
    val changeRequests = if (currentUser) roCrRepo.getAll else roCrRepo.getByContributor(CurrentUser.getActor)
    JsTableData(changeRequests match {
      case Full(changeRequests) =>
        
        val eventMap = getLastEventsMap

        val workflowStateMap : Map[ChangeRequestId,WorkflowNodeId]= workflowService.getAllChangeRequestsStep match {
          case Full(stateMap) => stateMap
          case eb:EmptyBox =>
            val fail = eb ?~! "Could not find change requests state"
            logger.error(fail.messageChain)
            Map()
        }
        changeRequests.map(ChangeRequestLine(_,workflowStateMap,eventMap)).toList
      case eb:EmptyBox =>
        val fail = eb ?~! s"Could not get change requests because of : ${eb}"
        logger.error(fail.msg)
        Nil
    } )
  }
  def dataTableInit = {
    val refresh = AnonFunc( SHtml.ajaxInvoke(() => JsRaw(s"refreshTable('${changeRequestTableId}',${getLines.json.toJsCmd})")))

    val filter = initFilter match {
      case Full(filter) => s"$$('#${changeRequestTableId}').dataTable().fnFilter('${filter}',1,true,false,true);"
      case eb:EmptyBox => s"$$('#${changeRequestTableId}').dataTable().fnFilter('pending',1,true,false,true);"
    }
    s"""
      var refreshCR = ${refresh.toJsCmd};
      createChangeRequestTable('${changeRequestTableId}',[], '${S.contextPath}', refreshCR)
      ${filter};
      refreshCR();
    """

  }

  /**
   * Get all events, merge them via a mutMap (we only want to keep the most recent event)
   */
  private[this] def getLastEventsMap = {
    val CREventsMap : Map[ChangeRequestId,EventLog] = changeRequestEventLogService.getLastCREvents match {
      case Full(map) => map
      case eb:EmptyBox =>
        val fail = eb ?~! "Could not find last Change requests events requests state"
        logger.error(fail.messageChain)
        Map()
    }
    val workflowEventsMap : Map[ChangeRequestId,EventLog] = workflowLoggerService.getLastWorkflowEvents match {
      case Full(map) => map
      case eb:EmptyBox =>
        val fail = eb ?~! "Could not find last Change requests events requests state"
        logger.error(fail.messageChain)
        Map()
    }

    import scala.collection.mutable.{Map => MutMap}

    val eventMap = MutMap() ++ CREventsMap

    for {
      (crId, event) <- workflowEventsMap
    } {
      eventMap.get(crId) match {
          case Some(currentEvent) if (currentEvent.creationDate isAfter event.creationDate) =>
          case _ => eventMap.update(crId, event)
        }
    }

    eventMap.toMap
  }


  def dispatch = {
    case "filter" =>
      xml => ("#actualFilter *" #> statusFilter).apply(xml)
    case "display" => xml =>
      xml ++
      Script(OnLoad(JsRaw(dataTableInit)))
  }

  def statusFilter = {

    val values =  workflowService.stepsValue.map(_.value)
    val selectValues =  values.map(x=> (x,x))
    var value = ""

    val filterFunction =
      s"""var filter = [];
          var selected = $$(this).find(":selected")
          if (selected.size() > 0) {
            selected.each(function () {
              filter.push($$(this).attr("value"));
            } );

            $$('#${changeRequestTableId}').dataTable().fnFilter(filter.join("|"),1,true,false,true);
          }
          else {
            // No filter, display nothing
            $$('#${changeRequestTableId}').dataTable().fnFilter(".",1);
          }"""
    val onChange = ("onchange" -> JsRaw(filterFunction))

    def filterForm (select:Elem,link:String, transform: String => NodeSeq) = {
      val submit =
          SHtml.a(Text(link),JsRaw(s"$$('.expand').click();"), ("style"," float:right;font-size:9px;margin-top:12px; margin-left: 5px;")) ++
          SHtml.ajaxSubmit(
              link
            , () => SetHtml("actualFilter",transform(value))
            , ("class","expand")
            , ("style","margin: 5px 10px; float:right; height:15px; width:18px;  padding: 0; border-radius:25px; display:none")
         ) ++ Script(JsRaw("correctButtons()"))

      SHtml.ajaxForm(
        <b style="float:left; margin: 5px 10px">Status:</b> ++
        select % onChange  ++ submit
    )
    }
    def unexpandedFilter(default:String):NodeSeq = {
      val multipleValues = ("","All") :: ("Pending","Open") :: ("^(?!Pending)","Closed") :: Nil
      val select :Elem =SHtml.select(
          (multipleValues ::: selectValues).map{ case (a,b) => SelectableOption(a,b)}
        , Full(default)
        , list => value = list
        , ("style","width:auto;")
      )
      (s"value='${default}' [selected]" #> "selected").apply(
              ("select *" #> {<optgroup label="Multiple" style="margin-bottom:10px" value="" >{multipleValues.map{case (value,label) => <option value={value} style="margin-left:10px">{label}</option>}}</optgroup>++
               <optgroup label="Single">{selectValues.map{case (value,label) => <option value={value} style="margin-left:10px">{label}</option>}}</optgroup> }  ).apply(
      filterForm(select,"more",expandedFilter)))
  }

    def expandedFilter(default:String) = {
      val extendedDefault =
        default match {
          case ""     =>  values
          case "Pending" => values.filter(_.contains("Pending"))
          case "^(?!Pending)" => values.filterNot(_.contains("Pending"))
          case default if (values.exists(_ == default)) =>  List(default)
          case _         =>  Nil
        }

      def computeDefault(selectedValues:List[String]) = selectedValues match {
          case allValues if allValues.size==4 => ""
          case value :: Nil => value
          case openValues if openValues.forall(_.contains("Pending")) => "Pending"
          case closedValues if closedValues.forall(!_.contains("Pending")) => "^(?!Pending)"
          case _ => selectedValues.head
      }

      val multiSelect =  SHtml.multiSelect(
            selectValues
          , extendedDefault
          , list => value = computeDefault(list)
          , ("style","width:auto;padding-right:3px;")
        )
      filterForm(multiSelect,"less",unexpandedFilter)
    }

  unexpandedFilter(initFilter.getOrElse("Pending"))
  }
}
