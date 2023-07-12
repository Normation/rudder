/*
*************************************************************************************
* Copyright 2023 Normation SAS
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

// Dict containing all directive input data
var directiveInputs = new Object();
var passwordForms   = new Object();

function newInputText(id, value, prefix, featureEnabled){
  // New input data
  var newInput = {
    prefix  : prefix // format of the input value
  , value   : value  // input value
  , feature : featureEnabled // determines if the features Text/Js is enabled, if not, the input is just a regular input
  }

  var inputContainer   = $("#" + id);
  var inputResult      = $("#" + id + "-value");
  var featureContainer = inputContainer.find(".input-feature");
  var inputElement     = inputContainer.find(".form-control");
  var btnFeature       = inputContainer.find(".dropdown-toggle");
  var dropdownFeature  = inputContainer.find(".dropdown-menu");

  // Hide Text/Js toggle if feature is disabled
  if(!featureEnabled){
    $(featureContainer).hide();
  }

  // Add this new input to dict, to track changes
  directiveInputs[id] = newInput;

  // Update inputs value and prefix
  inputElement.val(value);
  var prefixName = prefix === "" ? "Text" : "JS";
  btnFeature.find(".prefix-selected").text(prefixName);
  if (featureEnabled) {
    inputResult.val(newInput.prefix + value);
  } else {
    inputResult.val(value);
  }
}

// onClick event function
function updatePrefix(element, prefix){
  var inputContainer = $(element).parents(".directive-input-group");
  var formId         = $(inputContainer).get(0).id;
  var btnFeature     = inputContainer.find(".dropdown-toggle");
  var inputValue     = inputContainer.find(".form-control").get(0);
  var prefixName     = prefix === "" ? "Text" : "JS";
  // Update prefix button text and model
  btnFeature.find(".prefix-selected").text(prefixName);
  directiveInputs[formId].prefix = prefix;
  updateResult(inputValue);
}

// onInput event function
function updateResult(element){
  var inputContainer = $(element).parents(".directive-input-group");
  var formId         = $(inputContainer).get(0).id;
  var value          = element.value;
  var inputModel     = directiveInputs[formId];
  var newValue       = inputModel.feature ? (inputModel.prefix + value) : value;
  var inputResult    = $("#" + formId + "-value");
  inputResult.val(newValue);
}


// === Password field
class PasswordForm {
  // Current password, and 'other passwords' defined if there is a 'slave' field, displayedPass is the pass we currently display
  current =
    { password : undefined
    , hash     : "md5"
    , show     : false
    , isScript : false
    };

  otherPasswords = undefined;
  displayedPass  = undefined;

  // New password if we want to change it
  newPassword =
    { password : undefined
    , hash     : "md5"
    , show     : false
    , isScript : false
    };

  // Possible hashes defined for this password input
  hashes        = {};
  #defaultHash  = undefined;

  // Default value
  action        = "keep";
  formType      = "withHashes";
  canBeDeleted  = false;
  scriptEnabled = false;

  // Result (the value that will be sent back to Lift form), initialized as undefined, but will be update directly and on every change of action and the new password (so will be changed on init)
  result = undefined;

  formId = "";

  constructor(currentValue, currentHash, isScript, currentAction, hashes, otherPasswords, canBeDeleted, scriptEnabled, previousHash, previousAlgo, previousIsScript, formId) {
    console.log(currentHash)
    this.defaultHash  = currentHash;

    if (currentAction === "keep") {
      this.current.password = currentValue;
      this.current.hash     = currentHash;
      this.current.isScript = isScript;

    } else if (currentAction === "change") {
      this.newPassword.password = currentValue;
      this.newPassword.hash     = currentHash;
      this.newPassword.isScript = isScript;

      if (isScript) {
        this.formType = "script";
      } else if (currentHash === "pre-hashed") {
        this.formType = "preHashed";
      } else if (currentHash === "plain") {
        this.formType = "clearText";
      } else {
        this.formType = "withHashes";
      }

      this.current.password = previousPass;
      this.current.hash     = previousHash;
      this.current.isScript = previousIsScript;
    }

    this.hashes         = hashes;
    this.displayedPass  = this.current.password;
    this.action         = currentAction === undefined ? "change" : currentAction;
    this.otherPasswords = otherPasswords;
    this.canBeDeleted   = canBeDeleted;
    this.scriptEnabled  = scriptEnabled;

    this.formId         = formId;
  }

  updateView() {
    console.log("updating view")
    updatePasswordFormView(this.formId);
  }

  updateResult() {
    // Keep and delete, use current password as base
    let result = this.action === "change" ? Object.assign({}, this.newPassword) : Object.assign({}, this.current)

    if (result.hash === "plain" && result.isScript) {
      result.password = "evaljs:" + result.password;
    }

    // Action will allow to differentiate between 'delete' and 'keep' and is used for 'change' too
    result.action = this.action
    this.result   = JSON.stringify(result);

    // Update html
    this.updateView();
  }

  displayCurrentHash() {
    if (this.current.hash === "plain") {
      return "Clear text password";
    } else if (this.current.hash === "pre-hashed") {
      return "Pre hashed password";
    } else {
      return this.hashes[this.current.hash] + " hash";
    }
  }

  changeDisplayPass(password) {
    this.displayedPass = password;
    this.updateView();
  }

  passwordType(formType) {
    this.newPassword.isScript = false;
    if(formType === "withHashes") {
      // If no hash was set put it to default hash
      this.newPassword.hash = this.defaultHash;
    } else if (formType === "clearText") {
      this.newPassword.hash = "plain";
    } else if (formType === "preHashed") {
      this.newPassword.hash = "pre-hashed";
    } else if (formType === "script") {
      this.newPassword.hash = "plain";
      this.newPassword.isScript = true;
    }
    this.formType = formType;

    console.log("-- Change form type & update result & view");
    this.updateResult();
  }

  changeAction(action) {
    this.action = action;
    if (action === "change") {
      if (this.current.isScript) {
        this.formType = "script";
        this.newPassword = this.current;
      } else if (this.current.hash === "pre-hashed") {
        this.formType = "preHashed";
      } else if (this.current.hash === "plain") {
        this.formType = "clearText";
      } else {
        this.formType = "withHashes";
      }
      this.newPassword.hash = this.current.hash;
    }
    console.log("-- Change action & update result & view");
    this.updateResult();
  }

  // Do not display current password if undefined or if you want to delete password
  displayCurrent() {
    return this.current.password !== undefined && this.action !== 'delete';
  }

  revealPassword(passwd){
    if (passwd === "current"){
      this.current.show = !this.current.show;
    }else{
      this.newPassword.show = !this.newPassword.show;
    }
    this.updateView();
  }
}


function initPasswordFormEvents(formId){
  // Get passwordForm data
  var passwordForm  = passwordForms[formId];
  if (passwordForm === undefined) return false;

  var formContainer = $('#' + formId);

  formContainer.find(".btn.reveal-password").on('click', function(){
    var data = $(this).attr('data-reveal');
    passwordForm.revealPassword(data);
  });

  // Init buttons that change the password type
  formContainer.find("[data-form]").on('click', function(){
    var type = $(this).attr('data-form');
    passwordForm.passwordType(type);
  });

  // Init buttons that change the current action
  formContainer.find("[data-action]").on('click', function(){
    var action = $(this).attr("data-action");
    passwordForm.changeAction(action);
  });

  // Init passwords dropdown list
  var btnToggle = formContainer.find(".dropdown-toggle");
  var ulToggle  = formContainer.find(".dropdown-menu");
  if(passwordForm.otherPasswords !== undefined){
    var li = $("<li>");
    var a  = $("<a>").text("Default").on('click', function(){
      passwordForm.changeDisplayPass(passwordForm.otherPasswords[passwordForm.current.password]);
    });
    li.append(a);
    ulToggle.append(li)
    for (pwd in passwordForm.otherPasswords){
      li = $("<li>");
      a  = $("<a>").text(pwd).on('click', function(){
        passwordForm.changeDisplayPass(passwordForm.otherPasswords[pwd]);
      });
      li.append(a);
      ulToggle.append(li)
    }
  }else{
    btnToggle.hide();
  }

  // Update model when password changes
  formContainer.find(".input-new-passwd").on("input", function(){
    var newVal = this.value;
    // TODO : Check
    //passwordForm.current.password = newVal;
    //passwordForm.displayedPass    = newVal;
    console.log("-- Update value: "+ newVal)
  });

  // Hash algorithm select
  var selectHash = formContainer.find(".new-password-hash");
  var hash, opt;
  for (hash in passwordForm.hashes){
    opt = $("<option />").text(passwordForm.hashes[hash]).val(hash);
    if (hash === passwordForm.newPassword.hash){
      opt.attr("selected", true);
    }
    selectHash.append(opt);
  }

  //ng-model="newPassword.hash" ng-options="prefix as hash for (prefix, hash) in hashes"
}

function updatePasswordFormView(formId){
  // Get passwordForm data
  var passwordForm = passwordForms[formId];
  if (passwordForm === undefined) return false;

  var formContainer = $('#' + formId);
  formContainer.find('.action-section').hide();

  var actionSection = passwordForm.current.password === undefined ? "change" : passwordForm.action;
  var current       = passwordForm.current

  var passwdContainerClass = current.isScript ? ".is-script" : ".is-passwd";
  var passwdContainer = formContainer.find(passwdContainerClass);

  formContainer.find(".current-password").show();

  switch(actionSection){
    case "keep" :
      passwdContainer.show();
      if(current.isScript){
        formContainer.find(".is-passwd").hide();
        passwdContainer = formContainer.find(".is-script").show();
      } else {
        // Display current hash
        formContainer.find("[current-hash]").html(passwordForm.displayCurrentHash());
        passwdContainer = formContainer.find(".is-passwd").show();
        formContainer.find(".is-script").hide();
      }
      // Display current password
      $(".input-current-passwd").val(passwordForm.displayedPass);
      break;

    case "delete" :
      formContainer.find(".current-password").hide();
      break;

    case "change" :

      // Update buttons that change the form type
      formContainer.find("[data-form]").each(function(){
        var formType = $(this).attr("data-form");
        if(formType !== "script"){
          $(this).hide();
        }
        $(this).show();
        var btnClass = passwordForm.formType == formType ? "active" : "";
        $(this).removeClass('active').addClass(btnClass);
      });

      // Display the correct form type
      var formClass = "action-";
      formContainer.find('.bloc-action').hide();
      if(passwordForm.formType === "withHashes" || passwordForm.formType === "script"){
        formClass += passwordForm.formType;
      } else {
        if(passwordForm.formType === "clearText"){
          formContainer.find(".cleartext-reveal").show();
        }else{
          formContainer.find(".cleartext-reveal").hide();
        }
        formClass += 'newPassword'
      }
      formContainer.find(".bloc-action."+formClass).show();

      // Update info message
      var infoMsg = passwordForm.formType === 'preHashed' ? "hash" : "password"
      $(".variation").text(infoMsg);

      // Update hash algorithm info
      console.log("============")
      console.log(passwordForm.hashes)
      console.log(passwordForm.newPassword.hash)
      //$(".hash-algorithm").text(passwordForm.hashes[passwordForm.newPassword.hash]);

      break;

    default:
      createErrorNotification("Error while loading password form");
      break;
  }
  // Update the action change buttons
  var btnChange = passwdContainer.find("[data-action='change']");
  var btnKeep   = passwdContainer.find("[data-action='keep']  ");
  var btnDelete = passwdContainer.find("[data-action='delete']");
  if(passwordForm.action === "change"){
    btnChange.hide();
    btnKeep.show();
  }else{
    btnChange.show();
    btnKeep.hide();
  }
  if(passwordForm.canBeDeleted){
    btnDelete.show();
  }else{
    btnDelete.hide();
  }

  // Display the section according to the current action (change/delete/...)
  formContainer.find('.action-section.action-' + actionSection).show();

  // Update reveal password buttons and show/hide the current password value by changing input type (text/password);
  formContainer.find(".reveal-password").each(function(){
    var data = $(this).attr("data-reveal");
    var reveal    = data === "current" ? passwordForm.current.show : passwordForm.newPassword.show
    var iconClass = reveal ? "glyphicon glyphicon-eye-close" : "glyphicon glyphicon-eye-open"
    var inputType = reveal ? "text" : "password";
    $(this).find(".glyphicon").attr("class", iconClass);
    if(data === "current"){
      formContainer.find("input.input-current-passwd").attr("type", inputType);
    }else{
      formContainer.find("input.input-new-passwd").attr("type", inputType);
    }
  });
  return true;
}