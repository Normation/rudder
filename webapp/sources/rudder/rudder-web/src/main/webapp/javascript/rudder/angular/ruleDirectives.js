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

var ruleDirectives = angular.module('ruleDirectives', []);

ruleDirectives.controller('DirectiveCtrl', ['$scope', '$timeout', function($scope, $timeout) {
    $scope.directives = {};

    //really, angular...
    //a call to sortDirectives is needed.
    //the array here is ONLY used as a cache so
    //that angular can know when things don't change.
    //else, we get infinite looping....
    $scope.sortedDirectives = [];

    //needed to correctly display directive by alphaNum
    $scope.sortDirectives = function() {
      var arr = [];
      for (var o in $scope.directives) {
        arr.push($scope.directives[o]);
      }
      var sorted = arr.sort(function(a, b) {
        return a.name.localeCompare(b.name);
      });

      //test for equality... yeah..
      //we need that because angularJS must know what is "stable",
      //and by default, it uses the object id.
      //So we have to cache the data somewhere and return that cached data
      //if nothing changed.
      if(sorted.length == $scope.sortedDirectives.length) {
        for(var i=0; i<sorted.length; i++) {
          if(sorted[i].id != $scope.sortedDirectives[i].id ||
             sorted[i].name != $scope.sortedDirectives[i].name
          ) {
            $scope.sortedDirectives = sorted;
            return $scope.sortedDirectives;
          }
        }
      } else {
        $scope.sortedDirectives = sorted;
      }
      return $scope.sortedDirectives;
    }

    // Init function so values can be set from outside the scope
    // directiveIds is expected to be a json of {directiveId : directiveName }
    $scope.init = function ( selectedDirectives ) {
      $scope.directives = selectedDirectives;

      //init tooltips
      $timeout(function(){
        $('.icon-info').bsTooltip();
        $('#selectGroups, #selectDirectives').on('hidden.bs.modal', function (e) {
          $('.icon-info').bsTooltip();
        });
      }, 200);
    };

    // Get name of a directive instead of using the directive ID
    $scope.getDirectiveName = function (directiveId) {
      return $scope.directives[directiveId];
    };

    $scope.directivesIsEmpty = function() {
      return $scope.sortDirectives($scope.directives).length === 0;
    }

    // Update the html field that stocks the directive
    $scope.updateDirective = function() {
      $('#selectedDirectives').val(JSON.stringify(Object.keys($scope.directives)));
    };

    // Remove from included directives the directive passed as parameter
    $scope.removeInclude = function ( directiveId ) {
      delete $scope.directives[directiveId];
      $("#jsTree-"+directiveId).removeClass("included");
      $scope.updateDirective();
    };

    // Add the new directive to include, remove it from included directives if it was
    $scope.addInclude = function ( directive ) {
      $scope.directives[directive.id] = directive;
      $("#jsTree-"+directive.id).addClass("included");
      $scope.updateDirective();
    };

    // Toggle a directive =>
    // If it was not present => include that directive
    // If either from included or excluded => Exclude it
    $scope.toggleDirective = function ( directive ) {
      if ( $scope.directives[directive.id] !== undefined )  {
        // In included directives => remove from included
        $scope.removeInclude(directive.id);
      } else {
        // Not in directives => include
        $scope.addInclude(directive);
      }
     };

    $scope.modal = function(id, show) {
      var element = $('#' + id);
      element.bsModal(show ? 'show' : 'hide');
    };

    $scope.getTooltipContent = function(directive){
        var title = "<h4>"+directive.name+"</h4>";
        var tech  = "<div><label>Technique: </label><span>"+directive.techniqueName+"</span><span class='small'>"+directive.techniqueVersion+"</span></div>"
        var desc  = directive.desc != "" ? "<div>"+directive.desc+"</div>" : "<div><i class='empty'>This directive has no description.</i><div>";
        return title + tech + desc;
    }

    $scope.getListLength = function(list){
      return Object.keys(list).length;
    };
  } ] ) ;

// Add directive to create popup from angular, the directive should shared to future angular component
ruleDirectives.directive('tooltip', function () {
  return {
      restrict:'A'
    , link: function(scope, element, attrs) {
        var tooltipAttributes = {placement: "right"}
        $(element).attr('title',scope.$eval(attrs.tooltip)).tooltip(tooltipAttributes);
      }
  }
} );


// Helper function to access from outside angular scope
function onClickDirective(dId, dName, dLink, dDescription, dTechName, dTechVersion, dMode) {
  var selectedDir =
  { "id"               : dId
  , "link"             : dLink
  , "name"             : dName
  , "desc"             : dDescription
  , "techniqueName"    : dTechName
  , "techniqueVersion" : dTechVersion
  , "mode"             : dMode
  }
  var scope = angular.element($("#DirectiveCtrl")).scope();
  scope.$apply(function(){
    scope.toggleDirective(selectedDir);
  });
};