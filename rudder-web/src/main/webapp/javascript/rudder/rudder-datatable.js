/*
*************************************************************************************
* Copyright 2014 Normation SAS
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

var anOpen = [];

var ruleCompliances = {};
var recentChanges = {};
var recentChangesCount = {};
var recentGraphs = {};

var nodeCompliances = {};

/*
 * This function is used to resort a table after its sorting datas were changed ( like sorting function below)
 */
function resortTable (tableId) {
  var table = $("#"+tableId).dataTable();
  var sortingColumns = table.dataTableSettings[0].aaSorting;
  table.fnSort(sortingColumns);
}

$.fn.dataTableExt.afnSortData['compliance'] = function ( oSettings, iColumn )
{
    var data =
      $.map(
          // All data of the table
          oSettings.oApi._fnGetDataMaster(oSettings)
        , function (elem, index) {
            if (elem.id in ruleCompliances) {
              var compliance = ruleCompliances[elem.id];
              return compliance[0] + compliance[1] + compliance[2]
            }
            return -1;
          }
      )
    return data;
};


$.fn.dataTableExt.afnSortData['node-compliance'] = function ( oSettings, iColumn )
{
    var data =
      $.map(
          // All data of the table
          oSettings.oApi._fnGetDataMaster(oSettings)
        , function (elem, index) {
            if (elem.id in nodeCompliances) {
              var compliance = nodeCompliances[elem.id];
              return compliance[0] + compliance[1] + compliance[2]
            }
            return -1;
          }
      )
    return data;
};

$.fn.dataTableExt.afnSortData['changes'] = function ( oSettings, iColumn )
{
    var data =
      $.map(
          // All data of the table
          oSettings.oApi._fnGetDataMaster(oSettings)
        , function (elem, index) {
            if (elem.id in recentChangesCount) {
              return recentChangesCount[elem.id];
            }
            return -1;
          }
      )
    return data;
};



function computeChangeGraph(changes, id, currentRowsIds, changeCount, displayGraph) {
  recentChanges[id] = changes;
  recentChangesCount[id] = changeCount;
  if (currentRowsIds.indexOf(id) != -1) {
    generateRecentGraph(id, displayGraph);
  }
}

function generateRecentGraph(id, displayGraph) {
  if (displayGraph) {
    var graphId = "Changes-"+id;
    var changes = recentChanges[id];
    if (changes !== undefined) {
      var data = changes.y;
      data.splice(0,0,'Recent changes');
      var x = changes.x;
      x.splice(0,0,'x');
      var chart = c3.generate({
          size: { height: 30 , width: 168 }
        , legend: { show: false }
        , data: {
              x: 'x'
            , columns: [ x, data ]
            , type: 'area-step'
          }
        , axis: {
              x: {
                  show : false
                , type: 'categories'
              }
            , y: { show : false }
          }
      });
      recentGraphs[id] = chart;

      $("#"+graphId).html(chart.element);

    }
  } else {
    recentChangesText(id);
  }
}


function recentChangesText(id) {
  // Datas
  var graphId = "Changes-"+id;
  var tooltipId = graphId+"-description";
  var graphElem= $("#"+graphId);
  var count = recentChangesCount[id];
  var changes = recentChanges[id];
  var lastChanges = 0;
  if (changes !== undefined) {
    lastChanges = changes.y[changes.y.length - 1];
  }

  // Prepare graph elem to have tooltip
  graphElem.attr("tooltipid",tooltipId);
  graphElem.attr("title","");
  graphElem.addClass("tooltip tooltipable");

  // Tooltip
  var tooltip= $("<div></div>");
  var toolTipContainer = $("<div><h3>Recent changes</h3></div>");
  toolTipContainer.addClass("tooltipContent");
  toolTipContainer.attr("id",tooltipId);
  tooltip.html(count+" changes over the last 3 days <br/> "+ lastChanges+" changes over the last 6 hours ");
  toolTipContainer.append(tooltip);

  // Elem Content
  graphElem.text(count).addClass("center")
  graphElem.append(toolTipContainer);
  createTooltip();
}

/*
 *
 *  The main rule grid table (list of all rules with their application status, compliance, etc).
 *
 *   data:
 *   { "name" : Rule name [String]
 *   , "id" : Rule id [String]
 *   , "description" : Rule (short) description [String]
 *   , "applying": Is the rule applying the Directive, used in Directive page [Boolean]
 *   , "category" : Rule category [String]
 *   , "status" : Status of the Rule, "enabled", "disabled" or "N/A" [String]
 *   , "compliance" : Percent of compliance of the Rule [String]
 *   , "recentChanges" : Array of changes to build the sparkline [Array[String]]
 *   , "trClass" : Class to apply on the whole line (disabled ?) [String]
 *   , "callback" : Function to use when clicking on one of the line link, takes a parameter to define which tab to open, not always present[ Function ]
 *   , "checkboxCallback": Function used when clicking on the checkbox to apply/not apply the Rule to the directive, not always present [ Function ]
 *   , "reasons": Reasons why a Rule is a not applied, empty if there is no reason [ String ]
 *   }
 */
