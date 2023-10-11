const fs = require('fs');
const path = require('path');
const { watch, series, parallel, src, dest } = require('gulp');
const rename = require('gulp-rename');
const mode = require('gulp-mode');
const profile = mode();
const terser = require('gulp-terser');
const elm_p = require('gulp-elm');
const merge = require('merge-stream');
const del = require('del');
const through = require('through2');
const sass = require('gulp-sass')(require('sass'));
const sourcemaps = require('gulp-sourcemaps');

const paths = {
    'css': {
        'src': [
          'style/libs/**/*',
          'style/login.css'
        ],
        'dest': 'webapp/style/',
    },
    'scss': {
        'src': 'style/rudder/**/*',
        'dest': 'webapp/style/rudder',
    },
    'js': {
        'src': 'javascript/**/*.js',
        'dest': 'webapp/javascript',
    },
    'vendor_js': {
        'src': [
            'node_modules/*/dist/*.min.js*',
            'node_modules/*/js/*.min.js*',
            'node_modules/*/dist/**/*.min.js*',
            'node_modules/*/*.min.js*',
            'node_modules/datatables.net-plugins/sorting/natural.js',
            'node_modules/showdown-xss-filter/showdown-xss-filter.js',
            'node_modules/jsondiffpatch/dist/jsondiffpatch.umd.slim.js',
        ],
        'dest': 'webapp/javascript/libs',
    },
    'vendor_css': {
        'src': [
            'node_modules/*/dist/**/*.min.css*',
            'node_modules/jsondiffpatch/dist/formatters-styles/html.css',
        ],
        'dest': 'webapp/style/libs',
    },
    'elm': {
        'src': 'elm',
        'watch': 'elm/sources/*.elm',
        'dest': 'webapp/javascript/rudder/elm',
    },
};

// Derived from https://github.com/mixmaxhq/gulp-grep-contents (under MIT License)
var grep = function(regex) {
    var restoreStream = through.obj();
    return through.obj(function(file, encoding, callback) {
        var match = regex.test(String(file.contents))
        if (match) {
            callback(null, file);
            return;
        }
        restoreStream.write(file);
        callback();
    });
}

function clean(cb) {
    del.sync([paths.js.dest, paths.css.dest, paths.scss.dest]);
    cb();
}

// based on elm.json file presence
function getElmApps(dir) {
    return fs.readdirSync(dir)
        .filter(function(file) {
            return fs.existsSync(path.join(dir, file, "elm.json"));
        });
}

function elm(cb) {
    src(paths.elm.watch)
        // Detect entry points
        .pipe(grep(/Browser.element/))
        .pipe(elm_p({
            optimize: profile.production(),
            cwd: path.join(paths.elm.src),
        }))
        .pipe(rename(function (path) {
            return {
                dirname: '',
                basename: 'rudder-' + path.basename.toLowerCase(),
                extname: '.js'
            };
        }))
        // elm minification options from https://guide.elm-lang.org/optimization/asset_size.html#instructions
        .pipe(profile.production(terser({
            compress: {
                pure_funcs: ['F2', 'F3', 'F4', 'F5', 'F6', 'F7', 'F8', 'F9', 'A2', 'A3', 'A4',
                    'A5', 'A6', 'A7', 'A8', 'A9'
                ],
                pure_getters: true,
                keep_fargs: false,
                unsafe_comps: true,
                unsafe: true,
            },
        })))
        .pipe(profile.production(terser({
            mangle: true,
        })))
        .pipe(dest(paths.elm.dest));
    cb();
};

function js(cb) {
    src(paths.js.src)
        .pipe(dest(paths.js.dest));
    cb();
};

function vendor_js(cb) {
    src(paths.vendor_js.src)
        // flatten file hierarchy
        .pipe(rename({
            dirname: ''
        }))
        .pipe(dest(paths.vendor_js.dest));
    cb();
};

function css(cb) {
    src(paths.css.src)
        .pipe(dest(paths.css.dest));
    cb();
};


function vendor_css(cb) {
    src(paths.vendor_css.src)
        // flatten file hierarchy
        .pipe(rename({
            dirname: ''
        }))
        .pipe(dest(paths.vendor_css.dest));
    cb();
};

function scss(cb) {
    src(paths.scss.src)
      .pipe(sourcemaps.init())
      .pipe(sass({outputStyle: 'compressed'}).on('error', sass.logError))
      .pipe(sourcemaps.write())
      .pipe(dest(paths.scss.dest));
    cb();
};

exports.elm = series(clean, elm)
exports.watch = series(clean, function() {
    watch(paths.elm.watch, { ignoreInitial: false }, elm);
    watch(paths.js.src, { ignoreInitial: false }, js);
    watch(paths.css.src, { ignoreInitial: false }, css);
    watch(paths.scss.src, { ignoreInitial: false }, scss);
    watch(paths.vendor_js.src, { ignoreInitial: false }, vendor_js);
    watch(paths.vendor_css.src, { ignoreInitial: false }, vendor_css);
});
exports.default = series(clean, parallel(elm, css, scss, js, vendor_css, vendor_js));
