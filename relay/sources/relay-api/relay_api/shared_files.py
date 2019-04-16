from relay_api.common import *

import base64
import re
import os
import datetime
import requests
import hashlib

# disable ssl warning on rudder connection in all the possible ways
try:
  import urllib3
  urllib3.disable_warnings()
except:
  pass

try:
  from requests.packages import urllib3
  urllib3.disable_warnings()
except:
  pass

from pprint import pprint
from Crypto.Hash import SHA, SHA256, SHA512
from Crypto.Signature import PKCS1_v1_5
from Crypto.PublicKey import RSA


SIGNATURE_FORMAT="header=rudder-signature-v1"
METADATA_EXTENSION=".metadata"
BLOCKSIZE = 65536
SHARED_FOLDER="/var/rudder/configuration-repository/shared-files"

# convert a byte string to hexadecimal representation
toHex = lambda x:"".join([hex(ord(c))[2:].zfill(2) for c in x])

# convert an hexadecimal string to a byte string
toBin = lambda x:"".join([chr(int(x[c:c+2],16)) for c in range(0,len(x),2)])

# Parse a ttl string of the form "1day 2hours 3minute 4seconds"
# It can be abreviated to thos form "5h 3s"
# If it is a pure integer it is considered to be seconds
# Returns a timedelta object
def parse_ttl(string):
  m = re.match(r'^\s*(\d+)\s*$', string)
  if m:
    days = 0
    seconds = int(m.group(1))
  else:
    daymatch  = r'(\d+)\s*d(?:ays?)?'
    hourmatch = r'(\d+)\s*h(?:ours?)?'
    minmatch  = r'(\d+)\s*m(?:inutes?)?'
    secmatch  = r'(\d+)\s*s(?:econds?)?'
    match = r'^\s*(?:' + daymatch + r')?\s*(?:' + hourmatch + r')?\s*(?:' + minmatch + r')?\s*(?:' + secmatch + r')?\s*$'
    m = re.match(match, string)
    if m:
      days = 0
      seconds = 0
      if m.group(1) is not None:
        days = int(m.group(1))
      if m.group(2) is not None:
        seconds += 3600 * int(m.group(2))
      if m.group(3) is not None:
        seconds += 60 * int(m.group(3))
      if m.group(4) is not None:
        seconds += int(m.group(4))
    else:
      raise ValueError("ERROR invalid TTL specification:" + string)
  return datetime.timedelta(days, seconds)


# Extract the signature header from a data stream
# The header is delimited by an empty line
def get_header(data_stream):
  # format parsing
  line = data_stream.readline()
  if line.rstrip() != SIGNATURE_FORMAT:
    raise ValueError("ERROR unknown signature format: " + str(line))
  header = line
  while True:
    line = data_stream.readline()
    if line == "\n":
      return header
    header += line
  # the return is just above


# Extract informations from header
def parse_header(header):
  data = {}
  for line in header.rstrip().split("\n"):
    m = re.match(r"(\w+)\s*=\s*(.*)", line)
    if m:
      data[m.group(1)] = m.group(2)
    else:
      raise ValueError("ERROR invalid format: " + line)
  return data


# Extract a public key object from headers
def get_pubkey(header_info):
  # validate header content first
  if 'digest' not in header_info or 'short_pubkey' not in header_info:
    raise ValueError("ERROR incomplete header, missing digest or public key")
  pem = "-----BEGIN RSA PRIVATE KEY-----\n" + header_info['short_pubkey'] + "\n-----END RSA PRIVATE KEY-----\n"
  return RSA.importKey(pem)


# Create expiry header line
def expiry_line(header_info, ttl_value):
  if ttl_value is None or ttl_value == '':
    if 'ttl' not in header_info:
      raise ValueError("ERROR: No TTL provided")
    ttl = parse_ttl(header_info['ttl'])
  else:
    ttl = parse_ttl(ttl_value)
  expires = datetime.datetime.utcnow() + ttl # we take utcnow because we write a unix timestamp
  delta = expires - datetime.datetime(1970, 1, 1)
  timestamp = delta.days*24*3600 + delta.seconds # convert to unix timestamp
  return "expires=" + str(timestamp) + "\n"
    

# Hash a message with a given algorithm
# Returns a hash object
def get_hash(algorithm, message):
  if algorithm == "sha1":
    h=SHA.new(message)
  elif algorithm == "sha256":
    h=SHA256.new(message)
  elif algorithm == "sha512":
    h=SHA512.new(message)
  else:
    raise ValueError("ERROR unknown key hash type: " + str(algorithm))
  return h


# Validate that a given public key matches the provided hash
# The public key is a key object and the hash is of the form 'algorithm:kex_value'
def validate_key(pubkey, keyhash):
  try:
    (keyhash_type, keyhash_value) = keyhash.split(":",1)
  except:
    raise ValueError("ERROR invalid key hash, it should be 'type:value': " + keyhash)
  pubkey_bin = pubkey.exportKey(format="DER")
  h = get_hash(keyhash_type, pubkey_bin)
  return h.hexdigest() == keyhash_value

# Validate that a message has been properly signed by the given key
# The public key is a key object, algorithm is the hash algorithm and digest the hex signature
# The key algorithm will always be RSA because is is loaded as such
# Returns a booleas for the validity and the message hash to avoid computing it twice
def validate_message(message, pubkey, algorithm, digest):
  h = get_hash(algorithm, message)
  cipher = PKCS1_v1_5.new(pubkey)
  return (cipher.verify(h, toBin(digest)), h.hexdigest())