function createRuleTable(gridId, data, checkboxColumn, actionsColumn, complianceColumn, recentChangesGraph, allCheckboxCallback, contextPath, refresh) {

  //base element for the clickable cells
  function callbackElement(oData, action) {
    var elem = $("<a></a>");
    if("callback" in oData) {
        elem.click(function() {oData.callback(action);});
        elem.attr("href","javascript://");
    } else {
        elem.attr("href",contextPath+'/secure/configurationManager/ruleManagement#{"ruleId":"'+oData.id+'","action":"'+action+'"}');
    }
    return elem;
  }

  // Define which columns should be sorted by default
  var sortingDefault;
  if (checkboxColumn) {
    sortingDefault = 1;
  } else {
    sortingDefault = 0;
  }

  // Define all columns of the table

  // Checkbox used in check if a Directive is applied by the Rule
  var checkbox = {
      "mDataProp": "applying"
    , "sTitle" : "<input id='checkAll' type='checkbox'></input>"
    , "sWidth": "5%"
    , "bSortable": false
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var data = oData;
        var elem = $("<input type='checkbox'></input>");
        elem.prop("checked", data.applying);
        elem.click( function () {
          data.checkboxCallback(elem.prop("checked"));
        } );
        elem.attr("id",data.id+"Checkbox");
        $(nTd).empty();
        $(nTd).prepend(elem);
      }
  };

  // Name of the rule
  // First mandatory row, so do general thing on the row ( line css, description tooltip ...)
  var name = {
      "mDataProp": "name"
    , "sWidth": "20%"
    , "sTitle": "Name"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var data = oData;

        var action = "showForm"
        if (! actionsColumn) {
          action = "showEditForm"
        }
        // Define the elem and its callback
        var elem = callbackElement(oData, action);
        elem.text(data.name);

        // Row parameters
        var parent = $(nTd).parent();
        // Add Class on the row, and id
        parent.addClass(data.trClass);
        parent.attr("id",data.id);

        // Description tooltip over the row
        if ( data.description.length > 0) {
          var tooltipId = data.id+"-description";
          parent.attr("tooltipid",tooltipId);
          parent.attr("title","");
          parent.addClass("tooltip tooltipabletr");
          var tooltip= $("<div></div>");
          var toolTipContainer = $("<div><h3>"+data.name+"</h3></div>");
          toolTipContainer.addClass("tooltipContent");
          toolTipContainer.attr("id",tooltipId);
          tooltip.text(data.description);
          toolTipContainer.append(tooltip);
          elem.append(toolTipContainer);
        }

        // Append the content to the row
        $(nTd).empty();
        $(nTd).prepend(elem);
      }
  };

  // Rule Category
  var category =
    { "mDataProp": "category"
    , "sWidth": "20%"
    , "sTitle": "Category"
    };

  // Status of the rule (disabled) add reason tooltip if needed
  var status= {
      "mDataProp": "status"
    , "sWidth": "10%"
    , "sTitle": "Status"
    , "sClass" : "statusCell"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var data = oData;
        $(nTd).empty();
        var elem = $("<span></span>");
        elem.text(data.status);
        // If there a reasons field, add the tooltip
        if ("reasons" in data) {
          var tooltipId = data.id+"-status";
          elem.attr("tooltipid",tooltipId);
          elem.attr("title","");
          elem.addClass("tooltip tooltipable");
          var tooltip= $("<div></div>");
          var toolTipContainer = $("<div><h3>Reason(s)</h3></div>");
          toolTipContainer.addClass("tooltipContent");
          toolTipContainer.attr("id",tooltipId);
          tooltip.text(data.reasons);
          toolTipContainer.append(tooltip);
          $(nTd).prepend(toolTipContainer);
        }
        $(nTd).prepend(elem);
      }
  };

  // Compliance, with link to the edit form
  var compliance = {
      "mDataProp": "name"
    , "sWidth": "25%"
    , "sTitle": "Compliance"
    , "sSortDataType": "compliance"
    , "sType" : "numeric"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var elem = callbackElement(oData, "showForm");
        if (oData.status === "In application" || oData.status === "Partially applied" ) {
          elem.append('<div id="compliance-bar-'+oData.id+'"><center><img height="26" width="26" src="'+contextPath+'/images/ajax-loader.gif" /></center></div>');
        }
        $(nTd).empty();
        $(nTd).prepend(elem);
      }
  };

  // recent changes as graph, with link to the edit form
  var recentChanges = {
      "mDataProp": "name"
    , "sWidth": "10%"
    , "sTitle": "Recent changes"
    , "sSortDataType": "changes"
    , "sType" : "numeric"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var elem = callbackElement(oData, "showRecentChanges");
        var id = "Changes-"+oData.id;
        elem.append('<div id="'+id+'"><center><img height="26" width="26" src="'+contextPath+'/images/ajax-loader.gif" /></center></div>');
        $(nTd).empty();
        $(nTd).prepend(elem);
      }
  };

  // Action buttons, use id a dataprop as its is always present
  var actions = {
      "mDataProp": "id"
    , "sWidth": "5%"
    , "bSortable" : false
    , "sClass" : "parametersTd"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var data = oData;
        var elem = $("<buton></button>");
        elem.button();
        elem.addClass("smallButton");
        elem.click( function() {
          data.callback("showEditForm");
        } );
        elem.text("Edit");
        $(nTd).empty();
        $(nTd).prepend(elem);
      }
  };

  // Choose which columns should be included
  var columns = [];
  if (checkboxColumn) {
    columns.push(checkbox);
  }
  columns.push(name);
  columns.push(category);
  columns.push(status);
  if (complianceColumn) {
    columns.push(compliance);
    columns.push(recentChanges);
  }
  if (actionsColumn) {
    columns.push(actions);
  }

  var params = {
      "bFilter" : true
    , "bPaginate" : true
    , "bLengthChange": true
    , "sPaginationType": "full_numbers"
    , "oLanguage": {
          "sZeroRecords": "No matching rules!"
        , "sSearch": ""
      }
    , "fnDrawCallback": function( oSettings ) {
      var rows = this._('tr', {"page":"current"});
       $.each(rows, function(index,row) {
         var id = "Changes-"+row.id;
         // Display compliance progress bar if it has already been computed
         var compliance = ruleCompliances[row.id]
         if (compliance !== undefined) {
           $("#compliance-bar-"+row.id).html(buildComplianceBar(compliance));
         }
         if (recentChangesGraph) {
           var changes = recentGraphs[row.id]
           if (changes !== undefined) {
             $("#"+id).html(changes.element);
           } else {
             generateRecentGraph(row.id,recentChangesGraph);
           }
         } else {
           generateRecentGraph(row.id,recentChangesGraph);
         }
       })
      }
    , "aaSorting": [[ sortingDefault, "asc" ]]
    , "sDom": '<"dataTables_wrapper_top newFilter"f<"dataTables_refresh">>rt<"dataTables_wrapper_bottom"lip>'
  }

  var table = createTable(gridId,data,columns, params, contextPath, refresh, "rules");
  table.search("").columns().search("")
  createTooltip();

  // Add callback to checkbox column
  $("#checkAll").prop("checked", false);
  $("#checkAll").click( function () {
      var checked = $("#checkAll").prop("checked");
      allCheckboxCallback(checked);
  } );

}

////////////////////////////////////////////////////////////////
///////////////////  Rule compliance details ///////////////////
////////////////////////////////////////////////////////////////


/*
 * We have 3 ways of displaying compliance details:
 *
 *  1/ for ONE node, by rules -> directives -> components -> values status with messages
 *  2/ for ONE rule, by directives -> components -> values compliance
 *  3/ for ONE rule, by nodes -> directives -> components -> values status with messages
 *
 *  For 1/ and 3/, the value line looks like: [ VALUE | MESSAGES | STATUS ]
 *  For 2/, they looks like: [ VALUE | COMPLIANCE ]
 */

/*
 *   The table of rules compliance for a node (in the node details
 *   page, reports tab)
 *
 *   Javascript object containing all data to create a line in the DataTable
 *   { "rule" : Rule name [String]
 *   , "id" : Rule id [String]
 *   , "compliance" : array of number of reports by compliance status [Array[Float]]
 *   , "compliancePercent" : Compliance percentage [Float]
 *   , "details" : Details of Directives contained in the Rule [Array of Directive values]
 *   , "jsid"    : unique identifier for the line [String]
 *   , "isSystem" : Is it a system Rule? [Boolean]
 *   }
 */
