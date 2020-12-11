
var app = angular.module('filemanager-editor', ['FileManagerApp'])

app.controller('filemanager-editor', function ($scope, $window, fileManagerConfig, apiMiddleware, apiHandler) {

  $scope.fileManagerState = {
    open : false,
    updating : false,
  };

  $scope.updateResources = null
  $scope.closeWindow = function(event, enforce){
    if((enforce)||(event.currentTarget == event.target)){
      $scope.fileManagerState.open = false;
    }
    $scope.updateResources.send(null)
  }
  $scope.init = function(updateResourcesFun) {
    $scope.updateResources = updateResourcesFun
  }
$scope.updateFileManagerConf = function (newUrl) {
  $scope.fileManagerState.updating = true;

  apiHandler.prototype.deferredHandler = function(data, deferred, code, defaultMsg) {
    if (!data || typeof data !== 'object') {
      this.error = 'Error %s - Bridge response error, please check the API docs or this ajax response.'.replace('%s', code);
    }
    if (code == 404) {
      this.error = 'Error 404 - Backend bridge is not working, please check the ajax response.';
    }
    if (data.result && data.result.error) {
      this.error = data.result.error;
    }
    if (!this.error && data.error) {
      this.error = data.error.message;
    }
    if (!this.error && defaultMsg) {
      this.error = defaultMsg;
    }
    if (this.error) {
      return deferred.reject(data);
    }
    return deferred.resolve(data);
};
  apiMiddleware.prototype.list = function(path, customDeferredHandler) {
    return this.apiHandler.list(newUrl, this.getPath(path), customDeferredHandler);
  };

  apiMiddleware.prototype.upload = function(files, path) {
      if (! $window.FormData) {
          throw new Error('Unsupported browser version');
      }
      var destination = this.getPath(path);
      return this.apiHandler.upload(newUrl, destination, files);
  };


  apiMiddleware.prototype.createFolder = function(item) {
    var path = item.tempModel.fullPath();
    return this.apiHandler.createFolder(newUrl, path);
  };
  apiMiddleware.prototype.getContent = function(item) {
    var itemPath = this.getFilePath(item);
    return this.apiHandler.getContent(newUrl, itemPath);
  };

  apiMiddleware.prototype.rename = function(item) {
    var itemPath = this.getFilePath(item);
    var newPath = item.tempModel.fullPath();

    return this.apiHandler.rename(newUrl, itemPath, newPath);
  };

  apiMiddleware.prototype.remove = function(files) {
    var items = this.getFileList(files);
    return this.apiHandler.remove(newUrl, items);
  };
  apiMiddleware.prototype.edit = function(item) {
    var itemPath = this.getFilePath(item);
    return this.apiHandler.edit(newUrl, itemPath, item.tempModel.content);
  };


  apiMiddleware.prototype.copy = function(files, path) {
    var items = this.getFileList(files);
    var singleFilename = items.length === 1 ? files[0].tempModel.name : undefined;
    return this.apiHandler.copy(newUrl, items, this.getPath(path), singleFilename);
  };

  apiMiddleware.prototype.changePermissions = function(files, dataItem) {
    var items = this.getFileList(files);
    var code = dataItem.tempModel.perms.toCode();
    var octal = dataItem.tempModel.perms.toOctal();
    var recursive = !!dataItem.tempModel.recursive;

    return this.apiHandler.changePermissions(newUrl, items, code, octal, recursive);
  };


  apiMiddleware.prototype.move = function(files, path) {
    var items = this.getFileList(files);
    return this.apiHandler.move(newUrl, items, this.getPath(path));
  };


  apiMiddleware.prototype.download = function(item, forceNewWindow) {
    var itemPath = this.getFilePath(item);
    var toFilename = item.model.name;
    if (item.isFolder()) {
        return;
    }

    return this.apiHandler.download(
        newUrl,
        itemPath,
        toFilename,
        fileManagerConfig.downloadFilesByAjax,
        forceNewWindow
    );
  };

  $scope.fileManagerState.open = true;
}
})


app.config(['fileManagerConfigProvider', function (config) {
  var baseUrl = contextPath ? contextPath : "/rudder";
  var apiPath = baseUrl + '/secure/api/techniques/';
  var defaults = config.$get();

  	config.set({
    appName : 'resources',
    listUrl             : apiPath,
    uploadUrl           : apiPath,
    renameUrl           : apiPath,
    copyUrl             : apiPath,
    moveUrl             : apiPath,
    removeUrl           : apiPath,
    editUrl             : apiPath,
    getContentUrl       : apiPath,
    createFolderUrl     : apiPath,
    downloadFileUrl     : apiPath,
    downloadMultipleUrl : apiPath,
    compressUrl         : apiPath,
    extractUrl          : apiPath,
    permissionsUrl      : apiPath,
    isEditableFilePattern : /.*/,
    tplPath             : baseUrl + '/templates/angular/technique-editor-filemanager',
    allowedActions: angular.extend(defaults.allowedActions, {
      compress: false,
      compressChooseName: false,
      preview : true,
      edit: true,
      extract: false
    })
  });
}]);