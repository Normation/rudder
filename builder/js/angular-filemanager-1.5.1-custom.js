(function(window, angular, $) {
    'use strict';
    angular.module('FileManagerApp', ['pascalprecht.translate', 'ngFileUpload']);

    /**
     * jQuery inits
     */
    $(window.document).on('shown.bs.modal', '.modal', function() {
        window.setTimeout(function() {
            $('[autofocus]', this).focus();
        }.bind(this), 100);
    });

    $(window.document).on('click', function() {
        $('#context-menu').hide();
    });

    $(window.document).on('contextmenu', '.main-navigation .table-files tr.item-list:has("td"), .item-list', function(e) {
        var menu = $('#context-menu');

        if (e.pageX >= window.innerWidth - menu.width()) {
            e.pageX -= menu.width();
        }
        if (e.pageY >= window.innerHeight - menu.height()) {
            e.pageY -= menu.height();
        }

        menu.hide().css({
            left: e.pageX,
            top: e.pageY
        }).show();
        e.preventDefault();
    });

    if (! Array.prototype.find) {
        Array.prototype.find = function(predicate) {
            if (this == null) {
                throw new TypeError('Array.prototype.find called on null or undefined');
            }
            if (typeof predicate !== 'function') {
                throw new TypeError('predicate must be a function');
            }
            var list = Object(this);
            var length = list.length >>> 0;
            var thisArg = arguments[1];
            var value;

            for (var i = 0; i < length; i++) {
                value = list[i];
                if (predicate.call(thisArg, value, i, list)) {
                    return value;
                }
            }
            return undefined;
        };
    }
 
})(window, angular, jQuery);

(function(angular) {
    'use strict';
    var app = angular.module('FileManagerApp');

    app.directive('angularFilemanager', ['$parse', 'fileManagerConfig', function($parse, fileManagerConfig) {
        return {
            restrict: 'EA',
            templateUrl: fileManagerConfig.tplPath + '/main.html'
        };
    }]);

    app.directive('ngFile', ['$parse', function($parse) {
        return {
            restrict: 'A',
            link: function(scope, element, attrs) {
                var model = $parse(attrs.ngFile);
                var modelSetter = model.assign;

                element.bind('change', function() {
                    scope.$apply(function() {
                        modelSetter(scope, element[0].files);
                    });
                });
            }
        };
    }]);

    app.directive('ngRightClick', ['$parse', function($parse) {
        return function(scope, element, attrs) {
            var fn = $parse(attrs.ngRightClick);
            element.bind('contextmenu', function(event) {
                scope.$apply(function() {
                    event.preventDefault();
                    fn(scope, {$event: event});
                });
            });
        };
    }]);
    
})(angular);

(function(angular, $) {
    'use strict';
    angular.module('FileManagerApp').controller('FileManagerCtrl', [
        '$scope', '$rootScope', '$window', '$translate', 'fileManagerConfig', 'item', 'fileNavigator', 'apiMiddleware',
        function($scope, $rootScope, $window, $translate, fileManagerConfig, Item, FileNavigator, ApiMiddleware) {

        var $storage = $window.localStorage;
        $scope.config = fileManagerConfig;
        $scope.reverse = false;
        $scope.predicate = ['model.type', 'model.name'];        
        $scope.order = function(predicate) {
            $scope.reverse = ($scope.predicate[1] === predicate) ? !$scope.reverse : false;
            $scope.predicate[1] = predicate;
        };
        $scope.query = '';
        $scope.fileNavigator = new FileNavigator();
        $scope.apiMiddleware = new ApiMiddleware();
        $scope.uploadFileList = [];
        $scope.viewTemplate = $storage.getItem('viewTemplate') || 'main-icons.html';
        $scope.fileList = [];
        $scope.temps = [];

        $scope.$watch('temps', function() {
            if ($scope.singleSelection()) {
                $scope.temp = $scope.singleSelection();
            } else {
                $scope.temp = new Item({rights: 644});
                $scope.temp.multiple = true;
            }
            $scope.temp.revert();
        });

        $scope.fileNavigator.onRefresh = function() {
            $scope.temps = [];
            $scope.query = '';
            $rootScope.selectedModalPath = $scope.fileNavigator.currentPath;
        };

        $scope.setTemplate = function(name) {
            $storage.setItem('viewTemplate', name);
            $scope.viewTemplate = name;
        };

        $scope.changeLanguage = function (locale) {
            if (locale) {
                $storage.setItem('language', locale);
                return $translate.use(locale);
            }
            $translate.use($storage.getItem('language') || fileManagerConfig.defaultLang);
        };

        $scope.isSelected = function(item) {
            return $scope.temps.indexOf(item) !== -1;
        };

        $scope.selectOrUnselect = function(item, $event) {
            var indexInTemp = $scope.temps.indexOf(item);
            var isRightClick = $event && $event.which == 3;

            if ($event && $event.target.hasAttribute('prevent')) {
                $scope.temps = [];
                return;
            }
            if (! item || (isRightClick && $scope.isSelected(item))) {
                return;
            }
            if ($event && $event.shiftKey && !isRightClick) {
                var list = $scope.fileList;
                var indexInList = list.indexOf(item);
                var lastSelected = $scope.temps[0];
                var i = list.indexOf(lastSelected);
                var current = undefined;
                if (lastSelected && list.indexOf(lastSelected) < indexInList) {
                    $scope.temps = [];
                    while (i <= indexInList) {
                        current = list[i];
                        !$scope.isSelected(current) && $scope.temps.push(current);
                        i++;
                    }
                    return;
                }
                if (lastSelected && list.indexOf(lastSelected) > indexInList) {
                    $scope.temps = [];
                    while (i >= indexInList) {
                        current = list[i];
                        !$scope.isSelected(current) && $scope.temps.push(current);
                        i--;
                    }
                    return;
                }
            }
            if ($event && !isRightClick && ($event.ctrlKey || $event.metaKey)) {
                $scope.isSelected(item) ? $scope.temps.splice(indexInTemp, 1) : $scope.temps.push(item);
                return;
            }
            $scope.temps = [item];
        };

        $scope.singleSelection = function() {
            return $scope.temps.length === 1 && $scope.temps[0];
        };

        $scope.totalSelecteds = function() {
            return {
                total: $scope.temps.length
            };
        };

        $scope.selectionHas = function(type) {
            return $scope.temps.find(function(item) {
                return item && item.model.type === type;
            });
        };

        $scope.prepareNewFolder = function() {
            var item = new Item(null, $scope.fileNavigator.currentPath);
            $scope.temps = [item];
            return item;
        };

        $scope.smartClick = function(item) {
            var pick = $scope.config.allowedActions.pickFiles;
            if (item.isFolder()) {
                return $scope.fileNavigator.folderClick(item);
            }

            if (typeof $scope.config.pickCallback === 'function' && pick) {
                var callbackSuccess = $scope.config.pickCallback(item.model);
                if (callbackSuccess === true) {
                    return;
                }
            }

            if (item.isImage()) {
                if ($scope.config.previewImagesInModal) {
                    return $scope.openImagePreview(item);
                } 
                return $scope.apiMiddleware.download(item, true);
            }
            
            if (item.isEditable()) {
                return $scope.openEditItem(item);
            }
        };

        $scope.openImagePreview = function() {
            var item = $scope.singleSelection();
            $scope.apiMiddleware.apiHandler.inprocess = true;
            $scope.modal('imagepreview', null, true)
                .find('#imagepreview-target')
                .attr('src', $scope.apiMiddleware.getUrl(item))
                .unbind('load error')
                .on('load error', function() {
                    $scope.apiMiddleware.apiHandler.inprocess = false;
                    $scope.$apply();
                });
        };

        $scope.openEditItem = function() {
            var item = $scope.singleSelection();
            $scope.apiMiddleware.getContent(item).then(function(data) {
                item.tempModel.content = item.model.content = data.result;
            });
            $scope.modal('edit');
        };

        $scope.modal = function(id, hide, returnElement) {
            var element = $('#' + id);
            element.modal(hide ? 'hide' : 'show');
            $scope.apiMiddleware.apiHandler.error = '';
            $scope.apiMiddleware.apiHandler.asyncSuccess = false;
            return returnElement ? element : true;
        };

        $scope.modalWithPathSelector = function(id) {
            $rootScope.selectedModalPath = $scope.fileNavigator.currentPath;
            return $scope.modal(id);
        };

        $scope.isInThisPath = function(path) {
            var currentPath = $scope.fileNavigator.currentPath.join('/');
            return currentPath.indexOf(path) !== -1;
        };

        $scope.edit = function() {
            $scope.apiMiddleware.edit($scope.singleSelection()).then(function() {
                $scope.modal('edit', true);
            });
        };

        $scope.changePermissions = function() {
            $scope.apiMiddleware.changePermissions($scope.temps, $scope.temp).then(function() {
                $scope.modal('changepermissions', true);
            });
        };

        $scope.download = function() {
            var item = $scope.singleSelection();
            if ($scope.selectionHas('dir')) {
                return;
            }
            if (item) {
                return $scope.apiMiddleware.download(item);
            }
            return $scope.apiMiddleware.downloadMultiple($scope.temps);
        };

        $scope.copy = function() {
            var item = $scope.singleSelection();
            if (item) {
                var name = item.tempModel.name.trim();
                var nameExists = $scope.fileNavigator.fileNameExists(name);
                if (nameExists && validateSamePath(item)) {
                    $scope.apiMiddleware.apiHandler.error = $translate.instant('error_invalid_filename');
                    return false;
                }
                if (!name) {
                    $scope.apiMiddleware.apiHandler.error = $translate.instant('error_invalid_filename');
                    return false;
                }
            }
            $scope.apiMiddleware.copy($scope.temps, $rootScope.selectedModalPath).then(function() {
                $scope.fileNavigator.refresh();
                $scope.modal('copy', true);
            });
        };

        $scope.compress = function() {
            var name = $scope.temp.tempModel.name.trim();
            var nameExists = $scope.fileNavigator.fileNameExists(name);

            if (nameExists && validateSamePath($scope.temp)) {
                $scope.apiMiddleware.apiHandler.error = $translate.instant('error_invalid_filename');
                return false;
            }
            if (!name) {
                $scope.apiMiddleware.apiHandler.error = $translate.instant('error_invalid_filename');
                return false;
            }

            $scope.apiMiddleware.compress($scope.temps, name, $rootScope.selectedModalPath).then(function() {
                $scope.fileNavigator.refresh();
                if (! $scope.config.compressAsync) {
                    return $scope.modal('compress', true);
                }
                $scope.apiMiddleware.apiHandler.asyncSuccess = true;
            }, function() {
                $scope.apiMiddleware.apiHandler.asyncSuccess = false;
            });
        };

        $scope.extract = function() {
            var item = $scope.temp;
            var name = $scope.temp.tempModel.name.trim();
            var nameExists = $scope.fileNavigator.fileNameExists(name);

            if (nameExists && validateSamePath($scope.temp)) {
                $scope.apiMiddleware.apiHandler.error = $translate.instant('error_invalid_filename');
                return false;
            }
            if (!name) {
                $scope.apiMiddleware.apiHandler.error = $translate.instant('error_invalid_filename');
                return false;
            }

            $scope.apiMiddleware.extract(item, name, $rootScope.selectedModalPath).then(function() {
                $scope.fileNavigator.refresh();
                if (! $scope.config.extractAsync) {
                    return $scope.modal('extract', true);
                }
                $scope.apiMiddleware.apiHandler.asyncSuccess = true;
            }, function() {
                $scope.apiMiddleware.apiHandler.asyncSuccess = false;
            });
        };

        $scope.remove = function() {
            $scope.apiMiddleware.remove($scope.temps).then(function() {
                $scope.fileNavigator.refresh();
                $scope.modal('remove', true);
            });
        };

        $scope.move = function() {           
            var anyItem = $scope.singleSelection() || $scope.temps[0];
            if (anyItem && validateSamePath(anyItem)) {
                $scope.apiMiddleware.apiHandler.error = $translate.instant('error_cannot_move_same_path');
                return false;
            }
            $scope.apiMiddleware.move($scope.temps, $rootScope.selectedModalPath).then(function() {
                $scope.fileNavigator.refresh();
                $scope.modal('move', true);
            });
        };

        $scope.rename = function() {
            var item = $scope.singleSelection();
            var name = item.tempModel.name;
            var samePath = item.tempModel.path.join('') === item.model.path.join('');
            if (!name || (samePath && $scope.fileNavigator.fileNameExists(name))) {
                $scope.apiMiddleware.apiHandler.error = $translate.instant('error_invalid_filename');
                return false;
            }
            $scope.apiMiddleware.rename(item).then(function() {
                $scope.fileNavigator.refresh();
                $scope.modal('rename', true);
            });
        };

        $scope.createFolder = function() {
            var item = $scope.singleSelection();
            var name = item.tempModel.name;
            if (!name || $scope.fileNavigator.fileNameExists(name)) {
                return $scope.apiMiddleware.apiHandler.error = $translate.instant('error_invalid_filename');
            }
            $scope.apiMiddleware.createFolder(item).then(function() {
                $scope.fileNavigator.refresh();
                $scope.modal('newfolder', true);
            });
        };

        $scope.addForUpload = function($files) {
            $scope.uploadFileList = $scope.uploadFileList.concat($files);
            $scope.modal('uploadfile');
        };

        $scope.removeFromUpload = function(index) {
            $scope.uploadFileList.splice(index, 1);
        };

        $scope.uploadFiles = function() {
            $scope.apiMiddleware.upload($scope.uploadFileList, $scope.fileNavigator.currentPath).then(function() {
                $scope.fileNavigator.refresh();
                $scope.uploadFileList = [];
                $scope.modal('uploadfile', true);
            }, function(data) {
                var errorMsg = data.result && data.result.error || $translate.instant('error_uploading_files');
                $scope.apiMiddleware.apiHandler.error = errorMsg;
            });
        };

        var validateSamePath = function(item) {
            var selectedPath = $rootScope.selectedModalPath.join('');
            var selectedItemsPath = item && item.model.path.join('');
            return selectedItemsPath === selectedPath;
        };

        var getQueryParam = function(param) {
            var found = $window.location.search.substr(1).split('&').filter(function(item) {
                return param ===  item.split('=')[0];
            });
            return found[0] && found[0].split('=')[1] || undefined;
        };

        $scope.changeLanguage(getQueryParam('lang'));
        $scope.isWindows = getQueryParam('server') === 'Windows';
        $scope.fileNavigator.refresh();

    }]);
})(angular, jQuery);

