/*
*************************************************************************************
* Copyright 2014 Normation SAS
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

var anOpen = [];



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
function createRuleTable(gridId, data, needCheckbox, isPopup, allCheckboxCallback, contextPath, refresh) {

  //base eletement for the clickable cells
  function callbackElement(oData) {
    var elem = $("<a></a>");
    if("callback" in oData) {
        elem.click(function() {oData.callback("showForm");});
        elem.attr("href","javascript://");
    } else {
        elem.attr("href",contextPath+'/secure/configurationManager/ruleManagement#{"ruleId":"'+oData.id+'"}');
    }
    return elem
  }
  
  // Define which columns should be sorted by default
  var sortingDefault;
  if (needCheckbox) {
    sortingDefault = 1;
  } else {
    sortingDefault = 0;
  }

  // Define all columns of the table

  // Checkbox used in check if a Directive is applied by the Rule
  var checkbox = {
      "mDataProp": "applying"
    , "sTitle" : "<input id='checkAll' type='checkbox'></input>"
    , "sWidth": "30px"
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
    , "sWidth": "90px"
    , "sTitle": "Name"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var data = oData;
        // Define the elem and its callback
        var elem = callbackElement(oData);
        elem.text(data.name);

        // Row parameters
        var parent = $(nTd).parent()
        // Add Class on the row, and id
        parent.addClass(data.trClass);
        parent.attr("id",data.jsid);

        // Description tooltip over the row
        if ( data.description.length > 0) {
          var tooltipId = data.jsid+"-description";
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
    , "sWidth": "120px"
    , "sTitle": "Category"
    };

  // Status of the rule (disabled) add reson tooltip if needed
  var status= {
      "mDataProp": "status"
    , "sWidth": "60px"
    , "sTitle": "Status"
    , "sClass" : "statusCell"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var data = oData;
        $(nTd).empty();
        var elem = $("<span></span>");
        elem.text(data.status);
        // If there a reasons field, add the tooltip
        if ("reasons" in data) {
          var tooltipId = data.jsid+"-status";
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
      "mDataProp": "compliance"
    , "sWidth": "40px"
    , "sTitle": "Compliance"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var elem = callbackElement(oData);
        elem.append(buildComplianceBar(oData.compliance));
        $(nTd).empty();
        $(nTd).prepend(elem);
      }
  };

  // Compliance, with link to the edit form
  var recentChanges = {
      "mDataProp": "recentChanges"
    , "sWidth": "200px"
    , "sTitle": "Recent Changes (last 15 days, by hour)"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var elem = callbackElement(oData);
        elem.append($('<canvas class="recentChanges" height="30"></canvas>')); //chart drawn in fnRowCallback
        $(nTd).empty();
        $(nTd).prepend(elem);
      }
  };
  
  // Action buttons, use id a dataprop as its is always present
  var actions = {
      "mDataProp": "id"
    , "sWidth": "20px"
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
  if (needCheckbox) {
    columns.push(checkbox);
  }
  columns.push(name);
  columns.push(category);
  columns.push(status);
  columns.push(compliance);
  columns.push(recentChanges);
  if (!isPopup) {
    columns.push(actions);
  }

  var params = {
      "bFilter" : true
    , "bPaginate" : true
    , "bLengthChange": true
    , "sPaginationType": "full_numbers"
    , "bStateSave": true
    , "sCookiePrefix": "Rudder_DataTables_"
    , "oLanguage": {
          "sZeroRecords": "No matching rules!"
        , "sSearch": ""
      }
    , "fnStateLoadParams": function (oSettings, oData) {
        oData.aoSearchCols[1].sSearch = "";
      }
    , "fnRowCallback": function( nRow, aData, iDisplayIndex, iDisplayIndexFull ) {
        var ctx = $(nRow).find('.recentChanges').get(0).getContext("2d");
        //dataset: on x: timestamp, on y: number of changes
        
        console.log("aData content: ");
        console.log(aData);
        
        new Chart(ctx).Line(
            {
                labels: aData.recentChanges.x
              , datasets: [ {
                  label: "",
                  strokeColor: "rgba(220,220,220,1)",
                  pointColor: "rgba(220,220,220,1)",
                  pointStrokeColor: "#ddd",
                  pointHighlightFill: "#eee",
                  pointHighlightStroke: "rgba(220,220,220,1)",
                  data: aData.recentChanges.y
                }]
            }
          , chartjsSparklineOption()
        )
      }
    , "aaSorting": [[ sortingDefault, "asc" ]]
    , "sDom": '<"dataTables_wrapper_top newFilter"f<"dataTables_refresh">>rt<"dataTables_wrapper_bottom"lip>'
  }

  createTable(gridId,data,columns, params, contextPath, refresh);

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
 *   , "status" : Worst status of the Directive [String]
 *   , "statusClass" : Class to use on status cell [String]
 *   , "details" : Details of components contained in the Directive [Array of Component values ]
 *   }
 */
