/*
*************************************************************************************
* Copyright 2017 Normation SAS
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

$(document).ready(function(){
  var treeId = '#activeTechniquesTree';
  var storageTreeId = 'directiveTreeSettings_nodesState'
  $(treeId).on("searchtag.jstree",function(e, data){
	  data.res.length>0 ? $('#activeTechniquesTree_alert').hide() : $('#activeTechniquesTree_alert').show();
  });
  $(treeId).on("clear_search.jstree",function(e, data){
    $('#activeTechniquesTree_alert').hide();
    $(this).jstree(true).show_all();
  });
  $(treeId).on('close_node.jstree', function(e, data){
    updateTreeSettings(storageTreeId, data, false);
  });
  $(treeId).on('open_node.jstree', function(e, data){
    updateTreeSettings(storageTreeId, data, true);
  });
});

function navScroll(event, target, container){
  if(event) event.preventDefault();
  var container       = $(container);
  var target          = $(target);
  var paddingTop      = 10; // Substract padding-top of the container
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