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

var nodePropertiesApp = angular.module('nodeProperties', ['datatables', 'monospaced.elastic']);

nodePropertiesApp.directive('mandatory', function (){
   return {
      require: 'ngModel',
      link: function(scope, elem, attr, ngModel) {
          //For DOM -> model validation
          ngModel.$parsers.unshift(function(value) {
             var valid = (value !== undefined && value !== null && value !=="");
             ngModel.$setValidity('mandatory', valid);
             return valid ? value : undefined;
          });
          //For model -> DOM validation
          ngModel.$formatters.unshift(function(value) {
             ngModel.$setValidity('mandatory', (value !== undefined && value !== null && value !==""));
             return value;
          });
      }
   };
});

nodePropertiesApp.directive('bsTooltip', function() {
  return function(scope, element, attrs) {
    $(element).bsTooltip();
  };
});

nodePropertiesApp.controller('nodePropertiesCtrl', function ($scope, $http, DTOptionsBuilder, DTColumnDefBuilder) {
  //Initialize scope
  $scope.properties;
  $scope.hasEditRight;
  $scope.objectName       = "";
  $scope.nodeId;
  $scope.tableId          = "#nodePropertiesTab";
  $scope.urlAPI           = "in init"
  $scope.fetchProperties  = function() {};
  $scope.newProperty      = {'name':"", 'value':""};
  $scope.deletedProperty  = {'name':"", 'index':""};
  $scope.alreadyUsed      = false;
  $scope.errorSaving      = false;
  $scope.errorDeleting    = false;
  $scope.checkJson        = false;
  $scope.isValid          = true ;
  $scope.editedProperties = {};
  $scope.resetNewProperty = function(){
    $scope.newProperty = {'name':"", 'value':""};
  }

  $scope.formatIsJson = function(value) {
    var res = (value !== null && typeof value === 'object');
    return res;
  }
  $scope.getFormat = function(value) {
    var res = $scope.formatIsJson(value) ? "JSON" : "String";
    return res;
  }
  $scope.formatContent = function(property) {
    var value = property.value
    if ($scope.formatIsJson(value)) {
      value = JSON.stringify(value, null, 2);
    }
    return value;
  }

  $scope.options =
    DTOptionsBuilder.newOptions().
      withPaginationType('full_numbers').
	  withDOM('<"dataTables_wrapper_top newFilter"f>t<"dataTables_wrapper_bottom"lip>').
	  withLanguage({
		    "searchPlaceholder": 'Filter',
			"search": ''
	  }).
      withOption('sWidth', '100%').
      withOption('sDom', '<"dataTables_wrapper_top newFilter"f<"dataTables_refresh">>rt<"dataTables_wrapper_bottom"lip>').
      withOption("bLengthChange", true).
	  withOption( "lengthMenu", [ [10, 25, 50, 100, 500, 1000, -1], [10, 25, 50, 100, 500, 1000, "All"] ]).
      withOption("pageLength", 25).
      withOption("jQueryUI", false).
      withOption("bAutoWidth", false)


  $scope.columns = [
        DTColumnDefBuilder.newColumnDef(0).withOption("sWidth",'20%'),
        DTColumnDefBuilder.newColumnDef(1).withOption("sWidth",'75%'),
        DTColumnDefBuilder.newColumnDef(2).withOption("sWidth",'5%')
    ];
  var currentNodeId
  $scope.init = function(nodeId, isUserHasRights, objectName){

    $scope.hasEditRight = isUserHasRights;
    currentNodeId = nodeId
    $scope.objectName = objectName;
    $scope.urlAPI = contextPath + '/secure/api/'+ objectName +'s/' + nodeId;
    var getUrlAPI = contextPath + '/secure/api/'+ objectName +'s/' + nodeId + '/displayInheritedProperties';
    $scope.fetchProperties = function() {
      return $http.get(getUrlAPI).then(
        function successCallback(response) {
          var res = objectName === 'node' ? response.data.data[0] : response.data.data.groups[0];
          if(res !== undefined && res.properties !== undefined){
            $scope.properties = res.properties
          }else{
            $scope.properties = [];
            createErrorNotification("Error while fetching "+objectName+" properties")
          }
        },
        function errorCallback(response) {
          createErrorNotification("Error while fetching "+objectName+" properties")
        }
      );
    }
    $scope.fetchProperties();
    new ClipboardJS('.btn-clipboard');
  }

  $scope.addProperty = function(){
    function checkNameUnicity(property, index, array) {
      return property.name == $scope.newProperty.name && property.provider !== "inherited";
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
        , 'reason' : "Add property '"+$scope.newProperty.name+"' to "+$scope.objectName+" '"+currentNodeId+"'"
      };
      //Check if new property's name is already used or not.
      $scope.alreadyUsed = $scope.properties.some(checkNameUnicity);
      if(!$scope.alreadyUsed){
        $http.post($scope.urlAPI, data).then(function successCallback(response) {
          $scope.errorSaving = false;
          $scope.resetNewProperty();
          $('#newPropPopup').bsModal('hide');
          $scope.newPropForm.$setPristine();
          $scope.processResponse(response, "Property '"+propertyToSave.name+ "' has been added");
        }, function errorCallback(response) {
          $scope.errorSaving = response.data.errorDetails;
          createErrorNotification("Error while saving new property "+propertyToSave.name);
          return response.status==200;
        });
      }
    }
  };

  $scope.processResponse = function(response, successMsg) {
    // check if it created a change request
    var changeId = undefined;
    try {
      changeId = response.data.data[$scope.objectName+'s'][0].changeRequestId;
    } catch {}
    if(changeId === undefined) {
      createSuccessNotification(successMsg);
      // need to reload everything for inherited etc...
      $scope.fetchProperties();
    } else { // redirect to change request
      window.location = contextPath + "/secure/plugins/changes/changeRequest/" + changeId;
    }
  }


  $scope.popupDeletion = function(prop,index) {
    $scope.deletedProperty.name = prop;
    $scope.deletedProperty.index = index;
    $('#deletePropPopup').bsModal('show');
  };
  $scope.deleteProperty = function(){
    var data = {
        "properties":[{"name":$scope.deletedProperty.name, "value":""}]
      , 'reason' : "Delete property '"+$scope.deletedProperty.name+"' to "+$scope.objectName+" '"+currentNodeId+"'"
    };

    $scope.errorDeleting = false;
    $http.post($scope.urlAPI, data).then(function successCallback(response) {
      $('#deletePropPopup').bsModal('hide');
      $scope.processResponse(response, "Property '"+$scope.deletedProperty.name+ "' has been removed");
    }, function errorCallback(response) {
      $('#deletePropPopup').bsModal('hide');
      $scope.errorDeleting = response.data.errorDetails;
      var el = $('#nodePropertiesTab_wrapper');
      var height = parseFloat($('#errorProp').css('height')) > 0 ? parseFloat($('#errorProp').css('height')) : 52;
      var offsetTop = el.offset().top;
      $('body').animate({scrollTop:offsetTop - height}, 300, 'easeInSine');
      createErrorNotification("Error while deleting property "+propertyToSave.name);
      return response.status==200;
    });
  }


  $scope.editProperty = function(property){
    if (property.provider === undefined || property.provider === 'overridden'){
      var newProp = angular.copy(property)
      newProp.checkJson = $scope.getFormat(newProp.value)=="JSON";
      var value = property.value;
      if(property.provider === "overridden" ) {
        value = property.origval;
      }
      newProp.value = newProp.checkJson ? JSON.stringify(value, null, 4) : value;
      newProp.isValid = true;
      $scope.editedProperties[property.name] = angular.copy(property);
      $scope.editedProperties[property.name].new = newProp;
    }
  }
  $scope.changeFormat = function(prop, checkJson){
    $scope.editedProperties[prop].new.checkJson = checkJson;
    $scope.editedProperties[prop].new.isValid = true;
  }
  $scope.isEdited = function(prop){
    return $scope.editedProperties.hasOwnProperty(prop);
  }
  $scope.saveEdit = function(prop, index){
    function checkNameUnicity(property, index, array) {
      return (
           ($scope.editedProperties[prop].new.name != $scope.editedProperties[prop].name)
        && (property.name == $scope.editedProperties[prop].new.name)
        && (property.provider !== "inherited")
      );
    }
    //Check if the modified property's name is already used or not.
    $scope.editedProperties[prop].new.alreadyUsed = $scope.properties.some(checkNameUnicity);
    var newName  = $scope.editedProperties[prop].new.name
    var newValue = $scope.editedProperties[prop].new.value;
    if($scope.editedProperties[prop].new.checkJson){
      try {
        newValue = JSON.parse(newValue);
        $scope.editedProperties[prop].new.isValid = true;
      } catch(e) {
        $scope.editedProperties[prop].new.isValid = false;
      }
    }
    if($scope.editedProperties[prop].new.isValid && !$scope.editedProperties[prop].new.alreadyUsed){
      var propertyToSave =
      { "name"  : newName
      , "value" : newValue
      }
      var propertiesToSave = [propertyToSave];
      var keyInfoMessage   = "";
      //If key has been modified
      if(newName != $scope.editedProperties[prop].name){
        var oldProperty =
        { "name"  : $scope.editedProperties[prop].name
        , "value" : ""
        }
        propertiesToSave.push(oldProperty);
        keyInfoMessage   = "(now '"+newName+"') ";
      }
      var data =
      { "properties" : propertiesToSave
      , "reason"     : "Edit property '"+$scope.editedProperties[prop].name+"' "+keyInfoMessage+"from Node '"+currentNodeId+"'"
      };
      $http.post($scope.urlAPI, data).then(function successCallback(response) {
        delete $scope.editedProperties[prop];
        $scope.processResponse(response, "Property '"+propertyToSave.name+ "' has been saved");
      }, function errorCallback(response) {
        createErrorNotification("Error while saving "+$scope.objectName+" properties");
        return response.status==200;
      });
    }
  }
  $scope.cancelEdit = function(prop){
    delete $scope.editedProperties[prop];
  }

  $scope.isTooLong = function(property){
    var value = $scope.formatContent(property);
    var res   = (value.match(/\n/g) || []).length
    return res >= 3;
  }

  $('.rudder-label').bsTooltip();
});

nodePropertiesApp.config(function($locationProvider) {
  $locationProvider.html5Mode({
    enabled: true,
    requireBase: false
  });
})
