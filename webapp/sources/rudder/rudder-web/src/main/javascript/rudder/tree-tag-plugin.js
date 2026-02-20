/*
*************************************************************************************
* Copyright 2011 Normation SAS
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
  this.searchtag = function (str, tags, hideUnusedTechniques, filteringTagsOptions, skip_async, show_only_matches, inside, append, show_only_matches_children) {
    this.show_all();
    if((!Array.isArray(tags) || tags.length<=0)&&(str === false || $.trim(str.toString()) === "")&&(hideUnusedTechniques===false)) {
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
          // directive instance
          if(directiveTags){
            for(var j=0 ; j<tags.length ; j++){
              containsTags = false;
              for(var k in directiveTags){
                if(
                  ((!filteringTagsOptions.key && !filteringTagsOptions.value)&&(((tags[j].key == directiveTags[k].key)||(tags[j].key==""))&&((tags[j].value == directiveTags[k].value)||(tags[j].value==""))))||
                  (filteringTagsOptions.key && ((tags[j].key == directiveTags[k].key)||(tags[j].key=="")))||
                  (filteringTagsOptions.value && ((tags[j].value == directiveTags[k].value)||(tags[j].value=="")))
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
        }else if (directiveTags || hideUnusedTechniques===false){
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