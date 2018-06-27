/*
*************************************************************************************
* Copyright 2017 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
* 
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
* 
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
* 
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
* 
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

var app = angular.module('nodeProperties', ['DataTables', 'monospaced.elastic']);

app.controller('nodePropertiesCtrl', function ($scope, $http, $compile) {
  //Initialize scope
  $scope.properties;
  $scope.nodeId;
  $scope.tableId          = "#nodePropertiesTab";
  $scope.urlAPI           = contextPath + '/secure/api/latest/nodes/';
  $scope.newProperty      = {'name':"", 'value':""};
  $scope.deletedProperty  = {'name':"", 'index':""};
  $scope.alreadyUsed      = false;
  $scope.errorSaving      = false;
  $scope.errorDeleting    = false;
  $scope.checkJson        = false;
  $scope.isValid          = true ;

  $scope.resetNewProperty = function(){
    $scope.newProperty = {'name':"", 'value':""};
  }

  var columnDefs = [
    {
    "aTargets":[0],
    "mDataProp": "name",
    "sWidth": "20%",
    "sTitle" : "Name",
    "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
      $(nTd).empty();
      var divPropName = "<div>"+ sData +"</div>";
      $(nTd).append(divPropName);
      if(oData.provider){
        var span = "<span class='rudder-label label-provider label-sm' data-toggle='tooltip' data-placement='top' data-html='true' title=''>" + oData.provider + "</span>";
        var badge = $(span).get(0);
        var tooltipText = "This node property is managed by its provider ‘<b>"+ oData.provider +"</b>’, and can not be modified manually. Check Rudder’s settings to adjust this provider’s configuration.";
        badge.setAttribute("title", tooltipText);
        $(nTd).prepend(badge);
      }
    }
    }
  , {
    "aTargets":[1],
    "sWidth": "75%",
    "mDataProp": "value",
    "sTitle" :"Value",
    "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
      var pre = $("<pre class='json-beautify' onclick='$(this).toggleClass(\"toggle\")'></pre>");
      var content = sData;
      if (sData !== null && typeof sData === 'object') {
        content = JSON.stringify(sData, null, 2)
      }
      $(nTd).empty();
      pre.html(content)
      $(nTd).prepend(pre);
     }
   }
  , {
    "aTargets":[2],
    "sWidth": "5%",
    "mDataProp": "name",
    "sTitle" :"",
    "orderable": false,
    "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
      $(nTd).empty();
      if (oData.rights !== "read-only") {
        var deleteButton = $('<span class="fa fa-times text-danger" ng-click="popupDeletion(\''+sData+'\', \''+iRow+'\')"></span>');
        $(nTd).addClass('text-center delete-action');
        $(nTd).prepend(deleteButton);
        $compile(deleteButton)($scope);
      }
    }
  }];
  var overrideOptions = {
      "bFilter" : true
    , "bPaginate" : true
    , "bLengthChange": true
    , "sPaginationType": "full_numbers"
    , "oLanguage": {
        "sSearch": ""
      }
    , "aaSorting": [[ 0, "asc" ]]
    , "sDom": '<"dataTables_wrapper_top"fl>rt<"dataTables_wrapper_bottom"ip>'
  };

  var currentNodeId;
  $scope.init = function(properties, nodeId, right){
    if(!right){
      columnDefs[2].sClass = "hidden";
    }
    $scope.columnDefs = columnDefs;
    $scope.overrideOptions = overrideOptions;
    //Get current node properties
    $scope.properties = properties;
    currentNodeId = nodeId;
    $scope.urlAPI = contextPath + '/secure/api/latest/nodes/'+ nodeId;
    $($scope.tableId).dataTable().fnAddData($scope.properties);
  }

  $scope.addProperty = function(){
    function checkNameUnicity(property, index, array) {
      return property.name == $scope.newProperty.name;
    }
    var propertyToSave = angular.copy($scope.newProperty)
    var newValue = propertyToSave.value
    $scope.isValid = true;
    if($scope.checkJson){
      try {
        newValue = JSON.parse(propertyToSave.value)
      } catch(e) {
        $scope.isValid = false;
      }
    }
    if($scope.isValid){
      propertyToSave.value = newValue
      var data = {
          "properties": [ propertyToSave ]
        , 'reason' : "Add property '"+$scope.newProperty.name+"' to Node '"+currentNodeId+"'"
      };
      $http.post($scope.urlAPI, data).then(function successCallback(response) {
        $scope.errorSaving = false;
        //Check if new property's name is already used or not.
        $scope.alreadyUsed = $scope.properties.some(checkNameUnicity);
        if(!$scope.alreadyUsed){
          $scope.properties.push(propertyToSave);
          $scope.resetNewProperty();
          $('#newPropPopup').bsModal('hide');
          $scope.newPropForm.$setPristine();
        }
      }, function errorCallback(response) {
        $scope.errorSaving = response.data.errorDetails;
        return response.status==200;
      });
    }
  };
  $scope.popupDeletion = function(prop,index) {
    $scope.deletedProperty.name = prop;
    $scope.deletedProperty.index = index;
    $('#deletePropPopup').bsModal('show');
  };
  $scope.deleteProperty = function(){
    var data = {
        "properties":[{"name":$scope.deletedProperty.name, "value":""}]
      , 'reason' : "Delete property '"+$scope.deletedProperty.name+"' to Node '"+currentNodeId+"'"
    };
    
    $scope.errorDeleting = false;
    $http.post($scope.urlAPI, data).then(function successCallback(response) {
      $('#deletePropPopup').bsModal('hide');
      $scope.properties.splice($scope.deletedProperty.index, 1);
    }, function errorCallback(response) {
      $('#deletePropPopup').bsModal('hide');
      $scope.errorDeleting = response.data.errorDetails;
      var el = $('#nodePropertiesTab_wrapper');
      var height = parseFloat($('#errorProp').css('height')) > 0 ? parseFloat($('#errorProp').css('height')) : 52;
      var offsetTop = el.offset().top;
      $('body').animate({scrollTop:offsetTop - height}, 300, 'easeInSine');
      return response.status==200;
    });
  }
  $('.rudder-label').bsTooltip();
});

app.config(function($locationProvider) {
  $locationProvider.html5Mode({
    enabled: true,
    requireBase: false
  });
})
