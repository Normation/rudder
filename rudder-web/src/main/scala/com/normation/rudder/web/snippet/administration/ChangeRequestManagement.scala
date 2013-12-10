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

class ChangeRequestManagement extends DispatchSnippet with Loggable {

  private[this] val uuidGen = RudderConfig.stringUuidGenerator
  private[this] val roCrRepo = RudderConfig.roChangeRequestRepository
  private[this] val workflowService = RudderConfig.workflowService
  private[this] val changeRequestEventLogService = RudderConfig.changeRequestEventLogService
  private[this] val workflowLoggerService = RudderConfig.workflowEventLogService
  private[this] val changeRequestTableId = "ChangeRequestId"
  private[this] val currentUser = CurrentUser.checkRights(Read("validator")) || CurrentUser.checkRights(Read("deployer"))

  private[this] val initFilter : Box[String] = S.param("filter").map(_.replace("_", " "))

  val dataTableInit =
    """
     jQuery.extend( jQuery.fn.dataTableExt.oSort, {
        "num-html-pre": function ( a ) {
           var x = String(a).replace( /<[\s\S]*?>/g, "" );
           return parseFloat( x );
        },

        "num-html-asc": function ( a, b ) {
           return ((a < b) ? -1 : ((a > b) ? 1 : 0));
        },

        "num-html-desc": function ( a, b ) {
           return ((a < b) ? 1 : ((a > b) ? -1 : 0));
        }
     } );
    """ +
    s"""$$('#${changeRequestTableId}').dataTable( {
          "asStripeClasses": [ 'color1', 'color2' ],
          "bAutoWidth": false,
          "bFilter" : true,
          "bPaginate" : true,
          "bLengthChange": true,
          "bStateSave": true,
          "sCookiePrefix": "Rudder_DataTables_",
          "sPaginationType": "full_numbers",
          "bJQueryUI": true,
          "oLanguage": {
            "sSearch": ""
          },
          "sDom": '<"dataTables_wrapper_top"fl>rt<"dataTables_wrapper_bottom"ip>',
          "aaSorting": [[ 0, "asc" ]],
          "aoColumns": [
            { "sWidth": "20px" , "sType": "num-html"},
            { "sWidth": "40px" },
            { "sWidth": "100px" },
            { "sWidth": "40px" },
            { "sWidth": "40px" }
          ],
        } );
        $$('.dataTables_filter input').attr("placeholder", "Search");

        ${initFilter match {
          case Full(filter) => s"$$('#${changeRequestTableId}').dataTable().fnFilter('${filter}',1,true,false,true);"
          case eb:EmptyBox => s"$$('#${changeRequestTableId}').dataTable().fnFilter('pending',1,true,false,true);"
          }
        }"""


  def CRLine(cr: ChangeRequest)= {
    <tr>
      <td id="crId">
         {SHtml.a(() => S.redirectTo(s"/secure/utilities/changeRequest/${cr.id}"), Text(cr.id.value.toString))}
      </td>
      <td id="crStatus">
         {workflowService.findStep(cr.id).getOrElse("Unknown")}
      </td>
      <td id="crName">
         {cr.info.name}
      </td>
      <td id="crOwner">
         { cr.owner }
      </td>
      <td id="crDate">
         {(changeRequestEventLogService.getLastLog(cr.id),workflowLoggerService.getLastLog(cr.id)) match {
           case (Full(Some(crLog)),Full(Some(wfLog))) =>
             if (crLog.creationDate.isAfter(wfLog.creationDate))
               DateFormaterService.getFormatedDate(crLog.creationDate)
             else
               DateFormaterService.getFormatedDate(wfLog.creationDate)
           case (Full(Some(crLog)),_) => DateFormaterService.getFormatedDate(crLog.creationDate)
           case (_,Full(Some(wfLog))) => DateFormaterService.getFormatedDate(wfLog.creationDate)
           case (_,_) => "Error while fetching last action Date"
         }}
      </td>
   </tr>

  }
  def dispatch = {
    case "filter" =>
      xml => ("#actualFilter *" #> statusFilter).apply(xml)
    case "display" => xml =>
      ( "#crBody" #> {
        val changeRequests = if (currentUser) roCrRepo.getAll else roCrRepo.getByContributor(CurrentUser.getActor)
        changeRequests match {
        case Full(changeRequests) => changeRequests.flatMap(CRLine(_))
        case eb:EmptyBox => val fail = eb ?~! s"Could not get change requests because of : ${eb}"
        logger.error(fail.msg)
        <error>{fail.msg}</error>
      }  }).apply(xml) ++
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
          multipleValues ::: selectValues
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
