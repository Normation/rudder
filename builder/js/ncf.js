'use strict';

// Helpers functions

var converter = new showdown.Converter();
// Swap two two items in an array based on their index
function swapTwoArrayItems(array, index1, index2) {
    var item = array[index1];
    array[index1] = array[index2];
    array[index2] = item;
    return array;
};

// check if the parameter used contains $(somecontent), where somecontent is neither
// a valid variable name (key.value), nor does contain /
// If the content is invalid, returns true, else false
var re = new RegExp(/\$\([^./]*\)/, 'm');

function detectIfVariableIsInvalid(parameter) {
  return re.test(parameter);
}



// define ncf app, using ui-bootstrap and its default templates
var app = angular.module('ncf', ['ui.bootstrap', 'ui.bootstrap.tpls', 'monospaced.elastic', 'ngToast', 'dndLists', 'ngMessages', 'FileManagerApp'])

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

      var request = $http.post("/rudder/secure/api/techniques/parameter/check",data, { 'timeout' : timeout.promise }).then(
          function(successResult) {
            scope.parameter.$errors= [];
            if (! successResult.data.data.parameterCheck.result) {
              scope.parameter.$errors = successResult.data.data.parameterCheck.errors;
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
app.controller('ncf-builder', function ($scope, $uibModal, $http, $q, $location, $anchorScroll, ngToast, $timeout, focus, $sce, fileManagerConfig, apiMiddleware, apiHandler, $window) {
  // Variable we use in the whole application

  //UI state
  $scope.ui = {
    showTechniques    : true,
    activeTab         : 'general',
    showMethodsFilter : true,
    methodTabs        : {},
    selectedMethods   : [],
    editForm          : {}
  }
  // Path of ncf files, defined as a url parameter
  $scope.path;
  // generic methods container
  $scope.generic_methods;
  // Generic methods order by category, used when we want to add new methods
  $scope.methodsByCategory;
  // ncf technique container
  $scope.techniques;

  $scope.fileManagerState = {
    open : false
  };

  $scope.closeWindow = function(event, enforce){
    if((enforce)||(event.currentTarget == event.target)){
      $scope.fileManagerState.open = false;
      hideFileManager();
    }
  }

  // Selected technique, undefined when there is no selected technique
  $scope.parameters;
  $scope.selectedTechnique;
  $scope.originalTechnique;
  // Are we authenticated on the interface
  $scope.authenticated = undefined;

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
    var selectedMethodIndex = $scope.ui.selectedMethods.findIndex(function(m){return angular.equals(elem, m)});
    // Keep the method opened if it was
    if (selectedMethodIndex>=0) {
      $scope.ui.selectedMethods[selectedMethodIndex] = elem;
    }
    return elem;
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
function updateResources() {
  var resourceUrl = '/rudder/secure/api/techniques/' + $scope.selectedTechnique.bundle_name +"/" + $scope.selectedTechnique.version +"/resources"
  $http.get(resourceUrl).then(
    function(response) {
      $scope.selectedTechnique.resources = response.data.data.resources;
      $scope.originalTechnique.resources = $scope.originalTechnique.resources === undefined ? response.data.data.resources : $scope.originalTechnique.resources;
    }
  , function(response) {
      // manage error
    }
  )
}
function updateFileManagerConf () {
  var newUrl =  "/rudder/secure/api/resourceExplorer/"+ $scope.selectedTechnique.bundle_name +"/" + $scope.selectedTechnique.version
  updateResources()

  apiHandler.prototype.deferredHandler = function(data, deferred, code, defaultMsg) {
    updateResources()

    if (!data || typeof data !== 'object') {
        this.error = 'Error %s - Bridge response error, please check the API docs or this ajax response.'.replace('%s', code);
    }
    if (code == 404) {
        this.error = 'Error 404 - Backend bridge is not working, please check the ajax response.';
    }
    if (data.result && data.result.error) {
        this.error = data.result.error;
    }
    if (!this.error && data.error) {
        this.error = data.error.message;
    }
    if (!this.error && defaultMsg) {
        this.error = defaultMsg;
    }
    if (this.error) {
        return deferred.reject(data);
    }
    return deferred.resolve(data);
};
  apiMiddleware.prototype.list = function(path, customDeferredHandler) {
    return this.apiHandler.list(newUrl, this.getPath(path), customDeferredHandler);
  };

  apiMiddleware.prototype.upload = function(files, path) {
      if (! $window.FormData) {
          throw new Error('Unsupported browser version');
      }

      var destination = this.getPath(path);

      return this.apiHandler.upload(newUrl, destination, files);
  };


  apiMiddleware.prototype.createFolder = function(item) {
    var path = item.tempModel.fullPath();
    return this.apiHandler.createFolder(newUrl, path);
  };
  apiMiddleware.prototype.getContent = function(item) {
    var itemPath = this.getFilePath(item);
    return this.apiHandler.getContent(newUrl, itemPath);
  };

  apiMiddleware.prototype.rename = function(item) {
    var itemPath = this.getFilePath(item);
    var newPath = item.tempModel.fullPath();

    return this.apiHandler.rename(newUrl, itemPath, newPath);
  };

  apiMiddleware.prototype.remove = function(files) {
    var items = this.getFileList(files);
    return this.apiHandler.remove(newUrl, items);
  };
  apiMiddleware.prototype.edit = function(item) {
    var itemPath = this.getFilePath(item);
    return this.apiHandler.edit(newUrl, itemPath, item.tempModel.content);
  };


  apiMiddleware.prototype.copy = function(files, path) {
    var items = this.getFileList(files);
    var singleFilename = items.length === 1 ? files[0].tempModel.name : undefined;
    return this.apiHandler.copy(newUrl, items, this.getPath(path), singleFilename);
  };

  apiMiddleware.prototype.changePermissions = function(files, dataItem) {
    var items = this.getFileList(files);
    var code = dataItem.tempModel.perms.toCode();
    var octal = dataItem.tempModel.perms.toOctal();
    var recursive = !!dataItem.tempModel.recursive;

    return this.apiHandler.changePermissions(newUrl, items, code, octal, recursive);
  };


  apiMiddleware.prototype.move = function(files, path) {
    var items = this.getFileList(files);
    return this.apiHandler.move(newUrl, items, this.getPath(path));
  };


  apiMiddleware.prototype.download = function(item, forceNewWindow) {

    var itemPath = this.getFilePath(item);
    var toFilename = item.model.name;

    if (item.isFolder()) {
        return;
    }

    return this.apiHandler.download(
        newUrl,
        itemPath,
        toFilename,
        fileManagerConfig.downloadFilesByAjax,
        forceNewWindow
    );
  };
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
  if($scope.originalTechnique==undefined || technique==undefined) return false;
  return $scope.originalTechnique.bundle_name == technique.bundle_name;
};

$scope.getSelectedMethodIndex = function(method) {
  var result = $scope.ui.selectedMethods.findIndex(function(m) {
    return method["$$hashKey"] === m["$$hashKey"];
  });
  return result;
};
// Check if a method is selected
$scope.methodIsSelected = function(method) {
  return $scope.getSelectedMethodIndex(method) >= 0;
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

    updateFileManagerConf()
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
        var checkTechnique1 = angular.copy(existingTechnique);
        var checkTechnique2 = angular.copy(t2);
        if(checkTechnique1 && checkTechnique1.resources !== undefined) delete checkTechnique1.resources;
        if(checkTechnique2 && checkTechnique2.resources !== undefined) delete checkTechnique2.resources;
        if(!angular.equals(checkTechnique1, checkTechnique2)){
          $scope.conflictFlag = true;
          var modalInstance = $uibModal.open({
            templateUrl: 'RestoreWarningModal.html',
            controller: RestoreWarningModalCtrl,
            backdrop : 'static',
            resolve: {
              technique: function () {
                return $scope.selectedTechnique;
              }
              , editForm  : function() { return  $scope.ui.editForm }
            }
          });
          modalInstance.result.then(function (doSave) {
            $scope.originalTechnique = existingTechnique;
            if (doSave) {
              $scope.selectedTechnique = angular.copy($scope.originalTechnique);
              updateFileManagerConf();
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
  (function() {
    new ClipboardJS('.clipboard');
  })();
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
  $http.get('/ncf/api/techniques').
    success(function(response, status, headers, config) {

      if (response.data !== undefined && response.data.techniques !== undefined) {

        $scope.generic_methods = response.data.generic_methods;

        $.each( response.data.generic_methods, function(methodName, method) {
          method.documentation = converter.makeHtml(method.documentation)
          $scope.generic_methods[methodName] = method;
        });
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
      component:     call["component"]
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
          $scope.ui.editForm.$setDirty();
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
    $scope.fileManagerState.open   = false;
    $scope.restoreFlag  = false;
    $scope.suppressFlag = false;
    $scope.conflictFlag = false;
    // Always clean Selected methods and display methods list
    $scope.ui.selectedMethods = [];
    // Check if that technique is the same as the original selected one
    var test1 = angular.copy($scope.originalTechnique)
    var test2 = angular.copy(technique)
    if(test1 && test1.resources !== undefined) delete test1.resources;
    if(test2 && test2.resources !== undefined) delete test2.resources;
    if(angular.equals(test1,test2) ) {
      // It's the same, unselect the technique
      $scope.selectedTechnique = undefined;
      $scope.originalTechnique = undefined;
      $scope.ui.activeTab      = 'general';
    } else {
      // Select the technique, by using angular.copy to have different objects
      $scope.selectedTechnique=angular.copy(technique);
      $scope.originalTechnique=angular.copy($scope.selectedTechnique);
      $scope.$broadcast('endSaving');
      updateFileManagerConf()
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
  function checkVersion (os_list, method) {
    if (method.OS_class === undefined ) {
      return false;
    }
    return $.inArray(method.OS_class.name,os_list) >= 0;
  }

  $scope.checkMajorVersion= function(method) {
    return checkVersion($scope.major_OS, method);
  }

  $scope.checkMinorVersion= function(method) {
    return checkVersion($scope.minor_OS, method);
  }
  $scope.checkDeprecatedFilter = function(methods){
    return methods.some(function(method){return method.deprecated === undefined });
  }

  $scope.checkFilterCategory = function(methods){
    //If this function returns true, the category is displayed. Else, it is hidden by filters.
    var deprecatedFilter = $scope.filter.showDeprecated || $scope.checkDeprecatedFilter(methods);
    var agentTypeFilter  = false;
    var nameFilter = methods.some(function(m) {return m.name.toLowerCase().includes($scope.filter.text.toLowerCase())});
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
  $scope.updateOSType = function(method) {
    // Reset selected OS
    method.OS_class.name = "Any";
    // Do other update cleaning
    $scope.updateOSName(method);
  }
  // Function used when changing selected os
  $scope.updateOSName = function(method) {
    // Reset versions inputs
    method.OS_class.majorVersion = undefined;
    method.OS_class.minorVersion = undefined;
    // Update class context
    $scope.updateClassContext(method);
  }

  // Update class context, after a change was made on classes
  $scope.updateClassContext = function(method) {

    // Define os class from selected inputs
    var os = undefined;

    // do not define os if nothing was selected
    if ( !(method.OS_class === undefined) ) {
      // Get class from os type and selected os
      os = getClass(method.OS_class);
    }

    if (os === undefined) {
      // No OS selected, only use advanced OS
      method.class_context = method.advanced_class;
    } else {
      if (method.advanced_class === undefined || method.advanced_class === "") {
        // No adanced class, use only OS
        method.class_context = os;
      } else {
        // Both OS and advanced. Use class_context os.advanced
        method.class_context = os+".("+method.advanced_class+")";
      }
    }
  }

  // Select a method in a technique
  $scope.selectMethod = function(method_call) {
    if($scope.methodIsSelected(method_call)){
      var methodIndex = $scope.getSelectedMethodIndex(method_call);
      $scope.ui.selectedMethods.splice(methodIndex, 1);
      // Scroll to the previously selected method category
      // We need a timeout so model change can be taken into account and element to scroll is displayed
      $timeout( function() {$anchorScroll();}, 0 , false)
    } else {
      $scope.updateClassContext(method_call);
      $scope.ui.selectedMethods.push(method_call);
    }
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
      $(function() {
        var gmList = $('form.editForm');
        var height = gmList[0].scrollHeight;
        gmList.stop().animate( {scrollTop:height} , 400);
      });
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
  $scope.canResetMethod = function(method) {
    var canReset = method ? true : false;
    if (!canReset) return false;
    if (method.original_index === undefined) {
      var current_method_name = method.method_name;
      var oldValue = toMethodCall($scope.generic_methods[current_method_name]);
      canReset = !(angular.equals(method.class_context, oldValue.class_context) && (angular.equals(method.parameters, oldValue.parameters)));
    } else {
      var oldValue = $scope.originalTechnique.method_calls[method.original_index];
      if ( oldValue === undefined) {
        canReset = false;
      }  else {
        canReset = ! angular.equals(method, oldValue);
      }
    }
    return canReset;
  };

  // Reset a method to the current value in the technique
  $scope.resetMethod = function(method) {
    var oldValue = undefined;
    if (method.original_index === undefined) {
      var current_method_name = method.method_name;
      oldValue = toMethodCall($scope.generic_methods[current_method_name]);
    }else{
      oldValue = $scope.originalTechnique.method_calls[method.original_index];
    }
    method.class_context = oldValue.class_context;
    method.parameters = oldValue.parameters;
  };

  // Create a new technique stub
  var newTech = {
      "method_calls" : []
    , "name"         : ""
    , "description"  : ""
    , "version"      : "1.0"
    , "bundle_name"  : undefined
    , "bundle_args"  : []
    , "parameter"    : []
    , "resources"    : []
  };

  $scope.newTechnique = function() {
    if($scope.selectedTechnique === undefined || $scope.selectedTechnique.bundle_name){
      $scope.checkSelect(newTech, $scope.selectTechnique);
    }else{
      $scope.selectedTechnique = newTech
    }
    $scope.toggleDisplay(false)
  };


  // Utilitary methods on Method call

  $scope.getMethodName = function(method_call) {
    if (method_call && method_call.method_name in $scope.generic_methods ) {
      return $scope.generic_methods[method_call.method_name].name;
    }
    return method_call.method_name;
  };

  $scope.getMethodBundleName = function(method_call) {
    if (method_call && method_call.method_name in $scope.generic_methods ) {
      return $scope.generic_methods[method_call.method_name].bundle_name;
    }
    return method_call.method_name;
  };

  // Get the desciption of a method call in definition of the generic method
  $scope.getMethodDescription = function(method_call) {
    if (method_call && method_call.method_name in $scope.generic_methods ) {
      return $scope.generic_methods[method_call.method_name].description;
    }
    return "";
  };

  $scope.getMethodDocumentation = function(method_call) {
    if (method_call && method_call.method_name in $scope.generic_methods ) {
      return $scope.generic_methods[method_call.method_name].documentation;
    }
    return "";
  };

  $scope.methodUrl = function(method) {
    var name = method.bundle_name !== undefined ? method.bundle_name : $scope.getMethodBundleName(method);
    if (usingRudder) {
      return "/rudder-doc/reference/current/reference/generic_methods.html#"+name
    }
    return "http://www.ncf.io/pages/reference.html#"+name;
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
      }
      return res ||  $scope.isUnchanged($scope.selectedTechnique)
    }
    return false;
  }

  // Technique actions
  // clone method of specified index and add it right after index
  $scope.cloneMethod= function(index) {
    var newMethod = angular.copy($scope.selectedTechnique.method_calls[index]);
    delete newMethod.original_index;
    $scope.selectedTechnique.method_calls.splice(index+1, 0, newMethod);
    $scope.ui.selectedMethods.push(newMethod);
  }

  // Remove method on specified index
  $scope.removeMethod= function(index) {
    var methodIndex = $scope.getSelectedMethodIndex($scope.selectedTechnique.method_calls[index]);
    $scope.ui.selectedMethods.splice(methodIndex, 1);
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
    $scope.ui.editForm.$setPristine();
    $scope.selectedTechnique=angular.copy($scope.originalTechnique);
    $scope.resetFlags();
    $scope.$broadcast('endSaving');
  };

  // Delete a technique
  $scope.deleteTechnique = function() {
    $http.delete("/rudder/secure/api/techniques/"+$scope.selectedTechnique.bundle_name+"/"+$scope.selectedTechnique.version, {params : {force : false}}).
      success(function(data, status, headers, config) {

        ngToast.create({ content: "<b>Success!</b> Technique '" + $scope.originalTechnique.name + "' deleted!"});

        var index = $scope.techniques.findIndex(function(t){return t.bundle_name === $scope.originalTechnique.bundle_name});
        $scope.techniques.splice(index,1);
        $scope.ui.selectedMethods = [];
        $scope.selectedTechnique  = undefined;
        $scope.originalTechnique  = undefined;
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

    // Get methods used for our technique so we can send only those methods to Rudder api instead of sending all methods like we used to do...
    var usedMethodsSet = new Set();
    ncfTechnique.method_calls.forEach(
      function(m) {
        usedMethodsSet.add($scope.generic_methods[m.method_name]);
      }
    );
    var usedMethods = Array.from(usedMethodsSet);

    var reason = "Updating Technique " + technique.name + " using the Technique editor";

    var data = { "technique": ncfTechnique, "methods":usedMethods, "reason":reason }

    // Function to use after save is done
    // Update selected technique if it's still the same technique
    // update technique from the tree
    var saveSuccess = function(data, status, headers, config) {

      // Technique may have been modified by ncf API
      // Not good anymore, but maybe
      ncfTechnique = data.data.techniques.technique;

      // Transform back ncfTechnique to UITechnique, that will make it ok
      //
      var savedTechnique = toTechUI(ncfTechnique);

      var invalidParametersArray = [];
      // Iterate over each parameters to ensure their validity
      ncfTechnique.method_calls.forEach(
        function(m) {
          m.parameters.forEach(
            function(parameter) {
              var value = parameter.value;
              if (detectIfVariableIsInvalid(value)) {
                invalidContent.push("<div>In generic method: <b>" +m.component + "</b>,  parameter: " + parameter.name + " has incorrect value " + value+"</div>");
              }
            }
          )
        }
      );

      if (invalidParametersArray.length > 0) {
        ngToast.create({ content: "<b>Caution! </b> Some variables might be invalid (containing $() without . nor /):<br/>" + invalidParametersArray.join("<br/>"), className: 'warning'});
      }
      ngToast.create({ content: "<b>Success! </b> Technique '" + technique.name + "' saved!"});

      // Find index of the technique in the actual tree of technique (look for original technique)
      var index = $scope.techniques.findIndex(function(t){return t.bundle_name == origin_technique.bundle_name});
      if ( index === -1) {
       // Add a new techniuqe
       $scope.techniques.push(savedTechnique);
      } else {
       // modify techique in array
       $scope.techniques[index] = savedTechnique;
      }

      // Update technique if still selected
      if (angular.equals($scope.originalTechnique, origin_technique)) {
        // If we were cloning a technique, remove its 'clone' state
        savedTechnique.isClone    = false;
        $scope.originalTechnique  = angular.copy(savedTechnique);
        $scope.selectedTechnique  = angular.copy(savedTechnique);
        // We will lose the link between the selected method and the technique, to prevent unintended behavior, close the edit method panel
        $scope.ui.selectedMethods = [];
      }

      updateResources();
      $scope.resetFlags();
    }

    var saveError = function(action, data) {
      return handle_error("while "+action+" Technique '"+ data.technique.name+"'")
    }

    // Actually save the technique through API
    if ($scope.originalTechnique.bundle_name === undefined) {
      $http.put("/rudder/secure/api/techniques", data).success(saveSuccess).error(saveError("creating", data)).finally(function(){$scope.$broadcast('endSaving');});
    } else {
      $http.post("/rudder/secure/api/techniques", data).success(saveSuccess).error(saveError("updating", data)).finally(function(){$scope.$broadcast('endSaving');});
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
        , editForm  : function() { return  $scope.ui.editForm }
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
  $scope.checkErrorParameters = function(parameters){
    var result = false;
    for(var i=0; i<parameters.length; i++) {
      if(parameters[i].$errors && parameters[i].$errors.length > 0){
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

  $scope.getTooltipContent = function(method){
    var description = "";
    var deprecatedMessage = "";
    description = $scope.getMethodDescription(method)!= "" ? "<div class='description'>"+$scope.getMethodDescription(method)+"</div>" : "";
    if(method.deprecated || $scope.isDeprecated(method.method_name)){
      deprecatedMessage = "<div class='deprecated-info'><div>This generic method is <b>deprecated</b>.</div> <div class='deprecated-message'><b>↳</b>"+method.deprecated+"</div></div>";
    }
    var tooltipContent = "<div>" + description + deprecatedMessage + "</div>";
    return $sce.trustAsHtml(tooltipContent);
  }

  $scope.getStatusTooltipMessage = function(method){
    var msg;
    if($scope.checkErrorParameters(method.parameters)){
      msg = "Invalid parameters"
    }else if ($scope.checkMissingParameters(method.parameters)){
      msg = "Required parameters missing"
    }else if ($scope.canResetMethod(method)){
      msg = "This generic method has been edited"
    }else{
      msg = ""
    }
    return msg;
  }

  $scope.toggleDisplay = function(showTechniques){
    $scope.ui.showTechniques = showTechniques;
  }

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

app.config(['fileManagerConfigProvider', function (config) {
  var apiPath = '/rudder/secure/api/techniques/';
  var defaults = config.$get();

  	config.set({
    appName : 'resources',
    listUrl             : apiPath,
    uploadUrl           : apiPath,
    renameUrl           : apiPath,
    copyUrl             : apiPath,
    moveUrl             : apiPath,
    removeUrl           : apiPath,
    editUrl             : apiPath,
    getContentUrl       : apiPath,
    createFolderUrl     : apiPath,
    downloadFileUrl     : apiPath,
    downloadMultipleUrl : apiPath,
    compressUrl         : apiPath,
    extractUrl          : apiPath,
    permissionsUrl      : apiPath,
    isEditableFilePattern : /.*/,
    tplPath             : '/ncf-builder/templates',
    allowedActions: angular.extend(defaults.allowedActions, {
      compress: false,
      compressChooseName: false,
      preview : true,
      edit: true,
      extract: false
    })
  });
}]);

function hideFileManager(){
  $("angular-filemanager").remove();
}
