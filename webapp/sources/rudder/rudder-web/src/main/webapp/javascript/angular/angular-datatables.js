/*
 * An angular JS module for datatables. 
 */
angular
    .module('DataTables', [])
    .directive('datatables', function() {

    return function(scope, element, attrs) {

      
        // apply DataTable options, use defaults if none specified by user
        var options = {};
        if (attrs.datatables.length > 0) {
            options = scope.$eval(attrs.datatables);
        } else {
            options = {
                "bStateSave": true,
                "iCookieDuration": 2419200, /* 1 month */
                "bJQueryUI": true,
                "bPaginate": false,
                "bLengthChange": false,
                "bFilter": false,
                "bInfo": false,
                "bDestroy": true
            };
        }

        // aoColumnDefs is dataTables way of providing fine control over column config
        if (attrs.aoColumnDefs) {
            columns = scope.$eval(attrs.aoColumnDefs);
        }

        if (attrs.fnRowCallback) {
            options["fnRowCallback"] = scope.$eval(attrs.fnRowCallback);
        }
        var refresh = scope.$eval(attrs.refresh)

        // apply the plugin
        var dataTable = createTable(element.attr('id'),[], columns, options, contextPath, refresh, "api_accounts");


        
        
        // watch for any changes to our data, rebuild the DataTable
        scope.$watch(attrs.aaData, function(newValue, oldValue) {
            var val = newValue || null;
            if (val) {
              dataTable.clear();
              dataTable.rows.add(scope.$eval(attrs.aaData));
              dataTable.draw();
          }
        }, true);
    };
});
