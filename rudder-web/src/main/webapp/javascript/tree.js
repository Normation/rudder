

/*
 * Reference policy template library tree
 */
var buildReferencePolicyTemplateTree = function(id,  initially_select) {
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
              "image" : "img/tree/folder_16x16.png" 
            },
            "valid_children" : [ "category", "template" ],
            "hover_node" : false,
            "select_node" : false,
            "start_drag" : false
          },
          "template" : {
            "icon" : { 
              "image" : "img/tree/blueprint_16x16.png" 
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
          "check_move" : function () { return false; }
        }
      },
      "dnd" : {
        "drop_target" : false,
        "drag_target" : false
      },
      "plugins" : [ "themes", "html_data", "ui", "types", "dnd", "crrm"  ] 
    })   
}

/*
 * User policy template library tree
 */
var buildUserPolicyTemplateTree = function(id, foreignTreeId) {
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
            "image" : "img/tree/folder_16x16.png" 
          },
          "valid_children" : [ "category", "template" ],
          "start_drag" : false
        },
        "category" : {
          "icon" : { 
            "image" : "img/tree/folder_16x16.png" 
          },
          "valid_children" : [ "category", "template" ]
        },
        "template" : {
          "icon" : { 
            "image" : "img/tree/blueprint_16x16.png" 
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
          var checkNotAlreadyBound = true;
          if(foreignTreeId == m.ot.get_container().attr("id")) {
            //look if there is an li with attr ptid == moved object ptid
            checkNotAlreadyBound =  $(id + " [ptId=" + m.o.attr("ptId") + "]").size() < 1 ;
          }
          //only accept "inside" node move (yes, comparing m.p == "inside" does not work)
          //and into a new parent node. 
          var checkInside = (m.p != "before" && m.p != "after" && this._get_parent(m.o)[0] !== m.np[0]);
          
          return checkNotAlreadyBound && checkInside;
        }
      }
    },
    "dnd" : {
      "drop_target" : false,
      "drag_target" : false
    },
    "plugins" : [ "themes", "html_data", "ui", "types", "dnd", "crrm"  ] 
  })   
}

/*
 * Policy instance management
 */
var buildPolicyInstanceTree = function(id, initially_select) {
  jQuery(id).
    bind("loaded.jstree", function (event, data) {
      data.inst.open_all(-1);
    }).jstree({ 
      "core" : { 
      "animation" : 0,
      "html_titles" : true
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
                "image" : "img/tree/folder_16x16.png" 
              },
              "valid_children" : [ "category", "template" ],
              "select_node" : false
            },
            "template" : {
              "icon" : { 
                "image" : "img/tree/blueprint_16x16.png" 
              },
              "valid_children" : [ "policy" ]
            },
            "policy" : {
              "icon" : { 
                "image" : "img/tree/policy_16x16.gif" 
              },
              "valid_children" : "none"
            },
            "default" : {
              "valid_children" : "none"
            }
          }
        },
      "plugins" : [ "themes", "html_data", "ui", "types" ] 
    })
}


/*
 * Group tree
 */
var buildGroupTree = function(id, initially_select) {
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
      "valid_children" : [ "root-category" ],
      "types" : {
        "root-category" : {
          "icon" : { "image" : "img/tree/folder_16x16.png" },
          "valid_children" : [ "category", "group" , "special_target" ],
          "start_drag" : false
        },
        "category" : {
          "icon" : { "image" : "img/tree/folder_16x16.png" },
          "valid_children" : [ "category", "group" , "special_target" ]
        },
        "group" : {
          "icon" : { "image" : "img/tree/server_group_16x16.gif" },
          "valid_children" : "none" 
        },
        "special_target" : {
          "icon" : { "image" : "img/tree/special_target_16x16.gif" },
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
    "plugins" : [ "themes", "html_data", "ui", "types", "dnd", "crrm" ] 
    });
}


/*
 * Policy instance management
 * NOTE: all children are set to none because
 *       we don't want/have to allow node move -
 *       that tree is read only.
 */
var buildPolicyTemplateDependencyTree = function(id, initially_select) {
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
                "image" : "img/tree/folder_16x16.png" 
              },
              "valid_children" : [ "category", "template" ]
            },
            "template" : {
              "icon" : { 
                "image" : "img/tree/blueprint_16x16.png" 
              },
              "valid_children" : [ "policy" ]
            },
            "policy" : {
              "icon" : { 
                "image" : "img/tree/policy_16x16.gif" 
              },
              "valid_children" : [ "rule" ]
            },
            "rule" : {
                "icon" : { 
                  "image" : "img/tree/configuration_rule_16x16.png" 
                },
                "valid_children" : "none"
             },
            "default" : {
              "valid_children" : "none"
            }
          }
        },
      "plugins" : [ "themes", "html_data", "ui", "types" ] 
    })
}



var buildConfigurationRulePIdepTree = function(id, initially_select) {
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
              "image" : "img/tree/folder_16x16.png" 
            },
            "valid_children" : [ "category", "template" ],
            "select_node" : false
          },
          "template" : {
            "icon" : { 
              "image" : "img/tree/blueprint_16x16.png" 
            },
            "valid_children" : [ "policy" ],
            "select_node" : false
          },
          "policy" : {
            "icon" : { 
              "image" : "img/tree/policy_16x16.gif" 
            },
            "valid_children" : "none"
          },
          "default" : {
            "valid_children" : "none"
          }
        }
      },
      "plugins" : [ "themes", "html_data", "ui", "types" ]
    })
}


