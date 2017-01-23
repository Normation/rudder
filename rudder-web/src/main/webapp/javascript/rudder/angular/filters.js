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


var app = angular.module('filters', []);

app.controller('filterTagDirectiveCtrl', function ($scope, $http, $location, $timeout, $rootScope) {
  $scope.searchStr = "";
  $scope.showFilters = false;
  $scope.only = {"key":false , "value":false};
  $scope.newTag = {"key":""  , "value":""};
  $scope.tags = [];
  $scope.tagScopes = []
  $scope.isEmptyOrBlank = function(str){
    return (!str || 0 === str.length || /^\s*$/.test(str));
  }
  $scope.clearSearch = function(){
    $scope.searchStr = "";
    clearSearchFieldTree('#activeTechniquesTree');
    $scope.searchTree('#activeTechniquesTree');
  }
  $scope.resetNewTag = function(){
    $scope.newTag = {"key":"" , "value":""};
  }
  $scope.modifyTag = function(index,tag){
    $scope.toggleFilter('#helpTag',true);
    $scope.newTag.key   = tag.key;
    $scope.newTag.value = tag.value;
  }
  
  $scope.addTag = function(tag){
  	var newTag = angular.copy(tag);
    var alreadyExist = false;
    for(var i=0 ; i<$scope.tags.length ; i++){
      if((newTag.key==$scope.tags[i].key)&&(newTag.value==$scope.tags[i].value)){
        alreadyExist = true;
        $scope.tags[i].alreadyExist = true;
        (function(i){
          $timeout(function() {
            $scope.tags[i].alreadyExist = false;
          }, 200);
        })(i);
      }
    }
    if(!alreadyExist){
      $scope.tags.push(newTag);
      $scope.searchTree("#activeTechniquesTree");
      if(!tag){
        $scope.resetNewTag();
      }
      $scope.updateFilter();
      $timeout(function() {
        adjustHeight('#activeTechniquesTree');
      },0);
    }
  }
  
  $rootScope.$on("addTag", function(event,tag) {
    $scope.addTag(tag)
  })
  
  $scope.removeTag = function(index){
  var tag = $scope.tags[index];
    $scope.tags.splice(index, 1);
    $scope.searchTree('#activeTechniquesTree');
    $timeout(function() {
      adjustHeight('#activeTechniquesTree');
    },0);
    $scope.updateFilter();
  }
  
  $scope.registerScope = function(scope){
    $scope.tagScopes.push(scope)
    scope.$emit("registerScope", $rootScope)
  }
  
  $scope.updateFilter = function(){
    angular.forEach($scope.tagScopes, function(scope) {
      scope.$emit("updateFilter",{ "tags" : $scope.tags, "mode" : $scope.only})
    })
  }

  $scope.clearAllTags = function(){
    $scope.tags = [];
    $scope.updateFilter();
    $scope.searchTree('#activeTechniquesTree');
  }
  
  $scope.toggleFilter = function(chevron, forceOpen){
    if(forceOpen){
      $('#stateFilterTag').addClass('in');
      $('#form-tag').addClass('in');
    }else{
      $('#stateFilterTag').toggleClass('in');
      $('#form-tag').toggleClass('in');
    }
    var state = forceOpen ? forceOpen : !$scope.showFilters;
    $scope.showFilters = state;
    if($scope.showFilters){
      $('.input-key').focus();
    }
    adjustHeight('#activeTechniquesTree');
  }
  
  $scope.onlyKey = function(elem){
    var button = $(elem.currentTarget);
    button.toggleClass('active');
    $scope.only.key = !$scope.only.key;
    $scope.only.value = false;
    $scope.searchTree('#activeTechniquesTree');
    $scope.updateFilter();
  }
  
  $scope.onlyValue = function(elem){
    var button = $(elem.currentTarget);
    button.toggleClass('active');
    $scope.only.value = !$scope.only.value;
    $scope.only.key = false;
    $scope.searchTree('#activeTechniquesTree');
    $scope.updateFilter();
  }
  
  $scope.onlyAll = function(elem){
    var button = $(elem.currentTarget);
    button.addClass('active');
    $scope.only.key   = false;
    $scope.only.value = false;
    $scope.searchTree('#activeTechniquesTree');
    $scope.updateFilter();
  }

  $scope.searchTree = function(treeId) {
    $(treeId).jstree('searchtag', $scope.searchStr, $scope.tags, $scope.only);
  }
  
  $scope.clearSearchFieldTree = function(treeId) {
    $scope.searchStr = "";
    $(treeId).jstree('searchtag', '', $scope.tags, $scope.only);
  }
  
  $scope.refuseEnter = function(event){
    refuseEnter(event);
  }
  
  adjustHeight('#activeTechniquesTree');
});





/*==========  Rule filter controller, a lot to refactor ============*/



