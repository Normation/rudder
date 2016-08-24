/*
*************************************************************************************
* Copyright 2014 Normation SAS
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

var currentAgentRun

// Update agent run from compliance Mode controller
function updateAgentRun (run) {
  var scope = angular.element($("#complianceModeController")).scope();
  // compliance mode controller may not be defined yet when that function run for the first time
  if(scope !== undefined) {
    scope.$apply(function() {
      scope.updateAgentRun(run);
    } );
  } else {
    // Scope not available store it in global variable so whe can still use it after scope initialization
    currentAgentRun = run;
  }
}

var complianceModeModule = angular.module("complianceMode", ['ngAnimate'])
complianceModeModule.controller("complianceModeController", function($scope) {

  $scope.complianceMode = { name : "full-compliance", heartbeatPeriod : 1};
  $scope.globalValue
  $scope.agentRun = 5;
  if (currentAgentRun !== undefined) {
    $scope.agentRun = currentAgentRun;
  }
  $scope.isNodePage = false;
  $scope.callback;
  $scope.savedValue;
  $scope.contextPath;

 $scope.init = function(complianceMode, globalValue, isNodePage, callback, contextPath, allModes) {
   $scope.complianceMode=complianceMode;
   $scope.globalValue = globalValue;
   $scope.isNodePage =  isNodePage;
   $scope.callback=callback;
   $scope.contextPath=contextPath;
   $scope.savedValue = angular.copy($scope.complianceMode)
   
  }
 
 $scope.disableHeartbeat = function(){
   if ($scope.isNodePage) {
     return false;
   } else {
     return $scope.complianceMode.overrides;
   }
 }

  $scope.onChange = function() {
    $("#complianceModeMessage").empty();
  }
  
  $scope.checkMaximumValue = function() {
   return 60 * 24 / $scope.agentRun
  }
  
  $scope.updateAgentRun = function(run) {
    $scope.agentRun = run;
  }

  $scope.save = function() {
    var run = JSON.stringify($scope.complianceMode);
    $scope.callback(run);
    $scope.savedValue = angular.copy($scope.complianceMode);
  }

  $scope.isUnchanged = function(agentRun) {
    return angular.equals($scope.complianceMode, $scope.savedValue);
  };

  $scope.displayGlobal = function() {
    return $scope.complianceMode.overrides && $scope.globalRun !== undefined
  }

});
