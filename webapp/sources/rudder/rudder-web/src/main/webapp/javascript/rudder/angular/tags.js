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


var app = angular.module('tags', ["angucomplete-alt"]);

app.controller('tagsController', function ($scope, $http, $location, $timeout, $rootScope) {
  $scope.filterScope;
  $scope.newTag = { "key" : "" , "value" : "" };
  $scope.tags = [];
  $scope.isEditForm = false
  $scope.showDelete = [];
  $scope.filterTags = [];

  $scope.contextPath = contextPath
  $scope.kind = "directive"
  $scope.init = function(tags, filterId, isEditForm, isRuleTag){
    $scope.tags = tags;
    $scope.isEditForm = isEditForm
    
    if(isRuleTag) {
      $scope.kind = "rule"
    }

    angular.element($('#'+filterId)).scope().registerScope($rootScope)
  }
  
  $scope.toggleTag = function(tag){
    if( $scope.filterScope !== undefined) {
      $scope.filterScope.$emit("addTag", angular.copy(tag))
    }    
  }
  
  $scope.tagMatch = function(tag){
    if($scope.filterTags.length<=0)return false;
    for(var i=0 ; i<$scope.filterTags.length ; i++){
      if(($scope.filterTags[i].key == tag.key || $scope.filterTags[i].key == "") && ($scope.filterTags[i].value == "" || $scope.filterTags[i].value == tag.value)){
        return true;
      }
    }
    return false;
  }

  $scope.$watch('tags', updateResult, true)
  
  $rootScope.$on("registerScope", function(event,filterScope) {
    $scope.filterScope = filterScope;
  })
    
  $rootScope.$on("updateFilter", function(event,filters) {
    $scope.filterTags = filters.tags ? filters.tags : [];
    $timeout(function() {},0);
  })
  
  function updateResult () {
    $scope.result = JSON.stringify($scope.tags);
  }
  
  $scope.addTag = function(){
    $scope.tags.push($scope.newTag);
    $scope.newTag = { "key" : "" , "value" : "" };
    $scope.$broadcast('angucomplete-alt:clearInput', 'newTagKey');
    $scope.$broadcast('angucomplete-alt:clearInput', 'newTagValue');
  }
  
  $scope.removeTag = function(index){
    $scope.tags.splice(index, 1);
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
  
  
});

app.config(function($locationProvider) {
  $locationProvider.html5Mode({
    enabled: true,
    requireBase: false
  });
});