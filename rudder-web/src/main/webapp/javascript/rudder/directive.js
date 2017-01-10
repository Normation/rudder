$(document).ready(function(){
  var treeId = '#activeTechniquesTree';
  $(window).on('resize',function(){
    adjustHeight(treeId);
  });
  $(treeId).on("searchtag.jstree",function(e, data){
	  data.res.length>0 ? $('#activeTechniquesTree_alert').hide() : $('#activeTechniquesTree_alert').show();
    adjustHeight(treeId);
  });
  $(treeId).on("clear_search.jstree",function(e, data){
    $('#activeTechniquesTree_alert').hide();
    $(this).jstree(true).show_all();
    adjustHeight(treeId);
  });
});