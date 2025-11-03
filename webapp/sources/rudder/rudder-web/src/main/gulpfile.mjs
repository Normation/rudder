import gulp from 'gulp';
const { task, watch, series, parallel, src, dest } = gulp;
import fs from 'fs';
import path from 'path';
import rename from 'gulp-rename';
import mode from 'gulp-mode';
const profile = mode();
import terser from 'gulp-terser';
import elm_p from 'gulp-elm';
import merge2 from 'merge2';
import { deleteSync } from 'del';
import through from 'through2';
import * as dartSass from 'sass';
import gulpSass from 'gulp-sass';
const sass = gulpSass(dartSass);
import sourcemaps from 'gulp-sourcemaps';
import svgmin from 'gulp-svgmin';

const paths = {
    'svg': {
        'src': 'svg/**/*.svg',
        'dest': 'webapp/images',  
    },
    'css': {
        'src': 'style/libs/**/*',
        'dest': 'webapp/style',
    },
    'scss': {
        'src': 'style/rudder/**/*',
        'dest': 'webapp/style/rudder',
    },
    'login_scss': {
        'src': 'style/login.css',
        'dest': 'webapp/style',
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
            'node_modules/chart.js/dist/chart.umd.js',
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
    let mergeCleanPaths = function(){
        let getCleanPaths = function(p){
            let files  = (p + '/**');
            let parent = ('!' + p);
            return [files, parent];
        }
        return [].concat(
            getCleanPaths(paths.scss.dest),
            getCleanPaths(paths.css.dest),
            getCleanPaths(paths.js.dest),
        );
    }
    let cleanPaths = mergeCleanPaths();
    deleteSync(cleanPaths);
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
    src(paths.css.src, { encoding: false })
        .pipe(dest(paths.css.dest));
    cb();
};

function svg(cb) {
    src(paths.svg.src)
        .pipe(svgmin())
        .pipe(dest(paths.svg.dest));
    cb();
}

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
    // with the pinned sass version we could ignore some deprecations :
    // https://github.com/twbs/bootstrap/issues/40962#issuecomment-2470260308
    const deprecations = ['legacy-js-api', 'mixed-decls', 'color-functions', 'global-builtin', 'import'];
    const rudderScss = src(paths.scss.src)
      .pipe(sourcemaps.init())
      .pipe(sass({style: 'compressed', silenceDeprecations: deprecations}).on('error', sass.logError))
      .pipe(sourcemaps.write())
      .pipe(dest(paths.scss.dest));
    const loginScss = src(paths.login_scss.src)
      .pipe(sourcemaps.init())
      .pipe(sass({style: 'compressed', silenceDeprecations: deprecations}).on('error', sass.logError))
      .pipe(sourcemaps.write())
      .pipe(dest(paths.login_scss.dest));
    merge2(rudderScss, loginScss);
    cb();
};

task('elm', series(clean, elm));

task('watch', series(clean, function() {
    watch(paths.svg.watch, { ignoreInitial: false }, svg);
    watch(paths.elm.watch, { ignoreInitial: false }, elm);
    watch(paths.js.src, { ignoreInitial: false }, js);
    watch(paths.css.src, { ignoreInitial: false }, css);
    watch(paths.scss.src, { ignoreInitial: false }, scss);
    watch(paths.vendor_js.src, { ignoreInitial: false }, vendor_js);
    watch(paths.vendor_esm.src, { ignoreInitial: false }, vendor_esm);
    watch(paths.vendor_css.src, { ignoreInitial: false }, vendor_css);
}));

task('default', series(clean, parallel(svg, elm, css, scss, js, vendor_css, vendor_js)));
