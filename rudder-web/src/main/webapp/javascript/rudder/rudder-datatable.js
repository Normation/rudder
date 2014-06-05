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

/* Create Rule table
 *
 *   data:
 *   { "name" : Rule name [String]
 *   , "id" : Rule id [String]
 *   , "description" : Rule (short) description [String]
 *   , "applying": Is the rule applying the Directive, used in Directive page [Boolean]
 *   , "category" : Rule category [String]
 *   , "status" : Status of the Rule, "enabled", "disabled" or "N/A" [String]
 *   , "compliance" : Percent of compliance of the Rule [String]
 *   , "complianceClass" : Class to apply on the compliance td [String]
 *   , "trClass" : Class to apply on the whole line (disabled ?) [String]
 *   , "callback" : Function to use when clicking on one of the line link, takes a parameter to define which tab to open, not always present [ Function ]
 *   , "checkboxCallback": Function used when clicking on the checkbox to apply/not apply the Rule to the directive, not always present [ Function ]
 *   , "reasons": Reasons why a Rule is a not applied, empty if there is no reason [ String ]
 *   }
 */
function createRuleTable (gridId, data, needCheckbox, isPopup, allCheckboxCallback, contextPath, refresh) {

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
        var elem = $("<a></a>");
        if("callback" in data) {
            elem.click(function() {data.callback("showForm");});
            elem.attr("href","javascript://");
        } else {
            elem.attr("href",contextPath+'/secure/configurationManager/ruleManagement#{"ruleId":"'+data.id+'"}');
        }
        elem.text(data.name);

        // Row parameters
        var parent = $(nTd).parent()
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
      "mDataProp": "compliance"
    , "sWidth": "40px"
    , "sTitle": "Compliance"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var data = oData;
        var elem = $("<a></a>");
        if("callback" in data) {
            elem.click( function() {
                data.callback("showForm");
              } );
            elem.attr("href","javascript://");
        } else {
            elem.attr("href",contextPath+'/secure/configurationManager/ruleManagement#{"ruleId":"'+data.id+'"}');
        }
        elem.text(sData);
        $(nTd).empty();
        $(nTd).addClass(data.complianceClass+ " compliance");
        $(nTd).prepend(elem);
      }
  };

  // Action buttons, use id a dataprop as its is always present
  var actions = {
      "mDataProp": "id"
    , "sWidth": "20px"
    , "bSortable" : false
    , "sClass" : "parametersTd"
    , "fnCreatedCell" :    function (nTd, sData, oData, iRow, iCol) {
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
            oData.oSearch.sSearch = "";
            return false;
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

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "value" : value of the key [String]
 *   , "compliance" : compliance percent as String, not used in message popup [String]
 *   , "status" : Worst status of the Directive [String]
 *   , "statusClass" : Class to use on status cell [String]
 *   , "callback" : Function to when clicking on compliance percent, not used in message popup [ Function ]
 *   , "message" : Message linked to that value, only used in message popup [ Array[String] ]
 *   }
 */
function createComponentValueTable (isTopLevel, addCompliance, contextPath) {

  if (isTopLevel) {
    var statusWidth = "16.4%";
    var complianceWidth = "11.1%";
    if (addCompliance) {
      var componentSize = "72.5%";
    } else {
      var componentSize = "20%";
      var messageWidth = "63.6%";
    }
  } else {
    var statusWidth = "17.6%";
    var complianceWidth = "11.8%";
    if (addCompliance) {
      var componentSize = "70.6%";
    } else {
      var componentSize = "20%";
      var messageWidth = "62.4%";
    }
  }
  var columns = [ {
      "sWidth": componentSize
    , "mDataProp": "value"
    , "sTitle": "Value"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        if ("unexpanded" in oData) {
          var tooltipIcon = $("<img />");
          tooltipIcon.attr("src",contextPath+"/images/ic_question_14px.png");
          tooltipIcon.addClass("reportIcon");
          var tooltipId = oData.id+"-tooltip";
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
  } ];

  var status = {
      "sWidth": statusWidth
    , "mDataProp": "status"
    , "sTitle": "Status"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        $(nTd).addClass("center "+oData.statusClass);
      }
  }

  if (addCompliance) {

    var compliance = {
        "sWidth": complianceWidth
      , "mDataProp": "compliance"
      , "sTitle": "Compliance"
      , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
          var elem = $("<a></a>");
          elem.attr("href","javascript://");
          elem.addClass("right noexpand");
          elem.text(sData);
          elem.click(function() {oData.callback()});
          $(nTd).empty();
          $(nTd).append(elem);
        }
    };
    columns.push(status);
    columns.push(compliance);
  } else {
    var message = {
        "sWidth": messageWidth
      , "mDataProp": "message"
      , "sTitle": "Message"
    }
    columns.push(message);
    columns.push(status);
  }

  var params = {
      "bFilter" : false
    , "bPaginate" : false
    , "bLengthChange": false
    , "bInfo" : false
    , "aaSorting": [[ 0, "asc" ]]
  }

  return function (gridId,data) {createTable(gridId, data, columns, params, contextPath); createTooltip();}

}

