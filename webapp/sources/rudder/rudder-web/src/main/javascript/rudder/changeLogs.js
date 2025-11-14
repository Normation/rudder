/*
*************************************************************************************
* Copyright 2025 Normation SAS
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

import * as jsondiffpatch from '../libs/jsondiffpatch/lib/index.js'
import * as htmlFormatter from '../libs/jsondiffpatch/lib/formatters/html.js'

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "id" : Event log id [Int]
 *   , "date": date the event log was produced [Date/String]
 *   , "actor": Name of the actor making the event [String]
 *   , "type" : Type of the event log [String]
 *   , "description" : Description of the event [String]
 *   , "details" : function/ajax call, setting the details content, takes the id of the element to set [Function(String)]
 *   , "hasDetails" : do our event needs to display details (do we need to be able to open the row [Boolean]
 *   }
 */
function createEventLogTable(gridId, data, contextPath, refresh) {

  var columns = [ {
    "width"       : "10%"
  , "data"        : "id"
  , "title"       : "Id"
  , "className"   : "eventId"
  , "createdCell" :
      function (nTd, sData, oData, iRow, iCol) {
        if( oData.hasDetails ) {
          $(nTd).addClass("listopen");
        }
      }
  } , {
    "width": "20%"
  , "data" : "date"
  , "title": "Date"
  } , {
    "width": "10%"
  , "data" : "actor"
  , "title": "Actor"
  } , {
    "width": "30%"
  , "data" : "type"
  , "title": "Type"
  } , {
    "width"    : "30%"
  , "data"     : "description"
  , "title"    : "Description"
  , "orderable": false
  } ];

  var params =
  { "filter" : true
  , "processing" : true
  , "serverSide" : true
  , "ajax" :
    { "type" : "POST"
    , "contentType": "application/json"
    , "url" : contextPath + "/secure/api/eventlog"
    , "data" :
       function (d) {
         d.startDate = $(".pickStartInput").val()
         d.endDate = $(".pickEndInput").val()
         return JSON.stringify( d );
       }
    }
  , "paging" : true
  , "lengthChange": true
  , "pagingType": "full_numbers"
  , "order": [[ 0, "desc" ]]
  , "createdRow" :
      function( row, data, dataIndex, cells ) {
        var table = this.api();
        row = $(row);
        row.attr("id",data.id);
        if (data.hasDetails) {
          row.addClass("curspoint");
          // Remove all previously added callbacks on row or you will get problems
          row.unbind();
          // Add callback to open the line
          row.click( function (e) {
            e.stopPropagation();
            // Check if our line is opened/closed
            var IdTd = $(table.cell(row,0).node());
            if (IdTd.hasClass("listclose")) {
              table.row(row).child().hide();
              row.removeClass("opened");
            } else {
              row.addClass("opened");

              // Set data in the open row with the details function from data
              $.ajax({
                type: "GET",
                url: contextPath + "/secure/api/eventlog/" + data.id + "/details" ,
                contentType: "application/json; charset=utf-8",
                success: function (response, status, jqXHR) {
                  const id = response["data"]["id"]
                  const rollback = setupRollbackBlock(id)
                  const parser = new DOMParser();
                  const content = parser.parseFromString(response["data"]["content"], 'text/html');
                  const html = $(content.body).contents();

                  if(response["data"]["canRollback"]){
                    table.row(row).child($(rollback).append(html)).show();
                    $('#showParameters' + id).off('click').on('click', function() { showParameters(event, id) });
                    $("#restoreBtn" + id).click(function(event){
                      const rollback = "#rollback" + id
                      $(rollback).removeClass("d-flex").addClass("d-none");
                      const confirm = "#confirm" + id.toString();
                      const radios = $(".radio-btn");
                      const action = getRadioChecked(radios, value => (value === "before" || value === "after") ? value : null);
                      if (action !== null) {
                        const confirmHtml = "<div class='d-flex text-start column-gap-2'><div class='py-2'><i class='fa fa-exclamation-triangle fs-2' aria-hidden='true'></i></div><div><div>Are you sure you want to restore configuration policy " + action + " this change? </div><div class='mt-2'><button class='btn btn-default rollback-action'>Cancel</button><button class='btn btn-danger rollback-action ms-2'>Confirm</button></div></div>";
                        $(confirm).append(confirmHtml).addClass("alert alert-warning d-flex flex-column");
                        $('#confirm' + id + ' .rollback-action.btn-danger').off('click').on('click', '', function() { confirmRollback(id, action) });
                        $('#confirm' + id + ' .rollback-action.btn-default').off('click').on('click', '', function() { cancelRollback(id) });
                      }
                    });
                  } else {
                    table.row(row).child(html).show();
                    $('#showParameters' + id).off('click').on('click', function() { showParameters(event, id) });
                  }

                  // There may be some node properties to display in diff, we use JsonDiffPatch (see doc for formatter option: https://github.com/benjamine/jsondiffpatch/blob/master/docs/formatters.md)
                  // We can keep old, non modified values, but in our even log case, we prefer to jst show what changed.
                  const nodePropertiesDiff = response["data"]["nodePropertiesDiff"]
                  if (nodePropertiesDiff) {
                    document.getElementById(`nodepropertiesdiff-${data.id}`).innerHTML = htmlFormatter.format(
                      jsondiffpatch.diff(nodePropertiesDiff.from, nodePropertiesDiff.to)
                    )
                  }
                },
                error: function (jqXHR, textStatus, errorThrown) {
                  createErrorNotification("Error while retrieve eventlog details: " + jqXHR.responseJSON.errorDetails)
                }
              });
            }
              // toggle list open / close classes
              IdTd.toggleClass('listopen');
              IdTd.toggleClass('listclose');
            } );
          }

      }
    , "sDom": '<"dataTables_wrapper_top newFilter d-flex"f<"d-flex ms-auto my-auto" B <"dataTables_refresh ms-2" r>>'+
      '>t<"dataTables_wrapper_bottom"lip>'
    , "buttons" : [ csvButtonConfig("change_logs") ],
  };

  createTable(gridId,data, columns, params, contextPath, refresh, "event_logs", false);
}


export { createEventLogTable };