app.controller('filterTagRuleCtrl', function ($scope, $http, $location, $timeout, $rootScope) {
  $scope.tableId="#grid_rules_grid_zone";
  $scope.searchStr = "";
  $scope.showFilters = false;
  $scope.only = {"key":false , "value":false};
  $scope.newTag = {"key":""  , "value":""};
  $scope.tags = [];
  $scope.hide = {"search":false, "tag":true};
  $scope.tagScopes = []

  $scope.isEmptyOrBlank = function(str){
    return (!str || 0 === str.length || /^\s*$/.test(str));
  }

  $scope.toggleTagForm = function(element, input, forceOpen){
    for(x in $scope.hide){
      if(x === element){
       if(forceOpen){
          $scope.hide[x] = false;
       }else{
          $scope.hide[x] = !$scope.hide[x];
        }
      }else{
        $scope.hide[x] = true;
      }
    }
    $timeout(function() {
      $(input).focus();
    }, 0);
  }
  $scope.removeTag = function(index){
    var tag = $scope.tags[index];
    $scope.tags.splice(index, 1);
    $scope.searchTable();
    $scope.updateFilter();
  }
  $scope.resetNewTag = function(){
    $scope.newTag = {"key":"" , "value":""};
  }
  $scope.modifyTag = function(index,tag){
    $scope.toggleTagForm('tag','.input-key',true);
    $scope.newTag.key   = tag.key;
    $scope.newTag.value = tag.value;
  }
  
  $scope.registerScope = function(scope){
    $scope.tagScopes.push(scope)
    scope.$emit("registerScope", $rootScope)
  }
  
  $rootScope.$on("addTag", function(event,tag) {
    $scope.addTag(tag)
  })
  
  $scope.addTag = function(tag){
    
	var newTag = angular.copy(tag ? tag : $scope.newTag);
    var alreadyExist = false;
    for(var i=0 ; i<$scope.tags.length ; i++){
      if((newTag.key==$scope.tags[i].key)&&(newTag.value==$scope.tags[i].value)){
        alreadyExist = true;
        $scope.tags[i].alreadyExist = true;
        (function(i){
          $timeout(function() {
            $scope.tags[i].alreadyExist = false;
          }, 200);
        })(i);
      }
    }
    if(!alreadyExist){
      $scope.tags.push(newTag);
      $scope.searchTable();
      if(!tag){
        $scope.resetNewTag();
      }else{
        $scope.updateFilter();
      }
    }
    $timeout(function() {
      $('.input-key').focus();
    }, 0);
  }
  $scope.updateFilter = function(){
    angular.forEach($scope.tagScopes, function(scope) {
      scope.$emit("updateFilter",{ "tags" : $scope.tags, "mode" : $scope.only})
    })
  }
  
  $scope.clearSearch = function(){
	  $scope.strSearch =  '' ;
	  $scope.filterGlobal('');
  }
  
  $scope.toggleFilter = function(event,tree){
    $('#form-tag').toggleClass('in');
    $($(event.currentTarget).find('span.pull-right')).toggleClass('in');
    $scope.showFilters = !$scope.showFilters;
    if($scope.showFilters){
      $('.input-key').focus();
    }
  }
  
  $scope.filterGlobal = function(str) {
    $($scope.tableId).DataTable().search(str).draw();
  }
  
  $scope.onlyKey = function(elem){
    var button = $(elem.currentTarget);
    button.toggleClass('active');
    $scope.only.key = !$scope.only.key;
    $scope.only.value = false;
    $scope.searchTable();
    $scope.updateFilter();
  }
  $scope.onlyValue = function(elem){
    var button = $(elem.currentTarget);
    button.toggleClass('active');
    $scope.only.value = !$scope.only.value;
    $scope.only.key = false;
    $scope.searchTable();
    $scope.updateFilter();
  }
  //Avoid compilation error using ngClass directive with '&&' condition
  $scope.getOnlyAllValue = function(){
    return !$scope.only.key && !$scope.only.value;
  }
  
  $scope.onlyAll = function(elem){
    var button = $(elem.currentTarget);
    button.addClass('active');
    $scope.only.key   = false;
    $scope.only.value = false;
    $scope.searchTable();
    $scope.updateFilter();
  }
  
  $scope.clearAllTags = function(){
    $scope.tags = [];
    $scope.updateFilter();
    $scope.searchTable();
  }
  
  $scope.searchTable = function() {
	  var table = $($scope.tableId).DataTable();
	  table.draw();
  }
  
  $scope.clearSearchField = function() {
    $scope.searchStr = "";
  }

  $scope.initFilterTable = function(){
    $.fn.dataTable.ext.search.push(
      function( settings, data, dataIndex ){
        var containsTags;
        var matchTags = [];
        if($scope.tags.length>0){
          var ruleTags = JSON.parse(data[0]);
          for(i in $scope.tags){
            containsTags = false;
            for(j in ruleTags){
              if(((!$scope.only.key && !$scope.only.value)
                &&
                ((($scope.tags[i].key == j)||($scope.tags[i].key==""))&&(($scope.tags[i].value == ruleTags[j])||($scope.tags[i].value==""))))||($scope.only.key && (($scope.tags[i].key == j)||($scope.tags[i].key=="")))||($scope.only.value && (($scope.tags[i].value == ruleTags[j])||($scope.tags[i].value=="")))
              ){
                containsTags = true;
              }
            }
            matchTags.push(containsTags)
          }
          if($.inArray(false, matchTags) < 0){
            return true;
          }
          return false;
        }
        return true;
      }
    );
  }
  $scope.initFilterTable();
});

app.config(function($locationProvider) {
  $locationProvider.html5Mode({
    enabled: true,
    requireBase: false
  });
})

// Adjust tree height
function adjustHeight(treeId){
  var tree = $(treeId);
  var treeOffset = tree.offset()
  if(treeOffset){
    var offsetTop = treeOffset.top + 10;
    var maxHeight = 'calc(100vh - '+ offsetTop + 'px)';
    tree.css('max-height',maxHeight);
  }
}

