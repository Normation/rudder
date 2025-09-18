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


const allEvents = new Map();

// Since #24062, we inlined on click events from our lift generated nodes,
// there nodes are transformed into a tree using jstree
// jstree does not keep the event that were bound to the previous nodes
// We need to reattach the events here
function attachInlinedEvents( id ) {
  for (let [id, events] of allEvents) {
    if (events) {
      $("#" + id).off("click").on("click", function(e) {
        events.forEach(function (event) { event.handler(e) })
      })
    }
  }
}

/*
 * Reference Technique library tree
 */
var buildReferenceTechniqueTree = function(id,  initially_select, appContext) {

  allEventsRegisterTree();

  $(id).bind("loaded.jstree", function (event, data) {
    data.instance.open_all();
  }).jstree({
    "core" : {
        "animation" : 150
      , "html_titles" : true
      , "multiple" : false
    },
    "ui" : {
      "initially_select" : [initially_select],
      "select_limit" : 1
    },
    "types" : {
      "#" : {
        "valid_children" : [ "category" ]
      },
      "category" : {
            "icon" : "fa fa-folder",
            "valid_children" : [ "category", "template" ],
            "select_node" : function(e) {
              this.toggle_node(e);
              return false;
            }
          },
      "template" : {
            "icon" : false,
            "valid_children" : "none"
          },
          "default" : {
            "valid_children" : "none"
          }

      },
      "search" : {
          "case_insensitive" : true,
          "show_only_matches": true
        },
      "dnd": {
          always_copy: true
        , is_draggable: function(nodes,event) {
          return nodes.some(function(node) {return node.type !== "category"})
        }
        },
      "plugins" : [ "types", "dnd", "search" ]
  }).on("ready.jstree", function () {
    attachInlinedEvents(id)
  }).on("after_open.jstree", function (event, data) {
    attachInlinedEvents(id)
  }).on("redraw.jstree", function (event, data) {
    attachInlinedEvents(id)
  });
}

/*
 * Active Techniques library tree
 */
var buildActiveTechniqueTree = function(id, foreignTreeId, authorized, appContext) {
  $(id).bind("loaded.jstree", function (event, data) {
	  data.instance.open_all();
  }).jstree({
    "core" : {
    "animation" : 150
    , "multiple" : false
    , "html_titles" : true,
    "check_callback" : function (operation, node, node_parent, node_position, more) {
      if (operation === "copy_node") {
        if (authorized) {
          var activetechniqueid = node.li_attr.activetechniqueid;
          return ! $(id + " [activeTechniqueid=" + activetechniqueid + "]").length;
        } else {
          return authorized
        }
      }
      return true;
      }
    },
    "ui" : {
      "select_limit" : 1
    },
    "types" : {
      "#" : {
        "valid_children" : [ "root-category" ]
      },
      "root-category" : {
          "icon" : "fa fa-folder",
          "valid_children" : [ "category", "template" ],
          "start_drag" : false,
          "select_node" : function(e) {
        	  this.toggle_node(e);
        	  return true;
            },
      },
      "category" : {

          "icon" : "fa fa-folder",
          "valid_children" : [ "category", "template" ],
          "select_node" : function(e) {
        	  this.toggle_node(e);
        	  return true;
            },
      },
      "template" : {
          "icon" : false,
          "valid_children" : "none"
        },
        "default" : {
          "valid_children" : "none"
        }
    },    "dnd": {
      check_while_dragging: true
    },
    "search" : {
        "case_insensitive" : true,
        "show_only_matches": true
      },
    "plugins" : [ "types", "dnd", "search" ]
  }).on("ready.jstree", function () {
    attachInlinedEvents(id)
  }).on("after_open.jstree", function (event, data) {
    attachInlinedEvents(id)
  }).on("redraw.jstree", function (event, data) {
    attachInlinedEvents(id)
  });
}

/*
 * Rule category tree
 */
