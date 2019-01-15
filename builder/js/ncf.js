'use strict';

// Helpers functions


// Swap two two items in an array based on their index
function swapTwoArrayItems(array, index1, index2) {
    var item = array[index1];
    array[index1] = array[index2];
    array[index2] = item;
    return array;
};

function initScroll(){
  var categories, categoriesPosition;
  window.addEventListener('scroll', function() {
    if(!categories){
      categories = $('#categories-list').get(0);
      categoriesPosition = categories.getBoundingClientRect().top;
    }
    if (window.pageYOffset >= categoriesPosition) {
      $(categories).addClass('fixed');
    } else {
      $(categories).removeClass('fixed');
    }
  });
}
// Find index of an element in an array
function findIndex(array, elem) {
    for (var index in array) {
        var item = array[index];
        if (angular.equals(item, elem)) {
          return array.indexOf(item);
        }
    }
    return -1;
};

// define ncf app, using ui-bootstrap and its default templates
var app = angular.module('ncf', ['ui.bootstrap', 'ui.bootstrap.tpls', 'monospaced.elastic', 'ngToast', 'dndLists', 'ngMessages'])

// A directive to add a filter on the technique name controller
// It should prevent having techniques with same name (case insensitive)
// It should not check with the original name of the technique so we can change its case
app.directive('techniquename', function($filter) {
  return {
    require: 'ngModel',
    link: function(scope, elm, attrs, ctrl) {
      ctrl.$validators.techniqueName = function(modelValue, viewValue) {
         // Get all techniqueNames in lowercase
         var techniqueNames = scope.techniques.map(function (technique,index) { return technique.name.toLowerCase()})
         // Remove the original name from the technique names array
         if (scope.originalTechnique !== undefined && scope.originalTechnique.name !== undefined) {
           techniqueNames = $filter("filter")(techniqueNames, scope.originalTechnique.name.toLowerCase(), function(actual,expected) { return ! angular.equals(expected,actual)})
         }
         // technique name is ok if the current value is not in the array
         return $.inArray(viewValue.toLowerCase(), techniqueNames) === -1
      };
    }
  };
});

app.directive('focusOn', function() {
   return function(scope, elem, attr) {
      scope.$on('focusOn', function(e, name) {
        if(name === attr.focusOn) {
          elem[0].focus();
        }
      });
   };
});

app.factory('focus', function ($rootScope, $timeout) {
  return function(name) {
    $timeout(function (){
      $rootScope.$broadcast('focusOn', name);
    });
  }
});

app.directive('bundlename', function($filter) {
  return {
    require: 'ngModel',
    link: function(scope, elm, attrs, ctrl) {
      ctrl.$validators.bundleName = function(modelValue, viewValue) {
         // Get all bundleNames
         var bundleNames = scope.techniques.map(function (technique,index) { return technique.bundle_name})
         // Remove the original bundle name from the bundle names array
         if (scope.originalTechnique !== undefined && scope.originalTechnique.bundle_name !== undefined) {
           bundleNames = $filter("filter")(bundleNames, scope.originalTechnique.bundle_name, function(actual,expected) { return ! angular.equals(expected,actual)})
         }
         // bundle name is ok if the current value is not in the array
         return $.inArray(viewValue, bundleNames) === -1
      };
    }
  };
});

app.directive('constraint', function($http, $q, $timeout) {
  return {
    scope: {
      parameter: '='
    },
    require: 'ngModel',
    link: function(scope, elm, attrs, ctrl) {
    if((scope.parameter.constraints.allow_empty_string)&&(scope.parameter.value === undefined)){
      scope.parameter.value = "";
    }
    ctrl.$asyncValidators.constraint = function(modelValue, viewValue) {
      scope.parameter.$errors= [];
      if (modelValue === undefined) {
        return $q.reject("Value is empty");
      }
      var data = {"value" : modelValue, "constraints" : scope.parameter.constraints}

      var timeoutStatus = false;
      var timeout = $q.defer();

      var request = $http.post("/ncf/api/check/parameter",data, { 'timeout' : timeout.promise }).then(
          function(successResult) {
            scope.parameter.$errors= [];
            if (! successResult.data.result) {
              scope.parameter.$errors = successResult.data.errors;
              return $q.reject('Constraint is not valid');
            }
            return $q.when(modelValue);
          }
        , function(errorResult) {
          // If there was an error with the request, accept the value, it will be checked when saving
          // Maybe we should display a warning but I'm not sure at all ...
            if (timeoutStatus) {
              return $q.when('request timeout');
            }
            return $q.when('Error during check request')
          }
      )

      $timeout( function() {
        timeoutStatus = true;
        timeout.resolve();
      }, 500);
      return request;
    };
  }
};
});

app.directive('showErrors', function() {
return {
  restrict: 'A',
  require:  '^form',
  link: function (scope, el, attrs, formCtrl) {
    // find the text box element, which has the 'name' attribute
    var inputEl   = el[0].querySelector("[name]");
    // convert the native text box element to an angular element
    var inputNgEl = angular.element(inputEl);
    // get the name on the text box so we know the property to check
    // on the form controller
    var inputName = inputNgEl.attr('name');
    // only apply the has-error class after the user leaves the text box
    inputNgEl.bind('blur', function() {
      el.toggleClass('has-error', formCtrl[inputName].$invalid);
    })
  }
}
});

app.directive('popover', function() {
  return function(scope, elem) {
    $(elem).popover();
  }
});

