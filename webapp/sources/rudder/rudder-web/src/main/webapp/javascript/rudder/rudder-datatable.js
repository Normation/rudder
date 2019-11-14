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
/* Create an array with the values of all the checkboxes in a column */
$.fn.dataTable.ext.order['dom-checkbox'] = function  ( settings, col )
{
    return this.api().column( col, {order:'index'} ).nodes().map( function ( td, i ) {
        return $('input', td).prop('checked') ? '0' : '1';
    } );
}

/*
 * This function is used to resort a table after its sorting data were changed ( like sorting function below)
 */
function resortTable (tableId) {
  var table = $("#"+tableId).DataTable({"retrieve" : true});
  table.draw();
}


$.fn.dataTable.ext.search.push(
    function(settings, data, dataIndex ) {
        // param needs to be JSON only so we remove the hash tag # at index 0

        var param = decodeURIComponent(window.location.hash.substring(1));
        if (param !== "" && window.location.pathname === contextPath + "/secure/nodeManager/nodes") {
            var obj = JSON.parse(param);
            var min = obj.complianceFilter.min;
            var max = obj.complianceFilter.max;

            if (min === undefined)
                return true;

            if (nodeCompliances !== undefined) {

                // here, we get the id of the row element by looking deep inside settings...
                // maybe there exists something cleaner.

                var complianceArray = nodeCompliances[settings.aoData[dataIndex]._aData.id];
                 if (complianceArray !== undefined) {
                     var compliance = computeCompliancePercent(complianceArray);

                     if (max === undefined)
                        return compliance >= min;
                      else
                        return compliance >= min && compliance < max;
                 }
            }
        }
      return true;
    });