function createRuleComplianceTable(gridId, data, contextPath, refresh) {

  var columns = [ {
      "sWidth": "75%"
    , "mDataProp": "rule"
    , "sTitle": "Rule"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        $(nTd).addClass("listopen");

        if (! oData.isSystem) {
          var editLink = $("<a />");
          editLink.attr("href",contextPath + '/secure/configurationManager/ruleManagement#{"ruleId":"'+oData.id+'"}');
          var editIcon = $("<img />");
          editIcon.attr("src",contextPath + "/images/icPen.png");
          editLink.click(function(e) {e.stopPropagation();});
          editLink.append(editIcon);
          editLink.addClass("reportIcon");

          $(nTd).append(editLink);
        }
      }
  } , {
    "sWidth": "25%"
      , "mDataProp": "compliancePercent"
      , "sTitle": "Compliance"
      , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
          var elem = $("<a></a>");
          elem.addClass("noExpand");
          elem.attr("href","javascript://");
          elem.append(buildComplianceBar(oData.compliance));
          elem.click(function() {oData.callback()});
          $(nTd).empty();
          $(nTd).append(elem);
        }
    } ];

  var params = {
      "bFilter" : true
    , "bPaginate" : true
    , "bLengthChange": true
    , "sPaginationType": "full_numbers"
    , "oLanguage": {
        "sSearch": ""
      }
    , "aaSorting": [[ 0, "asc" ]]
    , "fnDrawCallback" : function( oSettings ) {
        createInnerTable(this, createDirectiveTable(false, true, contextPath), contextPath, "rule");
      }
    , "sDom": '<"dataTables_wrapper_top newFilter"f<"dataTables_refresh">>rt<"dataTables_wrapper_bottom"lip>'
  };

  createTable(gridId,data,columns, params, contextPath, refresh);

}

/**
 *
 * This is the expected report table that we display on node details, for
 * reports, when we don't have relevant information for compliance
 * (for example when we get reports for the wrong configuration id).
 *
 * The parameters are the same than for the above "createRuleComplianceTable"
 * method, and more preciselly, the whole implementation is a simplified
 * version of that method, where only the first(s) column are kept.
 *
 */
function createExpectedReportTable(gridId, data, contextPath, refresh) {
  var defaultParams = {
      "bFilter" : false
    , "bPaginate" : false
    , "bLengthChange": false
    , "bInfo" : false
    , "aaSorting": [[ 0, "asc" ]]
  };

  var localNodeComponentValueTable = function() {
    var columns = [ {
        "mDataProp": "value"
      , "sTitle"   : "Value"
    } ];
    return function (gridId,data) {createTable(gridId, data, columns, defaultParams, contextPath); createTooltip();}
  };

  var localComponentTable = function() {
    var columns = [ {
        "mDataProp": "component"
      , "sTitle"   : "Component"
      , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
            $(nTd).addClass("listopen");
        }
    } ];

    var params = jQuery.extend({"fnDrawCallback" : function( oSettings ) {
      createInnerTable(this, localNodeComponentValueTable());
    }}, defaultParams);

    return function (gridId,data) {createTable(gridId,data,columns, params, contextPath);}
  };

  var localDirectiveTable = function() {
    var columns = [ {
        "mDataProp": "directive"
      , "sTitle": "Directive"
      , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
          $(nTd).addClass("listopen");

          var tooltipIcon = $("<img />");
          tooltipIcon.attr("src",contextPath + "/images/ic_question_14px.png");
          tooltipIcon.addClass("reportIcon");
          var tooltipId = oData.jsid+"-tooltip";
          tooltipIcon.attr("tooltipid",tooltipId);
          tooltipIcon.attr("title","");
          tooltipIcon.addClass("tooltip tooltipable");
          var toolTipContainer= $("<div>Directive '<b>"+sData+"</b>' is based on technique '<b>"+oData.techniqueName+"</b>' (version "+oData.techniqueVersion+")</div>");
          toolTipContainer.addClass("tooltipContent");
          toolTipContainer.attr("id",tooltipId);

          $(nTd).append(tooltipIcon);
          $(nTd).append(toolTipContainer);

          if (! oData.isSystem) {
            var editLink = $("<a />");
            editLink.attr("href",contextPath + '/secure/configurationManager/directiveManagement#{"directiveId":"'+oData.id+'"}');
            var editIcon = $("<img />");
            editIcon.attr("src",contextPath + "/images/icPen.png");
            editLink.click(function(e) {e.stopPropagation();});
            editLink.append(editIcon);
            editLink.addClass("reportIcon");

            $(nTd).append(editLink);
          }
        }
    } ];

    var params = jQuery.extend({"fnDrawCallback" : function( oSettings ) {
      createInnerTable(this, localComponentTable(), contextPath, "directive");
    }}, defaultParams);


    return function (gridId, data, refresh) {
      createTable(gridId, data, columns, params, contextPath, refresh);
      createTooltip();
    }
  };

  var ruleColumn = [ {
    "mDataProp": "rule"
  , "sTitle"   : "Rule"
  , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
      $(nTd).addClass("listopen");

      if (! oData.isSystem) {
        var editLink = $("<a />");
        editLink.attr("href",contextPath + '/secure/configurationManager/ruleManagement#{"ruleId":"'+oData.id+'"}');
        var editIcon = $("<img />");
        editIcon.attr("src",contextPath + "/images/icPen.png");
        editLink.click(function(e) {e.stopPropagation();});
        editLink.append(editIcon);
        editLink.addClass("reportIcon");

        $(nTd).append(editLink);
      }
    }
  } ];
  var params = jQuery.extend({"fnDrawCallback" : function( oSettings ) {
        createInnerTable(this, localDirectiveTable(), contextPath, "rule");
      }
    , "sDom": '<"dataTables_wrapper_top newFilter"f<"dataTables_refresh">>rt<"dataTables_wrapper_bottom"lip>'
  }
  , defaultParams);

  createTable(gridId,data, ruleColumn, params, contextPath, refresh);

}


/*
 *   Create a table of compliance for a Directive.
 *   Used in the compliance details for a Rule, and in the
 *   node details page, in report tab.
 *
 *   Javascript object containing all data to create a line in the DataTable *   Javascript object containing all data to create a line in the DataTable
 *   { "directive" : Directive name [String]
 *   , "id" : Directive id [String]
 *   , "techniqueName": Name of the technique the Directive is based upon [String]
 *   , "techniqueVersion" : Version of the technique the Directive is based upon  [String]
 *   , "compliance" : array of number of reports by compliance status [Array[Float]]
 *   , "compliancePercent" : Compliance percentage [Float]
 *   , "details" : Details of components contained in the Directive [Array of Component values]
 *   , "jsid"    : unique identifier for the line [String]
 *   , "isSystem" : Is it a system Directive? [Boolean]
 *   }
 */
