var groupManagement = angular.module('groupManagement', []);

groupManagement.controller('GroupCtrl', ['$scope', function($scope) {
    // Init function so values can be set from outside the scope
    $scope.init = function ( target, mapTarget ) {
      $scope.mapTarget=mapTarget;
      $scope.target = target;
    };

    // Get name of a target (ie the group name) instead of using the target
    $scope.getTargetName = function (target) {
      return $scope.mapTarget[target];
    };

    // Text to display if there is no target selected
    $scope.emptyTarget = "Select groups from the tree on the left to add them here"

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
     $scope.includeExplanation = [ "<h3>Add Groups here to apply this Rule to the nodes they contain.</h3>"
                                  , "Groups will be merged together (union)."
                                  , "For example, if you add groups <b>'Datacenter 1'</b> and <b>'Production',</b>"
                                  , "this Rule will be applied to all nodes that are either"
                                  , "in that datacenter (production or not) or in production (in any datacenter)."
                                  ].join(" ");
     $scope.excludeExplanation = [ "<h3>Add Groups here to forbid applying this Rule to the nodes they contain.</h3>"
                                  , "Nodes in these Groups will never have this Rule applied,"
                                  , "even if they are also in a Group applied above."
                                  , "For example, if the above list contains groups '<b>Datacenter 1</b>' and '<b>Production</b>',"
                                  , "and this list contains the group '<b>Red Hat Linux</b>',"
                                  , "this Rule will be applied to all nodes that are running any OS except 'Red Hat Linux'"
                                  , "and are either in that datacenter (production or not) or in production (in any datacenter)."
                                  ].join(" ");
  } ] ) ;

// Add directive to create popup from angular, the directive should shared to future angular component
groupManagement.directive('tooltip', function () {
  return {
      restrict:'A'
    , link: function(scope, element, attrs) {
        var tooltipAttributes = {
            placement: "right"
          // We want no effects on popup
          , show: {
                effect: "none"
              , delay: 0
            }
          ,hide: {
                effect: "none"
              , delay: 0
            }
        }
        $(element).attr('title',scope.$eval(attrs.tooltip)).tooltip(tooltipAttributes);
      }
  }
} );


// Helper function to access from outside angular scope

function excludeTarget(target) {
  var scope = angular.element($("#GroupCtrl")).scope();
  scope.$apply(function() {
    scope.addExclude(target);
  });
};

function includeTarget(target) {
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