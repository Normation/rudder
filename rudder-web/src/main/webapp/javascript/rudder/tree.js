

/*
 * Reference Technique library tree
 */
var buildReferenceTechniqueTree = function(id,  initially_select, appContext) {
  $(id).bind("loaded.jstree", function (event, data) {
    data.inst.open_all(-1);
  }).jstree({ 
    "core" : { 
    "animation" : 0,
    "html_titles" : true
    },
    "ui" : { 
      "initially_select" : [initially_select],
      "select_limit" : 1
    },
    "types" : {
      // I set both options to -2, as I do not need depth and children count checking
      // Those two checks may slow jstree a lot, so use only when needed
      "max_depth" : -2,
      "max_children" : -2,
      "valid_children" : [ "category" ],
        "types" : {
          "category" : {
            "icon" : {
              "image" : appContext+"/images/tree/folder_16x16.png" 
            },
            "valid_children" : [ "category", "template" ],
            "select_node" : function(e) {
        	  this.toggle_node(e);
        	  return false;
            },
            "start_drag" : false
          },
          "template" : {
            "icon" : { 
              "image" : appContext+"/images/tree/technique_16x16.png" 
            },
            "valid_children" : "none"
          },
          "default" : {
            "valid_children" : "none"
          }
        }
      },
      "search" : {
          "case_insensitive" : true,
          "show_only_matches": true
        },
      "crrm" : {
        "move" : {
          "check_move" : function () { return false; }
        }
      },
      "dnd" : {
        "drop_target" : false,
        "drag_target" : false
      },
      "themes" : { 
    	  "theme" : "rudder",
    	  "url" : appContext+"/javascript/jstree/themes/rudder/style.css"
      },
      "plugins" : [ "themes", "html_data", "ui", "types", "dnd", "crrm", "search" ]
    })   
}

/*
 * Active Techniques library tree
 */