function createDirectiveTable(isTopLevel, isNodeView, contextPath) {

  if (isTopLevel) {
    var complianceWidth = "25%";
    var directiveWidth = "75%";
  } else {
    var complianceWidth = "26.3%";
    var directiveWidth = "73.7%";
  }

  var columns = [ {
     "sWidth": directiveWidth
    , "mDataProp": "directive"
    , "sTitle": "Directive"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        $(nTd).addClass("listopen");

        var tooltipIcon = $("<img />");
        tooltipIcon.attr("src",contextPath + "/images/ic_question_14px.png");
        tooltipIcon.addClass("reportIcon");
        var tooltipId = oData.jsid+"-tooltip";
        tooltipIcon.attr("tooltipid",tooltipId);
        tooltipIcon.attr("title","");
        tooltipIcon.addClass("tooltip tooltipable");
        var toolTipContainer= $("<div>Directive '<b>"+sData+"</b>' is based on technique '<b>"+oData.techniqueName+"</b>' (version "+oData.techniqueVersion+")</div>");
        toolTipContainer.addClass("tooltipContent");
        toolTipContainer.attr("id",tooltipId);

        $(nTd).append(tooltipIcon);
        $(nTd).append(toolTipContainer);

        if (! oData.isSystem) {
          var editLink = $("<a />");
          editLink.attr("href",contextPath + '/secure/configurationManager/directiveManagement#{"directiveId":"'+oData.id+'"}');
          var editIcon = $("<img />");
          editIcon.attr("src",contextPath + "/images/icPen.png");
          editLink.click(function(e) {e.stopPropagation();});
          editLink.append(editIcon);
          editLink.addClass("reportIcon");

          $(nTd).append(editLink);
        }
      }
  } , {
      "sWidth": complianceWidth
    , "mDataProp": "compliancePercent"
    , "sTitle": "Compliance"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var elem = buildComplianceBar(oData.compliance);
        $(nTd).empty();
        $(nTd).append(elem);
      }
  } ];

  var params = {
      "bFilter" : isTopLevel
    , "bPaginate" : isTopLevel
    , "bLengthChange": isTopLevel
    , "bInfo" : isTopLevel
    , "sPaginationType": "full_numbers"
    , "aaSorting": [[ 0, "asc" ]]
    , "fnDrawCallback" : function( oSettings ) {
        createInnerTable(this, createComponentTable(isTopLevel, isNodeView, contextPath), contextPath, "directive");
      }
  };

  if (isTopLevel) {
    var sDom = {
        "sDom" : '<"dataTables_wrapper_top newFilter"f<"dataTables_refresh">>rt<"dataTables_wrapper_bottom"lip>'
      , "oLanguage": {
          "sSearch": ""
        }
    };
    $.extend(params,sDom);
  }

  return function (gridId, data, refresh) {
    createTable(gridId, data, columns, params, contextPath, refresh);
    createTooltip();
  }
}

/*
 *   Create the table with the list of nodes, used in
 *   the pop-up from rule compliance details.
 *
 *   Javascript object containing all data to create a line in the DataTable
 *   { "node" : Node name [String]
 *   , "id" : Node id [String]
 *   , "compliance" : array of number of reports by compliance status [Array[Float]]
 *   , "compliancePercent" : Compliance percentage [Float]
 *   , "details" : Details of Directive applied by the Node [Array of Directive values ]
 *   , "jsid"    : unique identifier for the line [String]
 *   }
 */
function createNodeComplianceTable(gridId, data, contextPath, refresh) {

  var columns = [ {
      "sWidth": "75%"
    , "mDataProp": "node"
    , "sTitle": "Node"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        $(nTd).addClass("listopen");

        var editLink = $("<a />");
        editLink.attr("href",contextPath +'/secure/nodeManager/searchNodes#{"nodeId":"'+oData.id+'"}');
        var editIcon = $("<img />");
        editIcon.attr("src",contextPath + "/images/icMagnify-right.png");
        editLink.click(function(e) {e.stopPropagation();});
        editLink.append(editIcon);
        editLink.addClass("reportIcon");

        $(nTd).append(editLink);
      }
  } , {
      "sWidth": "25%"
    , "mDataProp": "compliancePercent"
    , "sTitle": "Compliance"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var elem = $("<a></a>");
        elem.addClass("noExpand");
        elem.attr("href","javascript://");
        elem.append(buildComplianceBar(oData.compliance));
        elem.click(function() {oData.callback()});
        $(nTd).empty();
        $(nTd).append(elem);
      }
  } ];

  var params = {
      "bFilter" : true
    , "bPaginate" : true
    , "bLengthChange": true
    , "sPaginationType": "full_numbers"
    , "oLanguage": {
        "sSearch": ""
      }
    , "aaSorting": [[ 0, "asc" ]]
    , "fnDrawCallback" : function( oSettings ) {
        createInnerTable(this,createDirectiveTable(false, true, contextPath),"node");
      }
    , "sDom": '<"dataTables_wrapper_top newFilter"f<"dataTables_refresh">>rt<"dataTables_wrapper_bottom"lip>'
  };

  createTable(gridId, data, columns, params, contextPath, refresh);

  createTooltip();
}

/*
 *   Details of a component. Used on all tables.
 *
 *   Javascript object containing all data to create a line in the DataTable
 *   { "component" : component name [String]
 *   , "compliance" : array of number of reports by compliance status [Array[Float]]
 *   , "compliancePercent" : Compliance percentage [Float]
 *   , "details" : Details of values contained in the component [ Array of Component values ]
 *   , "noExpand" : The line should not be expanded if all values are "None" [Boolean]
 *   }
 */
function createComponentTable(isTopLevel, isNodeView, contextPath) {

  if (isTopLevel) {
    var complianceWidth = "26.3%";
  } else {
    var complianceWidth = "27.9%";
    var componentSize = "72.4%";
  }
  var columns = [ {
      "sWidth": componentSize
    , "mDataProp": "component"
    , "sTitle": "Component"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        if(! oData.noExpand || isNodeView ) {
          $(nTd).addClass("listopen");
        } else {
          $(nTd).addClass("noExpand");
        }
      }
  } , {
      "sWidth": complianceWidth
    , "mDataProp": "compliancePercent"
    , "sTitle": "Compliance"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var elem = buildComplianceBar(oData.compliance);
        $(nTd).empty();
        $(nTd).append(elem);
      }
  } ];

  var params = {
      "bFilter" : false
    , "bPaginate" : false
    , "bLengthChange": false
    , "bInfo" : false
    , "aaSorting": [[ 0, "asc" ]]
    , "fnDrawCallback" : function( oSettings ) {
        if(isNodeView) {
          createInnerTable(this, createNodeComponentValueTable(contextPath));
        } else {
          createInnerTable(this, createRuleComponentValueTable(contextPath));
        }
      }
  }

  return function (gridId,data) {createTable(gridId,data,columns, params, contextPath);}
}


/*   Details of a value for a node
 *
 *   Javascript object containing all data to create a line in the DataTable
 *   { "value" : value of the key [String]
 *   , "status" : Worst status of the Directive [String]
 *   , "statusClass" : Class to use on status cell [String]
 *   , "messages" : Message linked to that value, only used in message popup [ Array[String] ]
 *   , "jsid"    : unique identifier for the line [String]
 *   }
 */
function createNodeComponentValueTable(contextPath) {

  var columns = [ {
      "sWidth": "20%"
    , "mDataProp": "value"
    , "sTitle": "Value"
  } , {
      "sWidth": "62.4%"
    , "mDataProp": "messages"
    , "sTitle": "Messages"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var list = $("<ul></ul>");
        for (index in sData) {
          var elem = $("<li></li>");
          if(sData.length > 1) {
            elem.text('['+ sData[index].status+'] '+ sData[index].value);
          } else {
            elem.text(sData[index].value);
          }
          list.append(elem);
        }
        $(nTd).empty();
        $(nTd).append(list);
      }
  } , {
      "sWidth": "17.6%"
    , "mDataProp": "status"
    , "sTitle": "Status"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        $(nTd).addClass("center "+oData.statusClass);
      }
  } ];

  var params = {
      "bFilter" : false
    , "bPaginate" : false
    , "bLengthChange": false
    , "bInfo" : false
    , "aaSorting": [[ 0, "asc" ]]
  }

  return function (gridId,data) {createTable(gridId, data, columns, params, contextPath); createTooltip();}

}