(function(angular) {
    'use strict';
    angular.module('FileManagerApp').controller('ModalFileManagerCtrl', 
        ['$scope', '$rootScope', 'fileNavigator', function($scope, $rootScope, FileNavigator) {

        $scope.reverse = false;
        $scope.predicate = ['model.type', 'model.name'];
        $scope.fileNavigator = new FileNavigator();
        $rootScope.selectedModalPath = [];

        $scope.order = function(predicate) {
            $scope.reverse = ($scope.predicate[1] === predicate) ? !$scope.reverse : false;
            $scope.predicate[1] = predicate;
        };

        $scope.select = function(item) {
            $rootScope.selectedModalPath = item.model.fullPath().split('/');
            $scope.modal('selector', true);
        };

        $scope.selectCurrent = function() {
            $rootScope.selectedModalPath = $scope.fileNavigator.currentPath;
            $scope.modal('selector', true);
        };

        $scope.selectedFilesAreChildOfPath = function(item) {
            var path = item.model.fullPath();
            return $scope.temps.find(function(item) {
                var itemPath = item.model.fullPath();
                if (path == itemPath) {
                    return true;
                }
                /*
                if (path.startsWith(itemPath)) {
                    fixme names in same folder like folder-one and folder-one-two
                    at the moment fixed hidding affected folders
                }
                */
            });
        };

        $rootScope.openNavigator = function(path) {
            $scope.fileNavigator.currentPath = path;
            $scope.fileNavigator.refresh();
            $scope.modal('selector');
        };

        $rootScope.getSelectedPath = function() {
            var path = $rootScope.selectedModalPath.filter(Boolean);
            var result = '/' + path.join('/');
            if ($scope.singleSelection() && !$scope.singleSelection().isFolder()) {
                result += '/' + $scope.singleSelection().tempModel.name;
            }
            return result.replace(/\/\//, '/');
        };

    }]);
})(angular);
(function(angular) {
    'use strict';
    angular.module('FileManagerApp').service('chmod', function () {

        var Chmod = function(initValue) {
            this.owner = this.getRwxObj();
            this.group = this.getRwxObj();
            this.others = this.getRwxObj();

            if (initValue) {
                var codes = isNaN(initValue) ?
                    this.convertfromCode(initValue):
                    this.convertfromOctal(initValue);

                if (! codes) {
                    throw new Error('Invalid chmod input data (%s)'.replace('%s', initValue));
                }

                this.owner = codes.owner;
                this.group = codes.group;
                this.others = codes.others;
            }
        };

        Chmod.prototype.toOctal = function(prepend, append) {
            var result = [];
            ['owner', 'group', 'others'].forEach(function(key, i) {
                result[i]  = this[key].read  && this.octalValues.read  || 0;
                result[i] += this[key].write && this.octalValues.write || 0;
                result[i] += this[key].exec  && this.octalValues.exec  || 0;
            }.bind(this));
            return (prepend||'') + result.join('') + (append||'');
        };

        Chmod.prototype.toCode = function(prepend, append) {
            var result = [];
            ['owner', 'group', 'others'].forEach(function(key, i) {
                result[i]  = this[key].read  && this.codeValues.read  || '-';
                result[i] += this[key].write && this.codeValues.write || '-';
                result[i] += this[key].exec  && this.codeValues.exec  || '-';
            }.bind(this));
            return (prepend||'') + result.join('') + (append||'');
        };

        Chmod.prototype.getRwxObj = function() {
            return {
                read: false,
                write: false,
                exec: false
            };
        };

        Chmod.prototype.octalValues = {
            read: 4, write: 2, exec: 1
        };

        Chmod.prototype.codeValues = {
            read: 'r', write: 'w', exec: 'x'
        };

        Chmod.prototype.convertfromCode = function (str) {
            str = ('' + str).replace(/\s/g, '');
            str = str.length === 10 ? str.substr(1) : str;
            if (! /^[-rwxts]{9}$/.test(str)) {
                return;
            }

            var result = [], vals = str.match(/.{1,3}/g);
            for (var i in vals) {
                var rwxObj = this.getRwxObj();
                rwxObj.read  = /r/.test(vals[i]);
                rwxObj.write = /w/.test(vals[i]);
                rwxObj.exec  = /x|t/.test(vals[i]);
                result.push(rwxObj);
            }

            return {
                owner : result[0],
                group : result[1],
                others: result[2]
            };
        };

        Chmod.prototype.convertfromOctal = function (str) {
            str = ('' + str).replace(/\s/g, '');
            str = str.length === 4 ? str.substr(1) : str;
            if (! /^[0-7]{3}$/.test(str)) {
                return;
            }

            var result = [], vals = str.match(/.{1}/g);
            for (var i in vals) {
                var rwxObj = this.getRwxObj();
                rwxObj.read  = /[4567]/.test(vals[i]);
                rwxObj.write = /[2367]/.test(vals[i]);
                rwxObj.exec  = /[1357]/.test(vals[i]);
                result.push(rwxObj);
            }

            return {
                owner : result[0],
                group : result[1],
                others: result[2]
            };
        };

        return Chmod;
    });
})(angular);
(function(angular) {
    'use strict';
    angular.module('FileManagerApp').factory('item', ['fileManagerConfig', 'chmod', function(fileManagerConfig, Chmod) {

        var Item = function(model, path) {
            var rawModel = {
                name: model && model.name || '',
                path: path || [],
                type: model && model.type || 'file',
                size: model && parseInt(model.size || 0),
                date: parseMySQLDate(model && model.date),
                perms: new Chmod(model && model.rights),
                content: model && model.content || '',
                recursive: false,
                fullPath: function() {
                    var path = this.path.filter(Boolean);
                    return ('/' + path.join('/') + '/' + this.name).replace(/\/\//, '/');
                }
            };

            this.error = '';
            this.processing = false;

            this.model = angular.copy(rawModel);
            this.tempModel = angular.copy(rawModel);

            function parseMySQLDate(mysqlDate) {
                var d = (mysqlDate || '').toString().split(/[- :]/);
                return new Date(d[0], d[1] - 1, d[2], d[3], d[4], d[5]);
            }
        };

        Item.prototype.update = function() {
            angular.extend(this.model, angular.copy(this.tempModel));
        };

        Item.prototype.revert = function() {
            angular.extend(this.tempModel, angular.copy(this.model));
            this.error = '';
        };

        Item.prototype.isFolder = function() {
            return this.model.type === 'dir';
        };

        Item.prototype.isEditable = function() {
            return !this.isFolder() && fileManagerConfig.isEditableFilePattern.test(this.model.name);
        };

        Item.prototype.isImage = function() {
            return fileManagerConfig.isImageFilePattern.test(this.model.name);
        };

        Item.prototype.isCompressible = function() {
            return this.isFolder();
        };

        Item.prototype.isExtractable = function() {
            return !this.isFolder() && fileManagerConfig.isExtractableFilePattern.test(this.model.name);
        };

        Item.prototype.isSelectable = function() {
            return (this.isFolder() && fileManagerConfig.allowedActions.pickFolders) || (!this.isFolder() && fileManagerConfig.allowedActions.pickFiles);
        };

        return Item;
    }]);
})(angular);
(function(angular) {
    'use strict';
    var app = angular.module('FileManagerApp');

    app.filter('strLimit', ['$filter', function($filter) {
        return function(input, limit, more) {
            if (input.length <= limit) {
                return input;
            }
            return $filter('limitTo')(input, limit) + (more || '...');
        };
    }]);

    app.filter('fileExtension', ['$filter', function($filter) {
        return function(input) {
            return /\./.test(input) && $filter('strLimit')(input.split('.').pop(), 3, '..') || '';
        };
    }]);

    app.filter('formatDate', ['$filter', function() {
        return function(input) {
            return input instanceof Date ?
                input.toISOString().substring(0, 19).replace('T', ' ') :
                (input.toLocaleString || input.toString).apply(input);
        };
    }]);

    app.filter('humanReadableFileSize', ['$filter', 'fileManagerConfig', function($filter, fileManagerConfig) {
      // See https://en.wikipedia.org/wiki/Binary_prefix
      var decimalByteUnits = [' kB', ' MB', ' GB', ' TB', 'PB', 'EB', 'ZB', 'YB'];
      var binaryByteUnits = ['KiB', 'MiB', 'GiB', 'TiB', 'PiB', 'EiB', 'ZiB', 'YiB'];

      return function(input) {
        var i = -1;
        var fileSizeInBytes = input;

        do {
          fileSizeInBytes = fileSizeInBytes / 1024;
          i++;
        } while (fileSizeInBytes > 1024);

        var result = fileManagerConfig.useBinarySizePrefixes ? binaryByteUnits[i] : decimalByteUnits[i];
        return Math.max(fileSizeInBytes, 0.1).toFixed(1) + ' ' + result;
      };
    }]);
})(angular);

(function(angular, $) {
    'use strict';
    angular.module('FileManagerApp').service('apiHandler', ['$http', '$q', '$window', '$translate', 'Upload',
        function ($http, $q, $window, $translate, Upload) {

        $http.defaults.headers.common['X-Requested-With'] = 'XMLHttpRequest';

        var ApiHandler = function() {
            this.inprocess = false;
            this.asyncSuccess = false;
            this.error = '';
        };

        ApiHandler.prototype.deferredHandler = function(data, deferred, code, defaultMsg) {
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

        ApiHandler.prototype.list = function(apiUrl, path, customDeferredHandler) {
            var self = this;
            var dfHandler = customDeferredHandler || self.deferredHandler;
            var deferred = $q.defer();
            var data = {
                action: 'list',
                path: path
            };

            self.inprocess = true;
            self.error = '';

            $http.post(apiUrl, data).success(function(data, code) {
                dfHandler(data, deferred, code);
            }).error(function(data, code) {
                dfHandler(data, deferred, code, 'Unknown error listing, check the response');
            })['finally'](function() {
                self.inprocess = false;
            });
            return deferred.promise;
        };

        ApiHandler.prototype.copy = function(apiUrl, items, path, singleFilename) {
            var self = this;
            var deferred = $q.defer();
            var data = {
                action: 'copy',
                items: items,
                newPath: path
            };

            if (singleFilename && items.length === 1) {
                data.singleFilename = singleFilename;
            }
            
            self.inprocess = true;
            self.error = '';
            $http.post(apiUrl, data).success(function(data, code) {
                self.deferredHandler(data, deferred, code);
            }).error(function(data, code) {
                self.deferredHandler(data, deferred, code, $translate.instant('error_copying'));
            })['finally'](function() {
                self.inprocess = false;
            });
            return deferred.promise;
        };

        ApiHandler.prototype.move = function(apiUrl, items, path) {
            var self = this;
            var deferred = $q.defer();
            var data = {
                action: 'move',
                items: items,
                newPath: path
            };
            self.inprocess = true;
            self.error = '';
            $http.post(apiUrl, data).success(function(data, code) {
                self.deferredHandler(data, deferred, code);
            }).error(function(data, code) {
                self.deferredHandler(data, deferred, code, $translate.instant('error_moving'));
            })['finally'](function() {
                self.inprocess = false;
            });
            return deferred.promise;
        };

        ApiHandler.prototype.remove = function(apiUrl, items) {
            var self = this;
            var deferred = $q.defer();
            var data = {
                action: 'remove',
                items: items
            };

            self.inprocess = true;
            self.error = '';
            $http.post(apiUrl, data).success(function(data, code) {
                self.deferredHandler(data, deferred, code);
            }).error(function(data, code) {
                self.deferredHandler(data, deferred, code, $translate.instant('error_deleting'));
            })['finally'](function() {
                self.inprocess = false;
            });
            return deferred.promise;
        };

        ApiHandler.prototype.upload = function(apiUrl, destination, files) {
            var self = this;
            var deferred = $q.defer();
            self.inprocess = true;
            self.progress = 0;
            self.error = '';

            var data = {
                destination: destination
            };

            for (var i = 0; i < files.length; i++) {
                data['file-' + i] = files[i];
            }

            if (files && files.length) {
                Upload.upload({
                    url: apiUrl,
                    data: data
                }).then(function (data) {
                    self.deferredHandler(data.data, deferred, data.status);
                }, function (data) {
                    self.deferredHandler(data.data, deferred, data.status, 'Unknown error uploading files');
                }, function (evt) {
                    self.progress = Math.min(100, parseInt(100.0 * evt.loaded / evt.total)) - 1;
                })['finally'](function() {
                    self.inprocess = false;
                    self.progress = 0;
                });
            }

            return deferred.promise;
        };

        ApiHandler.prototype.getContent = function(apiUrl, itemPath) {            
            var self = this;
            var deferred = $q.defer();
            var data = {
                action: 'getContent',
                item: itemPath
            };

            self.inprocess = true;
            self.error = '';
            $http.post(apiUrl, data).success(function(data, code) {
                self.deferredHandler(data, deferred, code);
            }).error(function(data, code) {
                self.deferredHandler(data, deferred, code, $translate.instant('error_getting_content'));
            })['finally'](function() {
                self.inprocess = false;
            });
            return deferred.promise;
        };

        ApiHandler.prototype.edit = function(apiUrl, itemPath, content) {
            var self = this;
            var deferred = $q.defer();
            var data = {
                action: 'edit',
                item: itemPath,
                content: content
            };

            self.inprocess = true;
            self.error = '';

            $http.post(apiUrl, data).success(function(data, code) {
                self.deferredHandler(data, deferred, code);
            }).error(function(data, code) {
                self.deferredHandler(data, deferred, code, $translate.instant('error_modifying'));
            })['finally'](function() {
                self.inprocess = false;
            });
            return deferred.promise;
        };

        ApiHandler.prototype.rename = function(apiUrl, itemPath, newPath) {
            var self = this;
            var deferred = $q.defer();
            var data = {
                action: 'rename',
                item: itemPath,
                newItemPath: newPath
            };
            self.inprocess = true;
            self.error = '';
            $http.post(apiUrl, data).success(function(data, code) {
                self.deferredHandler(data, deferred, code);
            }).error(function(data, code) {
                self.deferredHandler(data, deferred, code, $translate.instant('error_renaming'));
            })['finally'](function() {
                self.inprocess = false;
            });
            return deferred.promise;
        };

        ApiHandler.prototype.getUrl = function(apiUrl, path) {
            var data = {
                action: 'download',
                path: path
            };
            return path && [apiUrl, $.param(data)].join('?');
        };

        ApiHandler.prototype.download = function(apiUrl, itemPath, toFilename, downloadByAjax, forceNewWindow) {
            var self = this;
            var url = this.getUrl(apiUrl, itemPath);

            if (!downloadByAjax || forceNewWindow || !$window.saveAs) {
                !$window.saveAs && $window.console.log('Your browser dont support ajax download, downloading by default');
                return !!$window.open(url, '_blank', '');
            }
            
            var deferred = $q.defer();
            self.inprocess = true;
            $http.get(url).success(function(data) {
                var bin = new $window.Blob([data]);
                deferred.resolve(data);
                $window.saveAs(bin, toFilename);
            }).error(function(data, code) {
                self.deferredHandler(data, deferred, code, $translate.instant('error_downloading'));
            })['finally'](function() {
                self.inprocess = false;
            });
            return deferred.promise;
        };

        ApiHandler.prototype.downloadMultiple = function(apiUrl, items, toFilename, downloadByAjax, forceNewWindow) {
            var self = this;
            var deferred = $q.defer();
            var data = {
                action: 'downloadMultiple',
                items: items,
                toFilename: toFilename
            };
            var url = [apiUrl, $.param(data)].join('?');

            if (!downloadByAjax || forceNewWindow || !$window.saveAs) {
                !$window.saveAs && $window.console.log('Your browser dont support ajax download, downloading by default');
                return !!$window.open(url, '_blank', '');
            }
            
            self.inprocess = true;
            $http.get(apiUrl).success(function(data) {
                var bin = new $window.Blob([data]);
                deferred.resolve(data);
                $window.saveAs(bin, toFilename);
            }).error(function(data, code) {
                self.deferredHandler(data, deferred, code, $translate.instant('error_downloading'));
            })['finally'](function() {
                self.inprocess = false;
            });
            return deferred.promise;
        };

        ApiHandler.prototype.compress = function(apiUrl, items, compressedFilename, path) {
            var self = this;
            var deferred = $q.defer();
            var data = {
                action: 'compress',
                items: items,
                destination: path,
                compressedFilename: compressedFilename
            };

            self.inprocess = true;
            self.error = '';
            $http.post(apiUrl, data).success(function(data, code) {
                self.deferredHandler(data, deferred, code);
            }).error(function(data, code) {
                self.deferredHandler(data, deferred, code, $translate.instant('error_compressing'));
            })['finally'](function() {
                self.inprocess = false;
            });
            return deferred.promise;
        };

        ApiHandler.prototype.extract = function(apiUrl, item, folderName, path) {
            var self = this;
            var deferred = $q.defer();
            var data = {
                action: 'extract',
                item: item,
                destination: path,
                folderName: folderName
            };

            self.inprocess = true;
            self.error = '';
            $http.post(apiUrl, data).success(function(data, code) {
                self.deferredHandler(data, deferred, code);
            }).error(function(data, code) {
                self.deferredHandler(data, deferred, code, $translate.instant('error_extracting'));
            })['finally'](function() {
                self.inprocess = false;
            });
            return deferred.promise;
        };

        ApiHandler.prototype.changePermissions = function(apiUrl, items, permsOctal, permsCode, recursive) {
            var self = this;
            var deferred = $q.defer();
            var data = {
                action: 'changePermissions',
                items: items,
                perms: permsOctal,
                permsCode: permsCode,
                recursive: !!recursive
            };
            
            self.inprocess = true;
            self.error = '';
            $http.post(apiUrl, data).success(function(data, code) {
                self.deferredHandler(data, deferred, code);
            }).error(function(data, code) {
                self.deferredHandler(data, deferred, code, $translate.instant('error_changing_perms'));
            })['finally'](function() {
                self.inprocess = false;
            });
            return deferred.promise;
        };

        ApiHandler.prototype.createFolder = function(apiUrl, path) {
            var self = this;
            var deferred = $q.defer();
            var data = {
                action: 'createFolder',
                newPath: path
            };

            self.inprocess = true;
            self.error = '';
            $http.post(apiUrl, data).success(function(data, code) {
                self.deferredHandler(data, deferred, code);
            }).error(function(data, code) {
                self.deferredHandler(data, deferred, code, $translate.instant('error_creating_folder'));
            })['finally'](function() {
                self.inprocess = false;
            });
        
            return deferred.promise;
        };

        return ApiHandler;

    }]);
})(angular, jQuery);
(function(angular) {
    'use strict';
    angular.module('FileManagerApp').service('apiMiddleware', ['$window', 'fileManagerConfig', 'apiHandler', 
        function ($window, fileManagerConfig, ApiHandler) {

        var ApiMiddleware = function() {
            this.apiHandler = new ApiHandler();
        };

        ApiMiddleware.prototype.getPath = function(arrayPath) {
            return '/' + arrayPath.join('/');
        };

        ApiMiddleware.prototype.getFileList = function(files) {
            return (files || []).map(function(file) {
                return file && file.model.fullPath();
            });
        };

        ApiMiddleware.prototype.getFilePath = function(item) {
            return item && item.model.fullPath();
        };

        ApiMiddleware.prototype.list = function(path, customDeferredHandler) {
            return this.apiHandler.list(fileManagerConfig.listUrl, this.getPath(path), customDeferredHandler);
        };

        ApiMiddleware.prototype.copy = function(files, path) {
            var items = this.getFileList(files);
            var singleFilename = items.length === 1 ? files[0].tempModel.name : undefined;
            return this.apiHandler.copy(fileManagerConfig.copyUrl, items, this.getPath(path), singleFilename);
        };

        ApiMiddleware.prototype.move = function(files, path) {
            var items = this.getFileList(files);
            return this.apiHandler.move(fileManagerConfig.moveUrl, items, this.getPath(path));
        };

        ApiMiddleware.prototype.remove = function(files) {
            var items = this.getFileList(files);
            return this.apiHandler.remove(fileManagerConfig.removeUrl, items);
        };

        ApiMiddleware.prototype.upload = function(files, path) {
            if (! $window.FormData) {
                throw new Error('Unsupported browser version');
            }

            var destination = this.getPath(path);

            return this.apiHandler.upload(fileManagerConfig.uploadUrl, destination, files);
        };

        ApiMiddleware.prototype.getContent = function(item) {
            var itemPath = this.getFilePath(item);
            return this.apiHandler.getContent(fileManagerConfig.getContentUrl, itemPath);
        };

        ApiMiddleware.prototype.edit = function(item) {
            var itemPath = this.getFilePath(item);
            return this.apiHandler.edit(fileManagerConfig.editUrl, itemPath, item.tempModel.content);
        };

        ApiMiddleware.prototype.rename = function(item) {
            var itemPath = this.getFilePath(item);
            var newPath = item.tempModel.fullPath();

            return this.apiHandler.rename(fileManagerConfig.renameUrl, itemPath, newPath);
        };

        ApiMiddleware.prototype.getUrl = function(item) {
            var itemPath = this.getFilePath(item);
            return this.apiHandler.getUrl(fileManagerConfig.downloadFileUrl, itemPath);
        };

        ApiMiddleware.prototype.download = function(item, forceNewWindow) {
            //TODO: add spinner to indicate file is downloading
            var itemPath = this.getFilePath(item);
            var toFilename = item.model.name;

            if (item.isFolder()) {
                return;
            }
            
            return this.apiHandler.download(
                fileManagerConfig.downloadFileUrl, 
                itemPath,
                toFilename,
                fileManagerConfig.downloadFilesByAjax,
                forceNewWindow
            );
        };

        ApiMiddleware.prototype.downloadMultiple = function(files, forceNewWindow) {
            var items = this.getFileList(files);
            var timestamp = new Date().getTime().toString().substr(8, 13);
            var toFilename = timestamp + '-' + fileManagerConfig.multipleDownloadFileName;
            
            return this.apiHandler.downloadMultiple(
                fileManagerConfig.downloadMultipleUrl, 
                items, 
                toFilename, 
                fileManagerConfig.downloadFilesByAjax,
                forceNewWindow
            );
        };

        ApiMiddleware.prototype.compress = function(files, compressedFilename, path) {
            var items = this.getFileList(files);
            return this.apiHandler.compress(fileManagerConfig.compressUrl, items, compressedFilename, this.getPath(path));
        };

        ApiMiddleware.prototype.extract = function(item, folderName, path) {
            var itemPath = this.getFilePath(item);
            return this.apiHandler.extract(fileManagerConfig.extractUrl, itemPath, folderName, this.getPath(path));
        };

        ApiMiddleware.prototype.changePermissions = function(files, dataItem) {
            var items = this.getFileList(files);
            var code = dataItem.tempModel.perms.toCode();
            var octal = dataItem.tempModel.perms.toOctal();
            var recursive = !!dataItem.tempModel.recursive;

            return this.apiHandler.changePermissions(fileManagerConfig.permissionsUrl, items, code, octal, recursive);
        };

        ApiMiddleware.prototype.createFolder = function(item) {
            var path = item.tempModel.fullPath();
            return this.apiHandler.createFolder(fileManagerConfig.createFolderUrl, path);
        };

        return ApiMiddleware;

    }]);
})(angular);
(function(angular) {
    'use strict';
    angular.module('FileManagerApp').service('fileNavigator', [
        'apiMiddleware', 'fileManagerConfig', 'item', function (ApiMiddleware, fileManagerConfig, Item) {

        var FileNavigator = function() {
            this.apiMiddleware = new ApiMiddleware();
            this.requesting = false;
            this.fileList = [];
            this.currentPath = [];
            this.history = [];
            this.error = '';

            this.onRefresh = function() {};
        };

        FileNavigator.prototype.deferredHandler = function(data, deferred, code, defaultMsg) {
            if (!data || typeof data !== 'object') {
                this.error = 'Error %s - Bridge response error, please check the API docs or this ajax response.'.replace('%s', code);
            }
            if (code == 404) {
                this.error = 'Error 404 - Backend bridge is not working, please check the ajax response.';
            }
            if (!this.error && data.result && data.result.error) {
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

        FileNavigator.prototype.list = function() {
            return this.apiMiddleware.list(this.currentPath, this.deferredHandler.bind(this));
        };

        FileNavigator.prototype.refresh = function() {
            var self = this;
            var path = self.currentPath.join('/');
            self.requesting = true;
            self.fileList = [];
            return self.list().then(function(data) {
                self.fileList = (data.result || []).map(function(file) {
                    return new Item(file, self.currentPath);
                });
                self.buildTree(path);
                self.onRefresh();
            }).finally(function() {
                self.requesting = false;
            });
        };
        
        FileNavigator.prototype.buildTree = function(path) {
            var flatNodes = [], selectedNode = {};

            function recursive(parent, item, path) {
                var absName = path ? (path + '/' + item.model.name) : item.model.name;
                if (parent.name.trim() && path.trim().indexOf(parent.name) !== 0) {
                    parent.nodes = [];
                }
                if (parent.name !== path) {
                    parent.nodes.forEach(function(nd) {
                        recursive(nd, item, path);
                    });
                } else {
                    for (var e in parent.nodes) {
                        if (parent.nodes[e].name === absName) {
                            return;
                        }
                    }
                    parent.nodes.push({item: item, name: absName, nodes: []});
                }
                
                parent.nodes = parent.nodes.sort(function(a, b) {
                    return a.name.toLowerCase() < b.name.toLowerCase() ? -1 : a.name.toLowerCase() === b.name.toLowerCase() ? 0 : 1;
                });
            }

            function flatten(node, array) {
                array.push(node);
                for (var n in node.nodes) {
                    flatten(node.nodes[n], array);
                }
            }

            function findNode(data, path) {
                return data.filter(function (n) {
                    return n.name === path;
                })[0];
            }

            !this.history.length && this.history.push({name: '', nodes: []});
            flatten(this.history[0], flatNodes);
            selectedNode = findNode(flatNodes, path);
            selectedNode && (selectedNode.nodes = []);

            for (var o in this.fileList) {
                var item = this.fileList[o];
                item instanceof Item && item.isFolder() && recursive(this.history[0], item, path);
            }
        };

        FileNavigator.prototype.folderClick = function(item) {
            this.currentPath = [];
            if (item && item.isFolder()) {
                this.currentPath = item.model.fullPath().split('/').splice(1);
            }
            this.refresh();
        };

        FileNavigator.prototype.upDir = function() {
            if (this.currentPath[0]) {
                this.currentPath = this.currentPath.slice(0, -1);
                this.refresh();
            }
        };

        FileNavigator.prototype.goTo = function(index) {
            this.currentPath = this.currentPath.slice(0, index + 1);
            this.refresh();
        };

        FileNavigator.prototype.fileNameExists = function(fileName) {
            return this.fileList.find(function(item) {
                return fileName.trim && item.model.name.trim() === fileName.trim();
            });
        };

        FileNavigator.prototype.listHasFolders = function() {
            return this.fileList.find(function(item) {
                return item.model.type === 'dir';
            });
        };

        return FileNavigator;
    }]);
})(angular);
(function(angular) {
    'use strict';
    angular.module('FileManagerApp').provider('fileManagerConfig', function() {

        var values = {
            appName: 'angular-filemanager v1.5',
            defaultLang: 'en',

            listUrl: 'bridges/php/handler.php',
            uploadUrl: 'bridges/php/handler.php',
            renameUrl: 'bridges/php/handler.php',
            copyUrl: 'bridges/php/handler.php',
            moveUrl: 'bridges/php/handler.php',
            removeUrl: 'bridges/php/handler.php',
            editUrl: 'bridges/php/handler.php',
            getContentUrl: 'bridges/php/handler.php',
            createFolderUrl: 'bridges/php/handler.php',
            downloadFileUrl: 'bridges/php/handler.php',
            downloadMultipleUrl: 'bridges/php/handler.php',
            compressUrl: 'bridges/php/handler.php',
            extractUrl: 'bridges/php/handler.php',
            permissionsUrl: 'bridges/php/handler.php',

            searchForm: true,
            sidebar: true,
            breadcrumb: true,
            allowedActions: {
                upload: true,
                rename: true,
                move: true,
                copy: true,
                edit: true,
                changePermissions: true,
                compress: true,
                compressChooseName: true,
                extract: true,
                download: true,
                downloadMultiple: true,
                preview: true,
                remove: true,
                createFolder: true,
                pickFiles: false,
                pickFolders: false
            },

            multipleDownloadFileName: 'angular-filemanager.zip',
            showExtensionIcons: true,
            showSizeForDirectories: false,
            useBinarySizePrefixes: false,
            downloadFilesByAjax: true,
            previewImagesInModal: true,
            enablePermissionsRecursive: true,
            compressAsync: false,
            extractAsync: false,
            pickCallback: null,

            isEditableFilePattern: /\.(txt|diff?|patch|svg|asc|cnf|cfg|conf|html?|.html|cfm|cgi|aspx?|ini|pl|py|md|css|cs|js|jsp|log|htaccess|htpasswd|gitignore|gitattributes|env|json|atom|eml|rss|markdown|sql|xml|xslt?|sh|rb|as|bat|cmd|cob|for|ftn|frm|frx|inc|lisp|scm|coffee|php[3-6]?|java|c|cbl|go|h|scala|vb|tmpl|lock|go|yml|yaml|tsv|lst)$/i,
            isImageFilePattern: /\.(jpe?g|gif|bmp|png|svg|tiff?)$/i,
            isExtractableFilePattern: /\.(gz|tar|rar|g?zip)$/i,
            tplPath: 'src/templates'
        };

        return {
            $get: function() {
                return values;
            },
            set: function (constants) {
                angular.extend(values, constants);
            }
        };

    });
})(angular);

(function (angular) {
    'use strict';
    angular.module('FileManagerApp').config(['$translateProvider', function ($translateProvider) {
        $translateProvider.useSanitizeValueStrategy(null);

        $translateProvider.translations('en', {
            filemanager: 'File Manager',
            language: 'Language',
            english: 'English',
            spanish: 'Spanish',
            portuguese: 'Portuguese',
            french: 'French',
            german: 'German',
            hebrew: 'Hebrew',
            slovak: 'Slovak',
            chinese: 'Chinese',
            russian: 'Russian',
            ukrainian: 'Ukrainian',
            turkish: 'Turkish',
            persian: 'Persian',
            confirm: 'Confirm',
            cancel: 'Cancel',
            close: 'Close',
            upload_files: 'Upload files',
            files_will_uploaded_to: 'Files will be uploaded to',
            select_files: 'Select files',
            uploading: 'Uploading',
            permissions: 'Permissions',
            select_destination_folder: 'Select the destination folder',
            source: 'Source',
            destination: 'Destination',
            copy_file: 'Copy file',
            sure_to_delete: 'Are you sure to delete',
            change_name_move: 'Change name / move',
            enter_new_name_for: 'Enter new name for',
            extract_item: 'Extract item',
            extraction_started: 'Extraction started in a background process',
            compression_started: 'Compression started in a background process',
            enter_folder_name_for_extraction: 'Enter the folder name for the extraction of',
            enter_file_name_for_compression: 'Enter the file name for the compression of',
            toggle_fullscreen: 'Toggle fullscreen',
            edit_file: 'Edit file',
            file_content: 'File content',
            loading: 'Loading',
            search: 'Search',
            create_folder: 'Create folder',
            create: 'Create',
            folder_name: 'Folder name',
            upload: 'Upload',
            change_permissions: 'Change permissions',
            change: 'Change',
            details: 'Details',
            icons: 'Icons',
            list: 'List',
            name: 'Name',
            size: 'Size',
            actions: 'Actions',
            date: 'Date',
            no_files_in_folder: 'No files in this folder',
            no_folders_in_folder: 'This folder not contains children folders',
            select_this: 'Select this',
            go_back: 'Go back',
            wait: 'Wait',
            move: 'Move',
            download: 'Download',
            view_item: 'View item',
            remove: 'Delete',
            edit: 'Edit',
            copy: 'Copy',
            rename: 'Rename',
            extract: 'Extract',
            compress: 'Compress',
            error_invalid_filename: 'Invalid filename or already exists, specify another name',
            error_modifying: 'An error occurred modifying the file',
            error_deleting: 'An error occurred deleting the file or folder',
            error_renaming: 'An error occurred renaming the file',
            error_copying: 'An error occurred copying the file',
            error_compressing: 'An error occurred compressing the file or folder',
            error_extracting: 'An error occurred extracting the file',
            error_creating_folder: 'An error occurred creating the folder',
            error_getting_content: 'An error occurred getting the content of the file',
            error_changing_perms: 'An error occurred changing the permissions of the file',
            error_uploading_files: 'An error occurred uploading files',
            sure_to_start_compression_with: 'Are you sure to compress',
            owner: 'Owner',
            group: 'Group',
            others: 'Others',
            read: 'Read',
            write: 'Write',
            exec: 'Exec',
            original: 'Original',
            changes: 'Changes',
            recursive: 'Recursive',
            preview: 'Item preview',
            open: 'Open',
            these_elements: 'these {{total}} elements',
            new_folder: 'New folder',
            download_as_zip: 'Download as ZIP'
        });

        $translateProvider.translations('he', {
            filemanager: 'מנהל קבצים',
            language: 'שפה',
            english: 'אנגלית',
            spanish: 'ספרדית',
            portuguese: 'פורטוגזית',
            french: 'צרפתית',
            german: 'גרמנית',
            hebrew: 'עברי',
            slovak: 'סלובקי',
            chinese: 'סִינִית',
            russian: 'רוּסִי',
            ukrainian: 'אוקראיני',
            turkish: 'טורקי',
            persian: 'פַּרסִית',
            confirm: 'אשר',
            cancel: 'בטל',
            close: 'סגור',
            upload_files: 'העלה קבצים',
            files_will_uploaded_to: 'הקבצים יעלו ל',
            select_files: 'בחר קבצים',
            uploading: 'מעלה',
            permissions: 'הרשאות',
            select_destination_folder: 'בחר תיקיית יעד',
            source: 'מקור',
            destination: 'יעד',
            copy_file: 'העתק קובץ',
            sure_to_delete: 'האם אתה בטוח שברצונך למחוק',
            change_name_move: 'שנה שם / הזז',
            enter_new_name_for: 'הקלד שם חדש עבור',
            extract_item: 'חלץ פריט',
            extraction_started: 'תהליך החילוץ מתבצע ברקע',
            compression_started: 'תהליך הכיווץ מתבצע ברקע',
            enter_folder_name_for_extraction: 'הקלד שם תיקייה לחילוץ עבור',
            enter_file_name_for_compression: 'הזן את שם הקובץ עבור הדחיסה של',
            toggle_fullscreen: 'הפעל/בטל מסך מלא',
            edit_file: 'ערוך קובץ',
            file_content: 'תוכן הקובץ',
            loading: 'טוען',
            search: 'חפש',
            create_folder: 'צור תיקייה',
            create: 'צור',
            folder_name: 'שם תיקייה',
            upload: 'העלה',
            change_permissions: 'שנה הרשאות',
            change: 'שנה',
            details: 'פרטים',
            icons: 'סמלים',
            list: 'רשימה',
            name: 'שם',
            size: 'גודל',
            actions: 'פעולות',
            date: 'תאריך',
            no_files_in_folder: 'אין קבצים בתיקייה זו',
            no_folders_in_folder: 'התיקייה הזו אינה כוללת תתי תיקיות',
            select_this: 'בחר את זה',
            go_back: 'חזור אחורה',
            wait: 'חכה',
            move: 'הזז',
            download: 'הורד',
            view_item: 'הצג פריט',
            remove: 'מחק',
            edit: 'ערוך',
            copy: 'העתק',
            rename: 'שנה שם',
            extract: 'חלץ',
            compress: 'כווץ',
            error_invalid_filename: 'שם קובץ אינו תקין או קיים, ציין שם קובץ אחר',
            error_modifying: 'התרחשה שגיאה בעת שינוי הקובץ',
            error_deleting: 'התרחשה שגיאה בעת מחיקת הקובץ או התיקייה',
            error_renaming: 'התרחשה שגיאה בעת שינוי שם הקובץ',
            error_copying: 'התרחשה שגיאה בעת העתקת הקובץ',
            error_compressing: 'התרחשה שגיאה בעת כיווץ הקובץ או התיקייה',
            error_extracting: 'התרחשה שגיאה בעת חילוץ הקובץ או התיקייה',
            error_creating_folder: 'התרחשה שגיאה בעת יצירת התיקייה',
            error_getting_content: 'התרחשה שגיאה בעת בקשת תוכן הקובץ',
            error_changing_perms: 'התרחשה שגיאה בעת שינוי הרשאות הקובץ',
            error_uploading_files: 'התרחשה שגיאה בעת העלאת הקבצים',
            sure_to_start_compression_with: 'האם אתה בטוח שברצונך לכווץ',
            owner: 'בעלים',
            group: 'קבוצה',
            others: 'אחרים',
            read: 'קריאה',
            write: 'כתיבה',
            exec: 'הרצה',
            original: 'מקורי',
            changes: 'שינויים',
            recursive: 'רקורסיה',
            preview: 'הצגת פריט',
            open: 'פתח',
            new_folder: 'תיקיה חדשה',
            download_as_zip: 'להוריד כמו'
        });

        $translateProvider.translations('pt', {
            filemanager: 'Gerenciador de arquivos',
            language: 'Língua',
            english: 'Inglês',
            spanish: 'Espanhol',
            portuguese: 'Portugues',
            french: 'Francês',
            german: 'Alemão',
            hebrew: 'Hebraico',
            slovak: 'Eslovaco',
            chinese: 'Chinês',
            russian: 'Russo',
            ukrainian: 'Ucraniano',
            turkish: 'Turco',
            persian: 'Persa',
            confirm: 'Confirmar',
            cancel: 'Cancelar',
            close: 'Fechar',
            upload_files: 'Carregar arquivos',
            files_will_uploaded_to: 'Os arquivos serão enviados para',
            select_files: 'Selecione os arquivos',
            uploading: 'Carregar',
            permissions: 'Autorizações',
            select_destination_folder: 'Selecione a pasta de destino',
            source: 'Origem',
            destination: 'Destino',
            copy_file: 'Copiar arquivo',
            sure_to_delete: 'Tem certeza de que deseja apagar',
            change_name_move: 'Renomear / mudança',
            enter_new_name_for: 'Digite o novo nome para',
            extract_item: 'Extrair arquivo',
            extraction_started: 'A extração começou em um processo em segundo plano',
            compression_started: 'A compressão começou em um processo em segundo plano',
            enter_folder_name_for_extraction: 'Digite o nome da pasta para a extração de',
            enter_file_name_for_compression: 'Digite o nome do arquivo para a compressão de',
            toggle_fullscreen: 'Ativar/desativar tela cheia',
            edit_file: 'Editar arquivo',
            file_content: 'Conteúdo do arquivo',
            loading: 'Carregando',
            search: 'Localizar',
            create_folder: 'Criar Pasta',
            create: 'Criar',
            folder_name: 'Nome da pasta',
            upload: 'Fazer',
            change_permissions: 'Alterar permissões',
            change: 'Alterar',
            details: 'Detalhes',
            icons: 'Icones',
            list: 'Lista',
            name: 'Nome',
            size: 'Tamanho',
            actions: 'Ações',
            date: 'Data',
            no_files_in_folder: 'Não há arquivos nesta pasta',
            no_folders_in_folder: 'Esta pasta não contém subpastas',
            select_this: 'Selecione esta',
            go_back: 'Voltar',
            wait: 'Espere',
            move: 'Mover',
            download: 'Baixar',
            view_item: 'Veja o arquivo',
            remove: 'Excluir',
            edit: 'Editar',
            copy: 'Copiar',
            rename: 'Renomear',
            extract: 'Extrair',
            compress: 'Comprimir',
            error_invalid_filename: 'Nome do arquivo inválido ou nome de arquivo já existe, especifique outro nome',
            error_modifying: 'Ocorreu um erro ao modificar o arquivo',
            error_deleting: 'Ocorreu um erro ao excluir o arquivo ou pasta',
            error_renaming: 'Ocorreu um erro ao mudar o nome do arquivo',
            error_copying: 'Ocorreu um erro ao copiar o arquivo',
            error_compressing: 'Ocorreu um erro ao comprimir o arquivo ou pasta',
            error_extracting: 'Ocorreu um erro ao extrair o arquivo',
            error_creating_folder: 'Ocorreu um erro ao criar a pasta',
            error_getting_content: 'Ocorreu um erro ao obter o conteúdo do arquivo',
            error_changing_perms: 'Ocorreu um erro ao alterar as permissões do arquivo',
            error_uploading_files: 'Ocorreu um erro upload de arquivos',
            sure_to_start_compression_with: 'Tem certeza que deseja comprimir',
            owner: 'Proprietário',
            group: 'Grupo',
            others: 'Outros',
            read: 'Leitura',
            write: 'Escrita ',
            exec: 'Execução',
            original: 'Original',
            changes: 'Mudanças',
            recursive: 'Recursiva',
            preview: 'Visualização',
            open: 'Abrir',
            these_elements: 'estes {{total}} elements',
            new_folder: 'Nova pasta',
            download_as_zip: 'Download como ZIP'
        });

        $translateProvider.translations('es', {
            filemanager: 'Administrador de archivos',
            language: 'Idioma',
            english: 'Ingles',
            spanish: 'Español',
            portuguese: 'Portugues',
            french: 'Francés',
            german: 'Alemán',
            hebrew: 'Hebreo',
            slovak: 'Eslovaco',
            chinese: 'Chino',
            russian: 'Ruso',
            ukrainian: 'Ucraniano',
            turkish: 'Turco',
            persian: 'Persa',
            confirm: 'Confirmar',
            cancel: 'Cancelar',
            close: 'Cerrar',
            upload_files: 'Subir archivos',
            files_will_uploaded_to: 'Los archivos seran subidos a',
            select_files: 'Seleccione los archivos',
            uploading: 'Subiendo',
            permissions: 'Permisos',
            select_destination_folder: 'Seleccione la carpeta de destino',
            source: 'Origen',
            destination: 'Destino',
            copy_file: 'Copiar archivo',
            sure_to_delete: 'Esta seguro que desea eliminar',
            change_name_move: 'Renombrar / mover',
            enter_new_name_for: 'Ingrese el nuevo nombre para',
            extract_item: 'Extraer archivo',
            extraction_started: 'La extraccion ha comenzado en un proceso de segundo plano',
            compression_started: 'La compresion ha comenzado en un proceso de segundo plano',
            enter_folder_name_for_extraction: 'Ingrese el nombre de la carpeta para la extraccion de',
            enter_file_name_for_compression: 'Ingrese el nombre del archivo para la compresion de',
            toggle_fullscreen: 'Activar/Desactivar pantalla completa',
            edit_file: 'Editar archivo',
            file_content: 'Contenido del archivo',
            loading: 'Cargando',
            search: 'Buscar',
            create_folder: 'Crear carpeta',
            create: 'Crear',
            folder_name: 'Nombre de la carpeta',
            upload: 'Subir',
            change_permissions: 'Cambiar permisos',
            change: 'Cambiar',
            details: 'Detalles',
            icons: 'Iconos',
            list: 'Lista',
            name: 'Nombre',
            size: 'Tamaño',
            actions: 'Acciones',
            date: 'Fecha',
            no_files_in_folder: 'No hay archivos en esta carpeta',
            no_folders_in_folder: 'Esta carpeta no contiene sub-carpetas',
            select_this: 'Seleccionar esta',
            go_back: 'Volver',
            wait: 'Espere',
            move: 'Mover',
            download: 'Descargar',
            view_item: 'Ver archivo',
            remove: 'Eliminar',
            edit: 'Editar',
            copy: 'Copiar',
            rename: 'Renombrar',
            extract: 'Extraer',
            compress: 'Comprimir',
            error_invalid_filename: 'El nombre del archivo es invalido o ya existe',
            error_modifying: 'Ocurrio un error al intentar modificar el archivo',
            error_deleting: 'Ocurrio un error al intentar eliminar el archivo',
            error_renaming: 'Ocurrio un error al intentar renombrar el archivo',
            error_copying: 'Ocurrio un error al intentar copiar el archivo',
            error_compressing: 'Ocurrio un error al intentar comprimir el archivo',
            error_extracting: 'Ocurrio un error al intentar extraer el archivo',
            error_creating_folder: 'Ocurrio un error al intentar crear la carpeta',
            error_getting_content: 'Ocurrio un error al obtener el contenido del archivo',
            error_changing_perms: 'Ocurrio un error al cambiar los permisos del archivo',
            error_uploading_files: 'Ocurrio un error al subir archivos',
            sure_to_start_compression_with: 'Esta seguro que desea comprimir',
            owner: 'Propietario',
            group: 'Grupo',
            others: 'Otros',
            read: 'Lectura',
            write: 'Escritura',
            exec: 'Ejecucion',
            original: 'Original',
            changes: 'Cambios',
            recursive: 'Recursivo',
            preview: 'Vista previa',
            open: 'Abrir',
            these_elements: 'estos {{total}} elementos',
            new_folder: 'Nueva carpeta',
            download_as_zip: 'Descargar como ZIP'
        });

        $translateProvider.translations('fr', {
            filemanager: 'Gestionnaire de fichier',
            language: 'Langue',
            english: 'Anglais',
            spanish: 'Espagnol',
            portuguese: 'Portugais',
            french: 'Français',
            german: 'Allemand',
            hebrew: 'Hébreu',
            slovak: 'Slovaque',
            chinese: 'Chinois',
            russian: 'Russe',
            ukrainian: 'Ukrainien',
            turkish: 'Turc',
            persian: 'Persan',
            confirm: 'Confirmer',
            cancel: 'Annuler',
            close: 'Fermer',
            upload_files: 'Télécharger des fichiers',
            files_will_uploaded_to: 'Les fichiers seront uploadé dans',
            select_files: 'Sélectionnez les fichiers',
            uploading: 'Upload en cours',
            permissions: 'Permissions',
            select_destination_folder: 'Sélectionné le dossier de destination',
            source: 'Source',
            destination: 'Destination',
            copy_file: 'Copier le fichier',
            sure_to_delete: 'Êtes-vous sûr de vouloir supprimer',
            change_name_move: 'Renommer / Déplacer',
            enter_new_name_for: 'Entrer le nouveau nom pour',
            extract_item: 'Extraires les éléments',
            extraction_started: 'L\'extraction a démarré en tâche de fond',
            compression_started: 'La compression a démarré en tâche de fond',
            enter_folder_name_for_extraction: 'Entrer le nom du dossier pour l\'extraction de',
            enter_file_name_for_compression: 'Entrez le nom de fichier pour la compression de',
            toggle_fullscreen: 'Basculer en plein écran',
            edit_file: 'Éditer le fichier',
            file_content: 'Contenu du fichier',
            loading: 'Chargement en cours',
            search: 'Recherche',
            create_folder: 'Créer un dossier',
            create: 'Créer',
            folder_name: 'Nom du dossier',
            upload: 'Upload',
            change_permissions: 'Changer les permissions',
            change: 'Changer',
            details: 'Details',
            icons: 'Icons',
            list: 'Liste',
            name: 'Nom',
            size: 'Taille',
            actions: 'Actions',
            date: 'Date',
            no_files_in_folder: 'Aucun fichier dans ce dossier',
            no_folders_in_folder: 'Ce dossier ne contiens pas de dossier',
            select_this: 'Sélectionner',
            go_back: 'Retour',
            wait: 'Patienter',
            move: 'Déplacer',
            download: 'Télécharger',
            view_item: 'Voir l\'élément',
            remove: 'Supprimer',
            edit: 'Éditer',
            copy: 'Copier',
            rename: 'Renommer',
            extract: 'Extraire',
            compress: 'Compresser',
            error_invalid_filename: 'Nom de fichier invalide ou déjà existant, merci de spécifier un autre nom',
            error_modifying: 'Une erreur est survenue pendant la modification du fichier',
            error_deleting: 'Une erreur est survenue pendant la suppression du fichier ou du dossier',
            error_renaming: 'Une erreur est survenue pendant le renommage du fichier',
            error_copying: 'Une erreur est survenue pendant la copie du fichier',
            error_compressing: 'Une erreur est survenue pendant la compression du fichier ou du dossier',
            error_extracting: 'Une erreur est survenue pendant l\'extraction du fichier',
            error_creating_folder: 'Une erreur est survenue pendant la création du dossier',
            error_getting_content: 'Une erreur est survenue pendant la récupération du contenu du fichier',
            error_changing_perms: 'Une erreur est survenue pendant le changement des permissions du fichier',
            error_uploading_files: 'Une erreur est survenue pendant l\'upload des fichiers',
            sure_to_start_compression_with: 'Êtes-vous sûre de vouloir compresser',
            owner: 'Propriétaire',
            group: 'Groupe',
            others: 'Autres',
            read: 'Lecture',
            write: 'Écriture',
            exec: 'Éxécution',
            original: 'Original',
            changes: 'Modifications',
            recursive: 'Récursif',
            preview: 'Aperçu',
            open: 'Ouvrir',
            these_elements: 'ces {{total}} éléments',
            new_folder: 'Nouveau dossier',
            download_as_zip: 'Télécharger comme ZIP'
        });

        $translateProvider.translations('de', {
            filemanager: 'Dateimanager',
            language: 'Sprache',
            english: 'Englisch',
            spanish: 'Spansisch',
            portuguese: 'Portugiesisch',
            french: 'Französisch',
            german: 'Deutsch',
            hebrew: 'Hebräisch',
            slovak: 'Slowakisch',
            chinese: 'Chinesisch',
            russian: 'Russisch',
            ukrainian: 'Ukrainisch',
            turkish: 'Türkisch',
            persian: 'Persisch',
            confirm: 'Bestätigen',
            cancel: 'Abbrechen',
            close: 'Schließen',
            upload_files: 'Hochladen von vateien',
            files_will_uploaded_to: 'Dateien werden hochgeladen nach',
            select_files: 'Wählen Sie die Dateien',
            uploading: 'Lade hoch',
            permissions: 'Berechtigungen',
            select_destination_folder: 'Wählen Sie einen Zielordner',
            source: 'Quelle',
            destination: 'Ziel',
            copy_file: 'Datei kopieren',
            sure_to_delete: 'Sind Sie sicher, dass Sie die Datei löschen möchten?',
            change_name_move: 'Namen ändern / verschieben',
            enter_new_name_for: 'Geben Sie den neuen Namen ein für',
            extract_item: 'Archiv entpacken',
            extraction_started: 'Entpacken hat im Hintergrund begonnen',
            compression_started: 'Komprimierung hat im Hintergrund begonnen',
            enter_folder_name_for_extraction: 'Geben sie den verzeichnisnamen für die entpackung an, von',
            enter_file_name_for_compression: 'Geben sie den dateinamen für die kompression von',
            toggle_fullscreen: 'Vollbild umschalten',
            edit_file: 'Datei bearbeiten',
            file_content: 'Dateiinhalt',
            loading: 'Lade',
            search: 'Suche',
            create_folder: 'Ordner erstellen',
            create: 'Erstellen',
            folder_name: 'Verzeichnisname',
            upload: 'Hochladen',
            change_permissions: 'Berechtigungen ändern',
            change: 'Ändern',
            details: 'Details',
            icons: 'Symbolansicht',
            list: 'Listenansicht',
            name: 'Name',
            size: 'Größe',
            actions: 'Aktionen',
            date: 'Datum',
            no_files_in_folder: 'Keine Dateien in diesem Ordner',
            no_folders_in_folder: 'Dieser Ordner enthält keine Unterordner',
            select_this: 'Auswählen',
            go_back: 'Zurück',
            wait: 'Warte',
            move: 'Verschieben',
            download: 'Herunterladen',
            view_item: 'Datei ansehen',
            remove: 'Löschen',
            edit: 'Bearbeiten',
            copy: 'Kopieren',
            rename: 'Umbenennen',
            extract: 'Entpacken',
            compress: 'Komprimieren',
            error_invalid_filename: 'Ungültiger Dateiname oder existiert bereits',
            error_modifying: 'Beim Bearbeiten der Datei ist ein Fehler aufgetreten',
            error_deleting: 'Beim Löschen der Datei oder des Ordners ist ein Fehler aufgetreten',
            error_renaming: 'Beim Umbennenen der Datei ist ein Fehler aufgetreten',
            error_copying: 'Beim Kopieren der Datei ist ein Fehler aufgetreten',
            error_compressing: 'Beim Komprimieren der Datei oder des Ordners ist ein Fehler aufgetreten',
            error_extracting: 'Beim Entpacken der Datei ist ein Fehler aufgetreten',
            error_creating_folder: 'Beim Erstellen des Ordners ist ein Fehler aufgetreten',
            error_getting_content: 'Beim Holen des Dateiinhalts ist ein Fehler aufgetreten',
            error_changing_perms: 'Beim Ändern der Dateiberechtigungen ist ein Fehler aufgetreten',
            error_uploading_files: 'Beim Hochladen der Dateien ist ein Fehler aufgetreten',
            sure_to_start_compression_with: 'Möchten Sie die Datei wirklich komprimieren?',
            owner: 'Besitzer',
            group: 'Gruppe',
            others: 'Andere',
            read: 'Lesen',
            write: 'Schreiben',
            exec: 'Ausführen',
            original: 'Original',
            changes: 'Änderungen',
            recursive: 'Rekursiv',
            preview: 'Dateivorschau',
            open: 'Öffnen',
            these_elements: 'diese {{total}} elemente',
            new_folder: 'Neuer ordner',
            download_as_zip: 'Download als ZIP'
        });

        $translateProvider.translations('sk', {
            filemanager: 'Správca súborov',
            language: 'Jazyk',
            english: 'Angličtina',
            spanish: 'Španielčina',
            portuguese: 'Portugalčina',
            french: 'Francúzština',
            german: 'Nemčina',
            hebrew: 'Hebrejčina',
            slovak: 'Slovenčina',
            chinese: 'Čínština',
            russian: 'Ruský',
            ukrainian: 'Ukrajinský',
            turkish: 'Turecký',
            persian: 'Perzský',
            confirm: 'Potvrdiť',
            cancel: 'Zrušiť',
            close: 'Zavrieť',
            upload_files: 'Nahrávať súbory',
            files_will_uploaded_to: 'Súbory budú nahrané do',
            select_files: 'Vybrať súbory',
            uploading: 'Nahrávanie',
            permissions: 'Oprávnenia',
            select_destination_folder: 'Vyberte cieľový príečinok',
            source: 'Zdroj',
            destination: 'Cieľ',
            copy_file: 'Kopírovať súbor',
            sure_to_delete: 'Ste si istý, že chcete vymazať',
            change_name_move: 'Premenovať / Premiestniť',
            enter_new_name_for: 'Zadajte nové meno pre',
            extract_item: 'Rozbaliť položku',
            extraction_started: 'Rozbaľovanie začalo v procese na pozadí',
            compression_started: 'Kompresia začala v procese na pzoadí',
            enter_folder_name_for_extraction: 'Zadajte názov priečinka na rozbalenie',
            enter_file_name_for_compression: 'Zadajte názov súboru pre kompresiu',
            toggle_fullscreen: 'Prepnúť režim na celú obrazovku',
            edit_file: 'Upraviť súbor',
            file_content: 'Obsah súboru',
            loading: 'Načítavanie',
            search: 'Hľadať',
            create_folder: 'Vytvoriť priečinok',
            create: 'Vytvoriť',
            folder_name: 'Názov priećinka',
            upload: 'Nahrať',
            change_permissions: 'Zmeniť oprávnenia',
            change: 'Zmeniť',
            details: 'Podrobnosti',
            icons: 'Ikony',
            list: 'Zoznam',
            name: 'Meno',
            size: 'Veľkosť',
            actions: 'Akcie',
            date: 'Dátum',
            no_files_in_folder: 'V tom to priečinku nie sú žiadne súbory',
            no_folders_in_folder: 'Tento priečinok neobsahuje žiadne ďalšie priećinky',
            select_this: 'Vybrať tento',
            go_back: 'Ísť späť',
            wait: 'Počkajte',
            move: 'Presunúť',
            download: 'Stiahnuť',
            view_item: 'Zobraziť položku',
            remove: 'Vymazať',
            edit: 'Upraviť',
            copy: 'Kopírovať',
            rename: 'Premenovať',
            extract: 'Rozbaliť',
            compress: 'Komprimovať',
            error_invalid_filename: 'Neplatné alebo duplicitné meno súboru, vyberte iné meno',
            error_modifying: 'Vyskytla sa chyba pri upravovaní súboru',
            error_deleting: 'Vyskytla sa chyba pri mazaní súboru alebo priečinku',
            error_renaming: 'Vyskytla sa chyba pri premenovaní súboru',
            error_copying: 'Vyskytla sa chyba pri kopírovaní súboru',
            error_compressing: 'Vyskytla sa chyba pri komprimovaní súboru alebo priečinka',
            error_extracting: 'Vyskytla sa chyba pri rozbaľovaní súboru',
            error_creating_folder: 'Vyskytla sa chyba pri vytváraní priečinku',
            error_getting_content: 'Vyskytla sa chyba pri získavaní obsahu súboru',
            error_changing_perms: 'Vyskytla sa chyba pri zmene oprávnení súboru',
            error_uploading_files: 'Vyskytla sa chyba pri nahrávaní súborov',
            sure_to_start_compression_with: 'Ste si istý, že chcete komprimovať',
            owner: 'Vlastník',
            group: 'Skupina',
            others: 'Ostatní',
            read: 'Čítanie',
            write: 'Zapisovanie',
            exec: 'Spúštanie',
            original: 'Originál',
            changes: 'Zmeny',
            recursive: 'Rekurzívne',
            preview: 'Náhľad položky',
            open: 'Otvoriť',
            these_elements: 'týchto {{total}} prvkov',
            new_folder: 'Nový priečinok',
            download_as_zip: 'Stiahnuť ako ZIP'
        });

        $translateProvider.translations('zh', {
            filemanager: '文档管理器',
            language: '语言',
            english: '英语',
            spanish: '西班牙语',
            portuguese: '葡萄牙语',
            french: '法语',
            german: '德语',
            hebrew: '希伯来语',
            slovak: '斯洛伐克语',
            chinese: '中文',
            russian: '俄語',
            ukrainian: '烏克蘭',
            turkish: '土耳其',
            persian: '波斯語',
            confirm: '确定',
            cancel: '取消',
            close: '关闭',
            upload_files: '上传文件',
            files_will_uploaded_to: '文件将上传到',
            select_files: '选择文件',
            uploading: '上传中',
            permissions: '权限',
            select_destination_folder: '选择目标文件',
            source: '源自',
            destination: '目的地',
            copy_file: '复制文件',
            sure_to_delete: '确定要删除？',
            change_name_move: '改名或移动？',
            enter_new_name_for: '输入新的名称',
            extract_item: '解压',
            extraction_started: '解压已经在后台开始',
            compression_started: '压缩已经在后台开始',
            enter_folder_name_for_extraction: '输入解压的目标文件夹',
            enter_file_name_for_compression: '输入要压缩的文件名',
            toggle_fullscreen: '切换全屏',
            edit_file: '编辑文件',
            file_content: '文件内容',
            loading: '加载中',
            search: '搜索',
            create_folder: '创建文件夹',
            create: '创建',
            folder_name: '文件夹名称',
            upload: '上传',
            change_permissions: '修改权限',
            change: '修改',
            details: '详细信息',
            icons: '图标',
            list: '列表',
            name: '名称',
            size: '尺寸',
            actions: '操作',
            date: '日期',
            no_files_in_folder: '此文件夹没有文件',
            no_folders_in_folder: '此文件夹不包含子文件夹',
            select_this: '选择此文件',
            go_back: '后退',
            wait: '等待',
            move: '移动',
            download: '下载',
            view_item: '查看子项',
            remove: '删除',
            edit: '编辑',
            copy: '复制',
            rename: '重命名',
            extract: '解压',
            compress: '压缩',
            error_invalid_filename: '非法文件名或文件已经存在, 请指定其它名称',
            error_modifying: '修改文件出错',
            error_deleting: '删除文件或文件夹出错',
            error_renaming: '重命名文件出错',
            error_copying: '复制文件出错',
            error_compressing: '压缩文件或文件夹出错',
            error_extracting: '解压文件出错',
            error_creating_folder: '创建文件夹出错',
            error_getting_content: '获取文件内容出错',
            error_changing_perms: '修改文件权限出错',
            error_uploading_files: '上传文件出错',
            sure_to_start_compression_with: '确定要压缩？',
            owner: '拥有者',
            group: '群组',
            others: '其他',
            read: '读取',
            write: '写入',
            exec: '执行',
            original: '原始',
            changes: '变化',
            recursive: '递归',
            preview: '成员预览',
            open: '打开',
            these_elements: '共 {{total}} 个',
            new_folder: '新文件夹',
            download_as_zip: '下载的ZIP'
        });

        $translateProvider.translations('ru', {
            filemanager: 'Файловый менеджер',
            language: 'Язык',
            english: 'Английский',
            spanish: 'Испанский',
            portuguese: 'Португальский',
            french: 'Французкий',
            german: 'Немецкий',
            hebrew: 'Хинди',
            slovak: 'Словацкий',
            chinese: 'Китайский',
            russian: 'русский',
            ukrainian: 'украинец',
            turkish: 'турецкий',
            persian: 'персидский',
            confirm: 'Подьвердить',
            cancel: 'Отменить',
            close: 'Закрыть',
            upload_files: 'Загрузка файлов',
            files_will_uploaded_to: 'Файлы будут загружены в: ',
            select_files: 'Выберите файлы',
            uploading: 'Загрузка',
            permissions: 'Разрешения',
            select_destination_folder: 'Выберите папку назначения',
            source: 'Источкик',
            destination: 'Цель',
            copy_file: 'Скопировать файл',
            sure_to_delete: 'Действительно удалить?',
            change_name_move: 'Переименовать / переместить',
            enter_new_name_for: 'Новое имя для',
            extract_item: 'Извлечь',
            extraction_started: 'Извлечение начато',
            compression_started: 'Сжатие начато',
            enter_folder_name_for_extraction: 'Извлечь в укананную папку',
            enter_file_name_for_compression: 'Введите имя архива',
            toggle_fullscreen: 'На весь экран',
            edit_file: 'Редактировать',
            file_content: 'Содержимое файла',
            loading: 'Загрузка',
            search: 'Поиск',
            create_folder: 'Создать папку',
            create: 'Создать',
            folder_name: 'Имя папки',
            upload: 'Загрузить',
            change_permissions: 'Изменить разрешения',
            change: 'Изменить',
            details: 'Свойства',
            icons: 'Иконки',
            list: 'Список',
            name: 'Имя',
            size: 'Размер',
            actions: 'Действия',
            date: 'Дата',
            no_files_in_folder: 'Пустая папка',
            no_folders_in_folder: 'Пустая папка',
            select_this: 'Выбрать',
            go_back: 'Назад',
            wait: 'Подождите',
            move: 'Переместить',
            download: 'Скачать',
            view_item: 'Отобразить содержимое',
            remove: 'Удалить',
            edit: 'Редактировать',
            copy: 'Скопировать',
            rename: 'Переименовать',
            extract: 'Извлечь',
            compress: 'Сжать',
            error_invalid_filename: 'Имя неверное или уже существует, выберите другое',
            error_modifying: 'Произошла ошибка при модифицировании файла',
            error_deleting: 'Произошла ошибка при удалении',
            error_renaming: 'Произошла ошибка при переименовании файла',
            error_copying: 'Произошла ошибка при копировании файла',
            error_compressing: 'Произошла ошибка при сжатии',
            error_extracting: 'Произошла ошибка при извлечении',
            error_creating_folder: 'Произошла ошибка при создании папки',
            error_getting_content: 'Произошла ошибка при получении содержимого',
            error_changing_perms: 'Произошла ошибка при изменении разрешений',
            error_uploading_files: 'Произошла ошибка при загрузке',
            sure_to_start_compression_with: 'Действительно сжать',
            owner: 'Владелец',
            group: 'Группа',
            others: 'Другие',
            read: 'Чтение',
            write: 'Запись',
            exec: 'Выполнение',
            original: 'По-умолчанию',
            changes: 'Изменения',
            recursive: 'Рекурсивно',
            preview: 'Просмотр',
            open: 'Открыть',
            these_elements: 'всего {{total}} елементов',
            new_folder: 'Новая папка',
            download_as_zip: 'Download as ZIP'
        });

        $translateProvider.translations('ua', {
            filemanager: 'Файловий менеджер',
            language: 'Мова',
            english: 'Англійська',
            spanish: 'Іспанська',
            portuguese: 'Португальська',
            french: 'Французька',
            german: 'Німецька',
            hebrew: 'Хінді',
            slovak: 'Словацька',
            chinese: 'Китайська',
            russian: 'російський',
            ukrainian: 'український',
            turkish: 'турецька',
            persian: 'перський',
            confirm: 'Підтвердити',
            cancel: 'Відмінити',
            close: 'Закрити',
            upload_files: 'Завантаження файлів',
            files_will_uploaded_to: 'Файли будуть завантажені у: ',
            select_files: 'Виберіть файли',
            uploading: 'Завантаження',
            permissions: 'Дозволи',
            select_destination_folder: 'Виберіть папку призначення',
            source: 'Джерело',
            destination: 'Ціль',
            copy_file: 'Скопіювати файл',
            sure_to_delete: 'Дійсно удалить?',
            change_name_move: 'Перейменувати / перемістити',
            enter_new_name_for: 'Нове ім\'я для',
            extract_item: 'Извлечь',
            extraction_started: 'Извлечение начато',
            compression_started: 'Архівацію почато',
            enter_folder_name_for_extraction: 'Извлечь в укананную папку',
            enter_file_name_for_compression: 'Введите имя архива',
            toggle_fullscreen: 'На весь экран',
            edit_file: 'Редагувати',
            file_content: 'Вміст файлу',
            loading: 'Завантаження',
            search: 'Пошук',
            create_folder: 'Створити папку',
            create: 'Створити',
            folder_name: 'Ім\'я  папки',
            upload: 'Завантижити',
            change_permissions: 'Змінити дозволи',
            change: 'Редагувати',
            details: 'Властивості',
            icons: 'Іконки',
            list: 'Список',
            name: 'Ім\'я',
            size: 'Розмір',
            actions: 'Дії',
            date: 'Дата',
            no_files_in_folder: 'Пуста папка',
            no_folders_in_folder: 'Пуста папка',
            select_this: 'Выбрати',
            go_back: 'Назад',
            wait: 'Зачекайте',
            move: 'Перемістити',
            download: 'Скачати',
            view_item: 'Показати вміст',
            remove: 'Видалити',
            edit: 'Редагувати',
            copy: 'Копіювати',
            rename: 'Переіменувати',
            extract: 'Розархівувати',
            compress: 'Архівувати',
            error_invalid_filename: 'Ім\'я певірне або вже існує, виберіть інше',
            error_modifying: 'Виникла помилка при редагуванні файлу',
            error_deleting: 'Виникла помилка при видаленні',
            error_renaming: 'Виникла помилка при зміні імені файлу',
            error_copying: 'Виникла помилка при коміюванні файлу',
            error_compressing: 'Виникла помилка при стисненні',
            error_extracting: 'Виникла помилка при розархівації',
            error_creating_folder: 'Виникла помилка при створенні папки',
            error_getting_content: 'Виникла помилка при отриманні вмісту',
            error_changing_perms: 'Виникла помилка при зміні дозволів',
            error_uploading_files: 'Виникла помилка при завантаженні',
            sure_to_start_compression_with: 'Дійсно стиснути',
            owner: 'Власник',
            group: 'Група',
            others: 'Інші',
            read: 'Читання',
            write: 'Запис',
            exec: 'Виконання',
            original: 'За замовчуванням',
            changes: 'Зміни',
            recursive: 'Рекурсивно',
            preview: 'Перегляд',
            open: 'Відкрити',
            these_elements: 'усього {{total}} елементів',
            new_folder: 'Нова папка',
            download_as_zip: 'Download as ZIP'
        });

        $translateProvider.translations('tr', {
            filemanager: 'Dosya Yöneticisi',
            language: 'Dil',
            english: 'İngilizce',
            spanish: 'İspanyolca',
            portuguese: 'Portekizce',
            french: 'Fransızca',
            german: 'Almanca',
            hebrew: 'İbranice',
            slovak: 'Slovakça',
            chinese: 'Çince',
            russian: 'Rusça',
            ukrainian: 'Ukrayna',
            turkish: 'Türk',
            persian: 'Farsça',
            confirm: 'Onayla',
            cancel: 'İptal Et',
            close: 'Kapat',
            upload_files: 'Dosya yükle',
            files_will_uploaded_to: 'Dosyalar yüklenecektir.',
            select_files: 'Dosya Seç',
            uploading: 'Yükleniyor',
            permissions: 'İzinler',
            select_destination_folder: 'Hedef klasör seçin',
            source: 'Kaynak',
            destination: 'Hedef',
            copy_file: 'Dosyayı kopyala',
            sure_to_delete: 'Silmek istediğinden emin misin',
            change_name_move: 'İsmini değiştir / taşı',
            enter_new_name_for: 'Yeni ad girin',
            extract_item: 'Dosya çıkar',
            extraction_started: 'Çıkarma işlemi arkaplanda devam ediyor',
            compression_started: 'Sıkıştırma işlemi arkaplanda başladı',
            enter_folder_name_for_extraction: 'Çıkarılması için klasör adı girin',
            enter_file_name_for_compression: 'Sıkıştırılması için dosya adı girin',
            toggle_fullscreen: 'Tam ekran moduna geç',
            edit_file: 'Dosyayı düzenle',
            file_content: 'Dosya içeriği',
            loading: 'Yükleniyor',
            search: 'Ara',
            create_folder: 'Klasör oluştur',
            create: 'Oluştur',
            folder_name: 'Klasör adı',
            upload: 'Yükle',
            change_permissions: 'İzinleri değiştir',
            change: 'Değiştir',
            details: 'Detaylar',
            icons: 'simgeler',
            list: 'Liste',
            name: 'Adı',
            size: 'Boyutu',
            actions: 'İşlemler',
            date: 'Tarih',
            no_files_in_folder: 'Klasörde hiç dosya yok',
            no_folders_in_folder: 'Bu klasör alt klasör içermez',
            select_this: 'Bunu seç',
            go_back: 'Geri git',
            wait: 'Bekle',
            move: 'Taşı',
            download: 'İndir',
            view_item: 'Dosyayı görüntüle',
            remove: 'Sil',
            edit: 'Düzenle',
            copy: 'Kopyala',
            rename: 'Yeniden Adlandır',
            extract: 'Çıkart',
            compress: 'Sıkıştır',
            error_invalid_filename: 'Geçersiz dosya adı, bu dosya adına sahip dosya mevcut',
            error_modifying: 'Dosya düzenlenirken bir hata oluştu',
            error_deleting: 'Klasör veya dosya silinirken bir hata oluştu',
            error_renaming: 'Dosya yeniden adlandırılırken bir hata oluştu',
            error_copying: 'Dosya kopyalanırken bir hata oluştu',
            error_compressing: 'Dosya veya klasör sıkıştırılırken bir hata oluştu',
            error_extracting: 'Çıkartılırken bir hata oluştu',
            error_creating_folder: 'Klasör oluşturulurken bir hata oluştu',
            error_getting_content: 'Dosya detayları alınırken bir hata oluştu',
            error_changing_perms: 'Dosyanın izini değiştirilirken bir hata oluştu',
            error_uploading_files: 'Dosyalar yüklenirken bir hata oluştu',
            sure_to_start_compression_with: 'Sıkıştırmak istediğinden emin misin',
            owner: 'Sahip',
            group: 'Grup',
            others: 'Diğerleri',
            read: 'Okuma',
            write: 'Yazma',
            exec: 'Gerçekleştir',
            original: 'Orjinal',
            changes: 'Değişiklikler',
            recursive: 'Yinemeli',
            preview: 'Dosyayı önizle',
            open: 'Aç',
            these_elements: '{{total}} eleman',
            new_folder: 'Yeni Klasör',
            download_as_zip: 'ZIP olarak indir'
        });

        $translateProvider.translations('fa', {
            filemanager: 'مدیریت فایل ها',
            language: 'زبان',
            english: 'انگلیسی',
            spanish: 'اسپانیایی',
            portuguese: 'پرتغالی',
            french: 'فرانسه',
            german: 'آلمانی',
            hebrew: 'عبری',
            slovak: 'اسلواک',
            chinese: 'چینی',
            russian: 'روسی',
            ukrainian: 'اوکراینی',
            turkish: 'ترکی',
            persian: 'فارسی',
            confirm: 'تایید',
            cancel: 'رد',
            close: 'بستن',
            upload_files: 'آپلود فایل',
            files_will_uploaded_to: 'فایل ها آپلود می شوند به',
            select_files: 'انتخاب فایل ها',
            uploading: 'در حال آپلود',
            permissions: 'مجوز ها',
            select_destination_folder: 'پوشه مقصد را انتخاب کنید',
            source: 'مبدا',
            destination: 'مقصد',
            copy_file: 'کپی فایل',
            sure_to_delete: 'مطمين هستید می خواهید حذف کنید؟',
            change_name_move: 'تغییر نام و جابجایی',
            enter_new_name_for: 'نام جدیدی وارد کنید برای',
            extract_item: 'خارج کردن از حالت فشرده',
            extraction_started: 'یک پروسه در پس زمینه شروع به خارج کردن از حالت فشرده کرد',
            compression_started: 'یک پروسه در پس زمینه شروع به فشرده سازی کرد',
            enter_folder_name_for_extraction: 'نام پوشه مقصد برای خارج کردن از حالت فشرده را وارد کنید',
            enter_file_name_for_compression: 'نام پوشه مقصد برای فشرده سازی را وارد کنید',
            toggle_fullscreen: 'تعویض حالت تمام صفحه',
            edit_file: 'ویرایش',
            file_content: 'محتویات',
            loading: 'در حال بارگذاری',
            search: 'جستجو',
            create_folder: 'پوشه جدید',
            create: 'ساختن',
            folder_name: 'نام پوشه',
            upload: 'آپلود',
            change_permissions: 'تغییر مجوز ها',
            change: 'تغییر',
            details: 'جزییات',
            icons: 'آیکون ها',
            list: 'لیست',
            name: 'نام',
            size: 'سایز',
            actions: 'اعمال',
            date: 'تاریخ',
            no_files_in_folder: 'هیچ فایلی در این پوشه نیست',
            no_folders_in_folder: 'هیچ پوشه ای داخل این پوشه قرار ندارد',
            select_this: 'انتخاب',
            go_back: 'بازگشت',
            wait: 'منتظر بمانید',
            move: 'جابجایی',
            download: 'دانلود',
            view_item: 'مشاهده این مورد',
            remove: 'حذف',
            edit: 'ویرایش',
            copy: 'کپی',
            rename: 'تغییر نام',
            extract: 'خروج از حالت فشرده',
            compress: 'فشرده سازی',
            error_invalid_filename: 'نام فایل مورد درست نیست و یا قبلا استفاده شده است، لطفا نام دیگری وارد کنید',
            error_modifying: 'در هنگام تغییر فایل خطایی پیش آمد',
            error_deleting: 'در هنگام حذف فایل خطایی پیش آمد',
            error_renaming: 'در هنگام تغییر نام فایل خطایی پیش آمد',
            error_copying: 'در هنگام کپی کردن فایل خطایی پیش آمد',
            error_compressing: 'در هنگام فشرده سازی فایل خطایی پیش آمد',
            error_extracting: 'در هنگام خارک کردن فایل از حالت فشرده خطایی پیش آمد',
            error_creating_folder: 'در هنگام ساخت پوشه خطایی پیش امد',
            error_getting_content: 'در هنگام بارگذاری محتویات فایل خطایی رخ داد',
            error_changing_perms: 'در هنگام تغییر مجوز های فایل خطایی رخ داد',
            error_uploading_files: 'در آپلود فایل خطایی رخ داد',
            sure_to_start_compression_with: 'مطمئن هستید فشرده سازی انجام شد؟',
            owner: 'مالک فایل',
            group: 'گروه',
            others: 'دیگران',
            read: 'خواندن',
            write: 'نوشتن',
            exec: 'اجرا کردن',
            original: 'اصلی',
            changes: 'تغییرات',
            recursive: 'بازگشتی',
            preview: 'پیش نمایش',
            open: 'باز کردن',
            these_elements: 'تعداد {{total}} مورد',
            new_folder: 'پوشه جدید',
            download_as_zip: 'به عنوان فایل فشرده دانلود شود'
        });

    }]);
})(angular);