var buildRuleCategoryTree = function(id, initially_select , appContext) {
  $(id).bind("loaded.jstree", function (event, data) {
    data.instance.open_all();
  }).jstree({
      "core" : {
      "animation" : 150,
      "html_titles" : true,
      "check_callback" : true,
      },
     "ui" : {
        "select_limit" : 1,
        "initially_select" : [initially_select]
      },
      "types" : {
        "#" : {
        "valid_children" : [ "category" ]
        },

        "category" : {
              "icon" : "fa fa-folder",
              "valid_children" : [ "category" ],
              "select_node" : function(e) {
            	  return true;
              }
            },
        "default" : {
              "valid_children" : "none"
            }
          }
      ,
      "search" : {
        "case_insensitive" : true,
        "show_only_matches": true
      },
      "plugins" : [  "types", "dnd" ]
  })
  $(id).removeClass('d-none');
}

/*
 * Rule category tree no drag and drop
 */
var buildRuleCategoryTreeNoDnD = function(id, initially_select , appContext) {
  $(id).bind("loaded.jstree", function (event, data) {
    data.instance.open_all();
  }).jstree({
      "core" : {
      "animation" : 150,
      "html_titles" : true,
      "initially_open" : [ "jstn_0" ]
      },
     "ui" : {
        "select_limit" : 1,
        "initially_select" : [initially_select]
      },
      // I set both options to -2, as I do not need depth and children count checking
      // Those two checks may slow jstree a lot, so use only when needed
      "max_depth" : -2,
      "max_children" : -2,
      "types" : {
        "#" : {"valid_children" : [ "category" ]},
            "category" : {
              "icon" : "fa fa-folder",
              "valid_children" : [ "category" ],
              "select_node" : function(e) {
            	  return true;
              }
            },
            "default" : {
              "valid_children" : "none"
            }
      },
      "plugins" : [ "types" ]
  })
  $(id).removeClass('d-none');
}

/*
 * Group tree
 */
var buildGroupTree = function(id, appContext, initially_select, select_multiple_modifier, select_node, authorized) {

  if(select_multiple_modifier !== undefined) {
    select_limit = -1;
  } else {
    select_multiple_modifier = "";
    select_limit = 1;
  }

  /**
   * We want to be able to select category on group
   * page.
   */
  if(select_node == undefined) {
    select_node = false;
  }

  /**
   * We want to select all nodes, including
   * system one, on the rule page
   * (so where category are not selectable
   */
  var select_system_node_allowed = false;

  $(id).bind("loaded.jstree", function (event, data) {
    data.instance.open_all();
    initBsTooltips();
  }).on("ready.jstree", function () {
    // make jstree node openable in a new tab
    var items = document.querySelectorAll(".groupTreeNode");
    items.forEach(function (item) {
      var node = item.querySelector("a.jstree-anchor");
      var jsTreeId = item.getAttribute("id");
      if (node) {
        if(jsTreeId.startsWith("jstree-group:")) {
          var idGroupSystem = jsTreeId.slice("jstree-group:".length);
          node.setAttribute("href", contextPath + "/secure/nodeManager/groups#{\"groupId\":\"" + idGroupSystem + "\"}");
        } else {
          var idGroupUser = jsTreeId.slice("jstree-".length);
          node.setAttribute("href", contextPath + "/secure/nodeManager/groups#{\"target\":\"" + idGroupUser + "\"}");
        }
      }
    })
    allEventsRegisterTree();
  }).on("after_open.jstree", function (event, data){
    attachInlinedEvents(id)
  }).jstree({
    "core" : {
      "animation" : 150,
      "html_titles" : true
    },
    "ui" : {
      "initially_select" : initially_select,
      "select_limit" : select_limit,
      "select_multiple_modifier" : select_multiple_modifier
    },
    "types" : {
      // I set both options to -2, as I do not need depth and children count checking
      // Those two checks may slow jstree a lot, so use only when needed
      "#" : {
        "max_depth" : -2,
        "max_children" : -2,
        "valid_children" : [ "root-category" ]
      },
      "root-category" : {
          "icon" : "fa fa-folder",
          "valid_children" : [ "category", "group" , "special_target" ],
          "start_drag" : false,
          "select_node" : function(e) {
        	  this.toggle_node(e);
        	  return select_node;
          }
      },
      "category" : {
          "icon" : "fa fa-folder",
          "valid_children" : [ "category", "group" , "special_target" ],
          "select_node" : function(e) {
        	  this.toggle_node(e);
        	  return select_node;
          }
      },
      "system_category" : {
          "icon" : "fa fa-folder",
            "valid_children" : [ "category", "group" , "special_target" ],
            "select_node" : function(e) {
              this.toggle_node(e);
              return select_node;
            }
      },
      "group" : {
          "icon" : "fa fa-sitemap",
          "valid_children" : "none",
          "select_node" : select_node
      },
      "system_target" : {
          "icon" : "fa fa-sitemap",
          "select_node" :  select_system_node_allowed,
          "hover_node" : select_system_node_allowed,
          "valid_children" : "none"
      },
      "default" : {
          "valid_children" : "none"
      }
    },
    "crrm" : {
      "move" : {
        "check_move" : function (m) {
          //only accept "inside" node move (yes, comparing m.p == "inside" does not work)
          //and into a new parent node. refuse move to system category
          return authorized && (m.np.attr("rel") != "system_category" && m.p != "before" && m.p != "after" && this._get_parent(m.o)[0] !== m.np[0]);
        }
      }
    },
    "search" : {
      "case_insensitive" : true,
      "show_only_matches": true
    },
    "dnd" : {
      "drop_target" : false,
      "drag_target" : false
    },
    "themes" : {
  	  "theme" : "rudder",
  	  "url" : appContext+"/javascript/jstree/themes/rudder/style.css"
    },
    "plugins" : [ "themes", "html_data", "ui", "types", "dnd", "crrm", "search"]
    });
}


