#!/bin/bash

# Build the .ico favicon from the svg source file
# This script need to be run when modifying the Rudder logo

# SVG favicons would be a good choice if browers had a sane behavior...
# See https://dev.to/masakudamatsu/favicon-nightmare-how-to-maintain-sanity-3al7

# This script requires:
#
# * inkscape (best for SVG -> PNG)
# * zopfli (best png opimizer: https://iter.ca/post/zopfli/)
# * icoutils/icotool (for .ico favicon)

set -e

source="svg/logo/rudder-logo-notext.svg"
target="webapp/images/rudder-favicon.ico"
tmp=$(mktemp -d)

ico_sizes=(16 32 48)

echo "${source} (favicon)"

# ICO for compatibility
for size in "${ico_sizes[@]}"
do
    out="${tmp}/favicon-${size}.png"
    echo "    generating (inkscape, ${size}px)"
    inkscape --export-filename="${out}" -w "${size}" -h "${size}" "${source}"
    echo "    optimizing (zopflipng)"
    zopflipng -m -y "${out}" "${out}" >/dev/null
done

ico_s=$(IFS=, ; echo "${ico_sizes[*]}")
echo "    generating (icotool ${ico_s}) -> ${target}"
icotool --create --output "${target}" "${tmp}"/*.png
rm -rf "${tmp}"

