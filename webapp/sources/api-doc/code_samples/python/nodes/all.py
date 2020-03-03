import json
import requests

# Get all nodes having a hostname starting with node1 and based on Linux and only display minimal information (id, hostname, status)
url = "https://rudder.example.com/rudder/api/latest/nodes"
linux = {"objectType": "node", "attribute": "OS",
         "comparator": "eq", "value": "Linux"}
node1 = {"objectType": "node", "attribute": "nodeHostname",
         "comparator": "regex", "value": "node1.*"}
where = [linux, node1]
params = {"where": json.dumps(where), "include": "minimal"}
headers = {"X-API-TOKEN": "yourToken"}
requests.get(url, params=params, headers=headers, verify=False)
