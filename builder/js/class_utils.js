// Find OS class data from the class passed as input
// Return a structure containing only data for display, or an empty object if nothing match
/*
    { type: OS Type
      name: OS Name
      majorVersion: Major version contained in the class
      minorVersion: Minor version contained in the class
    }
*/
function find_os_class (myClass, os_list) {
  var result_OS = {};
  // Look into all os from the list
  for (index in os_list) {
    var osClass = os_list[index];
    //Build filter, to match the class, and possible major/minor version
    var os_class = osClass.class
    // Base filter, without versionning
    var filter = "^"+osClass.class+"$";

    // We need to remove parenthesis to handle major/minor version, we will add them back at the end
    var need_parenthesis = false
    if (os_class[0] === "(") {
        os_class = os_class.substring(1,os_class.length-1);
        need_parenthesis = true
    }

    // Split the class on the '|' separator so we can handle each_class separetelyt
    var splitted_class = os_class.split("|");
    if ( osClass.major ) {
      // Add major version filter
      // Add the major version regexp to each class
      var to_add = $.map(splitted_class, function(os,index) {return os+"_\\d+" });
      // Join the final major filter
      var major_class = to_add.join("|");
      // Add parenthesis back
      if (need_parenthesis) {
          major_class = "\\(" + major_class + "\\)";
      }
      // Add major version to filter
      filter += "|^"+major_class+"$";
      if ( osClass.minor ) {
        // Add minor version filter
        // Add the minor version regexp to each class
        to_add = $.map(to_add, function(os,index) {return os+"_\\d+" });
        // Join the final minor filter
        var minor_class = to_add.join("|");
        // Add parenthesis back
        if (need_parenthesis) {
          minor_class = "\\(" + minor_class + "\\)";
        }
        // Add minor version to filter
        filter += "|^"+minor_class+"$";
      }
    }
    // Build regexp from filter
    var regexp = new RegExp(filter, "g");

    if (regexp.test(myClass)) {
      // Regexp match
      // extract version from the class
      var intRegexp = new RegExp("\\d+");
      // Split the class on the '|' character, get the first entry, then split on "_" to extract version value
      var versions = myClass.split("|")[0].split("_");
      // Get only values from version that match an integer
      versions = $.grep(versions, function( value, index) {return intRegexp.test(value);});
      // Build result class
      result_OS = {   type : osClass.name
                    , majorVersion: versions[0]
                    , minorVersion: versions[1]
                  };
      if (osClass.childs.length > 0) {
        result_OS.name = "Any"
      }
    } else {
      // Not found, look into childs
      var child_result = find_os_class(myClass,osClass.childs);
      if ( ! $.isEmptyObject(child_result)  ) {
        // Result is not empty! a child has matched
        // Put the child 'type' in name field
        child_result.name = child_result.type;
        // and the parent name in the type field
        child_result.type = osClass.name;
        result_OS = child_result;
      }
    }
  }
  return result_OS;
}

// Get class from an os type and an os name
function getClass ( os_class ) {
  var myClass;
  // Find the type of os_class defined
  var typeClass = $.grep(cfengine_OS_classes, function(n,i) {return n.name === os_class.type;})[0];
  // is there a specific level child selected, if any is selected skip that part
  if (os_class.name === undefined || os_class.name === "Any") {
    myClass = typeClass.class;
  } else {
    myClass = $.grep(typeClass.childs, function(n,i) {return n.name === os_class.name;})[0].class;
  }
  // We need to handle parenthesis, we need to remove them and add them at the end
  var needParenthesis = false
  if (myClass[0] === "("){
      myClass = myClass.substring(1,myClass.length-1)
      needParenthesis = true
  }
  // Split the class on '|' character so we can handle versions
  var splittedClass = myClass.split("|")

  // Manage versions, add them at the end of each splitted class
  if (!(os_class.majorVersion === undefined || os_class.majorVersion === "" )) {
    if (os_class.minorVersion === undefined|| os_class.minorVersion === "" ) {
      // Add major version for each class
      splittedClass = $.map(splittedClass, function( os, index) { return os+"_"+os_class.majorVersion });
    } else {
      // Add minor version for each class
      splittedClass = $.map(splittedClass, function( os, index) { return os+"_"+os_class.majorVersion+"_"+os_class.minorVersion });
    }
  }

  // finally join splitted class on '|'
  var  final_class = splittedClass.join("|");
  // add potential parenthesis
  if (needParenthesis) {
      final_class = "("+final_class+")";
  }

  return final_class;
}