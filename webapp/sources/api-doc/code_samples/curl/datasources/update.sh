cat disable-datasource-1.json.json
{
  "description": "This data source is temporarly no more used and so disabled",
  "enabled": false
}

curl --header "X-API-Token: yourToken" --request POST https://rudder.example.com/rudder/api/latest/datasources/my-data-source --header "Content-type: application/json" --data @disable-datasource-1.json.json