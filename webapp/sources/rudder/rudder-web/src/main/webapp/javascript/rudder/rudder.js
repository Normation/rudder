/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

/* Event handler function */


var bootstrapButton = $.fn.button.noConflict();
var bootstrapAlert = $.fn.alert.noConflict();
var bootstrapCarousel = $.fn.carousel.noConflict();
var bootstrapCollapse = $.fn.collapse.noConflict();
var bootstrapTooltip = $.fn.tooltip.noConflict();
var bootstrapPopover = $.fn.popover.noConflict();
var bootstrapScrollspy = $.fn.scrollspy.noConflict();
var bootstrapTab = $.fn.tab.noConflict();
var bootstrapAffix = $.fn.affix.noConflict();
var bootstrapModal = $.fn.modal.noConflict();
$.fn.bsModal = bootstrapModal;
$.fn.bsTooltip = bootstrapTooltip;
$.fn.bsPopover = bootstrapPopover;
$.fn.bsTab = bootstrapTab;
/**
 * Instanciate the tooltip
 * For each element having the "tooltipable" class, when hovering it will look for it's
 * tooltipid attribute, and display in the tooltip the content of the div with the id
 * tooltipid
 */
$.widget("ui.tooltip", $.ui.tooltip, {
  options: {
    content: function () {return $(this).prop('title');},
    show: { duration: 200, delay:0, effect: "none" },
    hide: { duration: 200, delay:0, effect: "none" }
  }
});
function createTooltip() {

  $(".tooltipable").tooltip({
    content: function() {
      return $("#"+$(this).attr("tooltipid")).html();
    },
    position: {
      my: "left top+15",
      at: "right top",
      collision: "flipfit"
    }
  });
  createTooltiptr();
}
function createTooltiptr() {
    $(".tooltipabletr").tooltip({
      content: function() {
        return $("#"+$(this).attr("tooltipid")).html();
      },
      track : true
    });
  }


function callPopupWithTimeout(timeout, popupName){
  setTimeout("createPopup('"+popupName+"')", timeout);
}

