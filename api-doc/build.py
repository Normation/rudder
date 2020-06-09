#!/usr/bin/env python3

# loads a openapi.yml template and adds missing information to produce a valid
# openapi.yml file.
# Then build html docs

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
source = "%s/%s" % (source_dir, api)

# Read openapi(-ver).src.yml and read versions inside.
templates = glob.glob(source+"/openapi*.src.yml")

for template in templates:
    with open(template, 'r') as content_file:
        main = yaml.load(content_file.read(), Loader=yaml.FullLoader)
    version = main["info"]["version"]
    intro_file = main["info"]["description"]

    print("Building version %s from %s" % (version, template))

    # Fetch introduction content
    with open(source+'/introduction.md', 'r') as content_file:
        intro = content_file.read()

    # Add external content
    main["info"]["description"] = intro

    # Dump target in target .yml file
    src_openapi_file = template.replace(".src", "")
    with open(src_openapi_file, 'w') as file:
        documents = yaml.dump(main, file)

    target = "%s/%s/%s" % (target_dir, api, version)

    print("Built %s" % (src_openapi_file))

    # Build final openapi.yml
    openapi_file = "%s/openapi.yml" % target
    if subprocess.call(["openapi", "bundle", src_openapi_file,
                        "--output", openapi_file]):
        print("Could not build %s" % (openapi_file))
        exit(1)

    print("Built %s" % (openapi_file))

    # Build doc from yaml file (with pre-rendered html)
    html_file = "%s/index.html" % target
    if subprocess.call(["redoc-cli", "bundle", openapi_file,
                        "--output", html_file,
                        # Don't help google track our users
                        "--disableGoogleFont",
                        # The famous Rudder orange
                        "--options.theme.colors.primary.main='#f08004'",
                        # Expand success examples by default
                        "--options.expandResponses='200,'",
                        # More readable in central column
                        "--options.pathInMiddlePanel=1",
                        # Hostname is meaningless as it won't match rudder server
                        "--options.hideHostname=1"
                        ]):
        print("Could not build %s" % (html_file))
        exit(1)

    shutil.copytree("%s/assets" % source, "%s/assets" % target)
