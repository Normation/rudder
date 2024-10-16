# SVG image sources

This folder contains the sources for all Rudder's SVG images, including the logo.
The files are usually created and modified using [Inkscape](https://inkscape.org/).

The Gulp build task will produce optimized versions of the files (using [SVGO](https://svgo.dev/))
into the `webapp` folder, which is packaged.
All non-SVG images (PNG, etc.) should be placed directly into the `webapp` folder
sources.
