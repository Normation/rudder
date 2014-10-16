var techniqueVersion = angular.module('techniqueDetails', []);

techniqueVersion.controller('techniqueVersion', ['$scope', function($scope) {

  $scope.displayDeprecated = false;

  $scope.display = function(technique) {
    return (!$scope.displayDeprecated) && technique.isDeprecated
  }

  $scope.techniques = [];

  $scope.init = function( newTechniques) {
    $scope.techniques = newTechniques;
  };

}]);