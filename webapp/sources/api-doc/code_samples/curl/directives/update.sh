directive.json:
{
  "longDescription": "Add a loooooooooooong description",
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
                  "value": "Change Variable Content"
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
  "priority": 5
}

curl --header "X-API-Token: yourToken" --request POST https://rudder.example.com/rudder/api/latest/directives/cf2a6c72-18ae-4f82-a12c-0b887792db41 --header "Content-type: application/json" --data @directive.json