$.fn.dataTableExt.afnSortData['compliance'] = function ( oSettings, iColumn )
{
    var data =
      $.map(
          // All data of the table
          oSettings.oApi._fnGetDataMaster(oSettings)
        , function (elem, index) {
            if (elem.id in ruleCompliances) {
              var compliance = ruleCompliances[elem.id];
              return computeCompliancePercent(compliance)
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
              return computeCompliancePercent(compliance)
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

function recentChangesGraph(changes, graphId, displayFullGraph) {
  var context = $("#"+graphId)

  var chartData  = {
    labels  : changes.labels,
    datasets: [{
        data : changes.values
      , label: "changes"
      , backgroundColor: 'rgba(54, 162, 235, 0.2)'
      , borderWidth: 1
      , bordercolor: 'rgba(54, 162, 235, 1)'
    }]
  };

  var option = {
      legend : {
        display : false
      }
    , title : {
        display : false
      }
    , responsive: true
    , maintainAspectRatio: false
    , scales: {
        xAxes: [{
            display: displayFullGraph
          , categoryPercentage:1
          , barPercentage:1
        }]
      , yAxes: [{
          display: displayFullGraph
        , ticks: {
              beginAtZero: true
            , min : 0
          }
        }]
      }
    , tooltips : {
          enabled: displayFullGraph
        , custom: graphTooltip
        , displayColors : false
        , callbacks: {
            title: function(tooltipItem, data) {
              return tooltipItem[0].xLabel.join(" ");
            }
          , label: function(tooltipItem, data) {
              return tooltipItem.yLabel + " changes over the period";
            }
          }
      }
  }

  return new Chart(context, {
      type: 'bar'
    , data: chartData
    , options : option
  });

}

var count = 0
function generateRecentGraph(id, displayGraph) {
  if (displayGraph) {
    var container = $("#Changes-"+id);
    var graphId = "canvas-"+id;
    var changes = recentChanges[id];
    if (changes !== undefined) {
      container.empty()
      container.append('<canvas id="'+graphId+'" height="20" ></canvas>');

      var myBarChart = recentChangesGraph(changes,graphId,false)

      recentGraphs[id] = myBarChart;

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
    lastChanges = changes.values[changes.values.length - 1];
  }

  // Prepare graph elem to have tooltip
  graphElem.attr("tooltipid",tooltipId);
  graphElem.attr("title","");

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
  createTooltip();$('.rudder-label').bsTooltip();
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
function createRuleTable(gridId, data, checkboxColumn, actionsColumn, complianceColumn, recentChangesGraph, allCheckboxCallback, contextPath, refresh, isPopup) {

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
    , "bSortable": true
    , "orderDataType": "dom-checkbox"
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
  var tags = {
      "mDataProp"     : "tags"
    , "sTitle"        : "Tags"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        $(nTd).empty();
        var tag = JSON.stringify(oData.tags);
        $(nTd).text(tag);
      }
  };
  // Name of the rule
  // First mandatory row, so do general thing on the row ( line css, description tooltip ...)
  var name = {
      "mDataProp": "name"
    , "sWidth": "28%"
    , "sTitle": "Name"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var data = oData;
        // Define the elem and its callback
        var elem = callbackElement(oData, "showForm");
        elem.text(data.name);

        elem.click(function(){
          localStorage.setItem('Active_Rule_Tab', 1);
        });

        // Row parameters
        var parent = $(nTd).parent();
        // Add Class on the row, and id
        if(!isPopup){
            parent.addClass(data.trClass);
        }
        parent.attr("id",data.id);

        // Description tooltip over the row
        if ( data.description.length > 0) {
          elem.attr("title","<div><h3>"+data.name+"</h3>"+ data.description+"</div>");
        }

        // Append the content to the row
        $(nTd).empty();
        $(nTd).prepend(elem);
        var badge = createBadgeAgentPolicyMode('rule',data.policyMode, data.explanation);
        $(nTd).prepend(badge);
      }
  };

  // Rule Category
  var category =
    { "mDataProp": "category"
    , "sWidth": "12%"
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
          elem.attr("title","<div><h3>Reason(s)</h3>"+ data.reasons+"</div>");
          elem.addClass("tooltip tooltipable")
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
        elem.append('<div id="compliance-bar-'+oData.id+'"><center><img class="ajaxloader svg-loader" src="'+resourcesPath+'/images/ajax-loader.svg" /></center></div>');
        elem.click(function(){
          localStorage.setItem('Active_Rule_Tab', 1);
        });
        $(nTd).empty();
        $(nTd).prepend(elem);
      }
  };

  // recent changes as graph, with link to the edit form
  var recentChanges = {
      "mDataProp": "name"
    , "sWidth": "15%"
    , "sTitle": "Recent changes"
    , "sSortDataType": "changes"
    , "sType" : "numeric"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var elem = callbackElement(oData, "showRecentChanges");
        var id = "Changes-"+oData.id;
        elem.append('<div id="'+id+'"></div>');
        elem.click(function(){
          localStorage.setItem('Active_Rule_Tab', 1);
        });
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
        elem.addClass("btn btn-default btn-xs");
        elem.click( function() {
          data.callback("showForm");
          localStorage.setItem('Active_Rule_Tab', 0);
        } );
        elem.text("Edit");
        $(nTd).empty();
        $(nTd).prepend(elem);
      }
  };

  // Choose which columns should be included
  var columns = [];
  columns.push(tags);
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
    , "columnDefs": [{
          "targets": [ 0 ]
        , "visible": false
        , "searchable": true
      }]
    , "fnDrawCallback": function( oSettings ) {
      $('.rudder-label').bsTooltip();
      $('#updateRuleTable').on('click',function(){
        refresh();
      })
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
    , "aaSorting": [[ 0, "asc" ] , [ sortingDefault, "asc" ]]
    , "sDom": 'rt<"dataTables_wrapper_bottom"lip>'
  }
  var table = createTable(gridId,data,columns, params, contextPath, refresh, "rules", isPopup);
  table.search("").columns().search("");

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
        $(nTd).empty();
        //rule name is escaped server side, avoid double escape with .text()
        $(nTd).html(oData.rule);
        if (! oData.isSystem) {
          var editIcon = $("<i>");
          editIcon.addClass("fa fa-pencil");
          var editLink = $("<a />");
          editLink.attr("href",contextPath + '/secure/configurationManager/ruleManagement#{"ruleId":"'+oData.id+'"}');
          editLink.click(function(e) {e.stopPropagation();});
          editLink.append(editIcon);
          editLink.addClass("reportIcon");
          $(nTd).append(editLink);
          $(nTd).prepend(createBadgeAgentPolicyMode('rule', oData.policyMode, oData.explanation));
        }
      }
  } , {
    "sWidth": "25%"
      , "mDataProp": "compliancePercent"
      , "sTitle": "Status"
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
        $('.rudder-label').bsTooltip();
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
    return function (gridId,data) {createTable(gridId, data, columns, defaultParams, contextPath); createTooltip();$('.rudder-label').bsTooltip();}
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
          var tooltipIcon = $("<i>");
          tooltipIcon.addClass("fa fa-question-circle icon-info tooltipable");
          var tooltipId = oData.jsid+"-tooltip";
          tooltipIcon.attr("tooltipid",tooltipId);
          tooltipIcon.attr("title","");
          var toolTipContainer= $("<div>Directive '<b>"+sData+"</b>' is based on technique '<b>"+oData.techniqueName+"</b>' (version "+oData.techniqueVersion+")</div>");
          toolTipContainer.addClass("tooltipContent");
          toolTipContainer.attr("id",tooltipId);
          $(nTd).append(tooltipIcon);
          $(nTd).append(toolTipContainer);

          if (! oData.isSystem) {
            var editLink = $("<a />");
            editLink.attr("href",contextPath + '/secure/configurationManager/directiveManagement#{"directiveId":"'+oData.id+'"}');
            var editIcon = $("<i>");
            editIcon.addClass("fa fa-pencil");
            editLink.click(function(e) {e.stopPropagation();});
            editLink.append(editIcon);
            editLink.addClass("reportIcon");
            var policyMode = oData.policyMode ? oData.policyMode : "";
            $(nTd).prepend(createBadgeAgentPolicyMode('directive', policyMode, oData.explanation));
            $(nTd).append(editLink);
          }
        }
    } ];

    var params = jQuery.extend({"fnDrawCallback" : function( oSettings ) {
      createInnerTable(this, localComponentTable(), contextPath, "directive");
    }}, defaultParams);


    return function (gridId, data, refresh) {
      createTable(gridId, data, columns, params, contextPath, refresh);
      createTooltip();$('.rudder-label').bsTooltip();
    }
  };

  var ruleColumn = [ {
    "mDataProp": "rule"
  , "sTitle"   : "Rule"
  , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
      $(nTd).addClass("listopen");
      $(nTd).text(oData.rule);
      if (! oData.isSystem) {
        var editLink = $("<a />");
        editLink.attr("href",contextPath + '/secure/configurationManager/ruleManagement#{"ruleId":"'+oData.id+'"}');
        var editIcon = $("<i>");
        editIcon.addClass("fa fa-pencil");
        editLink.click(function(e) {e.stopPropagation();});
        editLink.append(editIcon);
        editLink.addClass("reportIcon");
        $(nTd).append(editLink);
        $(nTd).prepend(createBadgeAgentPolicyMode('rule', oData.policyMode, oData.explanation));
      }
    }
  } ];
  var params = jQuery.extend({"fnDrawCallback" : function( oSettings ) {
        createInnerTable(this, localDirectiveTable(), contextPath, "rule");
        $('.rudder-label').bsTooltip();
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
        $(nTd).empty();
        //directive name is escaped server side, avoid double escape with document.createTextNode()
        $(nTd).append(oData.directive);
        $(nTd).addClass("listopen");
        var tooltipIcon = $("<i>");
        tooltipIcon.addClass("fa fa-question-circle icon-info");
        var tooltipId = oData.jsid+"-tooltip";
        tooltipIcon.attr("tooltipid",tooltipId);
        tooltipIcon.attr("title","");
        tooltipIcon.addClass("tooltipable");
        var toolTipContainer= $("<div>Directive '<b>"+sData+"</b>' is based on technique '<b>"+oData.techniqueName+"</b>' (version "+oData.techniqueVersion+")</div>");
        toolTipContainer.addClass("tooltipContent");
        toolTipContainer.attr("id",tooltipId);
        $(nTd).append(tooltipIcon);
        $(nTd).append(toolTipContainer);
        if (! oData.isSystem) {
          var editLink = $("<a />");
          editLink.attr("href",contextPath + '/secure/configurationManager/directiveManagement#{"directiveId":"'+oData.id+'"}');
          var editIcon = $("<i>");
          editIcon.addClass("fa fa-pencil");
          editLink.click(function(e) {e.stopPropagation();});
          editLink.append(editIcon);
          editLink.addClass("reportIcon");
          $(nTd).append(editLink);
          var policyMode = oData.policyMode ? oData.policyMode : policyMode ;
          $(nTd).prepend(createBadgeAgentPolicyMode('directive', policyMode, oData.explanation, '#editRuleZonePortlet'));
        }
      }
  } , {
      "sWidth": complianceWidth
    , "mDataProp": "compliancePercent"
    , "sTitle": "Status"
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
        $('.rudder-label').bsTooltip();
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
    createTooltip();$('.rudder-label').bsTooltip();
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
        var editIcon = $("<i>");
        editIcon.addClass("fa fa-search node-details");
        editLink.click(function(e) {e.stopPropagation();});
        editLink.append(editIcon);
        editLink.addClass("reportIcon");
        $(nTd).append(editLink);
        $(nTd).prepend(createBadgeAgentPolicyMode('node', oData.policyMode, oData.explanation, '#editRuleZonePortlet'));
      }
  } , {
      "sWidth": "25%"
    , "mDataProp": "compliancePercent"
    , "sTitle": "Status"
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
        $('.rudder-label').bsTooltip();
      }
    , "sDom": '<"dataTables_wrapper_top newFilter"f<"dataTables_refresh">>rt<"dataTables_wrapper_bottom"lip>'
  };

  createTable(gridId, data, columns, params, contextPath, refresh);

  createTooltip();$('.rudder-label').bsTooltip();
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
    , "sTitle": "Status"
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
            //message  is escaped server side, avoid double escape with .text()
            elem.html('['+ sData[index].status+'] '+ sData[index].value);
          } else {
            //message  is escaped server side, avoid double escape with .text()
            elem.html(sData[index].value);
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
  return function (gridId,data) {createTable(gridId, data, columns, params, contextPath); createTooltip();$('.rudder-label').bsTooltip();}

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
      , "sTitle": "Status"
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

  return function (gridId,data) {createTable(gridId, data, columns, params, contextPath); createTooltip();$('.rudder-label').bsTooltip();}

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
 *   , "os" : Node OS name, version and service pack [String]
 *   , "lastReport" : Last report received about that node [ String ]
 *   , "callBack" : Callback on Node, if missing, replaced by a link to nodeId [ Function ]
 *   }
 */
