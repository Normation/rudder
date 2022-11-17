const fs = require('fs');
const path = require('path');
const { watch, series, parallel, src, dest } = require('gulp');
const rename = require('gulp-rename');
const mode = require('gulp-mode');
const profile = mode();
const terser = require('gulp-terser');
const elm_p = require('gulp-elm');
const merge = require('merge-stream');
const minifyCSS = require('gulp-clean-css');

const paths = {
    'css': {
        'src': 'style/**/*',
        'dest': 'webapp/style/',
    },
    'js': {
        'src': 'javascript/**/*.js',
        'dest': 'webapp/javascript/',
    },
    'vendor_js': {
        'src': [
            'node_modules/*/dist/*.min.js*',
            'node_modules/*/js/*.min.js*',
            'node_modules/*/dist/**/*.min.js*',
            'node_modules/datatables.net-plugins/sorting/natural.js',
            'node_modules/showdown-xss-filter/showdown-xss-filter.js',
        ],
        'dest': 'webapp/javascript/libs',
    },
    'vendor_css': {
        'src': [
            'node_modules/*/dist/**/*.min.css*',
        ],
        'dest': 'webapp/style/libs',
    },
    'elm': {
        'src': 'elm',
        'watch': 'elm/**/*.elm',
        'dest': 'webapp/javascript/elm',
    },
};

function clean(cb) {
    cb();
}

function css(cb) {
    src(paths.css.src)
        .pipe(profile.production(minifyCSS()))
        .pipe(dest(paths.css.dest));
    cb();
};

// based on elm.json file presence
function getElmApps(dir) {
    return fs.readdirSync(dir)
        .filter(function(file) {
            return fs.existsSync(path.join(dir, file, "elm.json"));
        });
}

function elm(cb) {
    var apps = getElmApps(paths.elm.src);
    var tasks = apps.map(function(app) {
        // Filename
        file = app[0].toUpperCase() + app.substring(1) + '.elm'
        return src(path.join(paths.elm.src, app, 'sources', file))
            .pipe(elm_p({
                optimize: profile.production(),
                cwd: path.join(paths.elm.src, app),
            }))
            .pipe(rename('rudder-' + app + '.js'))
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
    });
    merge(tasks);
    cb();
};

function js(cb) {
    src(paths.js.src)
        .pipe(profile.production(terser()))
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

function vendor_css(cb) {
    src(paths.vendor_css.src)
        // flatten file hierarchy
        .pipe(rename({
            dirname: ''
        }))
        .pipe(dest(paths.vendor_css.dest));
    cb();
};

exports.watch = function() {
    watch(paths.elm.watch, { ignoreInitial: false }, elm);
    watch(paths.js.src, { ignoreInitial: false }, js);
    watch(paths.css.src, { ignoreInitial: false }, css);
    watch(paths.vendor_js.src, { ignoreInitial: false }, vendor_js);
    watch(paths.vendor_css.src, { ignoreInitial: false }, vendor_css);
};
exports.default = series(clean, parallel(elm, css, js, vendor_css, vendor_js));