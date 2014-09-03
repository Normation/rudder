        var cfagentScheduleModule = angular.module("cfagentSchedule", [])
        cfagentScheduleModule.controller("cfagentScheduleController", function($scope) {

          //that's mandatory....
          $scope.agentRun = { 
        		              "overrides"   : true
        		  			, "interval"    : 5
                            , "starthour"   : 0
                            , "startminute" : 0
                            , "splayHour"   : 0
                            , "splayMinute" : 0
                            }
console.log($scope.agentRun);          
          $scope.intervals = [
                                 {"m":5, "name": "5 minutes"}
                               , {"m":10, "name": "10 minutes"}
                               , {"m":15, "name": "15 minutes"}
                               , {"m":20, "name": "20 minutes"}
                               , {"m":30, "name": "30 minutes"}
                               , {"m":60, "name": "1 hour"}
                               , {"m":120, "name": "2 hours"}
                               , {"m":240, "name": "4 hours"}
                               , {"m":360, "name": "6 hours"}
                             ];
          
          $scope.overridesInterval = function() {
        	return !$scope.agentRun.overrides;
          }
          
          $scope.checkHours = function() {
            return $scope.hours().length <= 1
          }

          $scope.hours = function() {
            var h = []
            var i = 0
            while(i < $scope.agentRun.interval / 60) {
              h.push(i);
              i = i+1;
            }
            return h;
          }
            
          $scope.minutes = function() {
            var m = [];
            var i = 0;
            while(i < $scope.agentRun.interval && i < 60) {
              m.push(i);
              i = i+1;
            }
            return m;
          }
          
          $scope.onChange = function() {
            jQuery("#cfagentScheduleMessage").empty();
          }
          
          $scope.onChangeInterval = function() {
            if(jQuery.inArray($scope.agentRun.starthour, $scope.hours() ) < 0 ) {
              $scope.agentRun.starthour = $scope.hours()[0];
            }
            if(jQuery.inArray($scope.agentRun.startminute, $scope.minutes() ) < 0 ) {
              $scope.agentRun.startminute = $scope.minutes()[0];
            }
            if(jQuery.inArray($scope.agentRun.splayHour, $scope.hours() ) < 0 ) {
              $scope.agentRun.splayHour = $scope.hours()[0];
            }
            if(jQuery.inArray($scope.agentRun.splayMinute, $scope.minutes() ) < 0 ) {
                $scope.agentRun.splayMinute = $scope.minutes()[0];
            }
            $scope.onChange();
          }
          
           
        });
        angular.bootstrap(document.getElementById("cfagentSchedule"),['cfagentSchedule']);