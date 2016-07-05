
var quicksearch = angular.module('quicksearch', ["angucomplete-ie8"]);

quicksearch.controller('QuicksearchCtrl', function QuicksearchCtrl($scope) {


  $scope.docinfo = []


  $scope.selectedObject = function(selected) {
    if(selected && selected.originalObject.url) {
      window.location = selected.originalObject.url;
    } else {
      return "";
    }
  }


} );


// Helper function to access from outside angular scope

function initQuicksearchDocinfo(json) {
  var scope = angular.element($("#quicksearch")).scope();
  scope.$apply(function() {
    scope.docinfo = JSON.parse(json);
  });
};
