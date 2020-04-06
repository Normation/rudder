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
  var formId = '#edit-box';
  var storageTreeId = 'directiveTreeSettings_nodesState'
  $(window).on('resize',function(){
    adjustHeight(treeId);
    adjustHeight(formId,"#directiveToolbar");
  });
  $(treeId).on("searchtag.jstree",function(e, data){
	  data.res.length>0 ? $('#activeTechniquesTree_alert').hide() : $('#activeTechniquesTree_alert').show();
    adjustHeight(treeId);
  });
  $(treeId).on("clear_search.jstree",function(e, data){
    $('#activeTechniquesTree_alert').hide();
    $(this).jstree(true).show_all();
    adjustHeight(treeId);
  });
  $(treeId).on('close_node.jstree', function(e, data){
    updateTreeSettings(storageTreeId, data, false);
  });
  $(treeId).on('open_node.jstree', function(e, data){
    updateTreeSettings(storageTreeId, data, true);
  });
});