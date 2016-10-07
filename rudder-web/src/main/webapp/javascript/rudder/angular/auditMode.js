
var app = angular.module('auditmode', []);

app.factory('configGlobalFactory', function ($http){
  //Case : global configuration
  this.policyMode = {
    url      : "/rudder/secure/api/latest/settings/global_policy_mode"
  , getValue : function(){
                 return $http.get(this.url).then(function successCallback(response) {
                   return response.data.data.settings.global_policy_mode;
                 }, function errorCallback(response) {
                   console.error('error - policy mode');
                 });
               }
 , save      : function(mode){
	             var data = {'value' : mode};
	             return $http.post(this.url, data).then(function successCallback(response) {
	               return response.status==200;
	             }, function errorCallback(response) {
	               return response.status==200;
	             });
	           }
  };
  this.overrideMode = {
    url      : "/rudder/secure/api/latest/settings/global_policy_mode_overridable"
  , getValue : function(){
                 return $http.get(this.url).then(function successCallback(response) {
                   return response.data.data.settings.global_policy_mode_overridable;
                 }, function errorCallback(response) {
                   console.error('error - policy mode');
                 });
               }
  , save     : function(mode){
                 var data = {'value' : mode};
                 return $http.post(this.url, data).then(function successCallback(response) {
                   return response.status==200;
                 }, function errorCallback(response) {
                   return response.status==200;
                 });
               }
  };
  return this;
});

app.factory('configNodeFactory', function ($http){
  //Case : node configuration
  this.policyMode = {
    url      : "/rudder/secure/api/latest/nodes/" + getNodeId()
  , getValue : function(){
                 return $http.get(this.url).then(function successCallback(response) {
                   return response.data.data.nodes[0].policyMode;
                 }, function errorCallback(response) {
                   console.error('error - policy mode');
                 });
               }
 , save      : function(mode){
	             var data = {'policyMode' : mode};
	             return $http.post(this.url, data).then(function successCallback(response) {
	               return response.status==200;
	             }, function errorCallback(response) {
	               return response.status==200;
	             });
	           }
  };
  return this;
});
app.controller('auditmodeCtrl', function ($scope, $http, $location, $timeout, configGlobalFactory, configNodeFactory) {
  var nodeId = getNodeId();
  // variable used for saving animations
  $scope.saving = {
    'policyMode'   : 0
  , 'overrideMode' : 0
  };
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
  if(nodeId){
    // case : node
    $scope.isGlobalForm = false;
    $scope.factory = configNodeFactory;
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
      $scope.saving = {
        'policyMode'   : 1
      , 'overrideMode' : 1
      };
      $scope.factory.policyMode.save($scope.conf.policyMode).then(function(success){
        if(success){
          //Reinitialize scope
          $scope.errorFeedback = false;
          $scope.nochange = true;
          $scope.currentConf.policyMode = $scope.conf.policyMode;
          $scope.saving.policyMode = 2;
        }else{
          $scope.errorFeedback = true;
          $scope.saving.policyMode = 2;
        }
      });
      if($scope.isGlobalForm){
        $scope.factory.overrideMode.save($scope.conf.overrideMode).then(function(success){
          if(success){
            //Reinitialize scope
            $scope.errorFeedback = false;
            $scope.nochange = true;
            $scope.currentConf.overrideMode = $scope.conf.overrideMode;
            $scope.saving.overrideMode = 2;
          }else{
            $scope.errorFeedback = true;
            $scope.saving.overrideMode = 2;
          }
        });
      }
    }
  };
});
function getNodeId(){
  var nodeId;
  try {
    var hash = JSON.parse(decodeURI(window.location.hash).slice(2));
    nodeId = hash.nodeId;
  } catch(err){}
  return nodeId;
}

