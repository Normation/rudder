var cfagentScheduleModule = angular.module("cfagentSchedule", [])
cfagentScheduleModule.controller("cfagentScheduleController", function($scope) {

  $scope.agentRun = {
      'overrides'   : undefined
    , 'interval'    : 5
    , 'startHour'   : 0
    , 'startMinute' : 0
    , 'splayHour'   : 0
    , 'splayMinute' : 0
  };

  $scope.globalRun;
  $scope.callback;
  $scope.savedValue;
  $scope.contextPath;

 $scope.init = function(agentRun,globalRun, callback, contextPath) {
    $scope.agentRun = agentRun;
    $scope.savedValue = angular.copy($scope.agentRun);
    $scope.globalRun = globalRun;
    $scope.callback = callback;
    $scope.contextPath = contextPath;
  }

  $scope.intervals = [
      {"m":5, "name": "5 minutes"}
    , {"m":10, "name": "10 minutes"}
    , {"m":15, "name": "15 minutes"}
    , {"m":20, "name": "20 minutes"}
    , {"m":30, "name": "30 minutes"}
    , {"m":60, "name": "1 hour", "frequency": "hour"}
    , {"m":120, "name": "2 hours"}
    , {"m":240, "name": "4 hours"}
    , {"m":360, "name": "6 hours"}
  ];

  $scope.getIntervalValue = function(runInterval) {
    var interval = $.grep($scope.intervals, function(v,i) { return v.m === runInterval; })[0];
    if ('frequency' in interval) {
      return interval.frequency;
    } else {
      return interval.name;
    }
  }

  $scope.overridesInterval = function() {
  if ($scope.agentRun.overrides === null)
    return false;
  else
    return !$scope.agentRun.overrides;
  }

  $scope.checkHours = function() {
    return $scope.hours().length <= 1
  }

  $scope.hours = function() {
    var h = []
    var i = 0
    if ($scope.agentRun === undefined)
      return h
    while(i < $scope.agentRun.interval / 60) {
      h.push(i);
      i = i+1;
    }
    return h;
  }

  $scope.minutes = function() {
    var m = [];
    var i = 0;
    if ($scope.agentRun === undefined)
      return m;
    while(i < $scope.agentRun.interval && i < 60) {
      m.push(i);
      i = i+1;
    }
    return m;
  }

  $scope.onChange = function() {
    $("#cfagentScheduleMessage").empty();
  }

  $scope.onChangeInterval = function() {
    if($.inArray($scope.agentRun.startHour, $scope.hours() ) < 0 ) {
      $scope.agentRun.startHour = $scope.hours()[0];
    }
    if($.inArray($scope.agentRun.startMinute, $scope.minutes() ) < 0 ) {
      $scope.agentRun.startMinute = $scope.minutes()[0];
    }
    if($.inArray($scope.agentRun.splayHour, $scope.hours() ) < 0 ) {
      $scope.agentRun.splayHour = $scope.hours()[0];
    }
    if($.inArray($scope.agentRun.splayMinute, $scope.minutes() ) < 0 ) {
      $scope.agentRun.splayMinute = $scope.minutes()[0];
    }
    $scope.onChange();
  }

  $scope.save = function() {
    var run = JSON.stringify($scope.agentRun);
    $scope.callback(run);
    $scope.savedValue = angular.copy($scope.agentRun);
  }

  $scope.isUnchanged = function(agentRun) {
    return angular.equals($scope.agentRun, $scope.savedValue);
  };

  $scope.displayGlobal = function() {
    return $scope.agentRun.overrides && $scope.globalRun !== undefined
  }

});
