/*
*************************************************************************************
* Copyright 2023 Normation SAS
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


/* Global variables */

var isLoggedIn = true;

/* Event handler function */

var entityMap = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
  '/': '&#x2F;',
  '`': '&#x60;',
  '=': '&#x3D;'
};

function escapeHTML (string) {
  return String(string).replace(/[&<>"'`=\/]/g, function (s) {
    return entityMap[s];
  });
}

function callPopupWithTimeout(timeout, popupName){
  setTimeout("initBsModal('"+popupName+"')", timeout);
}

function reverseErrorDetails(){
    $('#showTechnicalErrorDetails .panel-title span').toggleClass('up');
}
/* ignore event propagation (IE compliant) */

function noBubble(event){
    if(event.stopPropagation){
      event.stopPropagation();
    };
    event.cancelBubble=true;
}

/* ignore enter in a field */

function refuseEnter(event)
{
    // IE / Firefox
    if(!event && window.event) {
        event = window.event;
    }
    // IE
    if(event.keyCode == 13) {
        event.returnValue = false;
        event.cancelBubble = true;
    }
    // DOM
    if(event.which == 13) {
        event.preventDefault();
        event.stopPropagation();
    }
}

/**
 * Check all checkbox named name according to the status of the checkbox with id id
 * @param id
 * @param name
 * @return
 */
function jqCheckAll( id, name )
{
   $("input[name=" + name + "][type='checkbox']").prop('checked', $('#' + id).is(':checked'));
}

/* popin */

// increase the default animation speed to exaggerate the effect
  $.fx.speeds._default = 1000;
  $(function() {
    $('#dialog').dialog({
      autoOpen: false,
      position: [250,100],
      width: 535,
      show: '',
      hide: ''
    });
    $('#openerAccount').click(function() {
      $('#dialog').dialog('open');
      return false;
    });
  });

function processKey(e , buttonId){
    if (null == e)
        e = window.event ;
    if (e.keyCode == 13)  {
        e.preventDefault();
        document.getElementById(buttonId).click();
        return false;
    }
}


/**
 * Move the filter and paginate zones in the location described by tableId_paginate_area and tableId_filter_area
 * @param tableId
 * @return
 */
function moveFilterAndPaginateArea(tableId) {
  $(tableId+"_paginate_area").append($(tableId+"_next")).append($(tableId+"_info")).append($(tableId+"_previous"));

  if ($(tableId+"_filter_area")) {
    $(tableId+"_filter_area").append($(tableId+"_filter"));
  }
}

function dropFilterArea(tableId) {
  $(tableId+"_info").remove();
  $(tableId+"_filter").remove();
  $(tableId+"_length");
}

function activateButtonOnFormChange(containerDivId, buttonId, status) {
  $('#'+buttonId).removeProp("disabled")

  if ("false"==status) {
    disableButton(buttonId)
  } else {  $('#'+buttonId).removeProp("disabled")
  }

  // all change on the form
  $('#'+containerDivId+' > form').change(function() { $('#'+buttonId).removeProp("disabled")});
  // This one is for all input (text, textarea, password... and yes, button)
  $('#'+containerDivId+' :input').change(function() { $('#'+buttonId).removeProp("disabled")});

  // all change on not the form
  $('#'+containerDivId+' :radio').change(function() { $('#'+buttonId).removeProp("disabled")});
  // This one is for all input (text, textarea, password... and yes, button)
  $('#'+containerDivId+' :input').keyup(function() { $('#'+buttonId).removeProp("disabled")});

  $('#'+containerDivId+' :checkbox').bind('propertychange', function(e) {if (e.type == "change" || (e.type == "propertychange" && window.event.propertyName == "checked")) {  $('#'+buttonId).removeProp("disabled")}});

}

/**
 *
 */
function activateButtonDeactivateGridOnFormChange(containerDivId, buttonId, gridId, saveButton) {
  $('#'+buttonId).removeProp("disabled")

  // all change on the form
  $('#'+containerDivId+' > form').change(function() { disableButton(saveButton);});
  // This one is for all input (text, textarea, password... and yes, button)
  $('#'+containerDivId+' :input').change(function() { disableButton(saveButton);});

  // all change on not the form
  $('#'+containerDivId+' :radio').change(function() { disableButton(saveButton);});
  // This one is for all input (text, textarea, password... and yes, button)
  $('#'+containerDivId+' :input').keyup(function() { disableButton(saveButton);});

  $('#'+containerDivId+' :checkbox').bind('propertychange', function(e) {
    if (e.type == "change" || (e.type == "propertychange" && window.event.propertyName == "checked")) {
      disableButton(saveButton);
    }
  });
}