/*
 * Directive management
 * NOTE: all children are set to none because
 *       we don't want/have to allow node move -
 *       that tree is read only.
 */
var buildTechniqueDependencyTree = function(id, initially_select, appContext) {
  jQuery(id).
    bind("loaded.jstree", function (event, data) {
      data.instance.open_all();
    }).jstree({
      "core" : {
      "animation" : 150,
      "html_titles" : true
      },
     "ui" : {
        "initially_select" : [initially_select],
        "select_limit" : 1
      },
      "types" : {
          "#" :{
            "valid_children" : [ "category" ]
          }
        , "category" : {
              "icon" : "fa fa-folder"
            , "valid_children" : [ "category", "template" ]
          }
        , "template" : {
              "icon" : "fa fa-gear"
            , "valid_children" : [ "directive" ]
          }
        , "directive" : {
              "icon" : "fa fa-file-text"
            , "valid_children" : [ "rule" ]
          }
        , "rule" : {
              "icon" : "fa fa-book"
            , "valid_children" : "none"
          },
          "default" : {
            "valid_children" : "none"
          }
      },
        "themes" : {
      	  "theme" : "rudder",
      	  "url" : appContext+"/javascript/jstree/themes/rudder/style.css"
        },
      "plugins" : [ "themes", "html_data", "ui", "types"]
    })
}

var buildDirectiveTree = function(id, initially_select, appContext, select_limit) {
  var select_multiple_modifier = "on"
  if (select_limit > 0) {
    select_multiple_modifier = "ctrl"
  }

  allEventsRegisterTree();

  var tree = $(id).on("loaded.jstree", function (event, data) {
    openTreeNodes(id, "directiveTreeSettings_nodesState", data);
    initBsTooltips();
    }).on("ready.jstree", function () {
      // make jstree node openable in a new tab
      var items = document.querySelectorAll(".directiveNode")
       items.forEach(function (item) {
         var node = item.querySelector("a.jstree-anchor")
         var jsTreeId = item.getAttribute("id");
         var idDirective = jsTreeId.slice(7);
         if (node) {
           node.setAttribute("href", contextPath + "/secure/configurationManager/directiveManagement#{\"directiveId\":\"" + idDirective + "\"}");
         }
      });
    }).on("after_open.jstree", function (event, data){
      attachInlinedEvents(id)
    }).on("redraw.jstree", function (event, data){
      attachInlinedEvents(id)
    }).jstree({
      "core" : {
        "animation" : 150,
        "html_titles" : true
      },
      // I set both options to -2, as I do not need depth and children count checking
      // Those two checks may slow jstree a lot, so use only when needed
      "max_depth" : -2,
      "max_children" : -2,
      "types" : {
        "#" : {
          "valid_children" : [ "category" ]
          },
        "category" : {
            "icon" :  "fa fa-folder",
            "valid_children" : [ "category", "template" ],
	        "select_node" : function(e) {
         	  this.toggle_node(e);
	          return false;
	        }
        },
        "template" : {
            "icon" :  false,
            "valid_children" : [ "directive" ],
            "select_node" : function(e) {
               this.toggle_node(e);
               return select_limit > 0;
            }
          },
        "directive" : {
            "icon" : false,
            "valid_children" : "none"
        },
        "default" : {
          "valid_children" : "none"
        }
      },
      "search" : {
        "case_insensitive" : true,
        "show_only_matches": true
      },

      "themes" : {
    	  "theme" : "rudder",
    	  "url" : appContext+"/javascript/jstree/themes/rudder/style.css"
      },
      "plugins" : [ "themes", "html_data", "types", "search", "searchtag"]
    });
   if(tree.element){
     tree.element.jstree().select_node(initially_select)
   }else{
     tree.jstree().select_node(initially_select)
   }
}

