groups.json:
{
 "category": "c355f46e-11b0-4c7a-aedd-6a5f3b0303b6",
 "displayName": "Example group",
 "description": "This is an example Group to use in Rudder api documentation",
 "query":
   {"select":"node","composition":"Or","where":
     [
       {"objectType":"node","attribute":"nodeId","comparator":"eq","value":"1ae6ccfe-00ba-44c0-b1aa-362d2f386032"},
       {"objectType":"node","attribute":"nodeId","comparator":"eq","value":"e4a80fd8-373e-45fc-ad94-2ae618be32e3"}
     ]
   },
  "dynamic": true,
  "enabled": true
}

curl --header "X-API-Token: yourToken" --request PUT https://rudder.example.com/rudder/api/latest/groups --header "Content-Type: application/json" --data @group.json