/**
 * Disable button with id buttonId
 * @param buttonId
*/
function disableButton(buttonId) {
  $('#'+buttonId).prop("disabled", true );
}

function scrollToElement(elementId, containerSelector) {
  var container = $(containerSelector);
  // We need to remove the container offset from the elem offset so we scroll the correct amount in scroll function
  var offset = $("#"+ elementId).offset()
  if(offset){
    var offsetTop = offset.top - container.offset().top - 60;
    container.animate({ scrollTop: offsetTop }, 500);
  }
}

function scrollToElementPopup(elementSelector, popupId){
    //get the top offset of the target anchor
    var target_offset = $("#"+ popupId +" .modal-body "+elementSelector).offset();
    var container = $("#"+popupId+" .modal-body");
    var target_top = target_offset.top-container.offset().top;
    //goto that anchor by setting the body scroll top to anchor top
    container.animate({scrollTop:target_top}, 500, 'easeInSine');
};

function showParameters(e, s){
  var btn = $(e.target)
  var txt = btn.find(".action").text();
  btn.find(".action").text(txt=="Show" ? "Hide" : "Show");
  $("#showParametersInfo" + s).toggle();

}

function redirectTo(url,event) {
  // If using middle button, open the link in a new tab
  if( event.which == 2 ) {
    window.open(url, '_blank');
  } else {
    // On left button button, open the link the same tab
    if ( event.which == 1 ) {
      location.href=url;
    }
  }
}

/*
 * This function takes the content of 2 elements (represented by their ids)
 * , produce a diff beetween them and add the result in third element
 */
function makeDiff(beforeId,afterId,resultId) {
  function appendLines(c, s) {
    var res = s.replace(/\n/g, "\n" + c);
    res = c+res;
    if(res.charAt(res.length -1) == c)
      res = res.substring(0, res.length - 1);
    if(res.charAt(res.length -1) == "\n")
      return res;
    else
      return res+"\n"
  }
  var before = $('#'+beforeId);
  var after  = $('#'+afterId);
  var result = $('#'+resultId);
  var diff = Diff.diffLines(before.text(), after.text());
  var fragment = document.createDocumentFragment();
  for (var i=0; i < diff.length; i++) {
    if (diff[i].added && diff[i + 1] && diff[i + 1].removed) {
      var swap = diff[i];
      diff[i] = diff[i + 1];
      diff[i + 1] = swap;
    }

    var node;
    if (diff[i].removed) {
      node = document.createElement('del');
      node.appendChild(document.createTextNode(appendLines('-', diff[i].value)));
    }
    else
      if (diff[i].added) {
        node = document.createElement('ins');
        node.appendChild(document.createTextNode(appendLines('+', diff[i].value)));
      }
      else
        node = document.createTextNode(appendLines(" ", diff[i].value));

    fragment.appendChild(node);
  }
  result.css({
    "white-space": "pre-line",
    "word-break": "break-word",
    "overflow": "auto"
  });
  result.text('');
  result.append(fragment);
}

function filterTableInclude(tableId, filter, include) {
  var finalFilter = "^"+filter+"$";
  var includeFilter;
  // No filter defined or table is not initialized
  if (filter === undefined || ! $.fn.dataTable.isDataTable( tableId )) {
    return;
  }


  if (filter === "") {
    includeFilter = filter;
  } else {
    includeFilter = finalFilter +"|^"+filter+" »";
  }

  var table = $(tableId).DataTable({"retrieve": true});
  if (include === undefined || include) {
    table.column(column).search(includeFilter,true,false,true ).draw();
  } else {
    table.column(column).search(finalFilter,true,false,true ).draw();
  }
}

var openAllNodes = function(treeId)  { $(treeId).jstree('open_all' ); return false; }
function toggleTree(treeId, toggleButton) {
  var tree = $(treeId).jstree()
  var isOpen = $(treeId).find(".jstree-open").length > 0
  if (isOpen) {
    tree.close_all()
  } else {
    tree.open_all()
  }
  $(toggleButton).children().toggleClass('fa-folder-open');
}

