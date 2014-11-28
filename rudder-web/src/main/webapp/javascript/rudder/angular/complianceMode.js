/*
*************************************************************************************
* Copyright 2014 Normation SAS
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

function updateAgentRun (run) {
    var scope = angular.element($("#complianceModeController")).scope();
    scope.$apply(function(){
          scope.updateAgentRun(run);
        } );
  }

var complianceModeModule = angular.module("complianceMode", ['ngAnimate'])
complianceModeModule.controller("complianceModeController", function($scope) {

  $scope.complianceMode = { name : "full-compliance", heartbeatPeriod : 1, overrides : true};
  $scope.globalValue
  $scope.agentRun = 5;
  if (currentAgentRun !== undefined) {
    $scope.agentRun = currentAgentRun;
  }
  $scope.isNodePage = false;
  $scope.callback;
  $scope.savedValue;
  $scope.contextPath;

 $scope.init = function(complianceMode, heartbeatFreq,overrides, globalValue, callback, contextPath) {
   $scope.complianceMode.name=complianceMode;
   $scope.complianceMode.heartbeatPeriod=heartbeatFreq;
   $scope.complianceMode.overrides=overrides;
   $scope.globalValue = globalValue;
   $scope.isNodePage = globalValue !== undefined
   $scope.callback=callback;
   $scope.contextPath=contextPath;
   $scope.savedValue = angular.copy($scope.complianceMode)
   
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
