# Given the "data.json" JSON file with content:
{ "properties": [
  { "name": "env_type"    , "value": "production" },
  { "name": "shell"       , "value": "/bin/sh" },
  { "name": "utf-8 poetry", "value": "ᚠᛇᚻ᛫ᛒᛦᚦ᛫ᚠᚱᚩᚠᚢᚱ᛫ᚠᛁᚱᚪ᛫ᚷᛖᚻᚹᛦᛚᚳᚢᛗ" }
]
, "policyMode" : "audit"
}
# Setting properties from "data.json" and policy mode to audit:
curl --header "X-API-Token: yourToken" --request POST --header "Content-Type: application/json" https://rudder.example.com/rudder/api/latest/nodes/17dadf50-6056-4c8b-a935-6b97d14b89a7 --data @properties.json

# Removing the key "utf-8 poetry" from the command line and updating the "env_type" one
curl --header "X-API-Token: yourToken" --request POST --header "Content-Type: application/json" https://rudder.example.com/rudder/api/latest/nodes/17dadf50-6056-4c8b-a935-6b97d14b89a7 --data '{ "properties": [{ "name":"utf-8 poetry", "value":""}, {"name":"env_type", "value":"deprovisioned"}] }'

# Removing the key "env_type" and changing "shell" and use default policy mode
curl --header "X-API-Token: yourToken" --request POST https://rudder.example.com/rudder/api/latest/nodes/17dadf50-6056-4c8b-a935-6b97d14b89a7 --data "properties=shell=/bin/false" -d "properties=env_type=" -d "policyMode=default"
