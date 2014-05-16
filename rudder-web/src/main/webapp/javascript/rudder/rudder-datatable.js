
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
function createRuleTable (gridId, data, needCheckbox, isPopup, allCheckboxCallback, contextPath) {

  // Define which columns should be sorted by default
  var sortingDefault;
  if (needCheckbox) {
    sortingDefault = 1;
  } else {
    sortingDefault = 0;
  }

  // Define all columns of the table

  // Checkbox used in check if a Directive is applied by the Rule
  var checkbox =
    { "mDataProp": "applying"
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
  var name = 
    { "mDataProp": "name"
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
  var status=
    { "mDataProp": "status"
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
  var compliance =
    { "mDataProp": "compliance"
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
  var actions =
    { "mDataProp": "id"
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

  createTable(gridId,data,columns, sortingDefault);

  createTooltip();

  // Add callback to checkbox column
  $("#checkAll").prop("checked", false);
  $("#checkAll").click( function () {
      var checked = $("#checkAll").prop("checked");
      allCheckboxCallback(checked);
  } );
}

// Create a table from its id, data, columns, maybe the last one need to be all specific attributes, but for now only sorting
function createTable(gridId,data,columns, sortingDefault) {
  $('#'+gridId).dataTable(
    { "asStripeClasses": [ 'color1', 'color2' ]
    , "bAutoWidth": false
    , "bFilter" : true
    , "bPaginate" : true
    , "bLengthChange": true
    , "sPaginationType": "full_numbers"
    , "bJQueryUI": true
    , "bStateSave": true
    , "sCookiePrefix": "Rudder_DataTables_"
    , "oLanguage": {
          "sZeroRecords": "No matching rules!"
        , "sSearch": ""
        }
    , "aaData": data
    , "aaSorting": [[ sortingDefault, "asc" ]]
    , "aoColumns": columns
    , "sDom": '<"dataTables_wrapper_top"fl>rt<"dataTables_wrapper_bottom"ip>'
    }
  );
  $('.dataTables_filter input').attr("placeholder", "Search");
}