/*   Details of a value for component in a directive in a rule details.
 *   We don't have a status, but a compliance (composite values)
 *
 *   Javascript object containing all data to create a line in the DataTable
 *   { "value" : value of the key [String]
 *   , "compliance" : array of number of reports by compliance status [Array[Float]]
 *   , "compliancePercent" : Compliance percentage [Float]
 *   , "status" : Worst status of the Directive [String]
 *   , "statusClass" : Class to use on status cell [String]
 *   , "messages" : Message linked to that value, only used in message popup [ Array[String] ]
 *   , "jsid"    : unique identifier for the line [String]
 *   }
 */
function createRuleComponentValueTable (contextPath) {

  var complianceWidth = "27.7%";
  var componentSize = "72.3%";

  var columns = [ {
      "sWidth": componentSize
    , "mDataProp": "value"
    , "sTitle": "Value"
  } , {
        "sWidth": complianceWidth
      , "mDataProp": "compliancePercent"
      , "sTitle": "Compliance"
      , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
          var elem = buildComplianceBar(oData.compliance);
          $(nTd).empty();
          $(nTd).append(elem);
        }
  } ];

  var params = {
      "bFilter" : false
    , "bPaginate" : false
    , "bLengthChange": false
    , "bInfo" : false
    , "aaSorting": [[ 0, "asc" ]]
  }

  return function (gridId,data) {createTable(gridId, data, columns, params, contextPath); createTooltip();}

}


///////////////////////////////////////////////////
///////////////////  Nodes list ///////////////////
///////////////////////////////////////////////////


/*
 *  Table of nodes
 *
 *   Javascript object containing all data to create a line in the DataTable
 *   { "name" : Node hostname [String]
 *   , "id" : Node id [String]
 *   , "machineType" : Node machine type [String]
 *   , "osName" : Node OS name [String]
 *   , "osVersion" : Node OS version [ String ]
 *   , "servicePack" : Node OS service pack [ String ]
 *   , "lastReport" : Last report received about that node [ String ]
 *   , "callBack" : Callback on Node, if missing, replaced by a link to nodeId [ Function ]
 *   }
 */
function createNodeTable(gridId, data, contextPath, refresh) {

  //base element for the clickable cells
  function callbackElement(oData, displayCompliance) {
    var elem = $("<a></a>");
    if("callback" in oData) {
        elem.click(function(e) {
          oData.callback(displayCompliance);
          e.stopPropagation();
          $('#query-search-content').toggle(400);
          $('#querySearchSection').toggleClass('unfoldedSectionQuery');
        });
        elem.attr("href","javascript://");
    } else {
        elem.attr("href",contextPath+'/secure/nodeManager/searchNodes#{"nodeId":"'+oData.id+'","displayCompliance":'+displayCompliance+'}');
    }
    return elem;
  }
  var columns = [ {
      "sWidth": "25%"
    , "mDataProp": "name"
    , "sTitle": "Node name"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var link = callbackElement(oData, false)
        var icon = $("<img />");
        icon.attr("src",contextPath + "/images/icMagnify-right.png");
        link.append(icon);
        link.addClass("reportIcon");
        $(nTd).append(link);
      }
  } , {
      "sWidth": "10%"
    , "mDataProp": "machineType"
    , "sTitle": "Machine type"
  } , {
      "sWidth": "10%"
    , "mDataProp": "osName"
    , "sTitle": "OS name"
  } , {
      "sWidth": "5%"
    , "mDataProp": "osVersion"
    , "sTitle": "OS version"
  } , {
      "sWidth": "5%"
    , "mDataProp": "servicePack"
    , "sTitle": "OS SP"
  } , {
      "mDataProp": "name"
    , "sWidth": "25%"
    , "sTitle": "Compliance"
    , "sSortDataType": "node-compliance"
    , "sType" : "numeric"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var link = callbackElement(oData, true)
        var complianceBar = '<div id="compliance-bar-'+oData.id+'"><center><img height="26" width="26" src="'+contextPath+'/images/ajax-loader.gif" /></center></div>';
        link.append(complianceBar)
        $(nTd).empty();
        $(nTd).prepend(link);
      }
  } , {
      "sWidth": "20%"
    , "mDataProp": "lastReport"
    , "sTitle": "Last seen"
  } ];

  var params = {
      "bFilter" : true
    , "bPaginate" : true
    , "bLengthChange": true
    , "sPaginationType": "full_numbers"
    , "oLanguage": {
        "sSearch": ""
    }
    , "fnDrawCallback": function( oSettings ) {
        var rows = this._('tr', {"page":"current"});
        $.each(rows, function(index,row) {
          // Display compliance progress bar if it has already been computed
          var compliance = nodeCompliances[row.id]
          if (compliance !== undefined) {
            $("#compliance-bar-"+row.id).html(buildComplianceBar(compliance));
          }
        })
      }
    , "aaSorting": [[ 0, "asc" ]]
    , "sDom": '<"dataTables_wrapper_top newFilter"f<"dataTables_refresh">>rt<"dataTables_wrapper_bottom"lip>'
  };

  createTable(gridId,data, columns, params, contextPath, refresh, "nodes");

}

/*
 *  Table of changes requests
 *
 *   Javascript object containing all data to create a line in the DataTable
 *   { "name" : Change request name [String]
 *   , "id" : Change request id [String]
 *   , "step" : Change request validation step [String]
 *   , "creator" : Name of the user that has created the change Request [String]
 *   , "lastModification" : date of last modification [ String ]
 *   }
 */
function createChangeRequestTable(gridId, data, contextPath, refresh) {

  var columns = [ {
      "sWidth": "5%"
    , "mDataProp": "id"
    , "sTitle": "#"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        $(nTd).empty();
        $(nTd).addClass("link");
        var svg = '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" version="1.1" id="Capa_1" x="0px" y="0px" viewBox="0 0 493.356 493.356" xml:space="preserve"><g><path d="M490.498,239.278l-109.632-99.929c-3.046-2.474-6.376-2.95-9.993-1.427c-3.613,1.525-5.427,4.283-5.427,8.282v63.954H9.136 c-2.666,0-4.856,0.855-6.567,2.568C0.859,214.438,0,216.628,0,219.292v54.816c0,2.663,0.855,4.853,2.568,6.563 c1.715,1.712,3.905,2.567,6.567,2.567h356.313v63.953c0,3.812,1.817,6.57,5.428,8.278c3.62,1.529,6.95,0.951,9.996-1.708 l109.632-101.077c1.903-1.902,2.852-4.182,2.852-6.849C493.356,243.367,492.401,241.181,490.498,239.278z"/></g><g></g><g></g><g></g><g></g><g></g><g></g><g></g><g></g><g></g><g></g><g></g><g></g><g></g><g></g><g></g></svg>';
        var editLink = $("<a />");
        var iconNext =  $(svg);
        editLink.attr("href",contextPath +'/secure/utilities/changeRequest/'+sData);
        editLink.text(sData);
        editLink.append(iconNext);
        $(nTd).append(editLink);
      }
  } , {
      "sWidth": "10%"
    , "mDataProp": "step"
     , "sTitle": "Status"
  } , {
      "sWidth": "65%"
    , "mDataProp": "name"
    , "sTitle": "Name"
  } , {
      "sWidth": "10%"
    , "mDataProp": "creator"
    , "sTitle": "Creator"
  } , {
      "sWidth": "10%"
    , "mDataProp": "lastModification"
    , "sTitle": "Last Modification"
  } ];

  var params = {
      "bFilter" : true
    , "bPaginate" : true
    , "bLengthChange": true
    , "sPaginationType": "full_numbers"
    , "oLanguage": {
        "sSearch": ""
    }
    , "aaSorting": [[ 0, "asc" ]]
    , "sDom": '<"dataTables_wrapper_top newFilter"f<"dataTables_refresh">>rt<"dataTables_wrapper_bottom"lip>'
  };

  createTable(gridId,data, columns, params, contextPath, refresh, "change_requests");
}

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "executionDate" : Date report was executed [DateTime]
 *   , "severity" : Report severity [String]
 *   , "ruleName" : Rule name [String]
 *   , "directiveName": Directive name [String]
 *   , "component" : Report component [String]
 *   , "value" : Report value [String]
 *   , "message" : Report message [String]
 *   }
 */
