from ncf_api_flask_app import app
from flask import Flask, jsonify, request, abort
import requests
import os
import sys

# Add NCF_TOOLS_DIR to sys.path so we can access ncf module
NCF_TOOLS_DIR = '/usr/share/ncf/tools'
sys.path[0:0] = [NCF_TOOLS_DIR]

import ncf

@app.route('/api/techniques', methods = ['GET'])
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