function createNodeTable(gridId, data, contextPath, refresh) {
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
      //only for search, not visible - see "columnDefs" def in parameter
      "mDataProp": "state"
  } , {
      "sWidth": "25%"
    , "mDataProp": "name"
    , "sTitle": "Node name"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var link = callbackElement(oData, false)
        var state = "";
        if(oData.state != "enabled") {
          state = '<span class="rudder-label label-state label-sm" style="margin-left: 5px;">'+oData.state+'</span>'
        }
        var el = '<span>'+sData+state+'</span>';
        var nodeLink = $(el);
        link.append(nodeLink);
        $(nTd).empty();
        $(nTd).append(link);
      }
  } , {
      "sWidth": "10%"
    , "mDataProp": "machineType"
    , "sTitle": "Machine type"
  } , {
      "sWidth": "22%"
    , "mDataProp": "os"
    , "sTitle": "Operating System"
  } , {
      "mDataProp": "agentPolicyMode"
    , "sWidth": "8%"
    , "sTitle": "Policy Mode"
    , "sClass" : "tw-bs"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        $(nTd).empty();
        $(nTd).prepend(createTextAgentPolicyMode(true,oData.agentPolicyMode,oData.explanation));
      }
  } , {
      "mDataProp": "name"
    , "sWidth": "25%"
    , "sTitle": "Compliance"
    , "sSortDataType": "node-compliance"
    , "sType" : "numeric"
    , "sClass" : "tw-bs"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var link = callbackElement(oData, true)
        var complianceBar = '<div id="compliance-bar-'+oData.id+'"><center><img class="svg-loader" src="'+resourcesPath+'/images/ajax-loader.svg" /></center></div>';
        link.append(complianceBar)
        $(nTd).empty();
        $(nTd).prepend(link);
      }
  } , {
      "sWidth": "10%"
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
    , "columnDefs": [{
          "targets": [ 0 ]
        , "visible": false
        , "searchable": true

      } , {
        "type"    : "natural-ci"
      , "targets" : 3
      }]
    , "fnDrawCallback": function( oSettings ) {

        $('[data-toggle="tooltip"]').bsTooltip();
        var rows = this._('tr', {"page":"current"});
        $.each(rows, function(index,row){
           // Display compliance progress bar if it has already been computed
           var compliance = nodeCompliances[row.id];
           if (compliance !== undefined) {
           $("#compliance-bar-"+row.id).html(buildComplianceBar(compliance));
          }
        })
        $('.rudder-label').bsTooltip();
      }
    , "aaSorting": [[ 0, "asc" ]]
    , "sDom": '<"dataTables_wrapper_top newFilter"f<"dataTables_refresh">>rt<"dataTables_wrapper_bottom"lip>'
  };

  createTable(gridId,data, columns, params, contextPath, refresh, "nodes");
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

  createTable(gridId,data, columns, params, contextPath, refresh, "technical_logs", false, pickEventLogsInInterval);

}

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "executionDate" : Date report was executed [DateTime]
 *   , "node": node hostname [String]
 *   , "directiveName": Directive name [String]
 *   , "directiveId": Directive id [String]
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
                tableRow.removeClass("opened");
              } else {
                tableRow.addClass("opened");
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

  createTable(gridId,data, columns, params, contextPath, refresh, "event_logs", false, pickEventLogsInInterval);
}

