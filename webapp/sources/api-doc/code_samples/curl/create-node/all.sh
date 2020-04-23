nodes.json:

[
  {
    "id":"378740d3-c4a9-4474-8485-478e7e52db52",
    "hostname":"my.node.hostname.local",
    "status":"accepted",
    "os":{
      "type":"linux",
      "name":"debian",
      "version":"9.5",
      "fullName":"Debian GNU/Linux 9 (stretch)"
    },
    "policyServerId":"root",
    "machineType":"vmware",
    "state":"enabled",
    "policyMode":"enforce",
    "agentKey":{
      "value":"----BEGIN CERTIFICATE---- ...."
    },
    "properties":{
      "tags":[
        "some",
        "tags"
      ],
      "env":"prod",
      "vars":{
        "var1":"value1",
        "var2":"value2"
      }
    },
    "ipAddresses":[
      "192.168.180.90",
      "127.0.0.1"
    ],
    "timezone":{
      "name":"CEST",
      "offset":"+0200"
    }
  }
]

curl --header "X-API-Token: yourToken" --request PUT https://rudder.example.com/rudder/api/latest/createnodes --header "Content-type: application/json" --data @nodes.json