function createRuleComplianceTable(gridId, data, contextPath, refresh) {

  var columns = [ {
      "sWidth": "75%"
    , "mDataProp": "rule"
    , "sTitle": "Rule"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        $(nTd).addClass("listopen");

        var editLink = $("<a />");
        editLink.attr("href",contextPath + '/secure/configurationManager/ruleManagement#{"ruleId":"'+oData.id+'"}')
        var editIcon = $("<img />");
        editIcon.attr("src",contextPath + "/images/icPen.png");
        editLink.click(function(e) {e.stopPropagation();})
        editLink.append(editIcon);
        editLink.addClass("reportIcon");

        $(nTd).append(editLink);
      }
  } , {
    "sWidth": "25%"
      , "mDataProp": "compliance"
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
    , "bStateSave": true
    , "sCookiePrefix": "Rudder_DataTables_"
    , "oLanguage": {
        "sSearch": ""
      }
    , "aaSorting": [[ 0, "asc" ]]
    , "fnDrawCallback" : function( oSettings ) {
        createInnerTable(this, createDirectiveTable(false, true, contextPath), contextPath);
      }
    , "sDom": '<"dataTables_wrapper_top newFilter"f<"dataTables_refresh">>rt<"dataTables_wrapper_bottom"lip>'
  };

  createTable(gridId,data,columns, params, contextPath, refresh);

}

/*
 *   Create a table of compliance for a Directive. 
 *   Used in the compliance details for a Rule, and in the
 *   node details page, in report tab.
 *   
 *   Javascript object containing all data to create a line in the DataTable
 *   { "directive" : Directive name [String]
 *   , "id" : Rule id [String]
 *   , "compliance" : compliance percent as String [String]
 *   , "techniqueName": Name of the technique the Directive is based upon [String]
 *   , "techniqueVersion" : Version of the technique the Directive is based upon  [String]
 *   , "status" : Worst status of the Directive [String]
 *   , "statusClass" : Class to use on status cell [String]
 *   , "details" : Details of components contained in the Directive [Array of Directive values ]
 *   , "callback" : Function to when clicking on compliance percent [ Function ]
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

        var editLink = $("<a />");
        editLink.attr("href",contextPath + '/secure/configurationManager/directiveManagement#{"directiveId":"'+oData.id+'"}')
        var editIcon = $("<img />");
        editIcon.attr("src",contextPath + "/images/icPen.png");
        editLink.click(function(e) {e.stopPropagation();})
        editLink.append(editIcon);
        editLink.addClass("reportIcon");

        $(nTd).append(tooltipIcon);
        $(nTd).append(toolTipContainer);
        $(nTd).append(editLink);
      }
  } , {
      "sWidth": complianceWidth
    , "mDataProp": "compliance"
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
      "bFilter" : isTopLevel
    , "bPaginate" : isTopLevel
    , "bLengthChange": isTopLevel
    , "bInfo" : isTopLevel
    , "sPaginationType": "full_numbers"
    , "aaSorting": [[ 0, "asc" ]]
    , "fnDrawCallback" : function( oSettings ) {
        createInnerTable(this, createComponentTable(isTopLevel, isNodeView, contextPath), contextPath);
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
 *   { "node" : Directive name [String]
 *   , "id" : Rule id [String]
 *   , "status" : Worst status of the Directive [String]
 *   , "statusClass" : Class to use on status cell [String]
 *   , "details" : Details of components contained in the Directive [Array of Component values ]
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
        editLink.attr("href",contextPath +'/secure/nodeManager/searchNodes#{"nodeId":"'+oData.id+'"}')
        var editIcon = $("<img />");
        editIcon.attr("src",contextPath + "/images/icMagnify-right.png");
        editLink.click(function(e) {e.stopPropagation();})
        editLink.append(editIcon);
        editLink.addClass("reportIcon");

        $(nTd).append(editLink);
      }
  } , {
      "sWidth": "25%"
    , "mDataProp": "compliance"
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
    , "bStateSave": true
    , "sCookiePrefix": "Rudder_DataTables_"
    , "oLanguage": {
        "sSearch": ""
      }
    , "aaSorting": [[ 0, "asc" ]]
    , "fnDrawCallback" : function( oSettings ) {
        createInnerTable(this,createComponentTable(true, true, contextPath));
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
 *   , "id" : id generated about that component [String]
 *   , "compliance" : compliance percent as String, not used in message popup [String]
 *   , "status" : Worst status of the Directive [String]
 *   , "statusClass" : Class to use on status cell [String]
 *   , "details" : Details of values contained in the component [ Array of Component values ]
 *   , "noExpand" : The line should not be expanded if all values are "None", not used in message popup [Boolean]
 *   , "callback" : Function to when clicking on compliance percent, not used in message popup [ Function ]
 *   }
 */
