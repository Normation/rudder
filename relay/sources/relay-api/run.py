#!flask/bin/python3

# This file is only present for development/ local test and should not be used in production
# To deploy ncf api you should use it with a virtual environment and a wsgi file
# an example of this is available in ncf-api-virualenv package
# Virtualenv defined should be in local directory flask

import requests
from relay_api import app

app.run(debug = True)
