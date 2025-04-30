curl --header "X-API-Token: yourToken" -X POST https://rudder.example.com/rudder/api/latest/archives/import --form "archive=@my-archive-file.zip" --form "merge=keep-rule-targets"
