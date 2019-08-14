#! /bin/bash
PLUGIN_REPO="/root/rpkg/plugins"

extend_metadata() {
  if METADATA=`ar p $1 metadata 2>/dev/null`; then
    rpkgPath=`echo $1 | sed 's%/root/rpkg/plugins/%./%g'`
    echo $METADATA | jq " . + {\"path\": \"$rpkgPath\" }"
    echo ","
  fi
}

JSON="["
while read rpkg; do
    NEW=`extend_metadata $rpkg`
    JSON="$JSON $NEW"
done <<<$(find $PLUGIN_REPO/ -name '*.rpkg')
echo $JSON"]" | sed 's/\(.*\),/\1 /' > $PLUGIN_REPO"/rpkg.index"