/*
 * Directive management
 */
var buildChangesTree = function(id,appContext) {
  $(id).jstree({
      "core" : {
      "animation" : 150,
      "html_titles" : true,
      "initially_open" : [ "changes","directives","rules","groups", "params" ]
      },
     "ui" : {
        "select_limit" : 1,
        "initially_select" : [ "changes"]
      },
      // I set both options to -2, as I do not need depth and children count checking
      // Those two checks may slow jstree a lot, so use only when needed
      "max_depth" : -2,
      "max_children" : -2,
      "types" : {
        "#" : {
          "valid_children" : [ "changeType" ]
        },
        "changeType" : {
            "icon" : "fa fa-folder"
          , "valid_children" : [ "changeType", "change" ]
        },
        "change" : {
            "icon" : "fa fa-file-text"
          , "valid_children" : "none"
         },
        "default" : {
            "icon" : "fa fa-file-text"
          , "valid_children" : "none"
         }
      },
      "plugins" : [ "types" ]
  })
}

/**
 * Shows the sibling of the searched items in the tree.
 * This function must be called after each search call
 */
var enableSubtree = function(elem) {
  elem.siblings("ul:first").show();
  elem.siblings("ul:first").find("li").show();
  return correctNode(elem.siblings("ul:first"));
};

var correctNode = function(elem) {
  var child, children, last, _j, _len1, _results;
  last = elem.children("li").eq(-1);
  last.addClass("jstree-last");
  children = elem.children("li");
  _results = [];
  for (_j = 0, _len1 = children.length; _j < _len1; _j++) {
    child = children[_j];
    _results.push(correctNode($(child).children("ul:first")));
  }
  return _results;
};

function updateTreeSettings(settingsId, data, openedState){
  var node     = data.node;
  var settings = localStorage.getItem(settingsId);
  var newSettings;
  if(settings===null){
    newSettings = {}
    newSettings[node.id] = openedState;
  }else{
    try {
      newSettings = JSON.parse(settings);
      newSettings[node.id] = openedState;
    }catch(e){}
  }
  if(newSettings){
    try{
      localStorage.setItem(settingsId, JSON.stringify(newSettings));
    } catch(e){}
  }
}

function openTreeNodes(treeId, settingsStorageId, data){
  // Get the state of a tree and keep closed only nodes we want to
  var settings;
  try {
    settings = JSON.parse(localStorage.getItem(settingsStorageId));
  }catch(e){};

  if(settings !== undefined && settings !== null){
    var nodes  = $(treeId).jstree()._model.data;
    for(var node in nodes){
      if(settings[node] === undefined || settings[node] === true) $(treeId).jstree().open_node(node);
    }
  }else{
    data.instance.open_all();
  }
}

/**
 * Function that fills the `allEvents` map with js-tree event handlers. By default, techniqueNode and directiveNode selectors are used as tree nodes.
 */
function allEventsRegisterTree() {
  // Save node event handlers, in order to customize node by reassigning the click event handler
  const allNodes = $('li.techniqueNode > a[id^="lift-event-js-"], li.techniqueNode > * > span[id^="lift-event-js-"], li.directiveNode > a[id^="lift-event-js-"], li.directiveNode > * > span[id^="lift-event-js-"]');
  allNodes.each(function () {
    const events = $._data(this, 'events')?.click;
    if (Array.isArray(events) && events.length !== 0) {
      allEvents.set(this.id, [...events]) // make copy because events array is a reference
    }
  })
}
