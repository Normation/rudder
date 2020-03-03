directives.json:

{
  "id": "cf2a6c72-18ae-4f82-a12c-0b887792db41",
  "displayName": "Example Directive",
  "shortDescription": "This in an example Directive to use in Rudder api documentation",
  "longDescription": "",
  "techniqueName": "genericVariableDefinition",
  "techniqueVersion": "2.0",
  "tags": {
    "env" : "production",
    "country" : "FR"
  },
  "parameters": {
    "section": {
      "name": "sections",
      "sections": [
        {
          "section": {
            "name": "Variable definition",
            "vars": [
              {
                "var": {
                  "name": "GENERIC_VARIABLE_CONTENT",
                  "value": "new variable content"
                }
              },
              {
                "var": {
                  "name": "GENERIC_VARIABLE_NAME",
                  "value": "new_variable"
                }
              }
            ]
          }
        }
      ]
    }
  },
  "priority": 3,
  "enabled": true,
  "system": false,
  "policyMode": "default"
}

curl --header "X-API-Token: yourToken" --request PUT https://rudder.example.com/rudder/api/latest/directives --header "Content-type: application/json" --data @directive.json

