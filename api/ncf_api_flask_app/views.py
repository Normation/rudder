from ncf_api_flask_app import app
from flask import Flask, jsonify, request, abort
from functools import wraps
import requests
import os
import sys

# Add NCF_TOOLS_DIR to sys.path so we can access ncf module
NCF_TOOLS_DIR = '/usr/share/ncf/tools'
sys.path[0:0] = [NCF_TOOLS_DIR]

import ncf

def check_auth(f):
  """Method to use before each API call to check authentication"""
  @wraps(f)
  def decorated(*args, **kwargs):
    auth = get_auth()
    if auth.status_code == 401:
      return auth
    return f(*args, **kwargs)
  return decorated

def check_authentication_from_rudder(auth_request):
  """Send an authentication request to Rudder"""
  # We skip ssl certificate verification, since rudder and ncf-api are on the same domain and same virtualhost
  auth_result = requests.get('https://localhost/rudder/authentication', cookies =  request.cookies, verify = False)

  auth_response = jsonify( auth_result.json() )
  auth_response.status_code = auth_result.status_code

  return auth_response

def no_authentication(auth_request):
  """Always accept authentication request"""
  auth_response = auth_response = jsonify( {} )
  auth_response.status_code = 200

  return auth_response

def get_authentication_modules():
  """Find all available authentication modules"""
  # List of all authentication modules
  authentication_modules = { "Rudder" : check_authentication_from_rudder, "None" : no_authentication }
  # Name of all available modules, should read from a file or ncf path. only Rudder available for now
  available_modules_name = ["Rudder"]

  available_modules = [ module for module_name, module in authentication_modules.iteritems() if module_name in available_modules_name ]
  return available_modules

@app.route('/api/auth', methods = ['GET'])
def get_auth():
  """ Check authentication, should look for available modules and pass authentication on them"""
  for authentication_module in get_authentication_modules():
    authentication_result = authentication_module(request)
    if authentication_result.status_code == 200:
      # Authentication success return it
      return authentication_result
  # all authentication have failed, return an error
  error = jsonify ({ "error" : "Could not authenticate with ncf API"})
  error.status_code = 401
  return  error

@app.route('/api/techniques', methods = ['GET'])
@check_auth
def get_techniques():
  """Get all techniques from ncf folder passed as parameter, or default ncf folder if not defined"""
  # We need to get path from url params, if not present put "" as default value
  if "path" in request.args:
    path = request.args['path']
  else:
    path = ""

  techniques = ncf.get_all_techniques_metadata(alt_path = path)
  resp = jsonify( techniques )
  return resp

@app.route('/api/generic_methods', methods = ['GET'])
@check_auth
def get_generic_methods():
  """Get all generic methods from ncf folder passed as parameter, or default ncf folder if not defined"""
  # We need to get path from url params, if not present put "" as default value
  if "path" in request.args:
    path = request.args['path']
  else:
    path = ""

  generic_methods = ncf.get_all_generic_methods_metadata(alt_path = path)
  resp = jsonify( generic_methods )
  return resp

@app.route('/api/techniques', methods = ['POST', "PUT"])
@check_auth
def create_technique():
  # Get data in JSON
  # technique is a mandatory parameter, abort if not present
  if not "technique" in request.json:
    return jsonify( { 'error' : "No Technique metadata provided in the request body." } ), 400
  else:
    technique = request.json['technique']

  if "path" in request.json:
    path = request.json['path']
  else:
    path = ""

  try:
    ncf.write_technique(technique,path)
  except ncf.NcfError, ex:
    return jsonify( { 'error' : ex.message } ), 500
    
  return jsonify( technique ), 201


@app.route('/api/techniques/<string:bundle_name>', methods = ['DELETE'])
@check_auth
def delete_technique(bundle_name):
    
  if "path" in request.args:
    path = request.args['path']
  else:
    path = ""

  try:
    ncf.delete_technique(bundle_name,path)
  except ncf.NcfError, ex:
    return jsonify( { 'error' : ex.message } ), 500

  return jsonify( { "bundle_name" : bundle_name } )

if __name__ == '__main__':
  app.run(debug = True)