function computeCompliancePercent (complianceArray) {
  return computeComplianceOK(complianceArray).percent;
}

function computeComplianceOK (complianceArray) {
  if (Array.isArray(complianceArray)) {
    // Enforce N/A (1) + Audit N/A (9) + Repaired (3) + Enforce success (2) + Audit success (10)
    return {
     percent : complianceArray[1].percent + complianceArray[9].percent + complianceArray[3].percent + complianceArray[2].percent + complianceArray[10].percent
    , number :complianceArray[1].number + complianceArray[9].number + complianceArray[3].number + complianceArray[2].number + complianceArray[10].number
    }
  } else {
    return { percent: 0, number: 0};
  }
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

  if (Array.isArray(compliance)) {
    //Set the default minimal size and displayed value of compliance bars if not defined
    if (minPxSize === undefined) minPxSize = 5;

    var content = $('<div class="progress"></div>');

    // Correct compliance array, if sum is over 100, fix it y removing the excedent amount to the max value
    var sum = compliance.reduce(function(pv, cv) {return pv.percent + cv.percent; }, 0);
    if (sum > 100) {
      var compliancePercent = compliance.map(function(x) { return x.percent});
      var max_of_array = Math.max.apply(Math, compliancePercent);
      var index = compliancePercent.indexOf(max_of_array);
      var toRemove = sum - 100;
      var newMax = compliance[index];
      newMax[percent] = max_of_array - toRemove;
      compliance[index] = newMax;
    }

    var reportsDisabled      = compliance[0];  // - 5
    var enforceNotApplicable = compliance[1];  // - 0
    var enforceSuccess       = compliance[2];  // - 0
    var repaired             = compliance[3];  // - 0
    var enforceError         = compliance[4];  // - 2
    var pending              = compliance[5];  // - 4
    var noreport             = compliance[6];  // - 6
    var missing              = compliance[7];  // - 3
    var unknown              = compliance[8];  // - 3
    var auditNotApplicable   = compliance[9];  // - 0
    var compliant            = compliance[10]; // - 0
    var nonCompliant         = compliance[11]; // - 1
    var auditError           = compliance[12]; // - 2
    var badPolicyMode        = compliance[13]; // - 3

    var okStatus = computeComplianceOK(compliance);
    var unexpected =
    { percent : missing.percent + unknown.percent + badPolicyMode.percent
    , number : missing.number + unknown.number + badPolicyMode.number
    };
    var error =
    { percent : enforceError.percent + auditError.percent
    , number : enforceError.number + auditError.number
    };

    var complianceBars = getProgressBars([
        /*0*/ okStatus
      , /*1*/ nonCompliant
      , /*2*/ error
      , /*3*/ unexpected
      , /*4*/ pending
      , /*5*/ reportsDisabled
      , /*6*/ noreport
    ] , minPxSize);

    var precision = 2;
    if(okStatus.number != 0) {
      var text = []
      if (enforceSuccess.number != 0) {
        text.push("Success (enforce): "+enforceSuccess.percent.toFixed(precision)+"% <br> ");
      }
      if (compliant.number != 0) {
        text.push("Compliant: "+compliant.percent.toFixed(precision)+"% <br> ");
      }
      if (repaired.number != 0) {
        text.push("Repaired: "+repaired.percent.toFixed(precision)+"% <br> ");
      }
      if (enforceNotApplicable.number != 0) {
        text.push("Not applicable (enforce): "+enforceNotApplicable.percent.toFixed(precision)+"% <br> ");
      }
      if (auditNotApplicable.number != 0) {
        text.push("Not applicable (audit): "+auditNotApplicable.percent.toFixed(precision)+"% <br> ");
      }
      content.append('<div class="progress-bar progress-bar-success" style="width:'+complianceBars[0].width+'" title="'+text.join("\n")+'">'+complianceBars[0].value+'</div>');
    }

    if(nonCompliant.number != 0) {
      var text = []
      text.push("Non compliance: "+nonCompliant.percent.toFixed(precision)+"%");
      content.append('<div class="progress-bar progress-bar-audit-noncompliant" style="width:'+complianceBars[1].width+'" title="'+text.join("\n")+'">'+complianceBars[1].value+'</div>');
    }

    if(error.number != 0) {
      var text = []
      if (enforceError.number != 0) {
        text.push("Errors (enforce): "+enforceError.percent.toFixed(precision)+"% <br> ");
      }
      if (auditError.number != 0) {
        text.push("Errors (audit): "+auditError.percent.toFixed(precision)+"% <br> ");
      }
      content.append('<div class="progress-bar progress-bar-error" style="width:'+complianceBars[2].width+'" title="'+text.join("\n")+'">'+complianceBars[2].value+'</div>');
    }

    if(unexpected.number != 0) {
      var text = []
      if (missing.number != 0) {
        text.push("Missing reports: "+missing.percent.toFixed(precision)+"% <br> ");
      }
      if (unknown.number != 0) {
        text.push("Unknown reports: "+unknown.percent.toFixed(precision)+"% <br> ");
      }
      if (badPolicyMode.number != 0) {
        text.push("Not supported mixed mode on directive from same Technique: "+badPolicyMode.percent.toFixed(precision)+"% <br> ");
      }
      content.append('<div class="progress-bar progress-bar-unknown progress-bar-striped" style="width:'+complianceBars[3].width+'" title="'+text.join("\n")+'">'+complianceBars[3].value+'</div>');
    }

    if(pending.number != 0) {
      var tooltip = pending.percent.toFixed(precision);
      content.append('<div class="progress-bar progress-bar-pending progress-bar-striped" style="width:'+complianceBars[4].width+'" title="Applying: '+tooltip+'%">'+complianceBars[4].value+'</div>');
    }

    if(reportsDisabled.number != 0) {
      var tooltip = reportsDisabled.percent.toFixed(precision);
      content.append('<div class="progress-bar progress-bar-reportsdisabled" style="width:'+complianceBars[5].width+'" title="Reports Disabled: '+tooltip+'%">'+complianceBars[5].value+'</div>')
    }

    if(noreport.number != 0) {
      var tooltip = noreport.percent.toFixed(precision);
      content.append('<div class="progress-bar progress-bar-no-report" style=" width:'+complianceBars[6].width+'" title="No report: '+tooltip+'%">'+complianceBars[6].value+'</div>');
    }

    var container = $('<div></div>');
    container.append(content);

    $(window).on('resize',function(){
      adjustComplianceBar(content);
    });

    return container
  } else {
    return compliance
  }
}

