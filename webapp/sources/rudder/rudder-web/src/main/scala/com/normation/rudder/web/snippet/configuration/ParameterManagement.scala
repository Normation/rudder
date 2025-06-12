/*
 *************************************************************************************
 * Copyright 2013 Normation SAS
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
import com.normation.rudder.AuthorizationType
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.PropertyProvider
import com.normation.rudder.domain.properties.Visibility
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.services.workflows.GlobalParamChangeRequest
import com.normation.rudder.services.workflows.GlobalParamModAction
import com.normation.rudder.services.workflows.WorkflowService
import com.normation.rudder.users.CurrentUser
import com.normation.rudder.web.components.popup.CreateOrUpdateGlobalParameterPopup
import com.normation.rudder.web.snippet.WithNonce
import net.liftweb.common.*
import net.liftweb.http.*
import net.liftweb.http.SHtml.*
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.JsRaw
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.*
import net.liftweb.util.Helpers.*
import scala.xml.*

class ParameterManagement extends DispatchSnippet with Loggable {

  private val roParameterService = RudderConfig.roParameterService

  private val gridName      = "globalParametersGrid"
  private val gridContainer = "ParamGrid"
  private val linkUtil      = RudderConfig.linkUtil

  // the current GlobalParameterForm component
  private val parameterPopup = new LocalSnippet[CreateOrUpdateGlobalParameterPopup]

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = {
    case "display" => { _ => display()(using CurrentUser.queryContext) }
  }

  def display()(implicit qc: QueryContext): NodeSeq = {
    (for {
      seq <- roParameterService.getAllGlobalParameters()
    } yield {
      seq.filterNot(_.visibility == Visibility.Hidden)
    }) match {
      case Full((seq)) => displayGridParameters(seq, gridName)
      case eb: EmptyBox =>
        val e = eb ?~! "Error when trying to get global properties"
        logger.error(s"Error when trying to display global properties, casue is: ${e.messageChain}")
        <div class="error">{e.msg}</div>
    }
  }

  def displayGridParameters(params: Seq[GlobalParameter], gridName: String)(implicit qc: QueryContext): NodeSeq = {
    (
      "tbody *" #> ("tr" #> params.map { param =>
        val lineHtmlId = Helpers.nextFuncName
        ".parameterLine [jsuuid]" #> lineHtmlId &
        ".parameterLine [class]" #> Text("curspoint") &
        ".name *" #> <b>{param.name}</b> &
        ".value *" #> <pre class="json-beautify">{param.valueAsString}</pre> &
        ".description *" #> <span><ul class="ms-2"><li><b>Description:</b> {Text(param.description)}</li></ul></span> &
        ".description [id]" #> ("description-" + lineHtmlId) &
        ".change *" #> <div>{
          if (param.provider.isEmpty || param.provider == Some(PropertyProvider.defaultPropertyProvider)) {
            (if (CurrentUser.checkRights(AuthorizationType.Parameter.Edit)) {
               ajaxButton(
                 "Edit",
                 () => showPopup(GlobalParamModAction.Update, Some(param)),
                 ("class", "btn btn-default btn-sm"),
                 ("style", "min-width:50px;")
               )
             } else NodeSeq.Empty) ++
            (if (CurrentUser.checkRights(AuthorizationType.Parameter.Write)) {
               ajaxButton(
                 "Delete",
                 () => showPopup(GlobalParamModAction.Delete, Some(param)),
                 ("class", "btn btn-danger btn-sm"),
                 ("style", "margin-left:5px;min-width:50px;")
               )
             } else NodeSeq.Empty)
          } else NodeSeq.Empty
        }</div>
      }) &
      ".createParameter *" #> (if (CurrentUser.checkRights(AuthorizationType.Parameter.Write)) {
                                 ajaxButton(
                                   "Create",
                                   () => showPopup(GlobalParamModAction.Create, None),
                                   ("class", "btn btn-success new-icon space-bottom space-top")
                                 )
                               } else NodeSeq.Empty)
    ).apply(dataTableXml(gridName)) ++ WithNonce.scriptWithNonce(Script(initJs()))
  }

  private def dataTableXml(gridName: String) = {
    <div id={gridContainer}>
      <div id="actions_zone">
        <div class="createParameter"/>
      </div>
      <table id={gridName} class="display" cellspacing="0">
        <thead>
          <tr class="head">
            <th>Name</th>
            <th>Value</th>
            <th>Change</th>
          </tr>
        </thead>

        <tbody>
          <tr class="parameterLine">
            <td class="name listopen">[name of parameter]</td>
            <td class="value">[value of parameter]</td>
            <div class="description" style="display:none;" >[description]</div>
            <td class="change">[change / delete]</td>
          </tr>
        </tbody>
      </table>

      <div id="parametersGrid_paginate_area" class="paginate"></div>

    </div>

  }

  private def jsVarNameForId(tableId: String) = "oTable" + tableId

  private def initJs(): JsCmd = {
    OnLoad(
      JsRaw(s"""
          /* Event handler function */
          ${jsVarNameForId(gridName)} = $$('#${gridName}').dataTable({
            "asStripeClasses": [ 'color1', 'color2' ],
            "bAutoWidth"   : false,
            "bFilter"      : true,
            "bPaginate"    : true,
            "bLengthChange": true,
            "bStateSave"   : true,
                    "fnStateSave": function (oSettings, oData) {
                      localStorage.setItem( 'DataTables_${gridName}', JSON.stringify(oData) );
                    },
                    "fnStateLoad": function (oSettings) {
                      return JSON.parse( localStorage.getItem('DataTables_${gridName}') );
                    },
            "sPaginationType": "full_numbers",
            "oLanguage": {
              "sZeroRecords": "No parameters!",
              "sSearch": ""
            },
            "bJQueryUI"    : false,
            "aaSorting"    : [[ 0, "asc" ]],
            "aoColumns": [
              { "sWidth": "300px" },
              { "sWidth": "600px" },
              { "sWidth": "140px" }
            ],
            "sDom": '<"dataTables_wrapper_top"f>rt<"dataTables_wrapper_bottom"lip>',
            "lengthMenu": [ [10, 25, 50, 100, 500, 1000, -1], [10, 25, 50, 100, 500, 1000, "All"] ],
            "pageLength": 25
          });
          $$('.dataTables_filter input').attr("placeholder", "Filter");
          """) &
      JsRaw("""
        /* Formating function for row details */
          function fnFormatDetails(id) {
            var sOut = '<span id="'+id+'" class="parametersDescriptionDetails"/>';
            return sOut;
          };

          $(#table_var#.fnGetNodes() ).each( function () {
            $(this).click( function (event) {
              var jTr = $(this);
              var opened = jTr.prop("open");
              var source = event.target || event.srcElement;
              if (!( $(source).is("button"))) {
                if (opened && opened.match("opened")) {
                  jTr.prop("open", "closed");
                  $(this).find("td.listclose").removeClass("listclose").addClass("listopen");
                  #table_var#.fnClose(this);
                  $(this).removeClass("opened");
                } else {
                  $(this).addClass("opened");
                  jTr.prop("open", "opened");
                  $(this).find("td.listopen").removeClass("listopen").addClass("listclose");
                  var jsid = jTr.attr("jsuuid");
                  var color = 'color1';
                  if(jTr.hasClass('color2'))
                    color = 'color2';
                  var row = $(#table_var#.fnOpen(this, fnFormatDetails(jsid), 'details'));
                  $(row).addClass(color + ' parametersDescription');
                  $('#'+jsid).html($('#description-'+jsid).html());
                }
               }
            } );
          })

      """.replaceAll("#table_var#", jsVarNameForId(gridName))) // JsRaw ok, const
    )
  }

  private def showPopup(
      action:    GlobalParamModAction,
      parameter: Option[GlobalParameter]
  )(implicit qc: QueryContext): JsCmd = {
    val change = GlobalParamChangeRequest(action, parameter)

    parameterPopup.set(
      Full(
        new CreateOrUpdateGlobalParameterPopup(
          change,
          (cr, workflowService, contextPath) => workflowCallBack(action, change, workflowService, contextPath)(cr)
        )
      )
    )
    val popupHtml = createPopup
    SetHtml(CreateOrUpdateGlobalParameterPopup.htmlId_popupContainer, popupHtml) &
    JsRaw(""" initBsModal("%s",300,600) """.format(CreateOrUpdateGlobalParameterPopup.htmlId_popup)) // JsRaw ok, const

  }

  private def workflowCallBack(
      action:          GlobalParamModAction,
      change:          GlobalParamChangeRequest,
      workflowService: WorkflowService,
      contextPath:     String
  )(
      returns:         Either[GlobalParameter, ChangeRequestId]
  )(implicit qc: QueryContext): JsCmd = {

    val jsCmd = returns match {
      case Left(param)            => // ok, we've received a parameter, do as before
        closePopup() & updateGrid() & successPopup
      case Right(changeRequestId) => // oh, we have a change request, go to it
        linkUtil.redirectToChangeRequestLink(changeRequestId, contextPath)
    }

    println(jsCmd)

    if ((!workflowService.needExternalValidation()) & (action == GlobalParamModAction.Delete)) {
      closePopup() & onSuccessDeleteCallback()
    } else {
      jsCmd
    }

  }

  /**
    * Create the creation popup
    */
  def createPopup(implicit qc: QueryContext): NodeSeq = {
    parameterPopup.get match {
      case Failure(m, _, _) => <span class="error">Error: {m}</span>
      case Empty            => <div>The component is not set</div>
      case Full(popup)      => popup.popupContent()
    }
  }

  private def updateGrid()(implicit qc: QueryContext): JsCmd = {
    Replace(gridContainer, display())
  }

  ///////////// success pop-up ///////////////
  private def successPopup: JsCmd = {
    JsRaw("""createSuccessNotification()""") // JsRaw ok, const
  }

  private def onSuccessDeleteCallback()(implicit qc: QueryContext): JsCmd = {
    updateGrid() & successPopup
  }

  private def closePopup(): JsCmd = {
    JsRaw(s"""hideBsModal('${CreateOrUpdateGlobalParameterPopup.htmlId_popupContainer}');""") // JsRaw ok, const
  }

}