# Find in which directory a shared file should be stored
def file_directory(shared_path, nodes, my_uuid, target_uuid, source_uuid, file_id):
  if not re.match(r"^[\w\-.]+$", file_id):
    raise ValueError("ERROR file_id must be an identifier [A-z0-9_-.]: " + str(file_id))
  route_path = '/shared-files/'.join(node_route(nodes, my_uuid, target_uuid))
  return shared_path + "/" + route_path + "/files/" + source_uuid


# Returns the stored hash from the metadata file
def get_metadata_hash(metadata_file):
  fd = open(metadata_file, 'r')
  line = fd.readline().rstrip()
  if line != SIGNATURE_FORMAT:
    fd.close()
    raise ValueError("ERROR invalid storage: " + line)
  while True:
    line = fd.readline().rstrip()
    m = re.match(r"(\w+)\s*=\s*(.*)", line)
    if m:
      if m.group(1) == "hash_value":
        fd.close()
        return m.group(2)
    else:
      fd.close()
      raise ValueError("ERROR invalid storage: " + line)

def get_shared_folder_hash(file_path, hasher):
  with open(file_path, 'rb') as afile:
    buf = afile.read(BLOCKSIZE)
    while len(buf) > 0:
      hasher.update(buf)
      buf = afile.read(BLOCKSIZE)
  return hasher.hexdigest()

# =====================
#  Manage PUT API call
# =====================
# Parameters:
# - target_uuid  where to send the file to
# - source_uuid  who sent the file
# - file_id      under which name to store the file
# - data         the file content
# - nodes        the content of the nodes_list file
# - my_uuid      uuid of the current relay (self)
# - shared_path  the shared-files directory path
# - ttl          duration to keep the file
# Returns the full path of the created file
def shared_files_put(target_uuid, source_uuid, file_id, data_stream, nodes, my_uuid, shared_path, ttl):
  header = get_header(data_stream)
  info = parse_header(header)

  # extract information
  pubkey = get_pubkey(info)
  if source_uuid not in nodes:
    raise ValueError("ERROR unknown source node: " + str(source_uuid))
  if "key-hash" not in nodes[source_uuid]:
    raise ValueError("ERROR invalid nodes file on the server for " + source_uuid)
  keyhash = nodes[source_uuid]["key-hash"]

  # validate key
  if not validate_key(pubkey, keyhash):
    raise ValueError("ERROR invalid public key or not matching UUID")

  # validate message
  message = data_stream.read()
  (validated, message_hash) = validate_message(message, pubkey, info['algorithm'], info['digest'])
  if not validated:
    raise ValueError("ERROR invalid signature")

  # add headers
  header += expiry_line(info, ttl)
  # replace hash by a guaranteed one
  header = re.sub(r'hash_value=.*?\n', "hash_value=" + message_hash + "\n", header)

  # where to store file
  path = file_directory(shared_path, nodes, my_uuid, target_uuid, source_uuid, file_id)
  filename = path + "/" + file_id

  # write data & metadata
  try:
    os.makedirs(path, 0o750)
  except:
    pass # makedirs fails if the directory exists
  fd = open(filename, 'w')
  fd.write(message)
  fd.close()
  fd = open(filename + METADATA_EXTENSION, 'w')
  fd.write(header)
  fd.close()
  return filename


# ======================
#  Forward PUT API call
# ======================
# Parameters:
# - stream     stream of posted data
# - url        where to forward to
# Returns the full path of the created file
def shared_files_put_forward(stream, url):
  # This is needed to make requests know the length
  # It will avoid streaming the content which would make the next hop fail
  stream.len = stream.limit
  req = requests.put(url, data=stream, verify=False)
  if req.status_code == 200:
    return req.text
  else:
    raise ValueError("Upstream Error: " + req.text)

# ======================
#  Manage HEAD API call
# ======================
# Parameters:
# - target_uuid  where to send the file to
# - source_uuid  who sent the file
# - file_id      under which name to store the file
# - file_hash    the hash to compare with
# - nodes        the content of the nodes_list file
# - my_uuid      uuid of the current relay (self)
# - shared_path  the shared-files directory path
# Returns true of false
def shared_files_head(target_uuid, source_uuid, file_id, file_hash, nodes, my_uuid, shared_path):
  # where to find file
  path = file_directory(shared_path, nodes, my_uuid, target_uuid, source_uuid, file_id)
  filename = path + "/" + file_id
  metadata = filename + METADATA_EXTENSION

  # check if the file and signature exist
  if not os.path.isfile(filename) or not os.path.isfile(metadata):
    return False

  # check hash from metadata
  hash_value = get_metadata_hash(metadata)
  return hash_value == file_hash

# =======================
#  Forward HEAD API call
# =======================
# Parameters:
# - url     where to forward to
# Returns true of false
def shared_files_head_forward(url):
  req = requests.head(url, verify=False)
  if req.status_code == 200:
    return True
  if req.status_code == 404:
    return False
  raise ValueError("ERROR from server:" + str(req.status_code))

# ======================
# Share folder HEAD API call
# ======================
# Parameters:
# - file_name    Name of the file in shared folder
# - file_hash    The hash to compare with, can be None or Empty
# - hash_type    hash algorithm
# Returns correponding return code, 404 (file does not exists), 304 (hash is the same, not modified), 200 (file is different or no Hash sent, download)
# 500 if hash_type is invalid
def shared_folder_head(file_name, file_hash, hash_type):
  # where to find file
  file_path = os.path.join(SHARED_FOLDER, file_name)
  # check if the file and signature exist
  if not os.path.isfile(file_path):
    return 404
  else:
    if file_hash is None or file_hash == "":
      return 200
    try:
      hasher = hashlib.new(hash_type)
    except:
      return 500
    # check hash
    hash_value = get_shared_folder_hash(file_path, hasher)
    if hash_value == file_hash:
      return 304
    else:
      return 200
