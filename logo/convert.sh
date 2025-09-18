#!/bin/bash

# This script requires:
#
# * inkscape (best for SVG -> PNG)
# * svgo (for SVG optimization)
# * zopfli (best png opimizer: https://iter.ca/post/zopfli/)
#
# NOTE: This script aims at best size result over time, and should be used for one-shot jobs.

set -e

target="icons"
archive="rudder-logos"
rm -rf "${target}" "${archive}.*"

sources="../webapp/sources/rudder/rudder-web/src/main/svg/logo/*.svg"
png_sizes=(512 1024)

#################################################
# Favicon

out_dir="${target}/favicons"
mkdir -p "${out_dir}"
cd ../webapp/sources/rudder/rudder-web/src/main/ && ./favicon.sh && cd - >/dev/null
cp ../webapp/sources/rudder/rudder-web/src/main/webapp/images/rudder-favicon.ico "${target}/favicons/"

#################################################
### Optimized SVG

out_dir="${target}/svg"
mkdir -p "${out_dir}"
for file in ${sources}
do
    basename=$(basename "${file}")
    out="${out_dir}/${basename}"
    zout="${out_dir}/${basename%.svg}.svgz"
    echo "${file}"
    echo "    optimizing (svgo) -> ${out}"
    svgo --quiet --multipass --input "${file}" --output "${out}"
    #echo "    compressing (gzip) -> ${zout}"
    #gzip --best --stdout "${out}" > "${zout}"
done

#################################################
# Optimized PNG

for file in ${sources}
do
    echo "${file}"
    for size in "${png_sizes[@]}"
    do
        out_dir="${target}/png/${size}px" 
        mkdir -p "${out_dir}"
        basename=$(basename "${file}")
        out="${out_dir}/${basename%.svg}.png"
        echo "    generating (inkscape, ${size}px) -> ${out}"
        inkscape --export-filename="${out}" -w "${size}" "${file}"
        echo "    optimizing (zopflipng)"
        zopflipng -y -m "${out}" "${out}" >/dev/null
    done
done

#################################################
# Archive

zip -q -r "${archive}" icons
tar -czf "${archive}.tar.gz" icons