function adjustComplianceBar(bar){
  bar.find('.progress-bar').each(function(){
    var w  = $(this).width();
    var s  = $(this).find('span');
    var sw = s.width();
    if(sw > w){
      s.addClass('invisible');
    }else{
      s.removeClass('invisible');
    }
  });
}

function compliancePercentValue(compliances) {
  var decomposedValues = [];
  var obj = {};
  var tmp,diff,total;
  for(var i in compliances){
    tmp = compliances[i];
    obj = {
      val : parseInt(tmp.percent)
    , dec : (tmp.percent)%1
    , ind : parseInt(i)
    , number: tmp.number
    };
    decomposedValues.push(obj);
  }

  decomposedValues.sort(function(a,b){return b.dec - a.dec;});
  total = decomposedValues.reduce(function(a, b) {;return {val : (a.val + b.val)}; }, {val:0}).val;

  //we can have total = 0 in the case of overriden directives. We don't want to loop until 100.
  //in fact, that loop can't be ok if (100 - total) > decomposedValue.length
  diff = 100 - total;

  for(var i=0; i<diff && i<decomposedValues.length; i++){
    decomposedValues[i].val++;
  }
  decomposedValues.sort(function(a,b){return a.ind - b.ind;});
  return decomposedValues;
}

function computeSmallBarsSizeAndPercent (compliances, minVal, minPxSize) {
  res = {
    percent   : 0
  , pixelSize : 0
  };
  //We calculate the total percentage of the bars which are less than minSize%.
  //Then we calculate the total size taken by them after have been resized.
  $(compliances).each(function(index,compliance) {
    // This is the integer part, that we will use to compute size of the bar
    var compliancePercent = compliance.val;
    // Full compliance value (integer and decimal) we need that to check that we do have a value, we only ignore 0
    var realValue = compliancePercent+ compliance.dec;
    if((compliancePercent < minVal) && (realValue > 0 || compliance.number > 0)){
      res.percent += compliancePercent;
      res.pixelSize += minPxSize;
    }
  });
  return res;
}

