# To get the Linux nodes with a hostname starting with "node1"
curl --header "X-API-Token: yourToken" 'https://rudder.example.com/rudder/api/latest/nodes?where=\[\{"objectType":"node","attribute":"OS","comparator":"eq","value":"Linux"\},\{"objectType":"node","attribute":"nodeHostname","comparator":"regex","value":"node1.*"\}\]'

# To get the list of nodes with their agent version
curl -k -H "X-API-Token: yourToken" -X GET "https://rudder.example.com/rudder/api/latest/nodes?include=minimal,managementTechnology" | jq '.data.nodes[] | {"id": .id, "version": .managementTechnology[].version}'

# To get information about the eth0 interface of a specific node
curl -k -H "X-API-Token: yourToken" -H "Content-Type: application/json" -X GET 'https://rudder.example.com/rudder/api/latest/nodes/8b168194-c0b4-41ab-b2b5-9571a8906d59?include=networkInterfaces' | jq '.data.nodes[].networkInterfaces[] | select(.name == "eth0")'
# It gives:
#
#{
# "name": "eth0",
# "type": "ethernet",
# "status": "Up",
# "macAddress": "52:54:00:49:45:ac",
# "ipAddresses": [
#   "fe80:0:0:0:5054:ff:fe49:45ac",
#   "192.168.110.21"
# ]
#}