function createPopup(popupName){
  $('#'+popupName).bsModal('show');
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

/* portlet */

$(function() {

    $(".portlet").addClass("ui-widget ui-widget-content ui-helper-clearfix arrondis")
      .find(".portlet-header")
        .addClass("ui-widget-header")
        .end()
      .find(".portlet-content");

    $(".portlet-header .ui-icon").click(function() {
      $(this).toggleClass("ui-icon-minusthick").toggleClass("ui-icon-plusthick");
      $(this).parents(".portlet:first").find(".portlet-content").toggle();
    });

  });


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

  // Logout
  $(function() {
    $('#logout').click(function() {
      $('#ModalLogOut').bsModal('show');
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
  // this one is for the checkbox when using IE
  //if ($.browser.msie)
  //  $('#'+containerDivId+' > form :checkbox').bind('propertychange', function(e) {if (e.type == "change" || (e.type == "propertychange" && window.event.propertyName == "checked")) {  $('#'+buttonId).prop("disabled", false);}});

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
/*

Correctly handle PNG transparency in Win IE 5.5 & 6.
http://homepage.ntlworld.com/bobosola. Updated 18-Jan-2006.

Use in <HEAD> with DEFER keyword wrapped in conditional comments:
<!--[if lt IE 7]>
<script defer type="text/javascript" src="rudder.js"></script>
<![endif]-->

*/

var arVersion = navigator.appVersion.split("MSIE")
var version = parseFloat(arVersion[1])
/*
 * Sometimes body is not initiated on IE when javascript is launched
 * default value should be true/false ?
 */
var filters= true ;
if (document.body != null)
  {
    filters = document.body.filters;
  }

if ((version >= 5.5) && (filters))
{
   for(var i=0; i<document.images.length; i++)
   {
      var img = document.images[i]
      var imgName = img.src.toUpperCase()
      if (imgName.substring(imgName.length-3, imgName.length) == "PNG")
      {
         var imgID = (img.id) ? "id='" + img.id + "' " : ""
         var imgClass = (img.className) ? "class='" + img.className + "' " : ""
         var imgTitle = (img.title) ? "title='" + img.title + "' " : "title='" + img.alt + "' "
         var imgStyle = "display:inline-block;" + img.style.cssText
         if (img.align == "left") imgStyle = "float:left;" + imgStyle
         if (img.align == "right") imgStyle = "float:right;" + imgStyle
         if (img.parentElement.href) imgStyle = "cursor:hand;" + imgStyle
         var strNewHTML = "<span " + imgID + imgClass + imgTitle
         + " style=\"" + "width:" + img.width + "px; height:" + img.height + "px;" + imgStyle + ";"
         + "filter:progid:DXImageTransform.Microsoft.AlphaImageLoader"
         + "(src=\'" + img.src + "\', sizingMethod='scale');\"></span>"
         img.outerHTML = strNewHTML
         i = i-1
      }
   }
}

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
  var diff = JsDiff.diffLines(before.text(), after.text());
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

function parseSearchHash(nodeIdCallback, queryCallback) {
  var hash = parseURLHash();
  if( hash.nodeId != null && hash.nodeId.length > 0) {
    nodeIdCallback(JSON.stringify(hash));
  }
  if( hash.query != null && JSON.stringify(hash.query).length > 0) {
    queryCallback(JSON.stringify(hash.query));
  }
}

function updateHashString(key, value) {
  var hash = parseURLHash();
  hash[key] = value;
  var baseUrl = window.location.href.split('#')[0];
  window.location.replace(baseUrl + '#' + JSON.stringify(hash));
}

function directiveOverridenTooltip(explanation){
  var tooltip = "" +
    "<h4>Directive Skipped On Node</h4>" +
    "<div class='tooltip-content policy-mode overriden'>"+
    "<p>This directive is skipped because it is overriden by an other one on that node.</p>"+
    "<p>"+ explanation +"</p>"+
    "</div>";
  return tooltip;
}

function policyModeTooltip(kind, policyName, explanation){
  var tooltip = "" +
    "<h4>Node Policy Mode </h4>" +
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
  var span = "<span class='label-text " + labelType + " glyphicon glyphicon-question-sign' data-toggle='tooltip' data-placement='top' data-html='true' title=''></span>"
  var badge = $(span).get(0);
  var tooltip = policyModeTooltip(nodeOrDirective, policyMode, explanation);
  badge.setAttribute("title", tooltip);
  return badge;
}

function createBadgeAgentPolicyMode(elmnt, currentPolicyMode, explanation, container){
  var policyMode = currentPolicyMode.toLowerCase();
  var labelType  = "label-"+policyMode;
  var dataContainer;
  if(container && $(container).length){
    dataContainer = "data-container='" + container + "'";
  }else{
    dataContainer = "";
  }
  var span = "<span class='rudder-label label-sm "+ labelType +"' data-toggle='tooltip' data-placement='top' data-html='true' "+ dataContainer +" title=''></span>";
  var badge = $(span).get(0);
  var tooltip = null;
  if(currentPolicyMode == "overriden") {
    tooltip = directiveOverridenTooltip(explanation);
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
  var span = "<span class='rudder-label "+ labelType +" label-sm' data-toggle='tooltip' data-placement='top' data-html='true' title=''></span>"
  var badge = $(span).get(0);
  var tooltip = policyModeTooltip('policy', badgePolicyMode, '');
  badge.setAttribute("title", tooltip);
  return badge;
}

function showFileManager(idField){
  var filemanager = $("<angular-filemanager></angular-filemanager>");
  // The parent of the new element
  var fileManagerApp = $("#"+idField);
  angular.element(fileManagerApp).injector().invoke(function($compile) {
    var $scope = angular.element(fileManagerApp).scope();
    fileManagerApp.append($compile(filemanager)($scope));
    $scope.directiveId = idField;
    // Finally, refresh the watch expressions in the new element
    $scope.$apply();
  });
  //allow user to close the window pressing Escape
  $(document).on('keydown.closeWindow',function(e){
    if (e.which == 27) {
      hideFileManager();
    }
  });
}
function hideFileManager(){
  $(document).off('keydown.closeWindow');
  $("angular-filemanager").remove();
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

function callRemoteRun(nodeId, refreshCompliance) {
  var $textAction = $( "#triggerBtn" ).find("span").first();
  var $iconButton = $( "#triggerBtn" ).find("i");
  var $panelContent = $("#report").find("pre");
  var spinner = '<img class="svg-loading" src="'+resourcesPath+'/images/ajax-loader-white.svg"/>';

  function showOrHideBtn() {
    $(".collapse").on('show.bs.collapse', function(){
      $("#visibilityOutput").html($("#visibilityOutput").html().replace("Show", "Hide"));
    }).on('hide.bs.collapse', function(){
      $("#visibilityOutput").html($("#visibilityOutput").html().replace("Hide", "Show"));
    });
  }

  function showTriggerBtn() {
    $iconButton.children().remove(".svg-loading");
    $iconButton.addClass("fa fa-play");
    $( "#triggerBtn" ).prop('disabled', null).blur();
    $textAction.html("Trigger Agent")
  }

  $iconButton.removeClass("fa fa-play").append(spinner)
  $( "#triggerBtn" ).attr('disabled', 'disabled')
  $textAction.html("Loading")
  $("#report").removeClass("in");
  $("#visibilityOutput").removeClass("btn-success");
  $("#visibilityOutput").removeClass("btn-danger");
  $("#visibilityOutput").hide();
  $("#report").removeClass("border-success");
  $("#report").removeClass("border-fail");
  $("pre").remove();
  $(".alert-danger").remove();
  $("#countDown").find("span").empty();

  $.ajax({
    type: "POST",
    url: contextPath + "/secure/api/nodes/" + nodeId + "/applyPolicy" ,
    contentType: "application/json; charset=utf-8",
    success: function (response, status, jqXHR) {
        $("#visibilityOutput").addClass("btn-default").html("Show output").append('&nbsp;<i class="fa fa-check fa-lg fa-check-custom"></i>');
        $("#report").html('<pre>' + response + '</pre>');
        $("#report").addClass("border-success");
        $("#visibilityOutput").show();
        showOrHideBtn();
        var counter = 5;
        var interval = setInterval(function() {
          $('#countDown').find("span").show()
          counter--;
          $("#countDown").find("span").html("Refresh table in " + counter);
          if (counter == 0) {
            $("#countDown").find("span").html("Table of compliance has been refreshed");
            refreshCompliance();
            clearInterval(interval);
            setTimeout(function() {
              $('#countDown').find("span").fadeOut();
              showTriggerBtn();
            }, 3000);
              }
        }, 1000);
    },
    error: function (jqXHR, textStatus, errorThrown) {
        $iconButton.children().remove(".svg-loading");
        $iconButton.addClass("fa fa-play");
        $( "#triggerBtn" ).prop('disabled', null).blur();
        $textAction.html("Trigger Agent");
        $("#visibilityOutput").addClass("btn-default").html("Show error").append('&nbsp;<i class="fa fa-times fa-lg fa-times-custom"></i>');
        $("#report").remove("pre").html('<div class="alert alert-danger error-trigger" role="alert">' + '<b>' +jqXHR.status + ' - ' + errorThrown +'</b>' +'<br>' + jqXHR.responseText  + '</div>');
        $("#report").addClass("border-fail");
        $("#visibilityOutput").show();
        showOrHideBtn();
        showTriggerBtn();
    }
  });
}

function showHideRunLogs(scrollTarget, init, refresh) {
  $("#AllLogButton").toggle()
  $("#logRun").toggle()
  if ( ! $.fn.DataTable.isDataTable( '#complianceLogsGrid' ) ) {
    init()
  }
  if (refresh !== undefined) {
    refresh()
  }
  $([document.documentElement, document.body]).animate({
          scrollTop: $(scrollTarget).offset().top
      }, 400);
}

function initDatePickers(id, action) {
  $(id + ' .pickStartInput, ' + id + ' .pickEndInput').datetimepicker({dateFormat:'yy-mm-dd', timeFormat: 'HH:mm:ss', timeInput: true});
  $(id+"Button").click(function () {
    var param = '{"start":"'+$(id +" .pickStartInput").val()+'", "end":"'+$(id +" .pickEndInput").val()+'"}'
    action(param)
  });
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
