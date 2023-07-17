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