var searchTree = function(inputId, treeId) {

  if($(inputId).val() && $(inputId).val().length >= 3) {
      $(treeId).jstree('search', $(inputId).val());
  } else {
      $(treeId).jstree('clear_search');
  }
  enableSubtree($(".jstree-search"));
  return false;
}

var clearSearchFieldTree = function(inputId, treeId) {
  $(inputId).val('');
  $(treeId).jstree('clear_search');
  return false;
}

$(document).ready(function() {
  $.extend( $.fn.dataTable.ext.oSort, {
    "percent-pre": function ( a ) {
      var x = (a == "-") ? 0 : a.replace( /%/, "" );
      return parseFloat( x );
    }
  , "percent-asc": function ( a, b ) {
      return ((a < b) ? -1 : ((a > b) ? 1 : 0));
    }
  , "percent-desc": function ( a, b ) {
      return ((a < b) ? 1 : ((a > b) ? -1 : 0));
    }
  , "num-html-pre": function ( a ) {
      var x = String(a).replace( /<[\s\S]*?>/g, "" );
      return parseFloat( x );
    }
  , "num-html-asc": function ( a, b ) {
      return ((a < b) ? -1 : ((a > b) ? 1 : 0));
    }
  , "num-html-desc": function ( a, b ) {
      return ((a < b) ? 1 : ((a > b) ? -1 : 0));
    }
  } );
  sidebarControl();
  // Init tooltips
  initBsTooltips();
  // Init and check tab states
  initAndCheckTabs();

  // Hide any open tooltips when the anywhere else in the body is clicked
  $('body').on('click', function (e) {
      $('[data-bs-toggle=tooltip]').each(function () {
          if (!$(this).is(e.target) && $(this).has(e.target).length === 0 && $('.tooltip').has(e.target).length === 0) {
              $(this).tooltip('hide');
          }
      });
  });
});

function checkMigrationButton(currentVersion,selectId) {
  var selectedVersion = $("#"+selectId+" option:selected" ).text()
  if (currentVersion === selectedVersion) {
    $('#migrationButton').prop("disabled", true );
  } else {
    $('#migrationButton').prop("disabled", false );
  }
}


/*
 * a function that allows to set the height of a div to roughtly
 * the height of the content of a Rudder page (i.e: without
 * the header/footer and the place for a title).
 */
function correctContentHeight(selector) {
  $(selector).height(Math.max(400, $(document).height() - 200));
}



/**
 * A pair of function that allows to parse and set the # part of
 * the url to some value for node/query
 */
function parseURLHash() {
  try {
    return JSON.parse(decodeURI(window.location.hash.substring(1)));
  } catch(e) {
    return {};
  }
}

function parseSearchHash(queryCallback) {
  var hash = parseURLHash();
  if( hash.query != null && JSON.stringify(hash.query).length > 0) {
    queryCallback(JSON.stringify(hash.query));
  } else {
    queryCallback("");
  }
}

function updateHashString(key, value) {
  const hash = parseURLHash();
  hash[key] = value;
  const baseUrl = window.location.href.split('#')[0];
  window.location.replace(baseUrl + '#' + JSON.stringify(hash));
}

function directiveOverriddenTooltip(explanation){
  var tooltip = "" +
    "<h4>Directive Skipped</h4>" +
    "<div class='tooltip-content policy-mode overridden'>"+
    "<p>This directive is skipped because it is overridden by an other one here.</p>"+
    "<p>"+ explanation +"</p>"+
    "</div>";
  return tooltip;
}

function policyModeTooltip(kind, policyName, explanation){
  var tooltip = "" +
    "<h4>Policy mode </h4>" +
    "<div class='tooltip-content policy-mode "+policyName+"'>"+
    "<p>This "+ kind +" is in <b>"+ policyName +"</b> mode.</p>"+
    "<p>"+ explanation +"</p>"+
    "</div>";
  return tooltip;
}

function createTextAgentPolicyMode(isNode, currentPolicyMode, explanation){
  var policyMode = currentPolicyMode.toLowerCase();
  var nodeOrDirective = isNode ? "node" : "directive";
  var labelType = "label-"+policyMode;
  var span = "<span class='label-text " + labelType + " fa fa-question-circle' data-bs-toggle='tooltip' data-bs-placement='top' title=''></span>"
  var badge = $(span).get(0);
  var tooltip = policyModeTooltip(nodeOrDirective, policyMode, explanation);
  badge.setAttribute("title", tooltip);
  return badge;
}

