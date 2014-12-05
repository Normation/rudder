var accountManagement = angular.module('accountManagement', ['DataTables']);

var popUpCreated = false;
accountManagement.directive('validEmpty', function() {

  return {
      restrict: 'A'
    , require: 'ngModel'
    , link: function(scope, elm, attrs, ctrl) {
              var validator = function (viewValue) {
                var valid = (scope.newAccount == undefined || viewValue != "");
                ctrl.$setValidity('valid_empty', valid);
                return viewValue;
              }
              ctrl.$parsers.unshift(validator);
              ctrl.$formatters.unshift(validator);
            }
  };
} );
    
accountManagement.controller('AccountCtrl', function ($scope, $http) {

  $http.get(apiPath).
  success(function(data, status, headers, config) {
    $scope.accounts = data.data.accounts;
  }).
  error(function(data, status, headers, config) {
    $scope.errorTable = data;
  });


  $scope.deleteAccount = function(account,index) {
    $http.delete(apiPath + '/'+account.token).
        success(function(data, status, headers, config) {
          $scope.accounts.splice(index,1);
          $scope.myNewAccount = undefined;

          $("#accountGrid").dataTable().fnClearTable();
          $("#accountGrid").dataTable().fnAddData($scope.accounts);
        }).
        error(function(data, status, headers, config) {
         $scope.errorTable = data;
        });
  }

  $scope.regenerateAccount = function(account,index) {
     $http.post(apiPath + '/'+account.token+"/regenerate").
       success(function(data, status, headers, config) {
         var newAccount = data.data.accounts[0];
         $scope.accounts[index] = newAccount;

         $.extend($scope.myNewAccount, newAccount);
         $("#accountGrid").dataTable().fnClearTable();
         $("#accountGrid").dataTable().fnAddData($scope.accounts);
       }).
       error(function(data, status, headers, config) {
         $scope.errorTable = data;
       });
  }

$scope.enableButton = function(account,index) {
if (account.enabled) {
  var button = $("<button style='width: 80px; margin: 0px 10px;'>Disable</button>");
  button.button();
  button.click( function(){
      $scope.$apply(function() {
      account.enabled = false;
      $scope.saveAccount(account,index,false);
      });
    });
  return button;
} else {
  var button = $("<button style='width: 80px; margin: 0px 10px;'>Enable</button>");
    button.button();
    button.click( function() {
      $scope.$apply(function() {
      account.enabled = true;
      $scope.saveAccount(account,index,false);
      });
    })
  return button;
  }
}

$scope.addAccount = function() {
  var newAccount = { id : "", name : "", token: "not yet obtained", enabled : true, description : ""}
  console.log(newAccount);
  $scope.myNewAccount = newAccount;
}

//define what each column of the grid
//get from the JSON people
$scope.columnDefs = [
    { "aTargets":[0], "mDataProp": "name", "sWidth": "20%" }
  , {   "aTargets":[1]
      , "mDataProp": "token"
      , 'bSortable': false
      , "sWidth": "40%"
      , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
          var content  = $("<button style='margin-right:10px; float:left;' class='smallButton'><img style='width:20px; height:20px;' src='"+contextPath+"/images/refresh_reload.png' alt='Regenerate'  /> </button>")
          content.button()
          content.click( function() {
            $scope.popupDeletion(oData,iRow,$scope.regenerateAccount,"Regenerate token of");
          });
          var stringContent = $("<div style='padding-top:7px;' >"+sData+"</div>");
          $(nTd).empty();
          $(nTd).prepend(stringContent);
          $(nTd).prepend(content);
      }
    }
  , {   "aTargets"      : [2]
      , "mDataProp"     : "enabled"
      , 'bSortable': false
      , "sWidth": "10%"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
      var color = sData ? "#CCFFCC" : "#FF6655";
          var content  = $(" <b>"+$scope.accountDisabled(sData)+"</b>")
      $(nTd).empty();
          $(nTd).attr("style","text-align:center;  background-color:"+color);
          $(nTd).prepend(content );
      }
    }
  , {   "aTargets"      : [3]
      , "mDataProp"     : "enabled"
      , 'bSortable': false
      , "sWidth": "10%"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var data = oData
    var editButton  = $("<button style='width: 80px; margin: 0px 10px;'>Edit</button>")
        editButton.button();
        editButton.click( function() {
            data.oldName = data.name;
            $scope.popupCreation(data,iRow);
        });
      $(nTd).empty();
      $(nTd).attr("style","border-right:none");
        $(nTd).prepend(editButton);
    }
    }
  , {   "aTargets"      : [4]
  , "mDataProp"     : "enabled"
  , 'bSortable': false
  , "sWidth": "10%"
  , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
    var data = oData
    var statusButton = $scope.enableButton(data,iRow);
  $(nTd).empty();
  $(nTd).attr("style","border-right:none;border-left:none;");
  $(nTd).prepend(statusButton);
  }
}
  , {   "aTargets"      : [5]
  , "mDataProp"     : "enabled"
  , 'bSortable': false
  , "sWidth": "10%"
  , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
    var data = oData
    var deleteButton  = $("<button style='width: 80px; margin: 0px 10px;' class='dangerButton'>Delete</button>")
    deleteButton.button()
    deleteButton.click( function() {
      $scope.popupDeletion(data,iRow,$scope.deleteAccount,"Delete");
    });
  $(nTd).empty();
  $(nTd).prepend(deleteButton);
  $(nTd).attr("style","border-left:none;");
  }
}
]

