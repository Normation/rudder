from ncf_api_flask_app import app
from flask import Flask, jsonify, request, abort
from functools import wraps
import requests
import os
import sys
import traceback
from pprint import pprint

# Add NCF_TOOLS_DIR to sys.path so we can access ncf module
NCF_TOOLS_DIR = '/usr/share/ncf/tools'
sys.path[0:0] = [NCF_TOOLS_DIR]

default_path = ""
use_rudder_auth = True

import ncf

import urllib3
urllib3.disable_warnings()

def format_error(exception, when, code):
  if not isinstance(exception, ncf.NcfError):
    exception = ncf.NcfError("Unknown internal error during " + when, "Cause: " + str(exception) + "\n" + traceback.format_exc())
  error = jsonify ({ "error": ncf.format_errors([exception]) })
  error.status_code = code
  return  error


def check_auth(f):
  """Method to use before each API call to check authentication"""
  @wraps(f)
  def decorated(*args, **kwargs):
    auth = get_auth()
    if auth.status_code != 200:
      return auth
    return f(*args, **kwargs)
  return decorated


def check_authentication_from_rudder(auth_request):
  """Send an authentication request to Rudder"""
  if auth_request.method == "GET":
    acl = "read"
  else:
    acl = "write"
  # An error may occured here and we need to catch the exception here or it will not be catched
  try:
    # We skip ssl certificate verification, since rudder and ncf-api are on the same domain and same virtualhost
    if "X-API-TOKEN" in auth_request.headers:
        auth_result = requests.get('https://localhost/rudder/api/authentication?acl=' + acl, headers =  {"X-api-Token": auth_request.headers.get('X-API-TOKEN')} , verify = False)
    else:
      auth_result = requests.get('https://localhost/rudder/authentication?acl=' + acl, cookies =  auth_request.cookies, verify = False)

    if auth_result.status_code == 200 or auth_result.status_code == 401 or auth_result.status_code == 403:
      auth_response = auth_result.json()
    else:
      auth_response = {"response": auth_result.text }
    auth_response["status_code"] = auth_result.status_code

    return auth_response
  except Exception as e:
    error = { "response" : "An error while authenticating to Rudder.\n=> Error message is: " + str(e), "status_code": 500 }

    return error


def no_authentication(auth_request):
  """Always accept authentication request"""
  auth_response = {"response" : "No authentication, access granted"}
  auth_response["status_code"] = 200

  return auth_response


def get_authentication_modules():
  """Find all available authentication modules"""
  # List of all authentication modules
  authentication_modules = { "Rudder" : check_authentication_from_rudder, "None" : no_authentication }
  # Name of all available modules, should read from a file or ncf path. only Rudder available for now
  available_modules_name = ["None"]

  if use_rudder_auth:
    available_modules_name = ["Rudder"]

  available_modules = [ module for module_name, module in authentication_modules.items() if module_name in available_modules_name ]
  return available_modules


@app.route('/api/auth', methods = ['GET'])
def get_auth():
  """ Check authentication, should look for available modules and pass authentication on them"""
  try:
    result = []
    for authentication_module in get_authentication_modules():
      authentication_result = authentication_module(request)
      if authentication_result["status_code"] == 200:
        # Authentication success return it
        success = jsonify ( authentication_result )
        return success
      if ("errorDetails" in authentication_result):
        result.append(authentication_result["errorDetails"])
      else:
        result.append("authentication error: " + str(authentication_result["status_code"]))
    # all authentication have failed, return an error
    error = jsonify ({ "error" : {"message": "Could not authenticate with ncf API", "details": "\n".join(result) }})
    error.status_code = 401
    return error
  except Exception as e:
    return format_error(e, "authentication", 500)

@app.route('/api/techniques', methods = ['GET'])
@check_auth
def get_techniques():
  """Get all techniques from ncf folder passed as parameter, or default ncf folder if not defined"""
  try:
    migration = "migration" in request.args and request.args['migration'] == "true"
    techniques = ncf.get_all_techniques_metadata(True,migration)
    resp = jsonify( techniques )
    return resp
  except Exception as e:
    return format_error(e, "techniques fetching", 500)

@app.route('/api/generic_methods', methods = ['GET'])
@check_auth
def get_generic_methods():
  """Get all generic methods from ncf folder passed as parameter, or default ncf folder if not defined"""
  try:
    # We need to get path from url params, if not present put "" as default value
    generic_methods = ncf.get_all_generic_methods_metadata()
    generic_methods["data"]["usingRudder"] = use_rudder_auth
    resp = jsonify( generic_methods )
    return resp
  except Exception as e:
    return format_error(e, "generic methods fetching", 500)

if __name__ == '__main__':
  app.run(debug = True)
