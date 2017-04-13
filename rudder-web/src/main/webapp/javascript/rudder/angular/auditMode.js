
var app = angular.module('auditmode', []);

app.factory('configGlobalFactory', function ($http){
  //Case : global configuration
  this.policyMode = {
    url      : contextPath+"/secure/api/latest/settings"
  , getValue : function(){
                 return $http.get(this.url+"/global_policy_mode").then(function successCallback(response) {
                   return response.data.data.settings.global_policy_mode;
                 }, function errorCallback(response) {
                   console.error('error - policy mode');
                 });
               }
 , save      : function(mode){
               var overridableReason = "overridable"
               if (!mode.overrideMode) {
                 overridableReason = "not overridable"
               }
	             var data = { 'global_policy_mode' : mode.policyMode
	                        , 'global_policy_mode_overridable' : mode.overrideMode
	                        , 'reason' : "Change global policy mode to '"+mode.policyMode+"' ("+overridableReason+")"
	                        };
	             return $http.post(this.url, data).then(function successCallback(response) {
	               return response.status==200;
	             }, function errorCallback(response) {
	               return response.status==200;
	             });
	           }
  };
  this.overrideMode = {
    url      : contextPath+"/secure/api/latest/settings/global_policy_mode_overridable"
  , getValue : function(){
                 return $http.get(this.url).then(function successCallback(response) {
                   return response.data.data.settings.global_policy_mode_overridable;
                 }, function errorCallback(response) {
                   console.error('error - policy mode');
                 });
               }
  };
  return this;
});

app.factory('configNodeFactory', function ($http){
  //Case : node configuration
  return function(nodeId) {
    this.policyMode = {
      url      : contextPath+"/secure/api/latest/nodes/" + nodeId
    , getValue : function(){
                   return $http.get(this.url).then(function successCallback(response) {
                     return response.data.data.nodes[0].policyMode;
                   }, function errorCallback(response) {
                     console.error('error - policy mode');
                   });
                 }
 ,   save      : function(mode){
                   var data = {
                       'policyMode' : mode.policyMode
                     , 'reason' : "Change policy mode of node '"+nodeId+" to '"+mode.policyMode+"'"
                   };
                   return $http.post(this.url, data).then(function successCallback(response) {
                     return response.status==200;
                   }, function errorCallback(response) {
                     return response.status==200;
                   });
	               }
    };
    return this;
    }
});
app.controller('auditmodeCtrl', function ($scope, $http, $location, $timeout, configGlobalFactory, configNodeFactory) {
  function getNodeId(){
    var nodeId;
    try {
      var hash = JSON.parse($location.hash());
      nodeId = hash.nodeId;
    } catch(err){}
    return nodeId;
  }

  var nodeId = getNodeId();
  // variable used for saving animations
  $scope.saving = 0;
  // global configuration
  $scope.isGlobalForm;
  $scope.globalConfiguration = {};
  // current configuration
  $scope.currentConf = {};
  // modified configuration
  $scope.conf = {};
  // appropriated factory
  $scope.factory;

  // -- Get global configuration
  configGlobalFactory.policyMode.getValue().then(function(currentPolicyMode){
    $scope.globalConfiguration.policyMode = currentPolicyMode;
    if(!nodeId){
      $scope.conf.policyMode = currentPolicyMode;
    }
  });
  configGlobalFactory.overrideMode.getValue().then(function(currentOverrideMode){
    $scope.globalConfiguration.overrideMode = currentOverrideMode;
    if(!nodeId){
      $scope.conf.overrideMode = $scope.currentConf.overrideMode;
    }
  });

  // -- Get appropriated factory and initialize scope
  if(nodeId !== undefined){
    // case : node
    $scope.isGlobalForm = false;
    $scope.factory = configNodeFactory(nodeId)
    $scope.factory.policyMode.getValue().then(function(currentPolicyMode){
      $scope.currentConf.policyMode = currentPolicyMode;
      $scope.conf.policyMode = currentPolicyMode;
    });
  }else{
    // case : global
    $scope.isGlobalForm = true;
    $scope.factory = configGlobalFactory;
    $scope.currentConf = $scope.globalConfiguration;
  }

  // -- Detect current modifications
  $scope.$watch('conf', function(){
    $scope.nochange = angular.equals($scope.currentConf,$scope.conf);
  }, true);

  // -- Save modifications
  $scope.saveChanges = function($event) {
    if(!$scope.nochange){
      //Start loading animation
      $scope.saving = 1;
      $scope.factory.policyMode.save($scope.conf).then(function(success){
        if(success){
          //Reinitialize scope
          $scope.errorFeedback = false;
          $scope.nochange = true;
          $scope.currentConf.policyMode = $scope.conf.policyMode;
          $scope.currentConf.overrideMode = $scope.conf.overrideMode;
        }else{
          $scope.errorFeedback = true;
        }
        $scope.saving = 2;
      });
    }
  };
  $scope.redirect = function(url) {
    window.location = contextPath + url;
  };
});

app.config(function($locationProvider) {
  $locationProvider.html5Mode({
    enabled: true,
    requireBase: false
  });
})
