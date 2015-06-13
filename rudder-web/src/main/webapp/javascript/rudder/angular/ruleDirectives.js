var ruleDirectives = angular.module('ruleDirectives', []);

ruleDirectives.controller('DirectiveCtrl', ['$scope', function($scope) {
    $scope.directives = [];
    // Init function so values can be set from outside the scope
    // directiveIds is expected to be a json of {directiveId : directiveName }
    $scope.init = function ( selectedDirectives ) {
      $scope.directives = selectedDirectives;
    };

    // Get name of a directive instead of using the directive ID
    $scope.getDirectiveName = function (directiveId) {
      return $scope.directives[directiveId];
    };

    // Text to display if there is no directive selected
    $scope.emptyDirective = "Select directives from the tree on the left to add them here"

    $scope.directivesIsEmpty = function() {
      $scope.directives.length === 0;
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
    $scope.addInclude = function ( directiveId, directiveName ) {
      $scope.directives[directiveId] = directiveName;
      $("#jsTree-"+directiveId).addClass("included");
      $scope.updateDirective();
    };

    // Toggle a directive =>
    // If it was not present => include that directive
    // If either from included or excluded => Exclude it
    $scope.toggleDirective = function ( directiveId, directiveName ) {
      if ( $scope.directives[directiveId] !== undefined )  {
        // In included directives => remove from included
        $scope.removeInclude(directiveId);
      } else {
        // Not in directives => include
        $scope.addInclude(directiveId, directiveName);
      }
     };

     // Explanations to use in popups
     $scope.directiveAddExplanation = "<h3>Add Directives here to so that they will be applied.</h3>"
  } ] ) ;

// Add directive to create popup from angular, the directive should shared to future angular component
ruleDirectives.directive('tooltip', function () {
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

function includeDirective(directiveId, directiveName) {
  var scope = angular.element($("#DirectiveCtrl")).scope();
  scope.$apply(function(){
    scope.addInclude(directiveId, directiveName);
  });
};

function onClickDirective(directiveId, directiveName) {
  var scope = angular.element($("#DirectiveCtrl")).scope();
  scope.$apply(function(){
    scope.toggleDirective(directiveId, directiveName);
  });
};