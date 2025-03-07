# account.json:
#
# {
#   "name":"account updated",
#   "status": "enabled"
# }

curl --header "X-API-Token: yourToken" --request POST https://rudder.example.com/rudder/api/latest/apiaccounts/1ebee8c7-c898-4ee5-9470-139bfd80c442 --header "Content-type: application/json" --data @account.json