$scope.overrideOptions = {
  "asStripeClasses": [ 'color1', 'color2' ],
    "bAutoWidth": false,
    "bFilter" : true,
    "bPaginate" : true,
    "bInfo" : false,
    "bJQueryUI": true,
    "bLengthChange": true,
    "sPaginationType": "full_numbers",
    "oLanguage": {
        "sZeroRecords": "No matching API accounts!",
        "sSearch": ""
      },
    "sDom": '<"dataTables_wrapper_top"fl>rt<"dataTables_wrapper_bottom"ip>'
};

$scope.popupCreation = function(account,index) {
  $scope.$apply(function() {
     $scope.myNewAccount = account;
     // Maybe should use indexOf
     $scope.myNewAccount.index = index;
     $("#newAccountName").focus();
  });
  return account;
};

$scope.popupDeletion = function(account, index, action, actionName) {
  $scope.$apply(function() {
    $scope.myOldAccount = account;
    $scope.myOldAccount.index = index;
    $scope.myOldAccount.action = action;
    $scope.myOldAccount.actionName = actionName;
  });
  return account;
};

 $scope.closePopup = function () {
   $scope.myNewAccount = undefined;
   $scope.errorPopup = undefined;
 }

 $scope.checkAndSaveAccount = function (account,index,form) {
   if (form.$valid) {
     $scope.saveAccount(account,index,true);
   }
 }
 $scope.saveAccount = function(account,index,isPopup) {
   if (isPopup)  {
     $scope.errorPopup = undefined;
   } else {
     $scope.errorTable = undefined;
   }
   if(account.token == "not yet obtained" ) {
     $http.put(apiPath,account).
     success( function(data, status, headers, config) {
       var newAccount = data.data.accounts[0];
       var newLength = $scope.accounts.push(newAccount);
       newAccount.index = newLength - 1 ;
       $scope.myNewAccount = undefined;
       $("#accountGrid").dataTable().fnClearTable();
       $("#accountGrid").dataTable().fnAddData($scope.accounts);
     }).
     error(function(data, status, headers, config) {
       if (isPopup)  {
         $scope.errorPopup = data;
       } else {
         $scope.errorTable = data;
       }
     });
   } else {
     $http.post(apiPath + '/'+account.token,account).
     success(function(data, status, headers, config) {
       var newAccount = data.data.accounts[0];
       $scope.accounts[index] = newAccount;
       $.extend($scope.myNewAccount, newAccount);
       $scope.myNewAccount = undefined;
       $("#accountGrid").dataTable().fnClearTable();
       $("#accountGrid").dataTable().fnAddData($scope.accounts);
     }).
     error(function(data, status, headers, config) {
       if (isPopup)  {
         $scope.errorPopup = data;
       } else {
         $scope.errorTable = data;
       }
     });
   }
 }

  $scope.formTitle = function(account) {
    if (account!= undefined && account.index!=undefined) {
        return  "Update account '"+account.oldName+"'";
    } else {
      return "Create a new Account";
    }
  }
  $scope.accountDisabled = function(isEnabled) {
    if(isEnabled) return "Enabled";
    else return "Disabled";
  }
} );