function createTechnicalLogsTable(gridId, data, contextPath, refresh, pickEventLogsInInterval) {

  var columns = [ {
      "sWidth": "10%"
    , "mDataProp": "executionDate"
    , "sTitle": "Execution date"
  } , {
      "sWidth": "8%"
    , "mDataProp": "severity"
    , "sTitle": "Severity"
  } , {
      "sWidth": "17%"
    , "mDataProp": "ruleName"
    , "sTitle": "Rule"
  } , {
      "sWidth": "17%"
    , "mDataProp": "directiveName"
    , "sTitle": "Directive"
  } , {
      "sWidth": "12%"
    , "mDataProp": "component"
    , "sTitle": "Component"
  } , {
      "sWidth": "12%"
    , "mDataProp": "value"
    , "sTitle": "Value"
  } , {
      "sWidth": "24%"
    , "mDataProp": "message"
    , "sTitle": "Message"
  } ];

  var params = {
      "bFilter" : true
    , "bPaginate" : true
    , "bLengthChange": true
    , "sPaginationType": "full_numbers"
    , "oLanguage": {
        "sSearch": ""
    }
    , "aaSorting": [[ 0, "desc" ]]
    , "sDom": '<"dataTables_wrapper_top newFilter"f<"dataTables_refresh"><"dataTables_pickdates"><"dataTables_pickend"><"dataTables_pickstart">'+
      '>rt<"dataTables_wrapper_bottom"lip>'
  };

  createTable(gridId,data, columns, params, contextPath, refresh, "technical_logs", pickEventLogsInInterval);

}

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "executionDate" : Date report was executed [DateTime]
 *   , "node": node hostname [String]
 *   , "directiveName": Directive name [String]
 *   , "component" : Report component [String]
 *   , "value" : Report value [String]
 *   , "message" : Report message [String]
 *   }
 */
function createChangesTable(gridId, data, contextPath, refresh) {

  var columns = [ {
      "sWidth": "8%"
      , "mDataProp": "executionDate"
      , "sTitle": "Execution Date"
  } , {
      "sWidth": "10%"
    , "mDataProp": "nodeName"
    , "sTitle": "Node"
  } , {
      "sWidth": "17%"
    , "mDataProp": "directiveName"
    , "sTitle": "Directive"
  } , {
      "sWidth": "12%"
    , "mDataProp": "component"
    , "sTitle": "Component"
  } , {
      "sWidth": "12%"
    , "mDataProp": "value"
    , "sTitle": "Value"
  } , {
      "sWidth": "24%"
    , "mDataProp": "message"
    , "sTitle": "Message"
  } ];

  var params = {
      "bFilter" : true
    , "bPaginate" : true
    , "bLengthChange": true
    , "sPaginationType": "full_numbers"
    , "oLanguage": {
        "sSearch": ""
    }
    , "aaSorting": [[ 0, "asc" ]]
    , "sDom": '<"dataTables_wrapper_top newFilter"f>rt<"dataTables_wrapper_bottom"lip>'
  };

  createTable(gridId,data, columns, params, contextPath, refresh, "recent_changes");

}

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
function createEventLogTable(gridId, data, contextPath, refresh, pickEventLogsInInterval) {

  var columns = [ {
      "sWidth": "10%"
      , "mDataProp": "id"
      , "sTitle": "Id"
      , "sClass" : "eventId"
      , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        if( oData.hasDetails ) {
          $(nTd).addClass("listopen");
        }
      }
  } , {
      "sWidth": "20%"
    , "mDataProp": "date"
    , "sTitle": "Date"
  } , {
      "sWidth": "10%"
    , "mDataProp": "actor"
    , "sTitle": "Actor"
  } , {
      "sWidth": "30%"
    , "mDataProp": "type"
    , "sTitle": "Type"
  } , {
      "sWidth": "30%"
    , "mDataProp": "description"
    , "sTitle": "Description"
  } ];

  var params = {
      "bFilter" : true
    , "bPaginate" : true
    , "bLengthChange": true
    , "sPaginationType": "full_numbers"
    , "oLanguage": {
        "sSearch": ""
    }
    , "aaSorting": [[ 0, "desc" ]]
    , "fnDrawCallback" : function( oSettings ) {
        var myTable = this;
        var lines = $(myTable.fnGetNodes());
          lines.each( function () {
          var tableRow = $(this);
          var fnData = myTable.fnGetData( this );
          tableRow.attr("id",fnData.id);
          if (fnData.hasDetails) {
            tableRow.addClass("curspoint");
            // Remove all previously added callbacks on row or you will get problems
            tableRow.unbind();
            // Add callback to open the line
            tableRow.click( function (e) {
              e.stopPropagation();
              // Chack if our line is opened/closed
              var IdTd = tableRow.find("td.eventId");
              if (IdTd.hasClass("listclose")) {
                myTable.fnClose(this);
              } else {
                // Set details
                var detailsId =  'details-'+fnData.id;
                // First open the row an d set the id
                var openedRow = $(myTable.fnOpen(this,'',detailsId));
                var detailsTd = $("."+detailsId);
                detailsTd.attr("id",detailsId);
                // Set data in the open row with the details function from data
                fnData.details(detailsId);
                // Set final css
                var color = 'color1';
                if(tableRow.hasClass('color2'))
                  color = 'color2';
                openedRow.addClass(color + ' eventLogDescription');
              }
              // toggle list open / close classes
              IdTd.toggleClass('listopen');
              IdTd.toggleClass('listclose');
            } );
          }
        } );
      }
    , "sDom": '<"dataTables_wrapper_top newFilter"f<"dataTables_refresh"><"dataTables_pickdates"><"dataTables_pickend"><"dataTables_pickstart">'+
      '>rt<"dataTables_wrapper_bottom"lip>'
  };

  createTable(gridId,data, columns, params, contextPath, refresh, "event_logs", pickEventLogsInInterval);
}

/*
 * A function that build a compliance bar with colored zone for compliance
 * status cases based on Twitter Bootstrap: http://getbootstrap.com/components/#progress
 *
 * Await a JSArray:
 * (pending, success, repaired, error, noAnswer, notApplicable)
 *
 */
