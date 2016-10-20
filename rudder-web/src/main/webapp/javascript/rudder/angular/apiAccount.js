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

  $scope.getAccounts = function() {
    $http.get(apiPath).
    then(function (response) {
      $scope.accounts = response.data.data.accounts;
      return $scope.accounts
    }, function(response) {
      $scope.errorTable = response.data;
      return $scope.errorTable
    });
  }


  $scope.deleteAccount = function(account,index) {
    $http.delete(apiPath + '/'+account.token).
        success(function(data, status, headers, config) {
          $scope.accounts.splice(index,1);
          $scope.myNewAccount = undefined;
        }).
        error(function(data, status, headers, config) {
         $scope.errorTable = data;
        });
        $('#oldAccountPopup').bsModal('hide');
  }

  $scope.regenerateAccount = function(account,index) {
     $http.post(apiPath + '/'+account.token+"/regenerate").
       success(function(data, status, headers, config) {
         var newAccount = data.data.accounts[0];
         $scope.accounts[index] = newAccount;
         $.extend($scope.myNewAccount, newAccount);
       }).
       error(function(data, status, headers, config) {
         $scope.errorTable = data;
       });
      $('#oldAccountPopup').bsModal('hide');
  }

$scope.enableButton = function(account,index) {
  var button = $("<button class='btn btn-default'></button>");
  if (account.enabled) {
    button.text('Disabled');
    button.click( function(){
      $scope.$apply(function() {
        account.enabled = false;
        $scope.saveAccount(account,index,false);
      });
    });
  } else {
    button.text('Enabled');
    button.click( function() {
      $scope.$apply(function() {
        account.enabled = true;
        $scope.saveAccount(account,index,false);
      });
    })
  }
  return button;
}

$scope.addAccount = function() {
  var newAccount = { id : "", name : "", token: "not yet obtained", enabled : true, description : ""}
  $scope.myNewAccount = newAccount;
  $('#newAccountPopup').bsModal('show');  
}

//define what each column of the grid
//get from the JSON people
$scope.columnDefs = [
    { "aTargets":[0], "mDataProp": "name", "sWidth": "20%", "sTitle" : "Account Name" }
  , {   "aTargets":[1]
      , "mDataProp": "token"
      , 'bSortable': false
      , "sWidth": "40%", "sTitle" : "Token"
      , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
          var content  = $("<span class='tw-bs'><button class='btn btn-default reload-token'><span class='glyphicon glyphicon-repeat'></span></button></span>")
          content.find('.btn').click( function() {
            $scope.popupDeletion(oData,iRow,$scope.regenerateAccount,"Regenerate token of");
          });
          var stringContent = $("<span>"+sData+"</span>");
          $(nTd).empty();
          $(nTd).prepend(stringContent);
          $(nTd).prepend(content);
      }
    }
  , {   "aTargets"      : [2]
      , "mDataProp"     : "enabled"
      , 'bSortable': false
      , "sWidth": "10%"
        , "sTitle" : "Status"
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
        var color = sData ? "#5cb85c" : "#c9302c";
        var content  = $(" <b>"+$scope.accountDisabled(sData)+"</b>")
        $(nTd).empty();
        $(nTd).attr("style","text-align:center;  background-color:"+color);
        $(nTd).prepend(content);
      }
    }
  , {   "aTargets"      : [3]
      , "mDataProp"     : "enabled"
      , 'bSortable': false
      , "sWidth": "10%"
      
    , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
      $(nTd).addClass("tw-bs action-left");
      var data = oData
      var editButton  = $("<button class='btn btn-default'>Edit</button>");
      editButton.click( function() {
        data.oldName = data.name;
        $scope.popupCreation(data,iRow);
      });
      $(nTd).empty();
      $(nTd).prepend(editButton);
    }
  }
  , {   "aTargets"      : [4]
  , "mDataProp"     : "enabled"
  , 'bSortable': false
  , "sWidth": "10%"
  , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
      $(nTd).addClass("tw-bs action-center");
      var data = oData
      var statusButton = $scope.enableButton(data,iRow);
      $(nTd).empty();
      $(nTd).prepend(statusButton);
    }
}
  , {   "aTargets"      : [5]
  , "mDataProp"     : "enabled"
  , 'bSortable': false
  , "sWidth": "10%"
  , "fnCreatedCell" : function (nTd, sData, oData, iRow, iCol) {
      $(nTd).addClass("tw-bs action-right");
      var data = oData
      var deleteButton  = $("<button class='btn btn-danger'>Delete</button>");
      deleteButton.click( function() {
        $scope.popupDeletion(data,iRow,$scope.deleteAccount,"Delete");
      });
      $(nTd).empty();
      $(nTd).prepend(deleteButton);
    }
}
]

$scope.overrideOptions = {
      "bFilter" : true
    , "bPaginate" : true
    , "bLengthChange": true
    , "sPaginationType": "full_numbers"
    , "oLanguage": {
        "sSearch": ""
    }
    , "aaSorting": [[ 0, "asc" ]]
    , "sDom": '<"dataTables_wrapper_top newFilter"f<"dataTables_refresh">>rt<"dataTables_wrapper_bottom"lip>'
  };

$scope.popupCreation = function(account,index) {
  $scope.$apply(function() {
     $scope.myNewAccount = account;
     // Maybe should use indexOf
     $scope.myNewAccount.index = index;
     $("#newAccountName").focus();
  });
  $('#newAccountPopup').bsModal('show');  

  return account;
};

$scope.popupDeletion = function(account, index, action, actionName) {
  $scope.$apply(function() {
    $scope.myOldAccount = account;
    $scope.myOldAccount.index = index;
    $scope.myOldAccount.action = action;
    $scope.myOldAccount.actionName = actionName;
  });
    $('#oldAccountPopup').bsModal('show');
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
       $('#newAccountPopup').bsModal('hide');
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
       $('#newAccountPopup').bsModal('hide');

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
  
  $scope.getAccounts();
    
  $scope.defineActionName = function(formName){
      if(formName !== undefined) {
        return formName.split(' ')[0];
      }
  }  
} );

$( document ).ready(function() {
  angular.bootstrap(document.getElementById('accountManagement'), ['accountManagement']);
});
