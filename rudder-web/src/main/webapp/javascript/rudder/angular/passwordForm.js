/*
*************************************************************************************
* Copyright 2016 Normation SAS
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

var passwordModule = angular.module("password", [])
passwordModule.controller("passwordController", function($scope) {
  // Declare Variables

  // Current password, and 'other passwords' defined if there is a 'slave' field, displayedPass is the pass we currently display
  $scope.current = {password : undefined, hash : "md5", show : false};
  $scope.otherPasswords = undefined;
  $scope.displayedPass = $scope.current.password;

  // New password if we want to change it
  $scope.newPassword = { password : undefined, hash: "md5", show: false};
  // Possible hashes defined for this password input
  $scope.hashes = {};

  $scope.action = "keep";
  $scope.formType = "withHashes";
  $scope.canBeDeleted = false;

  // Result (the value that will be sent back to Lift form), initialized as undefined, but will be update directly and on every change of action and the new password (so will be changed on init)
  $scope.result = undefined;
  updateResult()

  $scope.$watch('action',updateResult);
  $scope.$watch('newPassword.password',updateResult);
  $scope.$watch('newPassword.hash',updateResult);

  function updateResult () {
    // Keep and delete, use current password as base
    var result = $scope.current
    if ($scope.action === "change") {
      result = $scope.newPassword;
    }
    // Action will allow to differentiate between 'delete' and 'keep' and is used for 'change' too
    result.action = $scope.action
    $scope.result = JSON.stringify(result);
  }

  // init function, That will be called from 'outside' angular scope to set with values sent from the webapp
  $scope.init = function(current, currentHash, hashes, otherPasswords, canBeDeleted) {
    $scope.current.password=current;
    $scope.hashes = hashes;
    $scope.current.hash = currentHash;
    $scope.displayedPass = $scope.current.password;
    $scope.newPassword.hash=Object.keys(hashes)[0];
    if (current === undefined) {
      $scope.action = "change";
    }
    $scope.otherPasswords = otherPasswords;
    $scope.canBeDeleted = canBeDeleted;
  }

  $scope.displayCurrentHash = function() {
    if ($scope.current.hash === "plain") {
      return "Clear text password";
    } else if ($scope.current.hash === "pre-hashed") {
      return "Pre hashed password";
    } else {
      return $scope.hashes[$scope.current.hash] + " hash";
    }
  }

  $scope.changeDisplayPass = function(password) {
    $scope.displayedPass = password;
  }

  $scope.passwordForm = function(formType) {
    if(formType === "withHashes") {
      $scope.newPassword.hash=Object.keys($scope.hashes)[0];
    } else if (formType === "clearText") {
      $scope.newPassword.hash="plain";
    } else if (formType === "preHashed") {
      $scope.newPassword.hash="pre-hashed";
    } else if (formType === "script") {
      $scope.newPassword.hash="script";
    }
    $scope.formType=formType;
  }

  $scope.changeAction = function(action) {
    $scope.action = action;
    if (action === "change") {
      if ($scope.current.hash === "script") {
        $scope.formType="script";
        $scope.newPassword.password=$scope.current.password;
      } else if ($scope.current.hash === "pre-hashed") {
        $scope.formType="preHashed";
      } else if ($scope.current.hash === "plain") {
        $scope.formType="clearText";
      } else {
        $scope.formType="withHashes";
      }
      $scope.newPassword.hash = $scope.current.hash;
    }
  }

  // Do not dissplay current password if undefined or if you want to delete password
  $scope.displayCurrent = function() {
    return $scope.current.password !== undefined && $scope.action !== 'delete';
  }

});
