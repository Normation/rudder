import traceback
import json
from flask import Flask, jsonify, request, abort, make_response

NODES=None


# Format error as api output
def format_error(exception, debug):
  message = "Internal error!\n"
  message += "Cause: " + type(u'')(exception) + "\n"
  if debug:
    message += traceback.format_exc() + "\n"
  error = make_response(message)
  error.headers['Content-Type'] = 'text/plain; charset=utf-8'
  error.status_code = 500
  return error


# Format a success response as api output
def format_response(text, code=200):
  response = make_response(text)
  response.headers['Content-Type'] = 'text/plain; charset=utf-8'
  response.status_code = code
  return response


# returns the UUID of localhost
def get_file_content(filename):
  fd = open(filename, 'r')
  content = fd.read().replace('\n','').strip()
  fd.close()
  return content


# returns [ relay_uuid, ... , node_uuid ], not including self
def node_route(nodes, my_uuid, uuid):
  if uuid not in nodes:
    raise ValueError("ERROR unknown node: " + str(uuid))
  if "policy-server" not in nodes[uuid]:
    raise ValueError("ERROR invalid nodes file on the server for " + uuid)
  server = nodes[uuid]["policy-server"]
  if server == my_uuid:
    return [uuid]
  route = node_route(nodes, my_uuid, server)
  route.append(uuid)
  return route


# Returns the parsed content of the nodeslist file
def get_nodes_list(nodeslist_file):
  global NODES
  ## uncomment to enable nodeslist caching
  #if NODES is not None:
  #  return NODES
  fd = open(nodeslist_file, 'r')
  NODES = json.load(fd)
  fd.close()
  return NODES

