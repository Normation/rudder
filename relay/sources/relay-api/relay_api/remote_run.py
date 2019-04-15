from relay_api.common import *

import base64
import re
import os
import datetime
import requests
from subprocess import Popen, PIPE, STDOUT
from flask import Flask, Response
import itertools

# disable ssl warning on rudder connection in all the possible ways
try:
  import urllib3
  urllib3.disable_warnings()
except:
  pass

try:
  requests.packages.urllib3.disable_warnings()
except:
  pass

NEXTHOP = None
REMOTE_RUN_COMMAND = "sudo /opt/rudder/bin/rudder remote run"
LOCAL_RUN_COMMAND = "sudo /opt/rudder/bin/rudder agent run > /dev/null 2>&1"

def get_next_hop(nodes, my_uuid):
  """ Build a dict of node_id => nexthop_id """
  global NEXTHOP
  ## uncomment to enable nexthop caching caching (depends on nodeslist caching)
  #if NEXTHOP is not None:
  #  return NEXTHOP
  #else:
  NEXTHOP = {}
  for node in nodes:
    NEXTHOP[node] = node_route(nodes, my_uuid, node)[0]
  return NEXTHOP

def get_all_my_nodes(nexthop):
  """ Get all my directly connected nodes """
  result = []
  for node in nexthop:
    if nexthop[node] == node:
      result.append(node)
  return result

def get_my_nodes(nexthop, nodes):
  """ Get all nodes directly connected in the given list """
  result = []
  for node in nodes:
    if node not in nexthop:
      raise ValueError("ERROR unknown node: " + str(node))
    if nexthop[node] == node:
      result.append(node)
  return result

def get_relay_nodes(nexthop, relay, nodes):
  """ Get all nodes behind the given relay from the given list """
  result = []
  for node in nodes:
    if node not in nexthop:
      raise ValueError("ERROR unknown node: " + str(node))
    if nexthop[node] == relay and nexthop[node] != node:
      result.append(node)
  return result

def get_next_relays(nexthop):
  """ Get a list of all relays directly connected to me """
  result = set([])
  for node in nexthop:
    next_hop = nexthop[node]
    if next_hop != node:
      result.add(next_hop)
  return result

def resolve_hostname(local_nodes, node):
  """ Get the hostname of a node from its uuid """
  if node not in local_nodes:
    raise ValueError("ERROR unknown node: " + str(node))
  if "hostname" not in local_nodes[node]:
    raise ValueError("ERROR invalid nodes file on the server for " + node)
  return local_nodes[node]["hostname"]

def call_agent_run(host, uuid, classes, keep_output, asynchronous):
  """ Call the run command locally """
  if classes:
    classes_parameter = " -D " + classes
  else:
    classes_parameter = ""
  
  if uuid == "root":
    # root cannot make a remote run call to itself (certificate is not recognized correctly)
    # We do a standard local run instead.
    command = LOCAL_RUN_COMMAND
  else:
    command = REMOTE_RUN_COMMAND
  return run_command(command + classes_parameter + " " + host, uuid, keep_output, asynchronous)

def run_command(command, prefix, keep_output, asynchronous):
  """ Run the given command, prefixing all output lines with prefix """

  if keep_output:
    process = Popen(command, shell=True, stdout=PIPE, stderr=STDOUT)
    if not asynchronous:
      output = "".join([prefix + ":" + line for line in process.stdout.readlines()])
      process.wait()
    else:
      def stream():
        for line in iter(process.stdout.readline,''):
          yield prefix + ":" + line.rstrip()+"\n"
      output=stream()
  else:
    output = ""
    process = Popen(command, shell=True)
    if not asynchronous:
      process.wait()
  return output

def make_api_call(host, nodes, all_nodes, classes, keep_output, asynchronous):
  if all_nodes:
    method = "all"
  else:
    method = "nodes"

  url = "https://" + host + "/rudder/relay-api/remote-run/" + method

  data = {}

  if classes:
    data["classes"] = classes

  data["keep_output"] = keep_output
  data["asynchronous"] = asynchronous

  if nodes:
    data["nodes"] = ",".join(nodes)

  req = requests.post(url, data=data, verify=False, stream = asynchronous)
  if req.status_code == 200:
    response = ""
    if asynchronous:
      def stream():
        for content in req.iter_lines():
          yield content+"\n"
      response = stream()
    else:
      response = req.text
    return response
  else:
    raise ValueError("Upstream Error: " + req.text)

def remote_run_generic(local_nodes, my_uuid, nodes, all_nodes, form):
  # Set default option values
  classes = None
  keep_output = False
  asynchronous = False

  if "classes" in form:
    classes = form['classes']

  if "keep_output" in form:
    keep_output = form['keep_output'].lower() == "true"

  if "asynchronous" in form:
    asynchronous = form['asynchronous'].lower() == "true"

  NEXTHOP = get_next_hop(local_nodes, my_uuid)

  def generate_output():
    result = []
    # Pass the call to sub relays
    for relay in get_next_relays(NEXTHOP):
      host = resolve_hostname(local_nodes, relay)
      if all_nodes:
        result.append(make_api_call(host, None, all_nodes, classes, keep_output, asynchronous))
      else:
        relay_nodes = get_relay_nodes(NEXTHOP, relay, nodes)
        if relay_nodes:
          result.append(make_api_call(host, get_relay_nodes(NEXTHOP, relay, nodes), all_nodes, classes, keep_output, asynchronous))
    # Call directly managed nodes when needed
    if all_nodes:
      local_nodes_to_call = get_all_my_nodes(NEXTHOP)
    else:
      local_nodes_to_call = get_my_nodes(NEXTHOP, nodes)
    for node in local_nodes_to_call:
      host = resolve_hostname(local_nodes, node)
      result.append( call_agent_run(host, node, classes, keep_output, asynchronous))
    return result

  # depending on wether we want an asynch result we need to do something different on the output
  if asynchronous:
    # An async response, we produce generators that will be the result of our various async calls
    # We jsut need to chain them
    # May be could mix them ? Don't know how ( pipe ? outputstream ? )
    response = itertools.chain(* generate_output())
  else:
    # A synch response, we already have the final output and 
    response = "\n".join(generate_output())

  return Response(response )
