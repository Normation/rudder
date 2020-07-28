curl --header "X-API-Token: yourToken" --request GET 'https://rudder.example.com/rudder/api/latest/rules'

# To get information about the target (included/excluded) groups of the rules
curl -H "X-API-Token: yourToken" -X GET 'https://rudder.example.com/rudder/api/latest/rules' | jq '.data.rules[] | {"d": .displayName, "id":.id, "inc": .targets[].include?.or, "exc":.targets[].exclude?.or}'
