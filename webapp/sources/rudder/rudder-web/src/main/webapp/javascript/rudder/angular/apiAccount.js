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

var accountManagement = angular.module('accountManagement', ['datatables', 'ui.toggle']);

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

/*
 * This module expects the following JS variables to be defined:
 * - apiPath: context path to use
 */
accountManagement.controller('AccountCtrl', function ($scope, $http, DTOptionsBuilder, DTColumnDefBuilder) {

  $scope.aclPlugin = false;
  $scope.authFilter = undefined;

  // currently updated api account
  $scope.myNewAccount = undefined;

  $scope.getAccounts = function() {
    $http.get(apiPath).
    then(function (response) {
      $scope.aclPlugin = response.data.data.aclPluginEnabled;
      $scope.accounts = response.data.data.accounts;
      return $scope.accounts
    }, function(response) {
      $scope.errorTable = response.data;
      return $scope.errorTable
    });
  }

  $scope.deleteAccount = function(account) {
    $http.delete(apiPath + '/'+account.token).
        success(function(data, status, headers, config) {
          $scope.accounts = $scope.accounts.filter(acc => acc.id !== account.id);
          $scope.myOldAccount = undefined;
        }).
        error(function(data, status, headers, config) {
         $scope.errorTable = data;
        });
        $('#oldAccountPopup').bsModal('hide');
  }

  $scope.regenerateAccount = function(account) {
     $http.post(apiPath + '/'+account.token+"/regenerate").
       success(function(data, status, headers, config) {
         var newAccount = data.data.accounts[0];
         account.token = newAccount.token;
       }).
       error(function(data, status, headers, config) {
         $scope.errorTable = data;
       });
      $('#oldAccountPopup').bsModal('hide');
  }

  $scope.filterAccount = function (value,index,array) {
    if ($scope.authFilter === undefined || $scope.authFilter === "" ) {
      return true;
    } else {
      return value.authorizationType === $scope.authFilter;
    }
  }

  $scope.addAccount = function() {
    var now = new Date();
    now.setMonth(now.getMonth()+1);
    var now = now.getFullYear()+"-"+(now.getMonth()+1)+"-"+now.getDate()+" "+now.getHours()+":"+now.getMinutes();
    var newAccount = { id : "", name : "", token: undefined, enabled : true, description : "", authorizationType : "rw",expirationDateDefined : true, expirationDate : now}
    $scope.myNewAccount = newAccount;
    $('#newAccountPopup').bsModal('show');
  }


  $scope.options = DTOptionsBuilder.newOptions().
    withPaginationType('full_numbers').
    withDOM('<"dataTables_wrapper_top newFilter"f>t<"dataTables_wrapper_bottom"lip>').
    withLanguage({
          "searchPlaceholder": 'Filter',
          "search": ''
    }).
    withOption("bLengthChange", true).
    withOption("lengthMenu", [ [10, 25, 50, 100, 500, 1000, -1], [10, 25, 50, 100, 500, 1000, "All"] ]).
    withOption("pageLength", 25).
    withOption("jQueryUI", true).
    withOption("bAutoWidth", false)

  $scope.columns = [
    DTColumnDefBuilder.newColumnDef(0).withOption("sWidth",'20%'),
    DTColumnDefBuilder.newColumnDef(1).withOption("sWidth",'30%'),
    DTColumnDefBuilder.newColumnDef(2).withOption("sWidth",'30%'),
    DTColumnDefBuilder.newColumnDef(3).withOption("sWidth",'20%').notSortable()
  ];

  $scope.popupCreation = function(account,index) {
    $scope.myNewAccount = angular.copy(account);
    $scope.myNewAccount.index = index;
    $scope.myNewAccount.oldName = account.name;
    $("#newAccountName").focus();
    $('#newAccountPopup').bsModal('show');
    return account;
  };

  $scope.popupDeletion = function(account, action, actionName) {
    $scope.myOldAccount = account;
    $scope.myOldAccount.action = function(a) { return action(account); };
    $scope.myOldAccount.actionName = actionName;
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
   if(account.token === undefined ) {
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
         $scope.accounts[index] = newAccount
         //$.extend($scope.myNewAccount, newAccount);
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
   }
 }

  $scope.authorisationName = function(authorizationKind) {
    var result = authorizationKind;
    switch (authorizationKind) {
      case "ro":
        result = "Read only";
        break
      case "rw":
        result = "Full access";
        break
      case "none":
        result = "No access";
        break
      case "acl":
        result = "Custom ACLs";
        break
      default:
        result = authorizationKind;
    }
    return result
  }

  $scope.formTitle = function() {
    if ($scope.myNewAccount === undefined || $scope.myNewAccount.token === undefined) {
      return "Create a new Account";
    } else {
      return  "Update account '"+$scope.myNewAccount.oldName+"'";
    }
  }

  $scope.getAccounts();

  $scope.defineActionName = function(){
      if($scope.myNewAccount === undefined || $scope.myNewAccount.token === undefined) {
        return "Create"
      } else {
        return "Save"
      }
  }
} );

$( document ).ready(function() {
  angular.bootstrap(document.getElementById('accountManagement'), ['accountManagement']);
  $("#newAccount-expiration").datetimepicker({dateFormat:'yy-mm-dd', timeFormat: 'HH:mm', timeInput: true});
});
