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

var app = angular.module('filters', []);

app.controller('filterTagDirectiveCtrl', function ($scope, $http, $location, $timeout) {
  $scope.searchStr = "";
  $scope.showFilters = false;
  $scope.only = {"key":false , "value":false};
  $scope.newTag = {"key":""  , "value":""};
  $scope.tags = [];
  $scope.isEmptyOrBlank = function(str){
    return (!str || 0 === str.length || /^\s*$/.test(str));
  }
  $scope.clearSearch = function(){
    $scope.searchStr = "";
    clearSearchFieldTree('#activeTechniquesTree');
    $scope.searchTree('#activeTechniquesTree');
  }
  $scope.resetNewTag = function(){
    $scope.newTag = {"key":"" , "value":""};
  }
  $scope.modifyTag = function(index,tag){
    $scope.toggleFilter('#helpTag',true);
    $scope.newTag.key   = tag.key;
    $scope.newTag.value = tag.value;
  }
  $scope.addTag = function(treeId, tag){
	var newTag = tag ? tag : $scope.newTag;
    var alreadyExist = false;
    for(var i=0 ; i<$scope.tags.length ; i++){
      if((newTag.key==$scope.tags[i].key)&&(newTag.value==$scope.tags[i].value)){
        alreadyExist = true;
        $scope.tags[i].alreadyExist = true;
        (function(i){
          $timeout(function() {
            $scope.tags[i].alreadyExist = false;
          }, 200);
        })(i);
      }
    }
    if(!alreadyExist){
      $scope.tags.push(newTag);
      $scope.searchTree(treeId);
      if(!tag){
        $scope.resetNewTag();
        $scope.updateTag();
      }else{
        $scope.updateFilter();
      }
      $timeout(function() {
        adjustHeight('#activeTechniquesTree');
      },0);
    }
  }
  $scope.updateTag = function(){
    var scopeDirectiveTag = angular.element($('[ng-controller="tagsDirectiveCtrl"]')).scope();
    if(scopeDirectiveTag){
      scopeDirectiveTag.$apply(function(){
        $scope.updateFunction(scopeDirectiveTag);
      })
    }
  }
  $scope.updateFilter = function(){
    var scopeDirectiveTag = angular.element($('[ng-controller="tagsDirectiveCtrl"]')).scope();
    $scope.updateFunction(scopeDirectiveTag);
  }
  $scope.updateFunction = function(scopeDirectiveTag){
    var key,val;
    if(scopeDirectiveTag.tags && scopeDirectiveTag.tags.length>0){
      for(var i=0 ; i<scopeDirectiveTag.tags.length ; i++){
        key   = false;
        val = false;
        for(var j=0 ; j<$scope.tags.length ; j++){
          if(!$scope.only.key && !$scope.only.value){
            if(((scopeDirectiveTag.tags[i].key == $scope.tags[j].key)||($scope.tags[j].key==""))&&((scopeDirectiveTag.tags[i].value == $scope.tags[j].value)||($scope.tags[j].value==""))){
              key = true;
              val = true;
            }
          }else{
            if($scope.only.key && ((scopeDirectiveTag.tags[i].key == $scope.tags[j].key)||($scope.tags[j].key==""))){
        	  key = true;
            }
            if($scope.only.value && ((scopeDirectiveTag.tags[i].value == $scope.tags[j].value)||($scope.tags[j].value==""))){
              val = true;
            }
          }
        }
        scopeDirectiveTag.tags[i].match = {
          'key'   :key,
          'value' :val
        }
      }
    }
  }
  $scope.removeTag = function(index, apply){
	var tag = $scope.tags[index];
    $scope.tags.splice(index, 1);
    $scope.searchTree('#activeTechniquesTree');
    $timeout(function() {
      adjustHeight('#activeTechniquesTree');
    },0);
    if(apply){
      $scope.updateFilter();
    }else{
      $scope.updateTag();
    }
  }
  $scope.clearAllTags = function(){
    $scope.tags = [];
    $scope.updateTag();
    $scope.searchTree('#activeTechniquesTree');
  }
  $scope.toggleFilter = function(chevron, forceOpen){
    if(forceOpen){
      $('#stateFilterTag').addClass('in');
      $('#form-tag').addClass('in');
    }else{
      $('#stateFilterTag').toggleClass('in');
      $('#form-tag').toggleClass('in');
    }
    var state = forceOpen ? forceOpen : !$scope.showFilters;
    $scope.showFilters = state;
    if($scope.showFilters){
      $('.input-key').focus();
    }
    adjustHeight('#activeTechniquesTree');
  }
  $scope.onlyKey = function(elem){
    var button = $(elem.currentTarget);
    button.toggleClass('active');
    $scope.only.key = !$scope.only.key;
    $scope.only.value = false;
    $scope.searchTree('#activeTechniquesTree');
    $scope.updateTag();
  }
  $scope.onlyValue = function(elem){
    var button = $(elem.currentTarget);
    button.toggleClass('active');
    $scope.only.value = !$scope.only.value;
    $scope.only.key = false;
    $scope.searchTree('#activeTechniquesTree');
    $scope.updateTag();
  }
  $scope.onlyAll = function(elem){
    var button = $(elem.currentTarget);
    button.addClass('active');
    $scope.only.key   = false;
    $scope.only.value = false;
    $scope.searchTree('#activeTechniquesTree');
    $scope.updateTag();
  }

  $scope.searchTree = function(treeId) {
    $(treeId).jstree('searchtag', $scope.searchStr, $scope.tags, $scope.only);
  }
  $scope.clearSearchFieldTree = function(treeId) {
    $scope.searchStr = "";
    $(treeId).jstree('searchtag', '', $scope.tags, $scope.only);
  }
  $scope.refuseEnter = function(event){
    refuseEnter(event);
  }
  adjustHeight('#activeTechniquesTree');
});
/*==============================================================================================================================================*/
app.controller('filterTagRuleCtrl', function ($scope, $http, $location, $timeout) {
  $scope.tableId="#grid_rules_grid_zone";
  $scope.searchStr = "";
  $scope.showFilters = false;
  $scope.only = {"key":false , "value":false};
  $scope.newTag = {"key":""  , "value":""};
  $scope.tags = [];
  $scope.hide = {"search":false, "tag":true};
  $scope.isEmptyOrBlank = function(str){
    return (!str || 0 === str.length || /^\s*$/.test(str));
  }
  $scope.toggleTagForm = function(element, input, forceOpen){
    for(x in $scope.hide){
      if(x === element){
       if(forceOpen){
          $scope.hide[x] = false;
       }else{
          $scope.hide[x] = !$scope.hide[x];
        }
      }else{
        $scope.hide[x] = true;
      }
    }
    $timeout(function() {
      $(input).focus();
    }, 0);
  }
  $scope.removeTag = function(index, apply){
    var tag = $scope.tags[index];
    $scope.tags.splice(index, 1);
    $scope.searchTable();
    if(apply){
      $scope.updateFilter();
    }else{
      $scope.updateTag();
    }
  }
  $scope.resetNewTag = function(){
    $scope.newTag = {"key":"" , "value":""};
  }
  $scope.modifyTag = function(index,tag){
    $scope.toggleTagForm('tag','.input-key',true);
    $scope.newTag.key   = tag.key;
    $scope.newTag.value = tag.value;
  }
  $scope.addTag = function(tag){
	var newTag = tag ? tag : $scope.newTag;
    var alreadyExist = false;
    for(var i=0 ; i<$scope.tags.length ; i++){
      if((newTag.key==$scope.tags[i].key)&&(newTag.value==$scope.tags[i].value)){
        alreadyExist = true;
        $scope.tags[i].alreadyExist = true;
        (function(i){
          $timeout(function() {
            $scope.tags[i].alreadyExist = false;
          }, 200);
        })(i);
      }
    }
    if(!alreadyExist){
      $scope.tags.push(newTag);
      $scope.searchTable();
      if(!tag){
        $scope.resetNewTag();
        $scope.updateTag();
      }else{
        $scope.updateFilter();
      }
    }
    $('.input-key').focus();
  }
  $scope.updateTag = function(){
    var scopeRuleTag = angular.element($('[ng-controller="tagsRuleCtrl"]')).scope();
    if(scopeRuleTag){
      scopeRuleTag.$apply(function(){
        $scope.updateFunction(scopeRuleTag);
      });
    }
  }
  $scope.updateFilter = function(){
	var scopeRuleTag = angular.element($('[ng-controller="tagsRuleCtrl"]')).scope();
    $scope.updateFunction(scopeRuleTag);
  }
  $scope.updateFunction = function(scopeRuleTag){
    var key,val;
    if(scopeRuleTag.tags.length>0){
      for(var i=0 ; i<scopeRuleTag.tags.length ; i++){
        key = false;
        val = false;
        for(var j=0 ; j<$scope.tags.length ; j++){
          if(!$scope.only.key && !$scope.only.value){
            if(((scopeRuleTag.tags[i].key == $scope.tags[j].key)||($scope.tags[j].key==""))&&((scopeRuleTag.tags[i].value == $scope.tags[j].value)||($scope.tags[j].value==""))){
              key = true;
              val = true;
            }
          }else{
            if($scope.only.key && ((scopeRuleTag.tags[i].key == $scope.tags[j].key)||($scope.tags[j].key==""))){
             key = true;
            }
            if($scope.only.value && ((scopeRuleTag.tags[i].value == $scope.tags[j].value)||($scope.tags[j].value==""))){
              val = true;
            }
          }
        }
        scopeRuleTag.tags[i].match = {
          'key'   :key,
          'value' :val
        }
      }
    }
  }
  $scope.clearSearch = function(){
	$scope.strSearch =  '' ;
	$scope.filterGlobal('');
  }
  $scope.toggleFilter = function(event,tree){
    $('#form-tag').toggleClass('in');
    $($(event.currentTarget).find('span.pull-right')).toggleClass('in');
    $scope.showFilters = !$scope.showFilters;
    if($scope.showFilters){
      $('.input-key').focus();
    }
  }
  $scope.filterGlobal = function(str) {
    $($scope.tableId).DataTable().search(str).draw();
  }
  $scope.onlyKey = function(elem){
    var button = $(elem.currentTarget);
    button.toggleClass('active');
    $scope.only.key = !$scope.only.key;
    $scope.only.value = false;
    $scope.searchTable();
    $scope.updateTag();
  }
  $scope.onlyValue = function(elem){
    var button = $(elem.currentTarget);
    button.toggleClass('active');
    $scope.only.value = !$scope.only.value;
    $scope.only.key = false;
    $scope.searchTable();
    $scope.updateTag();
  }
  //Avoid compilation error using ngClass directive with '&&' condition
  $scope.getOnlyAllValue = function(){
    return !$scope.only.key && !$scope.only.value;
  }
  $scope.onlyAll = function(elem){
    var button = $(elem.currentTarget);
    button.addClass('active');
    $scope.only.key   = false;
    $scope.only.value = false;
    $scope.searchTable();
    $scope.updateTag();
  }
  $scope.searchTable = function() {
	var table = $($scope.tableId).DataTable();
	table.draw();
  }
  $scope.clearSearchField = function() {
    $scope.searchStr = "";
  }

  $scope.initFilterTable = function(){
    $.fn.dataTable.ext.search.push(
      function( settings, data, dataIndex ){
        var containsTags;
        var matchTags = [];
        if($scope.tags.length>0){
          var ruleTags = JSON.parse(data[0]);
          for(i in $scope.tags){
            containsTags = false;
            for(j in ruleTags){
              if(((!$scope.only.key && !$scope.only.value)
                &&
                ((($scope.tags[i].key == j)||($scope.tags[i].key==""))&&(($scope.tags[i].value == ruleTags[j])||($scope.tags[i].value==""))))||($scope.only.key && (($scope.tags[i].key == j)||($scope.tags[i].key=="")))||($scope.only.value && (($scope.tags[i].value == ruleTags[j])||($scope.tags[i].value=="")))
              ){
                containsTags = true;
              }
            }
            matchTags.push(containsTags)
          }
          if($.inArray(false, matchTags) < 0){
            return true;
          }
          return false;
        }
        return true;
      }
    );
  }
  $scope.initFilterTable();
});