var buildActiveTechniqueTree = function(id, foreignTreeId, authorized, appContext) {
  $(id).bind("loaded.jstree", function (event, data) {
	  data.inst.open_all(-1);
  }).jstree({ 
    "core" : { 
    "animation" : 0,
    "html_titles" : true
    },
    "ui" : { 
      "select_limit" : 1
    },
    "types" : {
      // I set both options to -2, as I do not need depth and children count checking
      // Those two checks may slow jstree a lot, so use only when needed
      "max_depth" : -2,
      "max_children" : -2,
      "valid_children" : [ "root-category" ],
      "types" : {
        "root-category" : {
          "icon" : { 
            "image" : appContext+"/images/tree/folder_16x16.png" 
          },
          "valid_children" : [ "category", "template" ],
          "start_drag" : false,
          "select_node" : function(e) {
        	  this.toggle_node(e);
        	  return true;
            },
        },
        "category" : {
          "icon" : { 
            "image" : appContext+"/images/tree/folder_16x16.png" 
          },
          "valid_children" : [ "category", "template" ],
          "select_node" : function(e) {
        	  this.toggle_node(e);
        	  return true;
            },
        },
        "template" : {
          "icon" : { 
            "image" : appContext+"/images/tree/technique_16x16.png" 
          },
          "valid_children" : "none"
        },
        "default" : {
          "valid_children" : "none"
        }
      }
    },
    "crrm" : {
      "move" : {
        "always_copy" : "multitree",
        "check_move" : function (m) { 
          //only accept to move a node from the reference tree if it does not exists in that tree
           var checkNotAlreadyBound = function() {
	          var res = true;
	          var originTree = m.ot.get_container().prop("id");
	          var activetechniqueid = "";
	          for(i = 0 ; i < m.o[0].attributes.length; i++) {
	        	  if(m.o[0].attributes[i].name == "activetechniqueid") {
	        		  activetechniqueid = m.o[0].attributes[i].nodeValue;
	        		  break;
	        	  }
	          }
	          var list = $(id + " [activeTechniqueid=" + activetechniqueid + "]");
	          if(foreignTreeId == originTree) {
	            //look if there is an li with attr activeTechniqueId == moved object activeTechniqueId
	            res =  list.size() < 1 ;
	          }
	          return res;
          };
          //only accept "inside" node move (yes, comparing m.p == "inside" does not work)
          //and into a new parent node. 
          var checkInside = (m.p != "before" && m.p != "after" && this._get_parent(m.o)[0] !== m.np[0]);
          if (authorized){
          return checkNotAlreadyBound() && checkInside;
          }
          return authorized
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
    "plugins" : [ "themes", "html_data", "ui", "types", "dnd", "crrm", "search" ] 
  })   
}

/*
 * Directive management
 */
var buildDirectiveTree = function(id, initially_select , appContext) {
  jQuery(id).jstree({ 
      "core" : { 
      "animation" : 0,
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
        "valid_children" : [ "category" ],
          "types" : {
            "category" : {
              "icon" : { 
                "image" : appContext+"/images/tree/folder_16x16.png" 
              },
              "valid_children" : [ "category", "template" ],
              "select_node" : function(e) {
            	  this.toggle_node(e);
            	  return false;
              }
            },
            "template" : {
              "icon" : { 
                "image" : appContext+"/images/tree/technique_16x16.png" 
              },
              "valid_children" : [ "directive" ],
              "select_node" : function(e) {
            	  this.toggle_node(e);
            	  return true;
              }
            },
            "directive" : {
              "icon" : { 
                "image" : appContext+"/images/tree/directive_16x16.gif" 
              },
              "valid_children" : "none"
            },
            "default" : {
              "valid_children" : "none"
            }
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
      "plugins" : [ "themes", "html_data", "ui", "types", "search" ]      
  })
  
  $(id).removeClass('nodisplay');

}


/*
 * Group tree
 */
var buildGroupTree = function(id, appContext, initially_select, select_multiple_modifier) {
  if(select_multiple_modifier !== 'undefined') {
    select_limit = -1;
  } else {
    select_multiple_modifier = "";
    select_limit = 1;
  }
  $(id).bind("loaded.jstree", function (event, data) {
    data.inst.open_all(-1);
  }).jstree({
    "core" : { 
      "animation" : 0,
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
      "max_depth" : -2,
      "max_children" : -2,
      "valid_children" : [ "root-category" ],
      "types" : {
        "root-category" : {
          "icon" : { "image" : appContext+"/images/tree/folder_16x16.png" },
          "valid_children" : [ "category", "group" , "special_target" ],
          "start_drag" : false,
          "select_node" : function(e) {
        	  this.toggle_node(e);
        	  return false;
          }
        },
        "category" : {
          "icon" : { "image" : appContext+"/images/tree/folder_16x16.png" },
          "valid_children" : [ "category", "group" , "special_target" ],
          "select_node" : function(e) {
        	  this.toggle_node(e);
        	  return false;
          }
        },
        "group" : {
          "icon" : { "image" : appContext+"/images/tree/server_group_16x16.gif" },
          "valid_children" : "none" 
        },
        "special_target" : {
          "icon" : { "image" : appContext+"/images/tree/special_target_16x16.gif" },
          "valid_children" : "none"
        },
        "default" : {
          "valid_children" : "none"
        }
      }
    },
    "crrm" : {
      "move" : {
        "check_move" : function (m) { 
          //only accept "inside" node move (yes, comparing m.p == "inside" does not work)
          //and into a new parent node. 
          return (m.p != "before" && m.p != "after" && this._get_parent(m.o)[0] !== m.np[0]);
        }
      }
    },
    "dnd" : {
      "drop_target" : false,
      "drag_target" : false
    },
    "themes" : { 
  	  "theme" : "rudder",
  	  "url" : appContext+"/javascript/jstree/themes/rudder/style.css"
    },
    "plugins" : [ "themes", "html_data", "ui", "types", "dnd", "crrm" ] 
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
      data.inst.open_all(-1);
    }).jstree({ 
      "core" : { 
      "animation" : 0,
      "html_titles" : true
      },
     "ui" : { 
        "initially_select" : [initially_select],
        "select_limit" : 1
      },
      "types" : {
        // I set both options to -2, as I do not need depth and children count checking
        // Those two checks may slow jstree a lot, so use only when needed
        "max_depth" : -2,
        "max_children" : -2,
        "valid_children" : [ "category" ],
          "types" : {
            "category" : {
              "icon" : { 
                "image" : appContext+"/images/tree/folder_16x16.png" 
              },
              "valid_children" : [ "category", "template" ]
            },
            "template" : {
              "icon" : { 
                "image" : appContext+"/images/tree/technique_16x16.png" 
              },
              "valid_children" : [ "directive" ]
            },
            "directive" : {
              "icon" : { 
                "image" : appContext+"/images/tree/directive_16x16.gif" 
              },
              "valid_children" : [ "rule" ]
            },
            "rule" : {
                "icon" : { 
                  "image" : appContext+"/images/tree/configuration_rule_16x16.png" 
                },
                "valid_children" : "none"
             },
            "default" : {
              "valid_children" : "none"
            }
          }
        },
        "themes" : { 
      	  "theme" : "rudder",
      	  "url" : appContext+"/javascript/jstree/themes/rudder/style.css"
        },
      "plugins" : [ "themes", "html_data", "ui", "types"] 
    })
}



var buildRulePIdepTree = function(id, initially_select, appContext) {
  jQuery(id).
    bind("loaded.jstree", function (event, data) {
      data.inst.open_all(-1);
    }).jstree({ 
      "core" : { 
        "animation" : 0,
        "html_titles" : true
      },
      "ui" : { 
        "select_limit" : -1,
        "select_multiple_modifier" : "on", 
        "selected_parent_close" : false,
        "initially_select" : initially_select
      },
      // I set both options to -2, as I do not need depth and children count checking
      // Those two checks may slow jstree a lot, so use only when needed
      "max_depth" : -2,
      "max_children" : -2,
      "types" : {
        "valid_children" : [ "category" ],
        "types" : {
          "category" : {
            "icon" : { 
              "image" : appContext+"/images/tree/folder_16x16.png" 
            },
            "valid_children" : [ "category", "template" ],
	        "select_node" : function(e) {
         	  this.toggle_node(e);
	          return false;
	        }
          },
          "template" : {
            "icon" : { 
              "image" : appContext+"/images/tree/technique_16x16.png" 
            },
            "valid_children" : [ "directive" ],
            "select_node" : function(e) {
        	  this.toggle_node(e);
        	  return false;
            }
          },
          "directive" : {
            "icon" : { 
              "image" : appContext+"/images/tree/directive_16x16.gif" 
            },
            "valid_children" : "none"
          },
          "default" : {
            "valid_children" : "none"
          }
        }
      },
      "themes" : { 
    	  "theme" : "rudder",
    	  "url" : appContext+"/javascript/jstree/themes/rudder/style.css"
      },
      "plugins" : [ "themes", "html_data", "ui", "types"]
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
