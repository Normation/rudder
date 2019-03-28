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

var groupManagement = angular.module('groupManagement', []);

groupManagement.controller('GroupCtrl', ['$scope', function($scope) {
    // Init function so values can be set from outside the scope
    $scope.init = function ( target, mapTarget ) {
      $scope.mapTarget=mapTarget;
      $scope.target = target;
    };

    // Get name of a target (ie the group name) instead of using the target
    $scope.getTarget = function (target) {
      return $scope.mapTarget[target];
    };

    // Update the html field that stocks the target
    $scope.updateTarget = function() {
      $('#selectedTargets').val(JSON.stringify($scope.target));
    };

    // Remove from excluded targets the target passed as parameter
    $scope.removeExclude = function ( excluded ) {
      var index = $scope.target.exclude.or.indexOf(excluded);
      // remove only if the target is here
      if ( index > -1 )  {
        $scope.target.exclude.or.splice(index,1);
        $scope.updateTarget();
        var jsId = excluded.replace(':','\\:');
        $("#jstree-"+jsId).removeClass("excluded");
      };
    };

    // Remove from included targets the target passed as parameter
    $scope.removeInclude = function ( included ) {
      var index = $scope.target.include.or.indexOf(included);
      // remove only if the target is here
      if ( index > -1 )  {
        $scope.target.include.or.splice(index,1);
        $scope.updateTarget();
        var jsId = included.replace(':','\\:');
        $("#jstree-"+jsId).removeClass("included");
      };
    };

    // Add the new target to include, remove it from included targets if it was
    $scope.addInclude = function ( included ) {
      var index = $scope.target.include.or.indexOf(included);
      // Only add if the target is missing
      if ( index == -1 )  {
        $scope.target.include.or.push(included);
        var jsId = included.replace(':','\\:');
        $("#jstree-"+jsId).addClass("included");
        $scope.removeExclude(included);
        $scope.updateTarget();
      };
    };

    // Add the new target to exclude, remove it from included targets if it was
    $scope.addExclude = function ( excluded ) {
      var index = $scope.target.exclude.or.indexOf(excluded);
      // Only add if the target is missing
      if ( index == -1 )  {
        $scope.target.exclude.or.push(excluded);
        var jsId = excluded.replace(':','\\:');
        $("#jstree-"+jsId).addClass("excluded");
        $scope.removeInclude(excluded);
        $scope.updateTarget();
      };
    };

    // Toggle a target =>
    // If it was not present => include that target
    // If either from included or excluded => Exclude it
    $scope.toggleTarget = function ( target ) {
      var indexInclude = $scope.target.include.or.indexOf(target);
      var indexExclude = $scope.target.exclude.or.indexOf(target);
        if ( indexInclude == -1 )  {
          if ( indexExclude == -1 )  {
            // Not in targets => include
            $scope.addInclude(target);
          } else {
            // In excluded targets => remove from excluded
            $scope.removeExclude(target);
          }
        } else {
          // In included targets => remove from included
          $scope.removeInclude(target);
        }
      };

    // Explanations to use in popups
    $scope.includeExplanation = [ "<div>Add Groups here to apply this Rule to the nodes they contain.</div><br/>"
                                  , "<div>Groups will be merged together (union)."
                                  , "For example, if you add groups <b>'Datacenter 1'</b> and <b>'Production',</b>"
                                  , "this Rule will be applied to all nodes that are either"
                                  , "in that datacenter (production or not) or in production (in any datacenter).</div>"
                                ].join(" ");
    $scope.excludeExplanation = [ "<div>Add Groups here to forbid applying this Rule to the nodes they contain.</div><br/>"
                                  , "<div>Nodes in these Groups will never have this Rule applied,"
                                  , "even if they are also in a Group applied above."
                                  , "For example, if the above list contains groups '<b>Datacenter 1</b>' and '<b>Production</b>',"
                                  , "and this list contains the group '<b>Red Hat Linux</b>',"
                                  , "this Rule will be applied to all nodes that are running any OS except 'Red Hat Linux'"
                                  , "and are either in that datacenter (production or not) or in production (in any datacenter).</div>"
                                  ].join(" ");

    $scope.modal = function(id, show) {
      var element = $('#' + id);
      element.bsModal(show ? 'show' : 'hide');
    };

    $scope.getTooltipContent = function(group){
      var title = "<h4>"+group.name+"</h4>";
      var desc  = group.desc != "" ? ("<div>"+group.desc+"</div>") : "<div><i class='empty'>This group has no description.</div></i>";
      return title + desc;
    }

  } ] ) ;

// Add directive to create popup from angular, the directive should shared to future angular component
groupManagement.directive('tooltip', function () {
  return {
      restrict:'A'
    , link: function(scope, element, attrs) {
        var tooltipAttributes = {placement: "right"}
        $(element).attr('title',scope.$eval(attrs.tooltip)).tooltip(tooltipAttributes);
      }
  }
} );


// Helper function to access from outside angular scope

function excludeTarget(event, target) {
  event.stopPropagation();
  var scope = angular.element($("#GroupCtrl")).scope();
  scope.$apply(function() {
    scope.addExclude(target);
  });
};

function includeTarget(event, target) {
  event.stopPropagation();
  var scope = angular.element($("#GroupCtrl")).scope();
  scope.$apply(function(){
    scope.addInclude(target);
  });
};

function onClickTarget(target) {
  var scope = angular.element($("#GroupCtrl")).scope();
  scope.$apply(function(){
    scope.toggleTarget(target);
  });
};