function createBadgeAgentPolicyMode(elmnt, currentPolicyMode, explanation){
  var policyMode = currentPolicyMode.toLowerCase();
  var labelType  = "label-"+policyMode;
  var span = "<span class='rudder-label label-sm "+ labelType +"' data-bs-toggle='tooltip' data-bs-placement='top' title=''></span>";
  var badge = $(span).get(0);
  var tooltip = null;
  if(currentPolicyMode == "overridden") {
    tooltip = directiveOverriddenTooltip(explanation);
  } else {
    tooltip = policyModeTooltip(elmnt, policyMode, explanation);
  }
  badge.setAttribute("title", tooltip);
  return badge;
}

function getBadgePolicyMode(data){
  var enforce = audit = false;
  for (rule in data){
    if((data[rule].policyMode=="audit")&&(!audit)){
      audit = true;
    }else if((data[rule].policyMode=="enforce")&&(!enforce)){
      enforce = true;
    }
  }
  if(enforce && audit){
    return "mixed";
  }else if(enforce){
    return "enforce";
  }else if(audit){
    return "audit";
  }
  return "error";
}
function createBadgeAgentPolicyModeMixed(data){
  var rules = data.details;
  var badgePolicyMode = getBadgePolicyMode(rules);
  var labelType = "label-"+badgePolicyMode;
  var span = "<span class='rudder-label "+ labelType +" label-sm' data-bs-toggle='tooltip' data-bs-placement='top' title=''></span>"
  var badge = $(span).get(0);
  var tooltip = policyModeTooltip('policy', badgePolicyMode, '');
  badge.setAttribute("title", tooltip);
  return badge;
}

function showFileManager(idField){
  fm.ports.onOpen.send(null);
  fm.ports.close.subscribe(function(files) {
    if(files.length > 0){
      var inputField = $("#" + idField + "-fileInput")
      inputField.val(files[0].substring(1));
      }
  });
}

//Adjust tree height
function adjustHeight(treeId, toolbar){
  var tree = $(treeId);
  var maxHeight = "none";
  if(window.innerWidth>=992){
    var treeOffset = tree.offset();
    if(treeOffset){
      var toolbarHeight;
      if(toolbar && $(toolbar).offset()){
        toolbarHeight = parseFloat($(toolbar).css('height'));
      }else{
        toolbarHeight = 0;
      }
      var offsetTop = treeOffset.top + 15 + toolbarHeight;
      maxHeight = 'calc(100vh - '+ offsetTop + 'px)';
    }
  }
  tree.css('height',maxHeight);
}

