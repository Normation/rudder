# To get the compliance information of a specific node
curl --header "X-API-Token: yourToken" --request GET 'https://rudder.example.com/rudder/api/compliance/nodes?level=2'

# To get the list of nodes wich have a compliance <100 for a given directive (c5881268-5612-48f2-8ef4-0ab8387fccd6) 
curl -k -H "X-API-Token: yourToken" -X GET "https://rudder.example.com/rudder/api/latest/compliance/nodes?level=3" \
| jq '[.data.nodes[] 
  | {
      "nodeid":.id, 
      "dirs": [.rules[].directives[]] 
        | map(select(.id == "c5881268-5612-48f2-8ef4-0ab8387fccd6" and .compliance < 100)) 
    }
  ] 
| map(select(.dirs | length != 0)) 
| [.[] |
    {"nodeid":.nodeid, "comp":.dirs[0].complianceDetails}
  ]'