function buildComplianceBar(compliance, minPxSize) {

  function compliancePercentValue(complianceValue ) {
    var value = Number((complianceValue).toFixed(0));
    if (value == 0) {
      return "<1";
    } else {
      return value;
    }
  }
  var content = $('<div class="tw-bs progress"></div>');

  // set the default minimal size of array if not defined
  if (minPxSize === undefined) minPxSize = 20;
  // Correct compliance array, if sum is over 100, fix it y removing the excedent amount to the max value
  var sum = compliance.reduce(function(pv, cv) { return pv + cv; }, 0);

  if (sum > 100) {
    var max_of_array = Math.max.apply(Math, compliance);
    var index = compliance.indexOf(max_of_array);
    var toRemove = sum - 100
    compliance[index] = compliance[index] - toRemove;
  }

  var notApplicable = compliance[0];
  var success       = compliance[1];
  var repaired      = compliance[2];
  var okStatus      = success + repaired;
  var error         = compliance[3];
  var pending       = compliance[4];
  var noreport      = compliance[5];
  var missing       = compliance[6];
  var unknown       = compliance[7];
  var unexpected    = missing + unknown;

  var widthArr = getWidthProgressBar([notApplicable, okStatus, pending, noreport, unexpected, error], minPxSize);

  if(notApplicable != 0) {
    var value = compliancePercentValue(notApplicable);
    var tooltip = notApplicable.toFixed(2);
    content.append('<div class="progress-bar progress-bar-notapplicable" style=" width:'+widthArr[0]+'" title="Not applicable: '+tooltip+'%">'+value+'%</div>');
  }

  if(okStatus != 0) {
    var text = []
    if (success != 0) {
      text.push("Success: "+success.toFixed(2)+"%");
    }
    if (repaired != 0) {
      text.push("Repaired: "+repaired.toFixed(2)+"%");
    }
    var value = compliancePercentValue(okStatus);
    content.append('<div class="progress-bar progress-bar-success" style="width:'+widthArr[1]+'" title="'+text.join("\n")+'">'+value+'%</div>');
  }

  if(pending != 0) {
    var value = compliancePercentValue(pending);
    var tooltip = pending.toFixed(2);
    content.append('<div class="progress-bar progress-bar-pending active progress-bar-striped" style="width:'+widthArr[2]+'" title="Applying: '+tooltip+'%">'+value+'%</div>');
  }

  if(noreport != 0) {
    var value = compliancePercentValue(noreport);
    var tooltip = noreport.toFixed(2);
    content.append('<div class="progress-bar progress-bar-no-report" style=" width:'+widthArr[3]+'" title="No report: '+tooltip+'%">'+value+'%</div>');
  }

  if(unexpected != 0) {
    var text = []
    if (missing != 0) {
      text.push("Missing reports: "+missing.toFixed(2)+"%");
    }
    if (unknown != 0) {
      text.push("Unknown reports: "+unknown.toFixed(2)+"%");
    }
    var value = compliancePercentValue(unexpected);
    content.append('<div class="progress-bar progress-bar-unknown" style="width:'+widthArr[4]+'" title="'+text.join("\n")+'">'+value+'%</div>');
  }

  if(error != 0) {
    var value = compliancePercentValue(error);
    var tooltip = error.toFixed(2);
    content.append('<div class="progress-bar progress-bar-error" style="width:'+widthArr[5]+'" title="Error: '+tooltip+'%">'+value+'%</div>');
  }

  return content;

}

function getWidthProgressBar(arr, minPxSize){
  var finalWidth = [];

  //Recalculate the compliance bars size only on a Node Details page.
  //If it isn't the case, this function does nothing.
  if((Math.min.apply(Math,arr)<9)){
    var totalSmallBarsPercent = totalSmallBarsPx = 0;
    // Minimum given size (in px) of a bar
    var barWitdh;
    //We calculate the total percentage of the bars which are less than 5%.
    //Then we calculate the total size taken by them after have been resized.
    $(arr).each(function(index,compliancePercent) {
      if((compliancePercent<9)&&(compliancePercent>0)){
        totalSmallBarsPercent += compliancePercent;
        totalSmallBarsPx += minPxSize;
      }
    });
    //Here, we set the new width for each bar.
    $(arr).each(function(index,compliancePercent){
      if(compliancePercent<9){
        barWitdh = minPxSize+"px";
      }else{
        //We calculate the remaining free space of the Compliance Bar
        var baseSize = "(100% - " + totalSmallBarsPx + "px)";
        //Then we calculate the percentage of each bar with respect to this space.
        var percentBar = compliancePercent / (100 - totalSmallBarsPercent );
        barWitdh = "calc( "+baseSize+" * "+percentBar+")";
      }
      finalWidth.push(barWitdh);
    });
  }else{
    $(arr).each(function(index,compliancePercent){
      finalWidth.push(compliancePercent+"%");
    });
  }
  return finalWidth;
}

function refreshTable (gridId, data) {
  var table = $('#'+gridId).DataTable();
  table.clear();
  table.rows.add(data);
  table.draw();
}

function selectInterval(interval, element){
  $("#selectedPeriod").text(interval);
  $(".c3-bar-highlighted").each(function() {
    this.classList.remove("c3-bar-highlighted");
  });
  element.classList.add("c3-bar-highlighted");
}
function changeCursor(clickable){
  if(clickable){
    $('body').toggleClass('cursorPointer');
  }
}
/*
 * Function to define opening of an inner table
 */
function createInnerTable(myTable,  createFunction, contextPath, kind) {
  var plusTd = $(myTable.fnGetNodes());
  plusTd.each( function () {
    $(this).unbind();
    $(this).click( function (e) {
      if ($(e.target).hasClass('noExpand')) {
        return false;
      } else {
        var fnData = myTable.fnGetData( this );
        var i = $.inArray( this, anOpen );
        var detailsId = fnData.jsid ;
        if (kind !== undefined) {
          detailsId += "-"+kind
        }
        detailsId += "-details";
        if ( i === -1 ) {
          $(this).find("td.listopen").removeClass("listopen").addClass("listclose");
          var table = $("<table></table>");
          var tableId = fnData.jsid;
          if (kind !== undefined) {
            tableId += "-"+kind;
          }
          tableId += "-table";
          table.attr("id",tableId);
          table.attr("cellspacing",0);
          table.addClass("noMarginGrid");
          var div = $("<div></div>");
          div.addClass("innerDetails");
          div.attr("id",detailsId);
          div.append(table);
          var nDetailsRow = myTable.fnOpen( this, div, 'details' );
          var res = createFunction(tableId, fnData.details);
          $('div.dataTables_wrapper:has(table.noMarginGrid)').addClass('noMarginGrid');
          $('#'+detailsId).slideDown(300);
          anOpen.push( this );
        } else {
          $(this).find("td.listclose").removeClass("listclose").addClass("listopen");
          $('#'+detailsId).slideUp(300, function () {
            myTable.fnClose( this );
            anOpen.splice( i, 1 );
          } );
        }
      }
    } );
  } );
}

