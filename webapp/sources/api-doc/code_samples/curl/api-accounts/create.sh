# account.json:
#
# {
#   "name":"account 1",
#   "description":"account 1 description",
#   "expirationPolicy": "datetime",
#   "status": "enabled",
#   "generateToken":true,
#   "tenants":"*",
#   "authorizationType":"acl",
#   "acl":[
#     {
#       "path":"rules/tree",
#       "actions":["get","post"]
#     },
#     {
#       "path":"rules/categories/*",
#       "actions":["get"}
#     },
#     {
#       "path":"compliance/*",
#       "actions":["get"]
#     }
#   ]
# }

curl --header "X-API-Token: yourToken" --request POST https://rudder.example.com/rudder/api/latest/apiaccounts --header "Content-type: application/json" --data @account.json