app.config(function($locationProvider) {
  $locationProvider.html5Mode({
    enabled: true,
    requireBase: false
  });
})

// Adjust tree height
function adjustHeight(treeId){
  var tree = $(treeId);
  var offsetTop = tree.offset().top + 10;
  var maxHeight = 'calc(100vh - '+ offsetTop + 'px)';
  tree.css('max-height',maxHeight);
}

// PLUGIN JSTREE : SEARCH-TAG
$.jstree.plugins.searchtag = function (options, parent) {
  var that = this;
  this.bind = function () {
    parent.bind.call(this);
    this._data.searchtag.str = "";
    this._data.searchtag.dom = $();
    this._data.searchtag.res = [];
    this._data.searchtag.opn = [];
    this._data.searchtag.som = false;
    this._data.searchtag.smc = false;
    this._data.searchtag.hdn = [];

    this.element
      .on("searchtag.jstree", $.proxy(function (e, data) {
        if(this._data.searchtag.som && data.res.length) {
          var m = this._model.data, i, j, p = [], k, l;
          for(i = 0, j = data.res.length; i < j; i++) {
            if(m[data.res[i]] && !m[data.res[i]].state.hidden) {
              p.push(data.res[i]);
              p = p.concat(m[data.res[i]].parents);
              if(this._data.searchtag.smc) {
                for (k = 0, l = m[data.res[i]].children_d.length; k < l; k++) {
                  if (m[m[data.res[i]].children_d[k]] && !m[m[data.res[i]].children_d[k]].state.hidden) {
                    p.push(m[data.res[i]].children_d[k]);
                  }
                }
              }
            }
          }
          p = $.vakata.array_remove_item($.vakata.array_unique(p), $.jstree.root);
          this._data.searchtag.hdn = this.hide_all(true);
          this.show_node(p, true);
          this.redraw(true);
        }
      }, this))
      .on("clear_search.jstree", $.proxy(function (e, data) {
          if(this._data.searchtag.som && data.res.length) {
            this.show_node(this._data.searchtag.hdn, true);
            this.redraw(true);
          }
        }, this));
  };
  this.searchtag = function (str, tags, filteringTagsOptions, skip_async, show_only_matches, inside, append, show_only_matches_children) {
    this.show_all();
    if((!Array.isArray(tags) || tags.length<=0)&&(str === false || $.trim(str.toString()) === "")) {
      return this.clear_search();
    }
    inside = this.get_node(inside);
    inside = inside && inside.id ? inside.id : null;
    var str = str.toString();
    var s = this.settings.search,
        m = this._model.data,
        f = null,
        r = [],
        p = [], i, j;
    if(this._data.searchtag.res.length && !append) {
      this.clear_search();
    }
    if(show_only_matches === undefined) {
      show_only_matches = s.show_only_matches;
    }
    if(show_only_matches_children === undefined) {
      show_only_matches_children = s.show_only_matches_children;
    }
    if(!append) {
      this._data.searchtag.str = str;
      this._data.searchtag.dom = $();
      this._data.searchtag.res = [];
      this._data.searchtag.opn = [];
      this._data.searchtag.som = show_only_matches;
      this._data.searchtag.smc = show_only_matches_children;
    }
    f = new $.vakata.search(str, true, { caseSensitive : s.case_sensitive, fuzzy : s.fuzzy });
    $.each(m[inside ? inside : $.jstree.root].children_d, function (ii, i) {
      var v = m[i];
      var containsTags;
      var matchTags = [];
      if(v.text && !v.state.hidden && (!s.search_leaves_only || (v.state.loaded && v.children.length === 0)) && (!s.search_callback && f.search(v.text).isMatch) ) {
        var directiveTags = v.data.jstree.type=="directive" ? v.data.jstree.tags : false;
        if(tags.length>0){
          if(directiveTags){
            for(var j=0 ; j<tags.length ; j++){
              containsTags = false;
              for(var k in directiveTags){
                if(
                  ((!filteringTagsOptions.key && !filteringTagsOptions.value)&&(((tags[j].key == k)||(tags[j].key==""))&&((tags[j].value == directiveTags[k])||(tags[j].value==""))))||
                  (filteringTagsOptions.key && ((tags[j].key == k)||(tags[j].key=="")))||
                  (filteringTagsOptions.value && ((tags[j].value == directiveTags[k])||(tags[j].value=="")))
                ){
                  containsTags = true;
                }
              }
              matchTags.push(containsTags)
            }
            if($.inArray(false, matchTags) < 0){
              r.push(i);
              p = p.concat(v.parents);
            }
          }
        }else{
          r.push(i);
          p = p.concat(v.parents);
        }
      }
    });
    if(r.length) {
      p = $.vakata.array_unique(p);
      for(i = 0, j = p.length; i < j; i++) {
        if(p[i] !== $.jstree.root && m[p[i]] && this.open_node(p[i], null, 0) === true) {
          this._data.searchtag.opn.push(p[i]);
        }
      }
      if(!append) {
        this._data.searchtag.dom = $(this.element[0].querySelectorAll('#' + $.map(r, function (v) { return "0123456789".indexOf(v[0]) !== -1 ? '\\3' + v[0] + ' ' + v.substr(1).replace($.jstree.idregex,'\\$&') : v.replace($.jstree.idregex,'\\$&'); }).join(', #')));
        this._data.searchtag.res = r;
      }
      else {
        this._data.searchtag.dom = this._data.searchtag.dom.add($(this.element[0].querySelectorAll('#' + $.map(r, function (v) { return "0123456789".indexOf(v[0]) !== -1 ? '\\3' + v[0] + ' ' + v.substr(1).replace($.jstree.idregex,'\\$&') : v.replace($.jstree.idregex,'\\$&'); }).join(', #'))));
        this._data.searchtag.res = $.vakata.array_unique(this._data.searchtag.res.concat(r));
      }
      this._data.searchtag.dom.children(".jstree-anchor").addClass('jstree-search');
    }else{
      this.hide_all();
    }
    this.trigger('searchtag', { nodes : this._data.searchtag.dom, str : str, res : this._data.searchtag.res, show_only_matches : show_only_matches });
  };
  this.clear_search = function () {
    if(this.settings.search.close_opened_onclear) {
      this.close_node(this._data.searchtag.opn, 0);
    }
    this.trigger('clear_search', { 'nodes' : this._data.searchtag.dom, str : this._data.searchtag.str, res : this._data.searchtag.res });
    if(this._data.searchtag.res.length) {
      this._data.searchtag.dom = $(this.element[0].querySelectorAll('#' + $.map(this._data.searchtag.res, function (v) {
        return "0123456789".indexOf(v[0]) !== -1 ? '\\3' + v[0] + ' ' + v.substr(1).replace($.jstree.idregex,'\\$&') : v.replace($.jstree.idregex,'\\$&');
      }).join(', #')));
      this._data.searchtag.dom.children(".jstree-anchor").removeClass("jstree-search");
    }
    this._data.searchtag.str = "";
    this._data.searchtag.res = [];
    this._data.searchtag.opn = [];
    this._data.searchtag.dom = $();
  };
  this.redraw_node = function(obj, deep, callback, force_render) {
    obj = parent.redraw_node.apply(this, arguments);
    if(obj) {
      if($.inArray(obj.id, this._data.searchtag.res) !== -1) {
        var i, j, tmp = null;
        for(i = 0, j = obj.childNodes.length; i < j; i++) {
          if(obj.childNodes[i] && obj.childNodes[i].className && obj.childNodes[i].className.indexOf("jstree-anchor") !== -1) {
            tmp = obj.childNodes[i];
            break;
          }
        }
        if(tmp) {
          tmp.className += ' jstree-search';
        }
      }
    }
    return obj;
  };
};