/*
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
function createComponentTable (isTopLevel, addCompliance, contextPath) {

  if (isTopLevel) {
    var statusWidth = "15.8%";
    var complianceWidth = "10.5%";
    if (addCompliance) {
      var componentSize = "73.7%";
    } else {
      var componentSize = "84.2%";
    }
  } else {
    var statusWidth = "16.8%";
    var complianceWidth = "11.1%";
    if (addCompliance) {
      var componentSize = "72.4%";
    } else {
      var componentSize = "82.6%";
    }
  }
  var columns = [ {
      "sWidth": componentSize
    , "mDataProp": "component"
    , "sTitle": "Component"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        if (oData.noExpand) {
          $(nTd).addClass("noExpand");
        } else {
          $(nTd).addClass("listopen");
        }
      }
  } , {
      "sWidth": statusWidth
    , "mDataProp": "status"
    , "sTitle": "Status"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        $(nTd).addClass("center "+oData.statusClass);
      }
  } ];

  var compliance = {
      "sWidth": complianceWidth
    , "mDataProp": "compliance"
    , "sTitle": "Compliance"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var elem = $("<a></a>");
        elem.addClass("right noexpand");
        elem.attr("href","javascript://");
        elem.text(sData);
        elem.click(function() {oData.callback()});
        $(nTd).empty();
        $(nTd).append(elem);
      }
  }

  if (addCompliance) {
    columns.push(compliance)
  }

  var params = {
      "bFilter" : false
    , "bPaginate" : false
    , "bLengthChange": false
    , "bInfo" : false
    , "aaSorting": [[ 0, "asc" ]]
    , "fnDrawCallback" : function( oSettings ) {
        createInnerTable(this, createComponentValueTable(isTopLevel, addCompliance, contextPath));
      }
  }

  return function (gridId,data) {createTable(gridId,data,columns, params, contextPath);}
}

/*
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
function createDirectiveTable (isTopLevel, addCompliance, contextPath) {

  if (isTopLevel) {
    var statusWidth = "15%";
    var complianceWidth = "10%";
    if (addCompliance) {
      var directiveWidth = "75%";
    } else {
      var directiveWidth = "85%";
    }
  } else {
    var statusWidth = "15.8%";
    var complianceWidth = "10.5%";
    if (addCompliance) {
      var directiveWidth = "73.7%";
    } else {
      var directiveWidth = "82.2%";
    }
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
        var tooltipId = oData.id+"-tooltip";
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
      "sWidth": statusWidth
    , "mDataProp": "status"
    , "sTitle": "Status"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        $(nTd).addClass("center "+oData.statusClass);
       }
  } ];
  
  var compliance = {
      "sWidth": complianceWidth
    , "mDataProp": "compliance"
    , "sTitle": "Compliance"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var elem = $("<a></a>");
        elem.addClass("right noExpand");
        elem.attr("href","javascript://");
        elem.text(sData);
        elem.click(function() {oData.callback()});
        $(nTd).empty();
        $(nTd).append(elem);
      }
  }

  if (addCompliance) {
    columns.push(compliance)
  }

  var params = {
      "bFilter" : isTopLevel
    , "bPaginate" : isTopLevel
    , "bLengthChange": isTopLevel
    , "bInfo" : isTopLevel
    , "sPaginationType": "full_numbers"
    , "aaSorting": [[ 0, "asc" ]]
    , "fnDrawCallback" : function( oSettings ) {
        createInnerTable(this, createComponentTable(isTopLevel, addCompliance, contextPath), contextPath);
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
 *   Javascript object containing all data to create a line in the DataTable
 *   { "rule" : Rule name [String]
 *   , "id" : Rule id [String]
 *   , "status" : Worst status of the Directive [String]
 *   , "statusClass" : Class to use on status cell [String]
 *   , "details" : Details of components contained in the Directive [Array of Component values ]
 *   }
 */
function createRuleComplianceTable (gridId, data, contextPath, refresh) {

  var columns = [ {
      "sWidth": "85%"
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
      "sWidth": "15%"
    , "mDataProp": "status"
    , "sTitle": "Status"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        $(nTd).addClass("center "+oData.statusClass);
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
        createInnerTable(this, createDirectiveTable(false, false, contextPath), contextPath);
      }
    , "sDom": '<"dataTables_wrapper_top newFilter"f<"dataTables_refresh">>rt<"dataTables_wrapper_bottom"lip>'
  };

  createTable(gridId,data,columns, params, contextPath, refresh);

}

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "node" : Directive name [String]
 *   , "id" : Rule id [String]
 *   , "status" : Worst status of the Directive [String]
 *   , "statusClass" : Class to use on status cell [String]
 *   , "details" : Details of components contained in the Directive [Array of Component values ]
 *   }
 */
function createNodeComplianceTable (gridId, data, contextPath, refresh) {

  var columns = [ {
      "sWidth": "85%"
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
      "sWidth": "15%"
    , "mDataProp": "status"
    , "sTitle": "Status"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        $(nTd).addClass("center "+oData.statusClass);
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
        createInnerTable(this,createComponentTable(true, false, contextPath));
      }
    , "sDom": '<"dataTables_wrapper_top newFilter"f<"dataTables_refresh">>rt<"dataTables_wrapper_bottom"lip>'
  };

  createTable(gridId, data, columns, params, contextPath, refresh);

  createTooltip();
}

/*
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

function refreshTable (gridId, data) {
  var table = $('#'+gridId).dataTable();
  table.fnClearTable();
  table.fnAddData(data);
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
        var detailsId = fnData.id + "-details";
        if ( i === -1 ) {
          $(this).find("td.listopen").removeClass("listopen").addClass("listclose");
          var table = $("<table></table>");
          var tableId = fnData.id + "-compliance";
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

  $('.dataTables_filter input').attr("placeholder", "Search");
  $('.dataTables_filter input').css("background","white url("+contextPath+"/images/icMagnify.png) left center no-repeat");
}
