# settings.json:
#
#{
#  "username": "my-account-login",
#  "password": "xxxxxxxxx",
#  "url": "https://download.rudder.io/plugins",
#  "proxyUrl": ""
#  "proxyUser": ""
#  "proxyPassword": ""
#}
curl --header "X-API-Token: yourToken" --request POST https://rudder.example.com/rudder/api/latest/plugins/settings --header "Content-type: application/json" --data @dsettings.json
