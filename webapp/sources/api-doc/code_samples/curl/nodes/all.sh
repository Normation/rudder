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

# To get information about the nodes running a JVM process
#
# This gets information about nodes with processes matching the quesry, and then extract the flat list of matching processes
curl -s -k -H "X-API-Token: yourToken" 'https://rudder.example.com/rudder/api/latest/nodes?include=minimal,processes&where=\[\{"objectType":"process","attribute":"commandName","comparator":"regex","value":".*(java|jre|jdk).*"\}\]' | jq -r '.data.nodes[] | [ { hostname } + .processes[] ][] | select( .name | test(".*(java|jdk|jre).*")) | [.hostname, .user, .name] | @tsv' | cut -c -120
# It gives:
#
# node1.example.com	jenkins	java -jar remoting.jar -workDir /home/jenkins -jar-cache /home/jenkins/rem
# node2.example.com	tomcat8	/usr/lib/jvm/java-11-openjdk-amd64//bin/java -Djava.util.logging.config.fi
