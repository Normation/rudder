/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

function processKey(e , buttonId){
    if (null == e)
        e = window.event ;
    if (e.keyCode == 13)  {
        document.getElementById(buttonId).click();
        return false;
    }
}

function roundTabs() {
	$(".tabs").tabs();	
	$(".tabsv").tabs();
	$(".tabsv").removeClass('arrondishaut').addClass('ui-tabs-vertical ui-helper-clearfix arrondis');
	$(".tabsv > ul").removeClass('arrondishaut').addClass('arrondisleft');
	$(".tabsv > ul > li").removeClass('arrondishaut').addClass('arrondisleft');
		
	// test auto-ready logic - call corner before DOM is ready
    $('.arrondis').corner("10px");
	$('.arrondishaut').corner("10px top");
	$('.arrondisbas').corner("10px bottom");
	$('.arrondisleft').corner("10px left");
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



/**
 * Move the filter and paginate zones in the location described by tableId_paginate_area and tableId_filter_area
 * move the info (1 to 10) to the info area
 * @param tableId
 * @return
 */
function moveFilterAndFullPaginateArea(tableId) {
	$(tableId+"_paginate_area").append($(tableId+"_paginate"));
	$(tableId+"_info_area").append($(tableId+"_info"));
	if ($(tableId+"_filter_area")) {
		$(tableId+"_filter_area").append($(tableId+"_filter"));
	}
	if ($(tableId+"_length")) {
		$(tableId+"_info_area").append($(tableId+"_length"));
  }

}

function dropFilterAndPaginateArea(tableId) {
	$(tableId+"_paginate").remove();
	$(tableId+"_info").remove();
	$(tableId+"_filter").remove();
	$(tableId+"_length");
}

function activateButtonOnFormChange(containerDivId, buttonId, status) {
	if ("false"==status)
		$('#'+buttonId).attr("disabled", true);
	else 
		$('#'+buttonId).attr("disabled", false);
	
	// all change on the form
	$('#'+containerDivId+' > form').change(function() { $('#'+buttonId).attr("disabled", false);;});
	// This one is for all input (text, textarea, password... and yes, button)
	$('#'+containerDivId+' :input').change(function() { $('#'+buttonId).attr("disabled", false);;});
	// this one is for the checkbox when using IE
	//if ($.browser.msie) 
	//	$('#'+containerDivId+' > form :checkbox').bind('propertychange', function(e) {if (e.type == "change" || (e.type == "propertychange" && window.event.propertyName == "checked")) {  $('#'+buttonId).attr("disabled", false);}});

	// all change on not the form
	$('#'+containerDivId+' :radio').change(function() { $('#'+buttonId).attr("disabled", false);;});
	// This one is for all input (text, textarea, password... and yes, button)
	$('#'+containerDivId+' :input').keyup(function() { $('#'+buttonId).attr("disabled", false);;});

	$('#'+containerDivId+' :checkbox').bind('propertychange', function(e) {if (e.type == "change" || (e.type == "propertychange" && window.event.propertyName == "checked")) {  $('#'+buttonId).attr("disabled", false);}});

}

/**
 *
 */
function activateButtonDeactivateGridOnFormChange(containerDivId, buttonId, gridId, status, optionnalButton) {
	if ("false"==status)
		$('#'+buttonId).attr("disabled", true);
	else
		activateButtonDeactivateGrid(buttonId, gridId);

	// all change on the form
	$('#'+containerDivId+' > form').change(function() { activateButtonDeactivateGrid(buttonId, gridId, optionnalButton);});
	// This one is for all input (text, textarea, password... and yes, button)
	$('#'+containerDivId+' :input').change(function() { activateButtonDeactivateGrid(buttonId, gridId, optionnalButton);});
	// this one is for the checkbox when using IE
	//if ($.browser.msie)
	//	$('#'+containerDivId+' > form :checkbox').bind('propertychange', function(e) {if (e.type == "change" || (e.type == "propertychange" && window.event.propertyName == "checked")) {  activateButtonDeactivateGrid(buttonId, gridId, optionnalButton);}});

	// all change on not the form
	$('#'+containerDivId+' :radio').change(function() { activateButtonDeactivateGrid(buttonId, gridId, optionnalButton);});
	// This one is for all input (text, textarea, password... and yes, button)
	$('#'+containerDivId+' :input').keyup(function() { activateButtonDeactivateGrid(buttonId, gridId, optionnalButton);});

	$('#'+containerDivId+' :checkbox').bind('propertychange', function(e) {if (e.type == "change" || (e.type == "propertychange" && window.event.propertyName == "checked")) {  activateButtonDeactivateGrid(buttonId, gridId, optionnalButton);}});

}

function activateButtonDeactivateGrid(buttonId, gridId, optionnalButton) {
     $('#'+buttonId).attr("disabled", false);
     $('#'+gridId).addClass("desactivatedGrid");

     if ((optionnalButton)&&("" != optionnalButton))
      $('#'+optionnalButton).attr("disabled", true);
}

function scrollToElement(elementId) {
	$('html, body').animate({ scrollTop: $("#"+ elementId).offset().top }, 500);
	
}

$.fn.dataTableExt.oStdClasses.sPageButtonStaticDisabled="paginate_button_disabled";