function graphTooltip (tooltip, displayColor) {
  // Tooltip Element
  var tooltipEl = document.getElementById('chartjs-tooltip');
  if (!tooltipEl) {
    tooltipEl = document.createElement('div');
    tooltipEl.id = 'chartjs-tooltip';
    tooltipEl.innerHTML = "<ul></ul>"
    document.body.appendChild(tooltipEl);
  }

  // Hide if no tooltip
  if (tooltip.opacity === 0) {
    tooltipEl.style.opacity = 0;
    return;
  }
  function getBody(bodyItem) {
    return bodyItem.lines;
  }
  // Set Text
  if (tooltip.body) {
    var titleLines = tooltip.title || [];
    var bodyLines = tooltip.body.map(getBody);
    var innerHtml = ""
    if (titleLines.length > 0) {
      innerHtml += '<li><h4>' + titleLines.join(" ") + '</h4></li>';
    }
    bodyLines.forEach(function(body, i) {
      var span = "";
      if (displayColor) {
        var colors = tooltip.labelColors[i];
        var style = 'background:' + colors.backgroundColor;
        style += '; border-color:' + colors.borderColor;
        style += '; border-width: 2px';
        var span = '<span class="legend-square" style="' + style + '"></span>'
      }
      innerHtml += '<li>'+ span + body + '</li>';
    });
    var tableRoot = tooltipEl.querySelector('ul');
    tableRoot.innerHTML = innerHtml;
  }
  var position = this._chart.canvas.getBoundingClientRect();
  // Display, position, and set styles for font
  tooltipEl.style.opacity = 1;
  tooltipEl.style.left = position.left + tooltip.caretX + 'px';
  tooltipEl.style.top = position.top + tooltip.caretY + 10 + 'px';
  tooltipEl.style.fontFamily = tooltip._bodyFontFamily;
  tooltipEl.style.fontSize = tooltip.bodyfontSize;
  tooltipEl.style.fontWeight = tooltip._bodyFontStyle;
  tooltipEl.style.padding = tooltip.yPadding + 'px ' + tooltip.xPadding + 'px';
};
function checkIPaddress(address) {
  return (/^((((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)(\/([1-9]|(0|([1-2][0-9]))|(3[0-2])))?)|(([0-9a-f]|:){1,4}(:([0-9a-f]{0,4})*){1,7}(\/([0-9]{1,2}|1[01][0-9]|12[0-8]))?)|(0.0.0.0))$/i).test(address);
}

function callRemoteRun(nodeId, refreshCompliance, defaultOrInventory) {
  const $textAction = $("#triggerBtn" + defaultOrInventory).find("span").first();
  const $iconButton = $("#triggerBtn" + defaultOrInventory).find("i");
  const $panelContent = $("#report" + defaultOrInventory).find("pre");
  const spinner = '<img class="svg-loading" src="'+resourcesPath+'/images/ajax-loader-white.svg"/>';

  function showOrHideBtn() {
    $("#report" + defaultOrInventory + ".collapse").on('show.bs.collapse', function(){
      $("#visibilityOutput" + defaultOrInventory).html($("#visibilityOutput" + defaultOrInventory).html().replace("Show", "Hide"));
    }).on('hide.bs.collapse', function(){
      $("#visibilityOutput" + defaultOrInventory).html($("#visibilityOutput" + defaultOrInventory).html().replace("Hide", "Show"));
    });
  }

  function showTriggerBtn() {
    $iconButton.children().remove(".svg-loading");
    $iconButton.addClass("fa fa-play");
    $("#triggerBtn" + defaultOrInventory).prop('disabled', null).blur();
    $textAction.html("Trigger agent " + defaultOrInventory.toLowerCase())
  }

  $iconButton.removeClass("fa fa-play").append(spinner)
  $("#triggerBtn" + defaultOrInventory).attr('disabled', 'disabled')
  $textAction.html("Loading")
  $("#report" + defaultOrInventory).removeClass("in");
  $("#visibilityOutput" + defaultOrInventory).removeClass("btn-success");
  $("#visibilityOutput" + defaultOrInventory).removeClass("btn-danger");
  $("#visibilityOutput" + defaultOrInventory).hide();
  $("#report" + defaultOrInventory).removeClass("border-success");
  $("#report" + defaultOrInventory).removeClass("border-fail");
  $("pre" + "#response" + defaultOrInventory).remove();
  $(".alert-danger").remove();
  $("#countDown" + defaultOrInventory).find("span").empty();

  const classes = defaultOrInventory == "Inventory" ? '{"classes":["force_inventory"]}' : ""

  $.ajax({
    type: "POST",
    url: contextPath + "/secure/api/nodes/" + nodeId + "/applyPolicy",
    data: classes,
    contentType: "application/json; charset=utf-8",
    success: function (response, status, jqXHR) {
        $("#visibilityOutput" + defaultOrInventory).addClass("btn-default").html("Show output").append('&nbsp;<i class="fa fa-check fa-check-custom"></i>');
        $("#report" + defaultOrInventory).html('<pre id="response' + defaultOrInventory + '">' + escapeHTML(response) + '</pre>');
        $("#report" + defaultOrInventory).addClass("border-success");
        $("#visibilityOutput" + defaultOrInventory).show();
        showOrHideBtn();
        var counter = 5;
        var interval = setInterval(function() {
          $('#countDown' + defaultOrInventory).find("span").show()
          counter--;
          $("#countDown" + defaultOrInventory).find("span").html("Refresh table in " + counter);
          if (counter == 0) {
            $("#countDown" + defaultOrInventory).find("span").html("Table of compliance has been refreshed");
            refreshCompliance();
            clearInterval(interval);
            setTimeout(function() {
              $('#countDown' + defaultOrInventory).find("span").fadeOut();
              showTriggerBtn();
            }, 3000);
              }
        }, 1000);
    },
    error: function (jqXHR, textStatus, errorThrown) {
        $iconButton.children().remove(".svg-loading");
        $iconButton.addClass("fa fa-play");
        $("#triggerBtn" + defaultOrInventory).prop('disabled', null).blur();
        $textAction.html("Trigger agent " + defaultOrInventory.toLowerCase());
        $("#visibilityOutput" + defaultOrInventory).addClass("btn-default").html("Show error").append('&nbsp;<i class="fa fa-times fa-times-custom"></i>');
        $("#report" + defaultOrInventory).remove("pre" + "#response" + defaultOrInventory).html('<div class="alert alert-danger error-trigger" role="alert">' + '<b>' +jqXHR.status + ' - ' + errorThrown +'</b>' +'<br>' + jqXHR.responseText  + '</div>');
        $("#report" + defaultOrInventory).addClass("border-fail");
        $("#visibilityOutput" + defaultOrInventory).show();
        showOrHideBtn();
        showTriggerBtn();
    }
  });

}

function showHideRunLogs(scrollTarget, tabId, init, refresh) {
  $("#allLogButton-" + tabId).toggle()
  $("#logRun-" + tabId).toggle()
  if ( ! $.fn.DataTable.isDataTable( '#complianceLogsGrid-' + tabId ) && init !== undefined) {
    init()
  }
  if (refresh !== undefined) {
    refresh()
  }
  $([document.documentElement, document.body]).animate({
          scrollTop: $(scrollTarget).offset().top
      }, 400);
}

/**
 * If you specify a IANA timezone, it will initialize them with the current date in that timezone,
 * and clicking in the "Now" button takes that timezone into account.
 * You should initialize the endDate with the date with that timezone though.
 * If timezone is not specified, browser timezone is used.
 *
 * Initialize a date picker fields with default 2 hours from now date range.
 * You can specify a value that is greater than 24 for hours.
 */
function initDatePickers(id, action, endDate = new Date(), timezone = null, hours = 2) {
  function setDatetimepicker(pickElement) {
      function handleNowWithTimezone() {
        $('.ui-datepicker-current').off('click').on('click', function() {
          var now = new Date();
          var convertedDate = timezone ? changeTimezone(now, timezone) : now;
          $(pickElement).datetimepicker("setDate", convertedDate);
        });
     }
     var settings = {
       dateFormat:'yy-mm-dd',
       timeFormat: 'HH:mm:ss',
       timeInput: true,
       beforeShow: function() {
         // ensure the "Now" button click is handled when the datetimepicker is ready needs a delay
         setTimeout(handleNowWithTimezone, 100); // this value in millis seems fine before the user clicks the button
       },
       onSelect: function (selected) {
         $(this).val(selected);
         // we also need to set the "Now" button handler every time a selection is done
         // , or else jquery will override our custom handler
         handleNowWithTimezone(selected)
       },
     }
     return $(pickElement).datetimepicker(settings);
  }

  var startDate = new Date(endDate)
  startDate.setHours(endDate.getHours() - hours);
  setDatetimepicker(id + ' .pickStartInput');
  setDatetimepicker(id + ' .pickEndInput');
  $(".pickStartInput").datetimepicker("setDate", startDate);
  $(".pickEndInput").datetimepicker("setDate", endDate);
  $(id+"Button").click(function () {
    var param = '{"start":"'+$(id +" .pickStartInput").val()+'", "end":"'+$(id +" .pickEndInput").val()+'"}'
    action(param)
  });
}

function changeTimezone(date, ianatz) {
  var invdate = new Date(date.toLocaleString('en-US', {
    timeZone: ianatz
  }));
  var diff = date.getTime() - invdate.getTime();
  return new Date(date.getTime() - diff);
}

/**
 * Get the date as a string in "yyyy-mm-dd" format, taking timezone into account
 * see https://stackoverflow.com/a/29774197
 */
function getDateString(date = new Date()) {
  const offset = date.getTimezoneOffset()
  return new Date(date.getTime() - (offset*60*1000)).toISOString().split('T')[0]
}


function updateNodeIdAndReload(nodeId) {
  try {
    var json = JSON.parse(location.hash);
    if ('nodeId' in json) {
      json['nodeId'] = nodeId
    }
  } catch (e) {
    var json = {"nodeId" : nodeId}
  }
  location.hash = JSON.stringify(json)

  location.reload()
}

var converter = new showdown.Converter({ extensions: ['xssfilter'] });

function generateMarkdown(text, container) {
  var html = converter.makeHtml(text)
  $(container).html(html)
}

function setupMarkdown(initialValue, id) {
  $("#" + id + " textarea").keyup(function() {
    var value = $(this).val()
    generateMarkdown(value,"#" + id + "Markdown")
    generateMarkdown(value,"#" + id + "PreviewMarkdown")

    if (value.length === 0) {
      $("#" + id + "MarkdownEmpty").show();
    } else {
      $("#" + id + "MarkdownEmpty").hide();
    }
  } )

  if (initialValue.length === 0) {
    $("#" + id + "MarkdownEmpty").show();
  }
  generateMarkdown(initialValue,"#" + id + "Markdown")
  generateMarkdown(initialValue,"#" + id + "PreviewMarkdown")

}

function togglePreview(target, id) {
  $("#"+ id + "MarkdownPreviewContainer").toggleClass("d-none");
  $(target).toggleClass('fa-eye-slash fa-eye');
  $('#'+ id).toggleClass('col-xs-6 col-xs-12');
}

function toggleMarkdownEditor(id) {
  $("#"+ id + "Container").toggleClass("d-none");
  $("#"+ id + "MarkdownContainer").toggleClass("d-none");
}

function toggleOpacity(target) {
  $(target).toggleClass("half-opacity")
}

function navScroll(event, target, container){
  if(event) event.preventDefault();
  var container       = $(container);
  var target          = $(target);
  var paddingTop      = 20; // Substract padding-top of the container
  var anchorDiff      = 20; // Used to trigger the scrollSpy feature
  var containerOffset = container.offset().top;
  var targetOffset    = target.offset().top - paddingTop;
  var offsetDiff      = targetOffset - containerOffset;
  var scrollTop       = container.scrollTop()
  if(Math.abs(offsetDiff) > anchorDiff){
    container.animate({ scrollTop: scrollTop + offsetDiff + anchorDiff }, 200);
  }
  return false;
}

function buildScrollSpyNav(navbarId, containerId){
    const navbarUl = "#" + navbarId + " > ul";
    $(navbarUl).html("");
    var linkText, tmp, link, listItem;
    var regex = /[^a-z0-9]/gmi
    $("#"+containerId).find(".page-title, .page-subtitle").each(function(){
      linkText = $(this).text();
      tmp      = linkText.replace(regex, "-");
      $(this).attr('id', tmp);
      link     = $("<a class='nav-link'>");
      listItem = $("<li>");
      var targetLink = '#'+tmp;
      var subClass = $(this).hasClass("page-subtitle") ? "subtitle" : ""
      link.attr("href","#"+tmp).text(linkText).addClass(subClass).on('click',function(event){navScroll(event, targetLink, ".main-details[data-bs-spy='scroll']")});
      listItem.addClass("nav-item").append(link);
      $(navbarUl).append(listItem);
    });
}

function sidebarControl(){
  var speed = 400;
  $(".sidebar li a").on("click", function(a) {
    var d = $(this)
      , e = d.next();
    if (e.is(".treeview-menu") && e.is(":visible"))
      e.slideUp(speed, function() {
        e.removeClass("menu-open")
      }),
      e.parent("li").removeClass("active");
    else if (e.is(".treeview-menu") && !e.is(":visible")) {
      var f = d.parents("ul").first()
        , g = f.find("ul:visible").slideUp(speed);
      g.removeClass("menu-open");
      var h = d.parent("li");
      e.slideDown(speed, function() {
        e.addClass("menu-open"),
        f.find("li.active").removeClass("active"),
        h.addClass("active")
      })
    }
    e.is(".treeview-menu") && a.preventDefault()
  })
}

// Update the height of a input/textarea according to its content
function autoResize(e) {
  var elem = e.target ? e.target : e;
  if(elem !== undefined && elem !== null){
    elem.style.height = 'auto';
    var height = elem.scrollHeight > 0 ? elem.scrollHeight + 'px' : 'auto';
    elem.style.height = height;
  }
}

function logout(cb){
    if (isLoggedIn) cb();
    else window.location.replace(contextPath);
}

// BOOTSTRAP 5
function initBsTooltips(){
  var tooltipTriggerList = [].slice.call(document.querySelectorAll('[data-bs-toggle="tooltip"]'));
  return tooltipTriggerList.map(function (tooltipTriggerEl) {
    let dataTrigger = $(tooltipTriggerEl).attr('data-bs-trigger');
    let trigger = dataTrigger === undefined ? 'hover' : dataTrigger;
    tooltipTriggerEl.addEventListener('hide.bs.tooltip', () => {
      removeBsTooltips();
    });
    tooltipTriggerEl.addEventListener('show.bs.tooltip', () => {
      removeBsTooltips()
    });
    return new bootstrap.Tooltip(tooltipTriggerEl,{container : "body", html : true, trigger : trigger});
  });
}

function removeBsTooltips(){
  document.querySelectorAll(".tooltip").forEach(e => e.remove());
}
$('body').on('click', function (e) {
    $('[data-bs-toggle=tooltip]').each(function () {
        // hide any open tooltips when the anywhere else in the body is clicked
        if (!$(this).is(e.target) && $(this).has(e.target).length === 0 && $('.tooltip').has(e.target).length === 0) {
            $(this).tooltip('hide');
        }
    });
});
function initBsModal(modalName){
  var selector = document.querySelector('#' + modalName);
  var modal    = bootstrap.Modal.getInstance(selector);
  var instance = (modal === null || modal === undefined) ? new bootstrap.Modal('#'+modalName) : modal;
  instance.show();
}
function hideBsModal(modalName){
  var selector = document.querySelector('#' + modalName);
  var modal    = bootstrap.Modal.getInstance(selector);
  if(modal === null || modal === undefined) return false;
  modal.hide();
}
function initBsTabs(isJsonHash = false, adjustNodeTables = false){
  const triggerTabList = [].slice.call(document.querySelectorAll('[data-bs-toggle="tab"]'));
  triggerTabList.forEach(function (triggerEl) {
    const tabTrigger = new bootstrap.Tab(triggerEl);

    triggerEl.addEventListener('click', function (event) {
      event.preventDefault();
      tabTrigger.show();
      const newHash = this.getAttribute("data-bs-target");

      if (isJsonHash) {
        updateHashString("tab",newHash);
      }else{
        history.replaceState(undefined, undefined, newHash);
      }

      if (adjustNodeTables) {
        $("#nodes, #serverGrid").DataTable({"retrieve": true}).columns.adjust().draw();
      }
      return false;
    });
  });
}

function waitForElement(selector) {
  return new Promise((resolve) => {
    const observer = new MutationObserver((mutations, observer) => {
      const element = document.querySelector(selector);
      if (element) {
        observer.disconnect();
        resolve(element);
      }
    });
    observer.observe(document.body, {
      childList: true,
      subtree: true,
    });
  });
}

function initAndCheckTabs(){
  const isNodePage = window.location.pathname.includes("nodeManager/nodes");

  initBsTabs(isNodePage, isNodePage);
  let hash = window.location.hash;

  // If the anchor corresponds to a tab ID then this tab is opened automatically,
  // else nothing happens
  if (hash === "") return false;

  // An exception is made for the for the Nodes page,
  // which uses the anchor to store the search query AND the tab ID in a json object.
  if (isNodePage) {
    let tab = parseURLHash().tab;
    if (tab === undefined) return false;
    hash = tab;
  }
  const tabSelector = '[data-bs-target="' + hash + '"]';
  waitForElement(tabSelector).then((tab) => {
    bootstrap.Tab.getInstance(tab).show();
  });
}

function copy(value) {
  navigator.clipboard.writeText(value).then(function(){ createInfoNotification("Copied to clipboard!") }, function() {createErrorNotification("Could not copy to clipboard")})
}

// to create blob of binary data and download it, see https://stackoverflow.com/a/37340749
function base64ToArrayBuffer(base64) {
  var binaryString = window.atob(base64);
  var binaryLen = binaryString.length;
  var bytes = new Uint8Array(binaryLen);
  for (var i = 0; i < binaryLen; i++) {
    var ascii = binaryString.charCodeAt(i);
    bytes[i] = ascii;
  }
  return bytes;
}
function saveByteArray(downloadedFileName, contentType, byte) {
  var blob = new Blob([byte], {type: contentType});
  var link = document.createElement('a');
  link.href = window.URL.createObjectURL(blob);
  var fileName = downloadedFileName;
  link.download = fileName;
  link.click();
}