// Declare controller ncf-builder
app.controller('ncf-builder', function ($scope, $uibModal, $http, $log, $location, $anchorScroll, ngToast, $timeout, focus) {
  initScroll();
  // Variable we use in the whole application
  // Give access to the "General information" form
  $scope.editForm;
  // Path of ncf files, defined as a url parameter
  $scope.path;
  // generic methods container
  $scope.generic_methods;
  // Generic methods order by category, used when we want to add new methods
  $scope.methodsByCategory;
  // ncf technique container
  $scope.techniques;

  // Selected technique, undefined when there is no selected technique
  $scope.parameters;
  $scope.selectedTechnique;
  $scope.originalTechnique;
  // Information about the selected method in a technique
  $scope.selectedMethod;
  // Are we authenticated on the interface
  $scope.authenticated = false;
  // Open/Close by default the Conditions box
  $scope.conditionIsOpen = false;

  //Generic methods filters
  $scope.filter = {
    compatibility  : "all"
  , showDeprecated : false
  , text           : ""
  }

  var usingRudder = false;

  $scope.CForm = {};

  $scope.setPath = function() {
    var path = $location.search().path;
    if (path === undefined) {
      $scope.path = "";
    } else if ( path === true) {
      $scope.path = "";
    } else {
      $scope.path = path;
    }
  };
  // Callback when an element is dropped on the list of method calls
  // return the element that will be added, if false do not add anything
  $scope.dropCallback = function(elem, nextIndex, type){

    // Add element
    // if type is a bundle, then transform it to a method call and add it
    if (type === "bundle") {
      return toMethodCall(elem);
    }

    // If selected method is the same than the one moving, we need to update selectedMethod
    if (angular.equals($scope.selectedMethod, elem)) {
      $scope.selectedMethod = elem;
    }

    return elem
  }

// Define path by getting url params now
$scope.setPath();

// Define hash location url, this will make the page scroll to the good element since we use $anchorScroll
$scope.scroll = function(id) {
  $location.hash(id);
  $anchorScroll();
};

// Capitalize first letter of a string
$scope.capitaliseFirstLetter = function (string) {
  if (string.length === 0) {
    return string;
  } else {
    return string.charAt(0).toUpperCase() + string.slice(1);
  }
};

function errorNotification (message,details) {
  var errorMessage = '<b>An Error occured!</b> ' + message
  if (details !== undefined) {
    errorMessage += '<br/><b>Details:</b><pre class="error-pre">' + $('<div>').text(details).html() +"</pre>"
  }
  ngToast.create({
      content: errorMessage
    , className: 'danger'
    , dismissOnTimeout : false
    , dismissButton : true
    , dismissOnClick : false
  });
}

function handle_error ( actionName ) {
  return function(data, status, headers, config) {
    if (status === 401) {
      $scope.authenticated = false;
      errorNotification('Could not authenticate '+ actionName, data.error.details);
    } else {
      if (data.error !== undefined) {
          $.each(data.error, function(index,error) {
            var details = error.details === undefined ? error.errorDetails : error.details
            errorNotification(error.message,details);
          })
      } else {
        errorNotification('Error '+ actionName, data.errorDetails)
      }
    }
  }
};

function defineMethodClassContext (method_call) {
  // First split from .
  var myclasses =  method_call.class_context.split(".");
  // find os class from the first class of class_context
  var osClass = find_os_class(myclasses[0], cfengine_OS_classes);
  if ( $.isEmptyObject(osClass)) {
    // first class is not an os class, class_context is only advanced class
    method_call.advanced_class = method_call.class_context;
  } else {
    // We have an os class !
    method_call.OS_class = osClass;
    if (myclasses.length > 1) {
      // We have more than one class, rest of the context is an advanced class
      myclasses.splice(0,1);
      var advanced_class = myclasses.join(".");
      if (advanced_class.startsWith("(")) {
        advanced_class = advanced_class.slice(1,-1);
      }
      method_call.advanced_class = advanced_class;
    }
  }
}

// Transform a ncf technique into a valid UI technique
// Add original_index to the method call, so we can track their modification on index
// Handle classes so we split them into OS classes (the first one only) and advanced classes
function toTechUI (technique) {
  if ("method_calls" in technique) {
    var calls = technique.method_calls.map( function (method_call, method_index) {
      method_call["original_index"] = method_index;

      // Handle class_context
      defineMethodClassContext(method_call)
      method_call.parameters=$scope.getMethodParameters(method_call)
      return method_call;
    } );
    technique.method_calls = calls;
  }
  return technique;
};
// Transform a ui technique into a valid ncf technique by removint original_index param
function toTechNcf (baseTechnique) {
  var technique = angular.copy(baseTechnique);
  var calls = technique.method_calls.map( function (method_call, method_index) {
    delete method_call.original_index;
    method_call.args = method_call.parameters.map ( function ( param, param_index) {
      return param.value;
    });
    return method_call;
  });
  technique.method_calls = calls;
  return technique;
};

// Check if a technique is selected
$scope.isSelected = function(technique) {
  return angular.equals($scope.originalTechnique,technique);
};

// Check if a method is selected
$scope.isSelectedMethod = function(method) {
  if ($scope.selectedMethod === undefined) {
    return false
  }
  return method["$$hashKey"] === $scope.selectedMethod["$$hashKey"];
};
$scope.getSessionStorage = function(){
  $scope.resetFlags();
  var deleted = true;
  var t1,t2   = undefined;
  t1 = JSON.parse(sessionStorage.getItem('selectedTechnique'));
  t2 = JSON.parse(sessionStorage.getItem('originalTechnique'));
  if(t2 !== null && t2.bundle_name === "") t2.bundle_name=undefined;
  $scope.originalTechnique = angular.copy(t2);
  if(t1 !== null){
    $scope.restoreFlag  = true;
    $scope.selectedTechnique = angular.copy(t1);
    if(t2.bundle_name !== undefined){
      //Not a new technique
      var existingTechnique = $scope.techniques.find(function(technique){return technique.bundle_name === t2.bundle_name })
      if (existingTechnique !== undefined) {
        if(t2.hasOwnProperty('saving'))existingTechnique.saving   = false;
        if(t2.hasOwnProperty('isClone'))existingTechnique.isClone = false;

        for(var i=0; i<t2.method_calls.length; i++){
          if(t2.method_calls[i].hasOwnProperty('agent_support')){
            existingTechnique.method_calls[i].agent_support = t2.method_calls[i].agent_support;
          }
          if(existingTechnique.method_calls[i].hasOwnProperty('promiser')){
            t2.method_calls[i].promiser = existingTechnique.method_calls[i].promiser;
          }
        }
        if(!angular.equals(t2, existingTechnique)){
          $scope.conflictFlag = true;
          var modalInstance = $uibModal.open({
            templateUrl: 'RestoreWarningModal.html',
            controller: RestoreWarningModalCtrl,
            backdrop : 'static',
            resolve: {
              technique: function () {
                return $scope.selectedTechnique;
              }
              , editForm  : function() { return  $scope.editForm }
            }
          });
          modalInstance.result.then(function (doSave) {
            $scope.originalTechnique = existingTechnique;
            if (doSave) {
              $scope.selectedTechnique = angular.copy($scope.originalTechnique);
              $scope.resetFlags();
            }else{
              $scope.keepChanges();
            }
          });
        }
        deleted=false;
      }
      $scope.suppressFlag = (deleted && !$scope.conflictFlag && $scope.originalTechnique.bundle_name !== undefined);
      if(!$scope.conflictFlag && !deleted){
        $scope.originalTechnique = existingTechnique;
      }
    }else{// else : New technique
      var saved = $scope.techniques.find(function(technique){return technique.bundle_name === $scope.selectedTechnique.bundle_name })
      if(saved !== undefined){
        $scope.originalTechnique = angular.copy(saved);
        $scope.selectedTechnique = angular.copy(saved);
      }
    }
  }// else : Empty session storage
}

$scope.$watch('selectedTechnique', function(newValue, oldValue) {
  $scope.updateItemSessionStorage('selectedTechnique', oldValue, newValue);
},true);
$scope.$watch('originalTechnique', function(newValue, oldValue) {
  $scope.updateItemSessionStorage('originalTechnique', oldValue, newValue);
},true);

$scope.clearSessionStorage = function(){
  sessionStorage.removeItem('selectedTechnique');
  sessionStorage.removeItem('originalTechnique');
  $scope.resetFlags();
}

$scope.resetFlags = function(){
  $scope.restoreFlag  = false;
  $scope.suppressFlag = false;
  $scope.conflictFlag = false;
}

$scope.keepChanges = function(){
  $scope.restoreFlag  = false;
}
$scope.updateItemSessionStorage = function(item, oldTechnique, newTechnique){
  //Checking oldTechnique allows us to not clear the session storage when page is loading so $scope.selectedTechnique and $scope.originalTechnique are still undefined.
  if(oldTechnique && !newTechnique){
    $scope.clearSessionStorage();
  } else if(newTechnique){
    var savedTechnique = angular.copy(newTechnique);
    if(savedTechnique.name        === undefined) savedTechnique.name        = "";
    if(savedTechnique.bundle_name === undefined) savedTechnique.bundle_name = "";
    sessionStorage.setItem(item, JSON.stringify(savedTechnique));
  }
}
// Call ncf api to get techniques
$scope.getTechniques = function () {

  $scope.techniques = [];
  var data = {params: {path: $scope.path}}
  $http.get('/ncf/api/techniques',data).
    success(function(response, status, headers, config) {

      if (response.data !== undefined && response.data.techniques !== undefined) {

        $scope.generic_methods = response.data.generic_methods;
        $scope.methodsByCategory = $scope.groupMethodsByCategory();
        $scope.authenticated = true;
        if (response.data.usingRudder !== undefined) {
          usingRudder = response.data.usingRudder;
        }
        $.each( response.data.techniques, function(techniqueName, technique_raw) {
          var technique = toTechUI(technique_raw);
          $scope.techniques.push(technique);
        });
        $scope.getSessionStorage();
      } else {
        errorNotification( "Error while fetching methods and techniques", "Data received via api are invalid")
      }

      // Display single errors
      $.each( response.errors, function(index, error) {
        errorNotification(error.message,error.details)
      })
    } ).
    error(handle_error(" while fetching methods and techniques"));
};


// Group methods by category, a category of a method is the first word in its name
$scope.groupMethodsByCategory = function () {
  var groupedMethods = {};
  for (var methodKey in $scope.generic_methods) {
    var method = $scope.generic_methods[methodKey];
    var name = methodKey.split('_')[0];
    var grouped = groupedMethods[name];
    if (grouped === undefined) {
      groupedMethods[name] = [method];
    } else {
      groupedMethods[name].push(method);
    }
  };
  return groupedMethods;
};

// method to export technique content into a json file
$scope.exportTechnique = function(){
  // selectedTechnique exists otherwise the buttons couldn't call us
  var filename = $scope.selectedTechnique.name+'.json';
  // build the exported technique object from the current selected technique
  var calls = [];
  for (var i = 0; i < $scope.selectedTechnique['method_calls'].length; i++) {
    var call = $scope.selectedTechnique['method_calls'][i]
    calls[i] = {
      args:          call["args"],
      class_context: call["class_context"],
      method_name:   call["method_name"],
      promiser:      call["promiser"]
    }
  }
  var exportedTechnique = {
    type: 'ncf_technique', version: 1.0,
    data: {
      bundle_args: $scope.selectedTechnique["bundle_args"],
      bundle_name: $scope.selectedTechnique["bundle_name"],
      description: $scope.selectedTechnique["description"],
      name:        $scope.selectedTechnique["name"],
      version:     $scope.selectedTechnique["version"],
      parameter:   $scope.selectedTechnique["parameter"],
      method_calls: calls
    }
  };

  var blob = new Blob([angular.toJson(exportedTechnique, true)], {type: 'text/plain'});
  if (window.navigator && window.navigator.msSaveOrOpenBlob) {
    window.navigator.msSaveOrOpenBlob(blob, filename);
  } else{
    var e = document.createEvent('MouseEvents'),
    a = document.createElement('a');
    a.download = filename;
    a.href = window.URL.createObjectURL(blob);
    a.dataset.downloadurl = ['text/json', a.download, a.href].join(':');
    e.initEvent('click', true, false, window, 0, 0, 0, 0, 0, false, false, false, false, 0, null);
    a.dispatchEvent(e);
    // window.URL.revokeObjectURL(url); // clean the url.createObjectURL resource
  }
}

// method to import technique content from a json file
$scope.onImportFileChange = function (fileEl) {
  var files = fileEl.files;
  var file = files[0];
  var reader = new FileReader();

  reader.onloadend = function(evt) {
    if (evt.target.readyState === FileReader.DONE) {
      $scope.$apply(function () {
        var importedTechnique = JSON.parse(evt.target.result);
        if(importedTechnique['type'] == 'ncf_technique' && importedTechnique['version'] == 1.0) {
          var technique = toTechUI(importedTechnique['data']);
          $scope.checkSelect(technique, $scope.selectTechnique);
          $scope.editForm.$setDirty();
          $scope.suppressFlag = true;
        } else {
          alert("Unsupported file type ! This version of Rudder only support import of ncf techniques files in format 1.0");
        }
      });
    }
  };
  reader.readAsText(file);
}


  // Method used to check if we can select a technique without losing changes
  $scope.checkSelect = function(technique, select) {
    // No selected technique, select technique
    if ($scope.selectedTechnique === undefined) {
      select(technique);
    } else {
      if  ($scope.checkSelectedTechnique()) {
        // Selected technique is the same than actual selected technique, unselect it
        select(technique);
      } else {
        // Display popup that shanges will be lost, and possible discard them
        $scope.selectPopup(technique, select);
      }
    }
    focus('focusTechniqueName');
  };

  // Click on a Technique
  // Select it if it was not selected, unselect it otherwise
  $scope.selectTechnique = function(technique) {
    $scope.restoreFlag  = false;
    $scope.suppressFlag = false;
    $scope.conflictFlag = false;
    // Always clean Selected methods and display methods list
     $scope.selectedMethod = undefined;
    // Check if that technique is the same as the original selected one
    if(angular.equals($scope.originalTechnique,technique) ) {
      // It's the same, unselect the technique
      $scope.selectedTechnique = undefined;
      $scope.originalTechnique = undefined;
    } else {
      // Select the technique, by using angular.copy to have different objects
      $scope.selectedTechnique=angular.copy(technique);
      $scope.originalTechnique=angular.copy($scope.selectedTechnique);
      $scope.$broadcast('endSaving');
    }
  };

  ////////// OS Class ////////

  // Structures we will use to select our class, we can't use the big structure os_classes, we have to use simple list with angular

  // List of all OS types
  $scope.type_classes = $.map(cfengine_OS_classes,function(val,i){return val.name;});

  // Build Map of all OS  by type
  $scope.os_classes_by_type = {};
  for (var index in cfengine_OS_classes) {
      // for each type ...
      var current_type = cfengine_OS_classes[index];
      // Get all oses
      var oses = $.map(current_type.childs, function(os,i) {
         return os.name;
      });
      $scope.os_classes_by_type[current_type.name] = oses;
  }

  // Regexp used for input version fields
  $scope.versionRegex = /^\d+$/;

  // List of oses using major or minor version
  $scope.major_OS = $.map(cfengine_OS_classes, function(v,i) { return $.map($.grep(v.childs,function(os,i2) { return os.major}), function(os,i2) {return os.name});});
  $scope.minor_OS = $.map(cfengine_OS_classes, function(v,i) { return $.map($.grep(v.childs,function(os,i2) { return os.minor}), function(os,i2) {return os.name});});

  // Functiopn to check if the os selected need major/minor versionning
  function checkVersion (os_list) {
    if ($scope.selectedMethod.OS_class === undefined ) {
      return false;
    } else {
      return $.inArray($scope.selectedMethod.OS_class.name,os_list) >= 0;
    }
  }

  $scope.checkMajorVersion= function( ) {
      return checkVersion($scope.major_OS);
  }

  $scope.checkMinorVersion= function( ) {
      return checkVersion($scope.minor_OS);
  }
  $scope.checkDeprecatedFilter = function(methods){
    return methods.some(function(method){return method.deprecated === undefined });
  }

  $scope.checkFilterCategory = function(methods){
    //If this function returns true, the category is displayed. Else, it is hidden by filters.
    var deprecatedFilter = $scope.filter.showDeprecated || $scope.checkDeprecatedFilter(methods);
    var agentTypeFilter  = false;
    var nameFilter = methods.some(function(m) {return m.name.includes($scope.filter.text)});
    var i = 0;
    switch($scope.filter.compatibility){
      case "dsc":
        while(!agentTypeFilter && i<methods.length){
          agentTypeFilter = methods[i].agent_support.some(function(agent){return agent === "dsc"});
          i++;
        }
        break;
      case "classic":
        while(!agentTypeFilter && i<methods.length){
          agentTypeFilter = methods[i].agent_support.some(function(agent){return agent === "cfengine-community"});
          i++;
        }
        break;
      case "all":
        agentTypeFilter  = true;
        break;
    }
    return agentTypeFilter && deprecatedFilter && nameFilter;
  }

  $scope.checkMethodCallAgentSupport = function(methodName, agent){
    var gKey = Object.keys($scope.generic_methods).find(function(method){return $scope.generic_methods[method].bundle_name === methodName});
    var method = $scope.generic_methods[gKey];
    return $scope.checkAgentSupport(method,agent);
  }
  $scope.checkAgentSupport = function(method, agentSupport){
    return method.agent_support.some(function(agent){return agent === agentSupport});
  }
  $scope.checkFilterMethod = function(method){
    //If this function returns true, the method  is displayed. Else, it is hidden by filters.
    var deprecatedFilter = $scope.filter.showDeprecated || !method.deprecated;
    var agentTypeFilter  = false;
    switch($scope.filter.compatibility){
      case "dsc":
        agentTypeFilter = method.agent_support.some(function(agent){return agent === "dsc"});
        break;
      case "classic":
        agentTypeFilter = method.agent_support.some(function(agent){return agent === "cfengine-community"});
        break;
      case "all":
        agentTypeFilter = true;
        break;
    }
    return agentTypeFilter && deprecatedFilter;
  }

  // Function used when changing os type
  $scope.updateOSType = function() {
    // Reset selected OS
    $scope.selectedMethod.OS_class.name = "Any";
    // Do other update cleaning
    $scope.updateOSName();
  }
  // Function used when changing selected os
  $scope.updateOSName = function() {
    // Reset versions inputs
    $scope.selectedMethod.OS_class.majorVersion = undefined;
    $scope.selectedMethod.OS_class.minorVersion = undefined;
    // Update class context
    $scope.updateClassContext();
  }

  // Update class context, after a change was made on classes
  $scope.updateClassContext = function() {

    // Define os class from selected inputs
    var os = undefined;

    // do not define os if nothing was selected
    if ( !($scope.selectedMethod.OS_class === undefined) ) {
      // Get class from os type and selected os
      os = getClass($scope.selectedMethod.OS_class);
    }

    if (os === undefined) {
      // No OS selected, only use advanced OS
      $scope.selectedMethod.class_context = $scope.selectedMethod.advanced_class;
    } else {
      if ($scope.selectedMethod.advanced_class === undefined || $scope.selectedMethod.advanced_class === "") {
        // No adanced class, use only OS
        $scope.selectedMethod.class_context = os;
      } else {
        // Both OS and advanced. Use class_context os.advanced
        $scope.selectedMethod.class_context = os+".("+$scope.selectedMethod.advanced_class+")";
      }
    }
  }

  // Select a method in a technique
  $scope.selectMethod = function(method_call) {
    $scope.conditionIsOpen = method_call.class_context != "any";
    if(angular.equals($scope.selectedMethod,method_call) ) {
      $scope.selectedMethod = undefined;
      // Scroll to the previously selected method category
      // We need a timeout so model change can be taken into account and element to scroll is displayed
      $timeout( function() {$anchorScroll();}, 0 , false)
    } else {
      $scope.selectedMethod = method_call;
      $scope.updateClassContext();
    }
  };

  // Open generic methods menu to add them to the technique
  $scope.openMethods = function() {
    $scope.selectedMethod = undefined;
  };

  function toMethodCall(bundle) {
    var original_index = undefined;
    var call = {
        "method_name"    : bundle.bundle_name
      , "original_index" : original_index
      , "class_context"  : "any"
      , "agent_support"  : bundle.agent_support
      , "parameters"     : bundle.parameter.map(function(v,i) {
        v["value"] = undefined
        return  v;
      })
      , 'deprecated'     : bundle.deprecated
      , "component"      : bundle.name
    }
    defineMethodClassContext(call)
    return angular.copy(call)
  }

  // Add a method to the technique
  $scope.addMethod = function(bundle) {
    if ($scope.selectedTechnique) {
      var call = toMethodCall(bundle);
      $scope.selectedTechnique.method_calls.push(call);
    }
  };

  // Check if a technique has not been changed, and if we can use reset function
  $scope.isUnchanged = function(technique) {
    return (!$scope.suppressFlag && angular.equals(technique, $scope.originalTechnique));
  };

  // Check if a technique has been saved,
  $scope.isNotSaved = function() {
    return $scope.originalTechnique !== undefined && $scope.originalTechnique.bundle_name === undefined;
  };

  // Check if a method has not been changed, and if we can use reset function
  $scope.canResetMethod = function() {
    var canReset = true;
    if ($scope.selectedMethod.original_index === undefined) {
      canReset = false;
    } else {
      var oldValue = $scope.originalTechnique.method_calls[$scope.selectedMethod.original_index];
      if ( oldValue === undefined) {
        canReset = false;
      }  else {
        canReset = ! angular.equals($scope.selectedMethod, oldValue);
      }
    }

    return canReset;
  };

  // Reset a method to the current value in the technique
  $scope.resetMethod = function() {
    var oldValue = $scope.originalTechnique.method_calls[$scope.selectedMethod.original_index];
    $scope.selectedMethod.class_context = oldValue.class_context;
    $scope.selectedMethod.parameters = oldValue.parameters;
  };

  // Create a new technique stub
  var newTech = {
      "method_calls" : []
    , "name": ""
    , "description": ""
    , "version": "1.0"
    , "bundle_name": undefined
    , "bundle_args": []
    , "parameter": []
  };

  $scope.newTechnique = function() {
    $scope.editForm.$setPristine();
    $scope.checkSelect(newTech, $scope.selectTechnique);
  };


  // Utilitary methods on Method call

  $scope.getMethodName = function(method_call) {
    if (method_call.method_name in $scope.generic_methods ) {
      return $scope.generic_methods[method_call.method_name].name;
    } else {
      return method_call.method_name;
    }
  };

  $scope.getMethodBundleName = function(method_call) {
    if (method_call.method_name in $scope.generic_methods ) {
      return $scope.generic_methods[method_call.method_name].bundle_name;
    } else {
      return method_call.method_name;
    }
  };


  // Get the desciption of a method call in definition of the generic method
  $scope.getMethodDescription = function(method_call) {
    if (method_call.method_name in $scope.generic_methods ) {
      return $scope.generic_methods[method_call.method_name].description;
    } else {
      return "";
    }
  };

  $scope.methodUrl = function(method) {
    var name = method.bundle_name !== undefined ? method.bundle_name : $scope.getMethodBundleName(method);
    if (usingRudder) {
      return "/rudder-doc/_generic_methods.html#"+name
    } else {
      return "http://www.ncf.io/pages/reference.html#"+name;
    }
  }

  // Get parameters information relative to a method_call
  $scope.getMethodParameters = function(method_call) {
    function createParameter (name, value, description) {
      return {
          "name" : name
        , "value" : value
        , "description" : description
        , "$errors" : []
      };
    }
    var params = [];
    // parameter information are stored into generic methods (maybe a better solution would be to merge data when we fetch them ...)
    if (method_call.method_name in $scope.generic_methods ) {
      var method = angular.copy($scope.generic_methods[method_call.method_name]);
      for (var i = 0; i < method.parameter.length; i++) {
         var parameter = method.parameter[i];
         var param_value = method_call.args[i];
         // Maybe parameter does not exists in current method_call, replace with empty value
         param_value = param_value !== undefined ? param_value : '';
         parameter["value"] = param_value;
         parameter["$errors"] = [];
         params.push(parameter);
      }
    } else {
      // We have no informations about this generic method, just call it 'parameter'
      params = method_call.args.map( function(arg) { return createParameter ('parameter', arg);});
    }
    return params;
  };

  // Get the value of the parameter used in generated class
  $scope.getClassParameter= function(method_call) {
    if (method_call.method_name in $scope.generic_methods ) {
      var method = $scope.generic_methods[method_call.method_name];
      var class_parameter = method.class_parameter;
      var param_index = method.bundle_args.indexOf(class_parameter);
      return method_call.parameters[param_index];
    } else {
      return method_call.parameters[0];
    }
  }

  // Get the class prefix value
  $scope.getClassPrefix= function(method_call) {
    if (method_call.method_name in $scope.generic_methods ) {
      var method = $scope.generic_methods[method_call.method_name];
      return method.class_prefix;
    } else {
      // Not defined ... use method name
      return method_call.method_name;
    }
  }

  // Get the class value generated from a class prefix and a class kind (kept,repaired,error, ...)
  $scope.getClassKind= function(method_call,kind) {
    var param = $scope.getClassParameter(method_call).value
    if (param === undefined) {
      param=""
    }
    // do not canonify what is between ${ }
    // regex description: replace every valid sequence followed by and invalid char with the sequence followed by _
    //                    a valid sequence is either a word or a ${} expression
    //                    a ${} expression can contain a word, dots, []'s, or a variable replacement (recursion is not managed, only first level)
    //                    ! do not replace by _ if we match the end of line
    param = param.replace(/\\'/g, "'")
                 .replace(/\\"/g, '"')
                 .replace(/((?:\w+|\$\{(?:[\w\.\[\]]|\$\{[\w\.\[\]]+?\})+?\})?)([^\$\w]|$)/g,
                          function(all,g1,g2) {
                            if (g2 == "" || g2 == "\n") { return g1; }
                            return g1+"_";
                          });
    return  $scope.getClassPrefix(method_call)+"_"+param +"_"+kind
  }


  $scope.checkMethodCall = function(method_call) {}
  // Check if the selected technique is correct
  // selected technique is correct if:
  // * There is at least one method call
  $scope.checkSelectedTechnique = function() {
    if (typeof($scope.selectedTechnique.method_calls) !== 'undefined') {
      var res = $scope.selectedTechnique.method_calls.length === 0;
      if ($scope.selectedTechnique.isClone) {
        return res
      } else {
        return res ||  $scope.isUnchanged($scope.selectedTechnique)
      }
    } else {
      return false;
    }
  }

  // Technique actions



  // clone method of specified index and add it right after index
  $scope.cloneMethod= function(index) {
    var newMethod = angular.copy($scope.selectedTechnique.method_calls[index]);
    delete newMethod.original_index;
    $scope.selectedTechnique.method_calls.splice(index+1, 0, newMethod);
    $scope.selectedMethod = newMethod;
  }

  // Remove method on specified index
  $scope.removeMethod= function(index) {
    $scope.selectedMethod = undefined;
    $scope.selectedTechnique.method_calls.splice(index, 1);
  }

  // Check if a method is deprecated
  $scope.isDeprecated = function(methodName) {
    return $scope.generic_methods[methodName].deprecated;
  };

  $scope.hasDeprecatedMethod = function(t){
    for(var i=0; i<t.method_calls.length; i++){
      if($scope.isDeprecated(t.method_calls[i].method_name))return true
    }
    return false;
  }

  // Move a method from an index to another index, and switch those
  $scope.move = function(from,to) {
    $scope.selectedTechnique.method_calls = swapTwoArrayItems($scope.selectedTechnique.method_calls,from,to);
  }

  // Move up the method in the hierachy
  $scope.moveUp = function(event, index) {
    event.stopPropagation();
    if(!$(event.currentTarget).hasClass('disabled')){
      $scope.move(index,index+1);
    }
  }

  // Move down the method in the hierachy
  $scope.moveDown = function(event, index) {
    event.stopPropagation();
    if(!$(event.currentTarget).hasClass('disabled')){
      $scope.move(index,index-1);
    }
  }

  // Resets a Technique to its original state
  $scope.resetTechnique = function() {
    $scope.editForm.$setPristine();
    $scope.selectedTechnique=angular.copy($scope.originalTechnique);
    $scope.resetFlags();
    $scope.$broadcast('endSaving');
    // Reset selected method too
    if ($scope.selectedMethod !== undefined) {
      // if original_index did not exists, close the edit method tab
      if ($scope.selectedMethod.original_index === undefined) {
        $scope.selectedMethod = undefined;
      } else {
        // Synchronize the selected method with the one existing in the updated selected technique
        var method = $scope.selectedTechnique.method_calls[$scope.selectedMethod.original_index]
        $scope.selectedMethod = method;
      }
    }
  };

  // Delete a technique
  $scope.deleteTechnique = function() {
    var data = {params: {path: $scope.path}};
    $http.delete("/ncf/api/techniques/"+$scope.selectedTechnique.bundle_name, data).
      success(function(data, status, headers, config) {

        ngToast.create({ content: "<b>Success!</b> Technique '" + $scope.originalTechnique.name + "' deleted!"});

        var index = findIndex($scope.techniques,$scope.originalTechnique);
        $scope.techniques.splice(index,1);
        $scope.selectedMethod = undefined;
        $scope.selectedTechnique = undefined;
        $scope.originalTechnique = undefined;
      } ).
      error(handle_error("while deleting Technique '"+$scope.selectedTechnique.name+"'"));
  };

  $scope.setBundleName = function (technique) {
    if (technique.bundle_name === undefined) {
      technique.bundle_name =  $scope.getBundleName(technique.name);
    }
    return technique;
  }

  $scope.getBundleName = function (techniqueName) {
    // Replace all non alpha numeric character (\W is [^a-zA-Z_0-9]) by _
    return techniqueName ? techniqueName.replace(/\W/g,"_") : "";
  }

  $scope.updateBundleName = function () {
    if($scope.originalTechnique.bundle_name===undefined){
      $scope.selectedTechnique.bundle_name = $scope.getBundleName($scope.selectedTechnique.name);
    }
  };

  $scope.trimParameter = function(parameter) {
    return ! (parameter.constraints.allow_whitespace_string);
  }

  $scope.$on("saving",function(){
    $scope.saving = true;
  });
  $scope.$on("endSaving",function(){
    $scope.saving = false;
  });

  $scope.newParam = {}

  $scope.addParameter = function() {
    $scope.selectedTechnique.parameter.push(angular.copy($scope.newParam))
    $scope.newParam.name = ""
  }
  // Save a technique
  $scope.saveTechnique = function() {
    $scope.$broadcast('saving');
    // Set technique bundle name
    $scope.setBundleName($scope.selectedTechnique);
    // make a copy of data so we don't lose the selected technique
    var technique = angular.copy($scope.selectedTechnique);
    var origin_technique = angular.copy($scope.originalTechnique);
    // transform technique so it is valid to send to API:
    var ncfTechnique = toTechNcf(technique);
    var data = { "path" :  $scope.path, "technique" : ncfTechnique }
    // Function to use after save is done
    // Update selected technique if it's still the same technique
    // update technique from the tree
    var saveSuccess = function(data, status, headers, config) {
      // Technique may have been modified by ncf API
      ncfTechnique = data.data.technique;
      var usedMethods = new Set();
      ncfTechnique.method_calls.forEach(
        function(m) {
          usedMethods.add($scope.generic_methods[m.method_name]);
        }
      );

      var methodKeys = Object.keys($scope.generic_methods).map(function(e) {
        return $scope.generic_methods[e]
      });
      var reason = "Updating Technique " + technique.name + " using the Technique editor";

      var rudderApiSuccess = function(data) {
        // Transform back ncfTechnique to UITechnique, that will make it ok
        var savedTechnique = toTechUI(ncfTechnique);

        // Update technique if still selected
        if (angular.equals($scope.originalTechnique, origin_technique)) {
          // If we were cloning a technique, remove its 'clone' state
          savedTechnique.isClone = false;
          if($scope.originalTechnique.bundle_name!=="" && $scope.originalTechnique.bundle_name!==undefined){
            $scope.originalTechnique=angular.copy(savedTechnique);
          }
          $scope.selectedTechnique=angular.copy(savedTechnique);
          // We will lose the link between the selected method and the technique, to prevent unintended behavior, close the edit method panel
          $scope.selectedMethod = undefined;
        }
        $scope.resetFlags();
        ngToast.create({ content: "<b>Success! </b> Technique '" + technique.name + "' saved!"});
        // Find index of the technique in the actual tree of technique (look for original technique)
        var index = findIndex($scope.techniques,origin_technique);
        if ( index === -1) {
         // Add a new techniuqe
         $scope.techniques.push(savedTechnique);
        } else {
         // modify techique in array
         $scope.techniques[index] = savedTechnique;
        }
      }

      var errorRudder = handle_error("while updating Technique \""+ ncfTechnique.name + "\" through Rudder API")

      $http.post("/rudder/secure/api/ncf", { "technique": ncfTechnique, "methods":methodKeys, "reason":reason }).
        success(rudderApiSuccess).
        error(errorRudder);

    }
    var saveError = function(action, data) {
      return handle_error("while "+action+" Technique '"+ data.technique.name+"'")
    }

    // Actually save the technique through API
    if ($scope.originalTechnique.bundle_name === undefined) {
      $http.post("/ncf/api/techniques", data).success(saveSuccess).error(saveError("creating", data)).finally(function(){$scope.$broadcast('endSaving');});
    } else {
      $http.put("/ncf/api/techniques", data).success(saveSuccess).error(saveError("saving", data)).finally(function(){$scope.$broadcast('endSaving');});
    }
  };
  // Popup definitions

  // Popup to know if there is some changes to save before switching of selected technique
  // paramters:
  // - Next technique you want to switch too
  // - Action to perform once the technique you validate the popup
  $scope.selectPopup = function( nextTechnique, select ) {
    var modalInstance = $uibModal.open({
      templateUrl: 'SaveChangesModal.html',
      controller: SaveChangesModalCtrl,
      resolve: {
          technique: function () {
            return $scope.originalTechnique;
          }
        , editForm  : function() { return  $scope.editForm }
      }
    });

    modalInstance.result.then(function (doSave) {
      if (doSave) {
        $scope.saveTechnique();
      }
      // run on success function
      select(nextTechnique)
    });
  };

  $scope.clonePopup = function() {

    var modalInstance = $uibModal.open({
      templateUrl: 'template/cloneModal.html',
      controller: cloneModalCtrl,
      resolve: {
        technique: function () {
          return angular.copy($scope.originalTechnique);
        }
        , techniques : function() { return $scope.techniques}
      }
    });

    modalInstance.result.then(function (technique) {
      technique.isClone = true
      $scope.selectTechnique(technique);
    });
  };

  $scope.confirmPopup = function(actionName,kind,action,elem, name) {
    var modalInstance = $uibModal.open({
      templateUrl: 'template/confirmModal.html',
      controller: confirmModalCtrl,
      resolve: {
        actionName: function() { return actionName; }
      , kind : function() { return kind; }
      , name : function() { return name; }
      }
    });

    modalInstance.result.then(function () {
        action(elem)
    });
  };

  $scope.reloadData = function() {
    $scope.getTechniques();
  }

  $scope.reloadPage = function() {
    window.top.location.reload();
  }

  $scope.checkMissingParameters = function(parameters){
    var result = false;
    for(var i=0; i<parameters.length; i++) {
      if(parameters[i].constraints.allow_empty_string === false && !parameters[i].value && (parameters[i].$errors && parameters[i].$errors.length <= 0)){
        result = true;
      }
    }
    return result;
  }

  $scope.isUsed = function(method){
    var i,j = 0;
    if(method.deprecated){
      for(i=0; i<$scope.techniques.length; i++){
        while(j<$scope.techniques[i].method_calls.length){
          if($scope.techniques[i].method_calls[j].method_name == method.bundle_name){
            return true;
          }
          j++;
        }
      }
    }
  return false;
  };
  $scope.reloadData();
  $scope.setPath();
});
var confirmModalCtrl = function ($scope, $uibModalInstance, actionName, kind, name) {

  $scope.actionName = actionName;
  $scope.kind = kind;
  $scope.name = name;

  $scope.displayName = function() {
    if (name === undefined) {
      return "this "+ kind;
    } else {
      return kind + " '" + name + "'"
    }
  };

  $scope.confirm = function() {
    $uibModalInstance.close();
  };

  $scope.cancel = function () {
    $uibModalInstance.dismiss('cancel');
  };
};

var cloneModalCtrl = function ($scope, $uibModalInstance, technique, techniques) {

  technique.bundle_name = undefined;

  $scope.techniques = techniques;
  $scope.technique = technique;
  $scope.oldTechniqueName = technique.name;

  $scope.clone = function() {
    $uibModalInstance.close(technique);
  }

  $scope.cancel = function () {
    $uibModalInstance.dismiss('cancel');
  };
};

var SaveChangesModalCtrl = function ($scope, $uibModalInstance, technique, editForm) {
  $scope.editForm = editForm;
  $scope.technique = technique;
  $scope.save = function() {
    $uibModalInstance.close(true);
  }

  $scope.discard = function () {
    $uibModalInstance.close(false);
  };

  $scope.cancel = function () {
    $uibModalInstance.dismiss('cancel');
  };
};

var RestoreWarningModalCtrl = function ($scope, $uibModalInstance, technique, editForm) {
  $scope.save = function() {
    $uibModalInstance.close(true);
  }
  $scope.discard = function () {
    $uibModalInstance.close(false);
  };
};

app.config(function($httpProvider,$locationProvider) {
    $locationProvider.html5Mode(false).hashPrefix('!');
    //On some browsers, HTML get headers are bypassed during Angular GET requests, so these headers have to be reinitialized in the Angular httpProvider defaults headers GET.
    if (!$httpProvider.defaults.headers.get) {
        $httpProvider.defaults.headers.get = {};
    }
    // Allows the browser to not use the GET request response to satisfy subsequent responses without first checking with the originating server
    $httpProvider.defaults.headers.get['Cache-Control'] = 'no-cache';
    //This previous header is ignored by some caches and browsers, for that it may be simulated by setting the Expires HTTP version 1.0 header field value to a time earlier than the response time
    $httpProvider.defaults.headers.get['Expires'] = 'Thu, 01 Jan 1970 12:00:00 GMT';
    //Allows the browser to indicate to the cache to retrieve the GET request content from the original server rather than sending one he must keep.
    $httpProvider.defaults.headers.get['Pragma'] = 'no-cache';
});
