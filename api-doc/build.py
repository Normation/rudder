#!/usr/bin/env python3

# Loads an openapi.yml template and adds missing information to produce a valid
# openapi.yml file.
# Then lint it and build html docs

import sys
import yaml
import os
import subprocess
import glob
import shutil

# API to build
api = sys.argv[1]
source_dir = sys.argv[2]
target_dir = sys.argv[3]

# source dir
source = '%s/%s' % (source_dir, api)

# Read openapi(-ver).src.yml and read versions inside.
templates = glob.glob(source + '/openapi*.src.yml')

for template in templates:
    with open(template, 'r') as content_file:
        main = yaml.load(content_file.read(), Loader=yaml.FullLoader)
    version = main['info']['version']
    intro_file = main['info']['description']

    print('Building version %s from %s' % (version, template))

    # Fetch introduction content
    with open(source + '/introduction.md', 'r') as content_file:
        intro = content_file.read()

    # Add external content
    main['info']['description'] = intro

    # Dump target in target .yml file
    src_openapi_file = template.replace('.src', '')
    with open(src_openapi_file, 'w') as file:
        documents = yaml.dump(main, file)

    target = '%s/%s/%s' % (target_dir, api, version)

    print('Built %s' % (src_openapi_file))

    # Lint doc (on split files to allow correct file reports)
    if subprocess.call(['npx', 'redocly', 'lint', src_openapi_file]):
        print('Linter failed on %s' % (src_openapi_file))
        exit(1)

    # Build final openapi.yml
    openapi_file = '%s/openapi.yml' % target
    if subprocess.call(
        [
            'npx',
            'redocly',
            'bundle',
            src_openapi_file,
            '--output',
            openapi_file,
        ]
    ):
        print('Could not build %s' % (openapi_file))
        exit(1)

    print('Built %s' % (openapi_file))

    # Build doc from yaml file (with pre-rendered html)
    html_file = '%s/index.html' % target
    if subprocess.call(
        [
            'npx',
            'redocly',
            'build-docs',
            openapi_file,
            '--output',
            html_file,
            # Don't help google track our users
            '--disableGoogleFont',
        ]
    ):
        print('Could not build %s' % (html_file))
        exit(1)

    # Now let's change what we could not configure before...

    # Rudder 7 theme
    if subprocess.call(['sed', '-i', '/<style>/ r custom.css', html_file]):
        print('Could not insert custom CSS rules into %s' % (html_file))
        exit(1)

    # Extract URL to redoc JS
    # Allows getting the current version
    url = subprocess.check_output(
        [
            'grep',
            '-Eo',
            'https://cdn.redoc.ly/redoc/.+/bundles/redoc.standalone.js',
            html_file,
        ],
        text=True,
    )
    url = url.strip()

    redoc_js = 'redoc.js'
    redoc_js_file = f'{target}/{redoc_js}'
    # Download JS file
    if subprocess.call(
        ['curl', '--silent', '--fail', url, '--output', redoc_js_file]
    ):
        print(
            'Could not download redoc lib from %s into %s'
            % (url, redoc_js_file)
        )
        exit(1)
    # Remove external image from minified JS
    if subprocess.call(
        [
            'sed',
            '-i',
            f's@https://cdn.redoc.ly/redoc/logo-mini.svg@@',
            redoc_js_file,
        ]
    ):
        print('Could not insert redoc JS path into %s' % (redoc_js_file))
        exit(1)
    # Replace link to lib by local version
    if subprocess.call(['sed', '-i', f's@{url}@{redoc_js}@', html_file]):
        print('Could not insert redoc JS path into %s' % (html_file))
        exit(1)

    shutil.copytree('%s/assets' % source, '%s/assets' % target)
