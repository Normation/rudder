#!/usr/bin/python

# This file is only present for development/ local test and should not be used in production
# To deploy ncf api you should use it with a virtual environment and a wsgi file
# an example of this is available in ncf-api-virualenv package
# Virtualenv defined should be in local directory flask

import requests
from ncf_api_flask_app import app
app.run(debug = True)
