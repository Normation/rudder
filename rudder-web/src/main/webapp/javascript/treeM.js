  //<![CDATA[
    jQuery.extend(jQuery.expr[":"], {  
      "notContainsCI": function(elem, i, match, array) {  
        return (elem.textContent || elem.innerText || "").toLowerCase().indexOf((match[3] || "").toLowerCase()) < 0;  
       }  
    }); 
    
    var filterLi = function(filter) {
          jQuery(".filteredNode").removeClass("filteredNode");
          if( jQuery.tree && jQuery.tree.focused() && jQuery.tree.focused().selected ) {
            jQuery.tree.focused().selected.each( function(i) {    
              jQuery(this).find("li:notContainsCI("+filter+")").addClass("filteredNode");
            });
          }
    }
    
    jQuery(document).ready(function() {
      var filterTimer;
	    jQuery("#filterNode").keyup(function(event) {
	      var filter = jQuery(this).val();
        clearTimeout(filterTimer);
        if(event.keyCode == 13) { 
          filterLi(filter); 
        } else {
          filterTimer = setTimeout(function() {
            filterLi(filter);
          }, 1000);
        }
	    });
    }); 
  //]]>