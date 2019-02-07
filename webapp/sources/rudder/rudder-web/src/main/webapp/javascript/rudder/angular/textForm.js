/*
*************************************************************************************
* Copyright 2016 Normation SAS
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


var textModule = angular.module("text", []);
textModule.controller("textController", function($scope) {
  // Declare Variables

  
  $scope.current = {prefix : "", value : ""};
  $scope.feature = false;
  // New password if we want to change it
  $scope.result = undefined;

  $scope.$watch('current.value', updateResult)
  $scope.$watch('current.prefix', updateResult)
  function updateResult () {
    if ($scope.feature) {
      $scope.result = $scope.current.prefix + $scope.current.value;
    } else {
      $scope.result = $scope.current.value;
    }
  }

  // init function, That will be called from 'outside' angular scope to set with values sent from the webapp
  $scope.init = function(current, currentPrefix, featureEnabled) {
      $scope.current.value=current;
      $scope.current.prefix = currentPrefix;
      $scope.feature = featureEnabled;      
  }

  $scope.prefixName = function() {
    var prefix = "JS"
    if($scope.current.prefix === "") {
      prefix = "Text"  
    }
    return prefix
  }
});
