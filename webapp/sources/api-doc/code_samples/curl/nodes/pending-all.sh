# To get the list of pending nodes
curl --header "X-API-Token: yourToken" 'https://rudder.example.com/rudder/api/latest/nodes/pending'

# To get the pending Linux nodes with a hostname starting with "node1"
curl --header "X-API-Token: yourToken" 'https://rudder.example.com/rudder/api/latest/nodes/pending?where=\[\{"objectType":"node","attribute":"OS","comparator":"eq","value":"Linux"\},\{"objectType":"node","attribute":"nodeHostname","comparator":"regex","value":"node1.*"\}\]'

# To get the list of pending nodes with their agent version
curl -k -H "X-API-Token: yourToken" -X GET "https://rudder.example.com/rudder/api/latest/nodes/pending?include=minimal,managementTechnology" | jq '.data.nodes[] | {"id": .id, "version": .managementTechnology[].version}'



