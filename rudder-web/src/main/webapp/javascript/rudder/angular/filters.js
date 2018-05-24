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


var app = angular.module('filters', ["angucomplete-alt"]);

app.controller('filterTagCtrl', function ($scope, $http, $location, $timeout, $rootScope) {

  
  $scope.searchStr = "";
  $scope.showFilters = false;
  $scope.only = {"key":false , "value":false};
  $scope.newTag = {"key":""  , "value":""};
  $scope.tags = [];
  $scope.tagScopes = []
  
  $scope.contextPath = contextPath
  var directiveTreeId = "#activeTechniquesTree";
  var tableId = "#grid_rules_grid_zone";
  
  // Should be changed using ng-init
  var directiveFilter = true;
  
  $scope.search = function () {
    if (directiveFilter) {
      $scope.searchTree(directiveTreeId);
      $timeout(function() {
      adjustHeight(directiveTreeId);
      },0);
    } else {
      var table = $(tableId).DataTable();
      table.draw();
    }
    $scope.updateFilter();
  }
  
  
  $scope.isEmptyOrBlank = function(str){
    return (!str || 0 === str.length || /^\s*$/.test(str));
  }
  $scope.clearSearch = function(){
    $scope.searchStr = "";
    if (directiveFilter) {
      clearSearchFieldTree('#activeTechniquesTree');
      $scope.search();
    } else {
      $scope.filterGlobal('');
    }
  }
  $scope.resetNewTag = function(){
    $scope.newTag = {"key":"" , "value":""};
  }
  
  function toggle() {
    if (directiveFilter) {
      $scope.toggleFilter('#helpTag',true);
    } else {
      for(x in $scope.hide){
        if(x === 'tag'){
          $scope.hide[x] = false;
        }else{
          $scope.hide[x] = true;
        }
      }
      $timeout(function() {
        $('.input-key').focus();
      }, 0);
    }
  }
  $scope.modifyTag = function(tag, idKey, idValue){
    toggle();
    $scope.newTag.key   = tag.key;
    $scope.newTag.value = tag.value;
    $scope.$broadcast('angucomplete-alt:changeInput', idKey  , tag.key  );
    $scope.$broadcast('angucomplete-alt:changeInput', idValue, tag.value);
  }
  
  $scope.addTag = function(tag){
    var newTag = angular.copy(tag);
    var isNewTag = true;
    for (var i=0; i < $scope.tags.length; i++) {
      if(  (newTag.key   == $scope.tags[i].key)
        && (newTag.value == $scope.tags[i].value)
        ) {
        isNewTag = false;
        break;
        }
    }
    if(isNewTag){
      $scope.tags.push(newTag);
      $scope.$broadcast('angucomplete-alt:clearInput');
      $scope.search();
      $scope.resetNewTag();
    }
  }
  
  $rootScope.$on("addTag", function(event,tag) {
    $scope.addTag(tag)
  })
  
  $scope.removeTag = function(index){
  var tag = $scope.tags[index];
    $scope.tags.splice(index, 1);
    $scope.search();
  }
  
  $scope.registerScope = function(scope){
    $scope.tagScopes.push(scope)
    scope.$emit("registerScope", $rootScope)
    scope.$emit("updateFilter",{ "tags" : $scope.tags, "mode" : $scope.only})
  }
  
  $scope.updateFilter = function(){
    angular.forEach($scope.tagScopes, function(scope) {
      scope.$emit("updateFilter",{ "tags" : $scope.tags, "mode" : $scope.only})
    })
  }

  $scope.clearAllTags = function(){
    $scope.tags = [];
    $scope.search();
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
    $scope.search();
  }
  
  $scope.onlyValue = function(elem){
    var button = $(elem.currentTarget);
    button.toggleClass('active');
    $scope.only.value = !$scope.only.value;
    $scope.only.key = false;
    $scope.search();
  }
  
  $scope.onlyAll = function(elem){
    var button = $(elem.currentTarget);
    button.addClass('active');
    $scope.only.key   = false;
    $scope.only.value = false;
    $scope.search();
  }
  
  //Avoid compilation error using ngClass directive with '&&' condition
  $scope.getOnlyAllValue = function(){
    return !$scope.only.key && !$scope.only.value;
  }

  // Add a grace period before effectively do the search
  var to;
  $scope.searchTree = function(treeId) {
    if (to !== undefined) { clearTimeout(to); }
    to = setTimeout(function() {
      $(treeId).jstree('searchtag', $scope.searchStr, $scope.tags, $scope.only);
    }, 200);
  }
  
  $scope.clearSearchFieldTree = function(treeId) {
    $scope.searchStr = "";
    $scope.searchTree(treeId);
  }
  
  $scope.refuseEnter = function(event){
    refuseEnter(event);
  }

  // Add a grace period before effectively do the search
  var rto;
  $scope.filterGlobal = function(str) {
    if (rto !== undefined) { clearTimeout(rto); }
    rto = setTimeout(function() {
      $(tableId).DataTable().search(str).draw();
    }, 200);
  }

  // Autocomplete methods
  // Method used when a value is selected in the autocomplete
  $scope.selectTag = function(res) {
    if (res === undefined) {
      $scope.newTag.key = ""
    } else {
    if (res.title === undefined) {
      $scope.newTag.key = res.originalObject
    } else {
      $scope.newTag.key = res.originalObject.value 
    }}
  }

  $scope.selectValue = function(res) {
    if (res === undefined) {
      $scope.newTag.value = ""
    } else {
    if (res.title === undefined) {
      $scope.newTag.value = res.originalObject
    } else {
      $scope.newTag.value = res.originalObject.value 
    } }
  }
  // Method used when input is changed to update data model
  $scope.updateValue = function(res) {
    $scope.newTag.value = res
  }
  $scope.updateTag = function(res) {
    $scope.newTag.key = res
  }
  
  $scope.initRule = function(isDirective){
    directiveFilter = false
    // Initialize filter on rule table
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
  
  adjustHeight('#activeTechniquesTree');
});

app.config(function($locationProvider) {
  $locationProvider.html5Mode({
    enabled: true,
    requireBase: false
  });
});


function applyFilter (filterId, treeId) {
  var scope = angular.element($("#"+filterId)).scope();
  scope.search()
}