function getProgressBars(arr, minPxSize){
  //Values less than 8 are hidden by default on small devices
  var minVal = 3;
  var bars = [];
  function displayValue(value){
    //TODO : Add condition for tabs
    var percent = value>minVal ? String(value)+"%" : "";
    return "<span>"+percent+"</span>";
  }
  var compliances = compliancePercentValue(arr);

  // Minimum given size (in px) of a bar
  var bar;
  //We calculate the total percentage of the bars which are less than minSize%.
  //Then we calculate the total size taken by them after have been resized.
  var totalSmallBars = computeSmallBarsSizeAndPercent(compliances, minVal, minPxSize);

  //Here, we set the new width for each bar.
  $(compliances).each(function(index,compliance){

    var compliancePercent = compliance.val;
    if(compliancePercent < minVal){
      bar = {
        width: minPxSize+"px"
      , value: displayValue(compliancePercent)
      };
    }else{
      //We calculate the remaining free space of the Compliance Bar
      var baseSize = "(100% - " + totalSmallBars.pixelSize + "px)";
      //Then we calculate the percentage of each bar with respect to this space.
      var percentBar = compliancePercent / (100 - totalSmallBars.percent );
      bar = {
        width : "calc( "+baseSize+" * "+percentBar+")"
      , value: displayValue(compliancePercent)
      }
    }
    bars.push(bar);
  });
  return bars;
}