function createComponentTable(isTopLevel, isNodeView, contextPath) {

  if (isTopLevel) {
    var complianceWidth = "26.3%";
    var componentSize = "73.7%";
  } else {
    var complianceWidth = "27.9%";
    var componentSize = "72.4%";
  }
  var columns = [ {
      "sWidth": componentSize
    , "mDataProp": "component"
    , "sTitle": "Component"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        if(isNodeView) {
          $(nTd).addClass("listopen");
        } else {
          $(nTd).addClass("noExpand");
        }
      }
  } , {
      "sWidth": complianceWidth
    , "mDataProp": "compliance"
    , "sTitle": "Compliance"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var elem = $("<a></a>");
        elem.addClass("noexpand");
        elem.attr("href","javascript://");
        elem.append(buildComplianceBar(oData.compliance));
        elem.click(function() {oData.callback()});
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
 *   , "callback" : Function to when clicking on compliance percent, not used in message popup [ Function ]
 *   , "messages" : Message linked to that value, only used in message popup [ Array[String] ]
 *   }
 */
function createNodeComponentValueTable(contextPath) {

  var columns = [ {
      "sWidth": "20%"
    , "mDataProp": "value"
    , "sTitle": "Value"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        if ("unexpanded" in oData) {
          var tooltipIcon = $("<img />");
          tooltipIcon.attr("src",contextPath+"/images/ic_question_14px.png");
          tooltipIcon.addClass("reportIcon");
          var tooltipId = oData.jsid+"-tooltip";
          tooltipIcon.attr("tooltipid",tooltipId);
          tooltipIcon.attr("title","");
          tooltipIcon.addClass("tooltip tooltipable");
          var toolTipContainer= $("<div>Value '<b>"+sData+"</b>' was expanded from the entry '<b>"+oData.unexpanded+"</b>'</div>");
          toolTipContainer.addClass("tooltipContent");
          toolTipContainer.attr("id",tooltipId);
          $(nTd).append(tooltipIcon);
          $(nTd).append(toolTipContainer);
        }
      }
  } , {
      "sWidth": "62.4%"
    , "mDataProp": "messages"
    , "sTitle": "Messages"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var list = $("<ul></ul>");
        for (index in sData) {
          var elem = $("<li></li>");
          elem.text(sData[index]);
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
 *   , "compliance" : compliance percent as String, not used in message popup [String]
 *   , "callback" : Function to when clicking on compliance percent, not used in message popup [ Function ]
 *   , "messages" : Message linked to that value, only used in message popup [ Array[String] ]
 *   }
 */
function createRuleComponentValueTable (contextPath) {

  var complianceWidth = "29.4%";
  var componentSize = "70.6%";

  var columns = [ {
      "sWidth": componentSize
    , "mDataProp": "value"
    , "sTitle": "Value"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        if ("unexpanded" in oData) {
          var tooltipIcon = $("<img />");
          tooltipIcon.attr("src",contextPath+"/images/ic_question_14px.png");
          tooltipIcon.addClass("reportIcon");
          var tooltipId = oData.jsid+"-tooltip";
          tooltipIcon.attr("tooltipid",tooltipId);
          tooltipIcon.attr("title","");
          tooltipIcon.addClass("tooltip tooltipable");
          var toolTipContainer= $("<div>Value '<b>"+sData+"</b>' was expanded from the entry '<b>"+oData.unexpanded+"</b>'</div>");
          toolTipContainer.addClass("tooltipContent");
          toolTipContainer.attr("id",tooltipId);
          $(nTd).append(tooltipIcon);
          $(nTd).append(toolTipContainer);
        }
      }
  } , {
        "sWidth": complianceWidth
      , "mDataProp": "compliance"
      , "sTitle": "Compliance"
      , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
          var elem = $("<a></a>");
          elem.attr("href","javascript://");
          elem.addClass("noexpand");
          elem.append(buildComplianceBar(oData.compliance));
          elem.click(function() {oData.callback()});
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
 *   , "callBack" : Callback on Node, if absend replaced by a link to nodeId [ Function ]
 *   }
 */
function createNodeTable(gridId, data, contextPath, refresh) {

  var columns = [ {
      "sWidth": "30%"
    , "mDataProp": "name"
    , "sTitle": "Node name"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var editLink = $("<a />");
        if ("callback" in oData) {
          editLink.click(function(e) { oData.callback(); e.stopPropagation();});
          editLink.attr("href","javascript://");
        } else {
          editLink.attr("href",contextPath +'/secure/nodeManager/searchNodes#{"nodeId":"'+oData.id+'"}')
        }
        var editIcon = $("<img />");
        editIcon.attr("src",contextPath + "/images/icMagnify-right.png");
        editLink.append(editIcon);
        editLink.addClass("reportIcon");

        $(nTd).append(editLink);
      }
  } , {
      "sWidth": "10%"
    , "mDataProp": "machineType"
     , "sTitle": "Machine type"
  } , {
      "sWidth": "20%"
    , "mDataProp": "osName"
    , "sTitle": "OS name"
  } , {
      "sWidth": "10%"
    , "mDataProp": "osVersion"
    , "sTitle": "OS version"
  } , {
      "sWidth": "10%"
    , "mDataProp": "servicePack"
    , "sTitle": "OS SP"
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
    , "bStateSave": true
    , "sCookiePrefix": "Rudder_DataTables_"
    , "oLanguage": {
        "sSearch": ""
    }
    , "aaSorting": [[ 0, "asc" ]]
    , "sDom": '<"dataTables_wrapper_top newFilter"f<"dataTables_refresh">>rt<"dataTables_wrapper_bottom"lip>'
  };

  createTable(gridId,data, columns, params, contextPath, refresh);

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
function createTechnicalLogsTable(gridId, data, contextPath, refresh) {

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
    , "bStateSave": true
    , "sCookiePrefix": "Rudder_DataTables_"
    , "oLanguage": {
        "sSearch": ""
    }
    , "aaSorting": [[ 0, "asc" ]]
    , "sDom": '<"dataTables_wrapper_top newFilter"f<"dataTables_refresh">>rt<"dataTables_wrapper_bottom"lip>'
  };

  createTable(gridId,data, columns, params, contextPath, refresh);

}


/*
 * A function that build a compliance bar with colored zone for compliance
 * status cases based on Twitter Bootstrap: http://getbootstrap.com/components/#progress
 * 
 * Await a JSArray:
 * (pending, success, repaired, error, noAnswer, notApplicable)
 * 
 */
function buildComplianceBar(compliance) {
  var content = $('<div class="tw-bs progress"></div>')

  var notapplicable = compliance[0]
  if(notapplicable != 0) {
    content.append('<div class="progress-bar progress-bar-notapplicable" style="width:'+notapplicable+'%" title="Not Applicable: '+notapplicable+'%">&nbsp;</div>')
  }
  var success = compliance[1]
  if(success != 0) {
    content.append('<div class="progress-bar progress-bar-success" style="width:'+success+'%" title="Success: '+success+'%">&nbsp;</div>')
  }
  var repaired = compliance[2]
  if(repaired != 0) {
    content.append('<div class="progress-bar progress-bar-repaired" style="width:'+repaired+'%" title=Rrepaired: '+repaired+'%">&nbsp;</div>')
  }
  var error = compliance[3]
  if(error != 0) {
    content.append('<div class="progress-bar progress-bar-error" style="width:'+error+'%" title="Error: '+error+'%">&nbsp;</div>')
  }
  var pending = compliance[4]
  if(pending != 0) {
    content.append('<div class="progress-bar progress-bar-pending progress-bar-striped" style="width:'+pending+'%" title="Applying: '+pending+'%">&nbsp;</div>')
  }
  var noreport = compliance[5]
  if(noreport != 0) {
    content.append('<div class="progress-bar progress-bar-noanswer" style="width:'+noreport+'%" title="No Report: '+noreport+'%">&nbsp;</div>')
  }
  var missing = compliance[6]
  if(missing != 0) {
    content.append('<div class="progress-bar progress-bar-missing" style="width:'+missing+'%" title="Missing Report: '+missing+'%">&nbsp;</div>')
  }
  var unknown = compliance[7]
  if(unknown != 0) {
    content.append('<div class="progress-bar progress-bar-unknown" style="width:'+unknown+'%" title="Unexpected Report: '+unknown+'%">&nbsp;</div>')
  }
  
  return content
  
}


function refreshTable (gridId, data) {
  var table = $('#'+gridId).dataTable();
  table.fnClearTable();
  table.fnAddData(data, false);
  table.fnDraw();
}

/*
 * Function to define opening of an inner table
 */
function createInnerTable(myTable,  createFunction, contextPath) {
  var plusTd = $(myTable.fnGetNodes());
  plusTd.each( function () {
    $(this).unbind();
    $(this).click( function (e) {
      if ($(e.target).hasClass('noExpand')) {
        return false;
      } else {
        var fnData = myTable.fnGetData( this );
        var i = $.inArray( this, anOpen );
        var detailsId = fnData.jsid + "-details";
        if ( i === -1 ) {
          $(this).find("td.listopen").removeClass("listopen").addClass("listclose");
          var table = $("<table></table>");
          var tableId = fnData.jsid + "-compliance";
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
function createTable(gridId,data,columns, customParams, contextPath, refresh) {

  var defaultParams = {
      "asStripeClasses": [ 'color1', 'color2' ]
    , "bAutoWidth": false
    , "aoColumns": columns
    , "aaData": data
    , "bJQueryUI": true
  };

  var params = $.extend({},defaultParams,customParams)
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
  $('.dataTables_filter input').css("background","white url("+contextPath+"/images/icMagnify.png) left center no-repeat");
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
      
      ///// line specific /////
  
      //Boolean - Whether grid lines are shown across the chart
    , scaleShowGridLines : false
      //String - Colour of the grid lines
    , scaleGridLineColor : "rgba(0,0,0,.05)"
      //Number - Width of the grid lines
    , scaleGridLineWidth : 1
      //Boolean - Whether the line is curved between points
    , bezierCurve : false
      //Number - Tension of the bezier curve between points
    , bezierCurveTension : 0.4
      //Boolean - Whether to show a dot for each point
    , pointDot : false
      //Number - Radius of each point dot in pixels
    , pointDotRadius : 2// 4
      //Number - Pixel width of point dot stroke
    , pointDotStrokeWidth : 0 //1
      //Number - amount extra to add to the radius to cater for hit detection outside the drawn point
    , pointHitDetectionRadius : 0 //20
      //Boolean - Whether to show a stroke for datasets
    , datasetStroke : true
      //Number - Pixel width of dataset stroke
    , datasetStrokeWidth : 2
      //Boolean - Whether to fill the dataset with a colour
    , datasetFill : false // true
      //String - A legend template
    , legendTemplate : "" //"<ul class=\"<%=name.toLowerCase()%>-legend\"><% for (var i=0; i<datasets.length; i++){%><li><span style=\"background-color:<%=datasets[i].lineColor%>\"></span><%if(datasets[i].label){%><%=datasets[i].label%><%}%></li><%}%></ul>"
  };
}
