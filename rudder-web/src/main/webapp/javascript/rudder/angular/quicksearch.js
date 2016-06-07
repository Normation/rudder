
var quicksearch = angular.module('quicksearch', ["angucomplete-ie8"]);

quicksearch.controller('QuicksearchCtrl', function QuicksearchCtrl($scope) {



  $scope.selectedObject = function(selected) {
    if(selected && selected.originalObject.url) {
      window.location = selected.originalObject.url;
    } else {
      return "";
    }
  }


} );


// Helper function to access from outside angular scope
//
//function initQuicksearchUrl(url) {
//  var scope = angular.element($("#quicksearch")).scope();
//  scope.$apply(function() {
//    scope.setContextPath(url);
//  });
//};
//
