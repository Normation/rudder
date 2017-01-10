var app = angular.module('tags', []);

app.controller('tagsDirectiveCtrl', function ($scope, $http, $location, $timeout) {
  $scope.treeId;
  $scope.scopeFilter;
  $scope.tags = [];

  $scope.init = function(treeId, tags){
    $scope.treeId = treeId;
    var temp;
    for (key in tags){
      temp = {'key':key, 'value':tags[key]};
      $scope.tags.push(temp);
    }
    $scope.scopeFilter = angular.element($('[ng-controller="filterTagDirectiveCtrl"]')).scope();
    $scope.scopeFilter.updateFilter();
  }
  $scope.toggleTag = function(tag){
    $scope.scopeFilter.$apply(function(){
      var newTag = {"key":tag.key, "value":tag.value};
      $scope.scopeFilter.addTag($scope.treeId,newTag);
    });
  }
  $scope.keyTagMatch = function(tag){
    return tag.match.key && $scope.scopeFilter.tags.length>0;
  }
  $scope.valTagMatch = function(tag){
    return tag.match.value && $scope.scopeFilter.tags.length>0;
  }
});

app.controller('tagsRuleCtrl', function ($scope, $http, $location, $timeout) {
  $scope.scopeFilter;

  $scope.init = function(treeId, tags){
    $scope.tags = [];
    var jsonTags;
    if(typeof tags == "string"){
      jsonTags = JSON.parse(tags);
    }
    var temp;
    for (key in jsonTags){
      temp = {'key':key, 'value':jsonTags[key]};
      $scope.tags.push(temp);
    }
    $scope.scopeFilter = angular.element($('[ng-controller="filterTagRuleCtrl"]')).scope();
    $scope.scopeFilter.updateFilter();
  }

  $scope.toggleTag = function(tag){
    $scope.scopeFilter.$apply(function(){
      var newTag = {"key":tag.key, "value":tag.value};
      $scope.scopeFilter.addTag(newTag);
    });
  }
  $scope.keyTagMatch = function(tag){
    return tag.match.key && $scope.scopeFilter.tags.length>0;
  }
  $scope.valTagMatch = function(tag){
    return tag.match.value && $scope.scopeFilter.tags.length>0;
  }
});
app.config(function($locationProvider) {
  $locationProvider.html5Mode({
    enabled: true,
    requireBase: false
  });
});