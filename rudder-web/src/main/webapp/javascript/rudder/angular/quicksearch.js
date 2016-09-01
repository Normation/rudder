var quicksearch = angular.module('quicksearch', ["angucomplete-ie8"]);

quicksearch.filter("getResults", function(){
  return function(results,scope){
    if(($.isEmptyObject(scope.results))&&(scope.autoCompleteScope.searchStr)){
      var categories = {};
      var categorie;
      for (result in results){
        categorie = results[result].originalObject.type.toLowerCase();
        if(!categories[categorie]){
          categories[categorie]=[{},[]];
          categories[categorie][0]=results[result]
        }else{
          categories[categorie][1].push(results[result])
        }
      }
      scope.results = categories;
      scope.getNumResults();
    }
    return scope.results;
  }
});
quicksearch.controller('QuicksearchCtrl', function QuicksearchCtrl($scope, $rootScope) {
  $scope.strBaseSearch='';
  $scope.results;
  $scope.filter = {"all":{activated:true,nbResults:0}
                  ,"directive":{activated:false,nbResults:0}
                  ,"group":{activated:false,nbResults:0}
                  ,"node":{activated:false,nbResults:0}
                  ,"rule":{activated:false,nbResults:0}
                  ,"parameter":{activated:false,nbResults:0}
                  };
  $scope.setFocus = function(selector){
    $(selector).focus();
  };
  $scope.docinfo = [];
  $scope.selectedObject = function(selected) {
    console.log(selected);
    if(selected && selected.url) {
      window.location = selected.url;
    } else {
      return "";
    }
  }
  $scope.autoCompleteScope = {};
  $scope.setValueSearchInput = function (value){
    $scope.results = {};
    $scope.autoCompleteScope = angular.element("#searchInput").scope();
    $scope.autoCompleteScope.searchStr=value;
    $('#searchInput').val(value);
    $scope.setFocus('strBaseSearch#searchInput');
    $('#searchInput').trigger('keyup');
  }
  $scope.getValueSearchInput = function (){
    return $('#searchInput').val();
  }
  $scope.addFilter = function(filter) {
    $scope.filter[filter].activated=true;
    $scope.setValueSearchInput($scope.getValueSearchInput()+" is:" + filter);
  }
  $scope.removeFilters = function(filters) {
    var regExpString;
    var regexpA;
    var regexpB;
    var newVal = $scope.getValueSearchInput();
    for(var i=0 ; i<filters.length ; i++){
      $scope.filter[filters[i]].activated=false;
      regExpString = 'is:\\s*'+filters[i]+'\\s*,\\s*';
      regexpA = new RegExp(regExpString , "gi");
      regExpString = '((is:\\s*'+filters[i]+'\\s*)|(,'+filters[i]+'))';
      regexpB = new RegExp(regExpString , "gi");
      if(newVal.search(regexpA)>=0){
        newVal = newVal.replace(regexpA, 'is:');
      }else if(newVal.search(regexpB)>=0){
        newVal = newVal.replace(regexpB, '');
      }
    }
    $scope.setValueSearchInput(newVal.trim());
  }
  $scope.activeFilter = function(filter) {
    $scope.filter[filter].activated=true;
    $('#filter-'+filter).parent().addClass('active');
  }
  $scope.desactiveFilter = function(filter) {
    $scope.filter[filter].activated=false;
    $('#filter-'+filter).parent().removeClass('active');
  }
  $scope.refreshFilterSearch = function(inputField) {
    $scope.autoCompleteScope.searchStr=inputField;
    $scope.results = {};
    var regexp;
    for (filter in $scope.filter){
      regexp = new RegExp('is:(\\s*[a-z0-9]+\\s*,)*\\s*'+filter, "gi");
      if(inputField.search(regexp)>=0){
        $scope.desactiveFilter('all');
        $scope.activeFilter(filter);
      }else if(filter != 'all'){
        $scope.desactiveFilter(filter);
      }
    }

  }
  $scope.checkFilter =function(event, isAll,isChecked,filterName){
    if(isAll){
      $('.group-filters .active').removeClass('active');
      for(filter in $scope.filter){
        if(filter=='all'){
          $scope.filter[filter].activated=true;
        }else{
          $scope.filter[filter].activated=false;
        }
      }
      var properties = Object.getOwnPropertyNames($scope.filter);
      $scope.removeFilters(properties);
    }else{
      $('.group-all .active').removeClass('active');
      $scope.filter.all.activated=false;
      if(!isChecked){
        $scope.addFilter(filterName);
      }else{
        $scope.removeFilters([filterName]);
      }
    }
  }
  $scope.getNumResults = function(){
    var num;
    var result;
    var strRegExp = '((\\s*in:\\s*((directive)|(group)|(node)|(parameter)|(rule))+\\s*,*\\s*)|(,\\s*((directive)|(group)|(node)|(parameter)|(rule))+\\s*))';
    var regexp = new RegExp(strRegExp , "gi");
    var baseSearch = $('#searchInput').val().replace(regexp, '');

    //Si il n'y a pas de filtre
    if((($('#searchInput').val().match(regexp))&&(baseSearch.toUpperCase()!==$scope.strBaseSearch.toUpperCase()))||(!$('#searchInput').val().match(regexp))){
      $scope.strBaseSearch = baseSearch;
      $scope.filter.all.nbResults = 0;
      for(filter in $scope.filter){
        result = $scope.results.hasOwnProperty(filter) ? $scope.results[filter] : false;
        if(result){
          num = result[0].originalObject.numbers;
        }else{
          num = 0;
        }
        $scope.filter.all.nbResults += num;
        $scope.filter[filter].nbResults = num;
      }
    //Si il y a des filtres
    }
  }

  $scope.noSearch = function() {
    return $scope.searchStr.length<1;
  }
} );

// Helper function to access from outside angular scope
function initQuicksearchDocinfo(json) {
  var scope = angular.element($("#quicksearch")).scope();
  scope.$apply(function() {
    scope.docinfo = JSON.parse(json);
  });
  (function () {
    if($('.angucomplete-holder .input-group').offset().left==15){
      $('.dropdown-search.help').offset({left : $('.angucomplete-holder .input-group').offset().left});
    }else{
      $('.dropdown-search.help').css('left','');
    }
  })();
  $(window).resize(function(){
    if($('.angucomplete-holder .input-group').offset().left==15){
      $('.dropdown-search.help').offset({left : $('.angucomplete-holder .input-group').offset().left});
    }else{
      $('.dropdown-search.help').css('left','');
    }
  });
  $('#searchInput').on('focus', function (event) {
    if($(this).val().length>0){
      $(this).parent().addClass('open');
    }
  });
  $('#toggleDoc, #toggleResult').click(function (e) {
    $('#search-tab').toggleClass('hidden');
    $('#info-tab').toggleClass('hidden');
  })
  $(document).on('click', function (e) {
    var el = $('.group-search .dropdown-menu.dropdown-search');
    if((!el.is(e.target))&&(el.has(e.target).length === 0)&&($('.open').has(e.target).length === 0)){
      $('.group-search').removeClass('open');
    }
  });
};
