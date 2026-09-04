curl --header "X-API-Token: yourToken" --request POST https://rudder.example.com/rudder/api/latest/techniques/categories --header "Content-Type: application/json" --data @- <<EOF
{
  "action": "create",
  "parent": "ncf_techniques",
  "name": "Linux hardening",
  "description": "Linux default hardening"
}
EOF