// Create a table from its id, data, columns, custom params, context patch and refresh function
function createTable(gridId,data,columns, customParams, contextPath, refresh, storageId, pickEventLogsInInterval) {
  var defaultParams = {
      "asStripeClasses": [ 'color1', 'color2' ]
    , "bAutoWidth": false
    , "aoColumns": columns
    , "aaData": data
    , "bJQueryUI": true
    , "lengthMenu": [ [10, 25, 50, 100, 500, 1000, -1], [10, 25, 50, 100, 500, 1000, "All"] ]
    , "pageLength": 25
  };

  if (storageId !== undefined) {
    var storageParams = {
        "bStateSave" : true
      , "fnStateSave": function (oSettings, oData) {
          localStorage.setItem( 'DataTables_'+storageId, JSON.stringify(oData) );
        }
      , "fnStateLoad": function (oSettings) {
          return JSON.parse( localStorage.getItem('DataTables_'+storageId) );
        }
    }

    $.extend(defaultParams,storageParams);
  }

  var params = $.extend({},defaultParams,customParams);
  $('#'+gridId).dataTable( params );
  $('#'+gridId+' thead tr').addClass("head");
  if (!( typeof refresh === 'undefined')) {
    var refreshButton = $("<button><img src='"+contextPath+"/images/icRefresh.png'/></button>");
    refreshButton.button();
    refreshButton.attr("title","Refresh");
    refreshButton.click( function() { refresh(); } );
    refreshButton.addClass("refreshButton");
    $("#"+gridId+"_wrapper .dataTables_refresh").append(refreshButton);
  }

  $("#"+gridId+"_wrapper .dataTables_refresh button").tooltip({
      show: { effect: "none", delay: 0 }
    , hide: { effect: "none",  delay: 0 }
    , position: { my: "left+40 bottom-10", collision: "flipfit" }
  } );

  $('.dataTables_filter input').attr("placeholder", "Filter");

  if (!( typeof pickEventLogsInInterval === 'undefined')) {
    $('#filterLogs').removeClass('hide');
    //Initialize the two datepickers
    $('#filterLogs .pickStartInput, #filterLogs .pickEndInput').datetimepicker({dateFormat:'yy-mm-dd', timeFormat: 'HH:mm:ss', timeInput: true});
    $('#filterLogsButton').click(pickEventLogsInInterval);
  }

  return $('#'+gridId).DataTable();
}



/**
 * The set of option that allows to configure chart js to be used as a sparkline.
 */
function chartjsSparklineOption() {
  return {
      // Boolean - Whether to animate the chart
      animation: false //true
      // Number - Number of animation steps
    , animationSteps: 60
      // String - Animation easing effect
    , animationEasing: "easeOutQuart"
      // Boolean - If we should show the scale at all
    , showScale: false //true
      // Boolean - If we want to override with a hard coded scale
    , scaleOverride: false
      // ** Required if scaleOverride is true **
      // Number - The number of steps in a hard coded scale
    , scaleSteps: null
      // Number - The value jump in the hard coded scale
    , scaleStepWidth: null
      // Number - The scale starting value
    , scaleStartValue: null
      // String - Colour of the scale line
    , scaleLineColor: "rgba(0,0,0,.1)"
      // Number - Pixel width of the scale line
    , scaleLineWidth: 1
      // Boolean - Whether to show labels on the scale
    , scaleShowLabels: false //true
      // Interpolated JS string - can access value
    , scaleLabel: "<%=value%>"
      // Boolean - Whether the scale should stick to integers, not floats even if drawing space is there
    , scaleIntegersOnly: true
      // Boolean - Whether the scale should start at zero, or an order of magnitude down from the lowest value
    , scaleBeginAtZero: false
      // String - Scale label font declaration for the scale label
    , scaleFontFamily: "'Helvetica Neue', 'Helvetica', 'Arial', sans-serif"
      // Number - Scale label font size in pixels
    , scaleFontSize: 12
      // String - Scale label font weight style
    , scaleFontStyle: "normal"
      // String - Scale label font colour
    , scaleFontColor: "#666"
      // Boolean - whether or not the chart should be responsive and resize when the browser does.
    , responsive: false
      // Boolean - whether to maintain the starting aspect ratio or not when responsive, if set to false, will take up entire container
    , maintainAspectRatio: true
      // Boolean - Determines whether to draw tooltips on the canvas or not
    , showTooltips: true
      // Array - Array of string names to attach tooltip events
    , tooltipEvents: ["mousemove", "mouseout"]
      // String - Tooltip background colour
    , tooltipFillColor: "rgba(0,0,0,0.8)"
      // String - Tooltip label font declaration for the scale label
    , tooltipFontFamily: "'Helvetica Neue', 'Helvetica', 'Arial', sans-serif"
      // Number - Tooltip label font size in pixels
    , tooltipFontSize: 10 //14
      // String - Tooltip font weight style
    , tooltipFontStyle: "normal"
      // String - Tooltip label font colour
    , tooltipFontColor: "#fff"
      // String - Tooltip title font declaration for the scale label
    , tooltipTitleFontFamily: "'Helvetica Neue', 'Helvetica', 'Arial', sans-serif"
      // Number - Tooltip title font size in pixels
    , tooltipTitleFontSize: 10 //14
      // String - Tooltip title font weight style
    , tooltipTitleFontStyle: "bold"
      // String - Tooltip title font colour
    , tooltipTitleFontColor: "#fff"
      // Number - pixel width of padding around tooltip text
    , tooltipYPadding: 2 //6
      // Number - pixel width of padding around tooltip text
    , tooltipXPadding: 2 //6
      // Number - Size of the caret on the tooltip
    , tooltipCaretSize: 2 //8
      // Number - Pixel radius of the tooltip border
    , tooltipCornerRadius: 6
      // Number - Pixel offset from point x to tooltip edge
    , tooltipXOffset: 2 //10
      // String - Template string for single tooltips
    , tooltipTemplate: "<%if (label){%><%=label%>: <%}%><%= value%> changes"
      // String - Template string for single tooltips
    , multiTooltipTemplate: "<%= value %>"
      // Function - Will fire on animation progression.
    , onAnimationProgress: function(){}
      // Function - Will fire on animation completion.
    , onAnimationComplete: function(){}

      ///// bar specific /////


    //Boolean - Whether the scale should start at zero, or an order of magnitude down from the lowest value
    , scaleBeginAtZero : true

    //Boolean - Whether grid lines are shown across the chart
    , scaleShowGridLines : false

    //String - Colour of the grid lines
    , scaleGridLineColor : "rgba(0,0,0,.05)"

    //Number - Width of the grid lines
    , scaleGridLineWidth : 1

    //Boolean - If there is a stroke on each bar
    , barShowStroke : true

    //Number - Pixel width of the bar stroke
    , barStrokeWidth : 1

    //Number - Spacing between each of the X value sets
    , barValueSpacing : 0

    //Number - Spacing between data sets within X values
    , barDatasetSpacing : 0

      //String - A legend template
    , legendTemplate : "" //"<ul class=\"<%=name.toLowerCase()%>-legend\"><% for (var i=0; i<datasets.length; i++){%><li><span style=\"background-color:<%=datasets[i].lineColor%>\"></span><%if(datasets[i].label){%><%=datasets[i].label%><%}%></li><%}%></ul>"
  };
}