function refreshTable (gridId, data) {
  var table = $('#'+gridId).DataTable({"retrieve": true});
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
          $(this).addClass("opened");
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
          $(this).removeClass("opened");
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
function createTable(gridId,data,columns, customParams, contextPath, refresh, storageId, isPopup, pickEventLogsInInterval) {
  var defaultParams = {
      "asStripeClasses": [ 'color1', 'color2' ]
    , "bAutoWidth": false
    , "aoColumns": columns
    , "aaData": data
    , "bJQueryUI": true
    , "lengthMenu": [ [10, 25, 50, 100, 500, 1000, -1], [10, 25, 50, 100, 500, 1000, "All"] ]
    , "pageLength": 25
    , "retrieve" : true
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
  var table = $('#'+gridId).DataTable( params );

  $('#'+gridId+' thead tr').addClass("head");
  if (!( typeof refresh === 'undefined')) {
    var refreshBtn = $("<button class='btn btn-sm btn-blue'><i class='fa fa-refresh'></i></button>");
    refreshBtn.button();
    refreshBtn.attr("title","Refresh");
    refreshBtn.click( function() { refresh(); } );
    refreshBtn.removeClass("ui-button ui-corner-all ui-widget");
    $("#"+gridId+"_wrapper .dataTables_refresh").append(refreshBtn);
  }

  $("#"+gridId+"_wrapper .dataTables_refresh button").tooltip({position:{my:"left+40 bottom-10",collision: "flipfit"}});

  $('.dataTables_filter input').attr("placeholder", "Filter");

  $('.modal .dataTables_filter input').addClass("form-control");
  $('#grid_remove_popup_grid').parent().addClass("table-responsive");
  $('#grid_remove_popup_grid').parents('.modal-dialog').addClass("modal-lg");

  if (!( typeof pickEventLogsInInterval === 'undefined')) {
    $('#filterLogs').removeClass('hide');
    //Initialize the two datepickers
    $('#filterLogs .pickStartInput, #filterLogs .pickEndInput').datetimepicker({dateFormat:'yy-mm-dd', timeFormat: 'HH:mm:ss', timeInput: true});
    $('#filterLogsButton').click(pickEventLogsInInterval);
  }
  return table;
}

