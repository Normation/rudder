#!/usr/bin/env bash

# you can export env variable APITOKEN and BASEURL to whatever you want

USERAPITOKEN="${APITOKEN:-$(cat /var/rudder/run/api-token)}"
USERBASEURL="${BASEURL:-"https://localhost:8080/rudder/api/latest"}"

CURL="curl -k --fail -H Content-Type:application/json -H X-API-TOKEN:${USERAPITOKEN}"

echo "running with API TOKEN: ${USERAPITOKEN} on ${USERBASEURL}"

# Some random uuids to use in the tests

UUIDS="2413513f-3778-4f20-91d5-b4fa835763ac
041715d7-ccce-4523-900a-f89c7cb6de4f
739571a5-ed63-40a1-9397-49a7712ce485
d9927670-c109-4900-b1c8-ca9c726ed6bd
69a74f58-cd34-48d7-9092-6d61f4b0c547
03a12597-f34b-4f66-a4e1-3ed6c816fd95
5908399e-d629-47c7-ab7c-2bae796fe6da
715170f3-9c50-49d5-bed6-494adfc2500e
6c309439-8aa7-480f-bf59-54f205ca74ff
8eb2ac5f-6bb8-43b1-8481-7caa9ee07794"

#
# Set to false to avoid runing corresponding tests
#
DO_PARAMETERS=true
DO_RULES=true
DO_RULE_CATEGORIES=true
DO_DIRECTIVES=true
DO_GROUPS=true
DO_GROUP_CATEGORIES=true
DO_NODE_PROPERTIES=true

# For each object kind, for $UUIDS id:
# - 1/ concurrently create corresponding objets
# - 2/ concurrently modify
# - 3/ concurrently delete

#######################
##### Parameters ######
#######################

if ${DO_PARAMETERS}; then
  SUBPATH="parameters"

### create ####
  for i in $(echo ${UUIDS}); do
    read -r -d '' JSON <<-JSONEOF
    {
      "id":"${i}"
    , "value":"value-${i}"
    }
JSONEOF

    ${CURL} -X PUT "${USERBASEURL}/${SUBPATH}" -d "${JSON}" | jq '.' &
  done

  sleep 2

### modify ###
  for i in $(echo ${UUIDS}); do
    read -r -d '' JSON <<-JSONEOF
    {
      "description":"new description"
    }
JSONEOF

    ${CURL} -X POST "${USERBASEURL}/${SUBPATH}/${i}" -d "${JSON}" | jq '.' &
  done

  sleep 2

### delete ####
  for i in $(echo ${UUIDS}); do
    ${CURL} -X DELETE "${USERBASEURL}/${SUBPATH}/${i}" | jq '.' &
  done

  sleep 2
fi


##################
##### Rules ######
##################

if ${DO_RULES}; then
  SUBPATH="rules"

### create ###
  for i in $(echo ${UUIDS}); do
    read -r -d '' JSON <<-JSONEOF
    {
      "id":"${i}"
    , "displayName":"name-${i}"
    }
JSONEOF

    ${CURL} -X PUT "${USERBASEURL}/${SUBPATH}" -d "${JSON}" | jq '.' &
  done

  sleep 2

### modify ###
  for i in $(echo ${UUIDS}); do
    read -r -d '' JSON <<-JSONEOF
    {
      "shortDescription":"new description"
    }
JSONEOF

    ${CURL} -X POST "${USERBASEURL}/${SUBPATH}/${i}" -d "${JSON}" | jq '.' &
  done

  sleep 2

### delete ####
  for i in $(echo ${UUIDS}); do
    ${CURL} -X DELETE "${USERBASEURL}/${SUBPATH}/${i}" | jq '.' &
  done

  sleep 2
fi

########################
### Rules Categories ###
########################

if ${DO_RULE_CATEGORIES}; then
  SUBPATH="rules/categories"

### create ###
  for i in $(echo ${UUIDS}); do
    read -r -d '' JSON <<-JSONEOF
    {
      "parent":"rootRuleCategory"
    , "id":"${i}"
    , "name":"value-${i}"
    }
JSONEOF

    ${CURL} -X PUT "${USERBASEURL}/${SUBPATH}" -d "${JSON}" | jq '.' &
  done

  sleep 2

### modify ###
  for i in $(echo ${UUIDS}); do
    read -r -d '' JSON <<-JSONEOF
    {
      "shortDescription":"new description"
    }
JSONEOF

    ${CURL} -X POST "${USERBASEURL}/${SUBPATH}/${i}" -d "${JSON}" | jq '.' &
  done

  sleep 2

### delete ####
  for i in $(echo ${UUIDS}); do
    ${CURL} -X DELETE "${USERBASEURL}/${SUBPATH}/${i}" | jq '.' &
  done

  sleep 2
fi


##################
### DIRECTIVES ###
##################

if ${DO_DIRECTIVES}; then
  SUBPATH="directives"

### create ###
  for i in $(echo ${UUIDS}); do
    read -r -d '' JSON <<-JSONEOF
    {
      "id":"${i}"
    , "displayName":"name-${i}"
    , "techniqueName":"packageManagement"
    , "techniqueVersion":"1.1"
    , "parameters": {
        "section": {
          "name": "sections",
          "sections": [
            {
              "section": {
                "name": "Package",
                "vars": [
                  {
                    "var": {
                      "name": "PACKAGE_LIST",
                      "value": "package"
                    }
                  }
                , {
                    "var": {
                      "name": "PACKAGE_STATE",
                      "value": "present"
                    }
                  }
                ]
              }
            }
          ]
        }
      }
    }
JSONEOF

    ${CURL} -X PUT "${USERBASEURL}/${SUBPATH}" -d "${JSON}" | jq '.' &
  done

  sleep 2

### modify ###
  for i in $(echo ${UUIDS}); do
    read -r -d '' JSON <<-JSONEOF
    {
      "shortDescription":"new description"
    }
JSONEOF

    ${CURL} -X POST "${USERBASEURL}/${SUBPATH}/${i}" -d "${JSON}" | jq '.' &
  done

  sleep 2

### delete ####
  for i in $(echo ${UUIDS}); do
    ${CURL} -X DELETE "${USERBASEURL}/${SUBPATH}/${i}" | jq '.' &
  done

  sleep 2
fi

##############
### GROUPS ###
##############

if ${DO_GROUPS}; then
  SUBPATH="groups"

### create ###
  for i in $(echo ${UUIDS}); do
    read -r -d '' JSON <<-JSONEOF
    {
      "id":"${i}"
    , "displayName":"name-${i}"
    , "query": {
        "select": "node", "composition": "and", "where":
        [{"objectType": "node", "attribute": "OS", "comparator": "eq", "value": "Linux"}]
      }
    }
JSONEOF

    ${CURL} -X PUT "${USERBASEURL}/${SUBPATH}" -d "${JSON}" | jq '.' &
  done

  sleep 2

### modify ###
  for i in $(echo ${UUIDS}); do
    read -r -d '' JSON <<-JSONEOF
    {
      "description":"new description"
    }
JSONEOF

    ${CURL} -X POST "${USERBASEURL}/${SUBPATH}/${i}" -d "${JSON}" | jq '.' &
  done

  sleep 2

### delete ####
  for i in $(echo ${UUIDS}); do
    ${CURL} -X DELETE "${USERBASEURL}/${SUBPATH}/${i}" | jq '.' &
  done

  sleep 2
fi

########################
### GROUP CATEGORIES ###
########################

if ${DO_GROUP_CATEGORIES}; then
  SUBPATH="groups/categories"

### create ###
  for i in $(echo ${UUIDS}); do
    read -r -d '' JSON <<-JSONEOF
    {
      "parent":"GroupRoot"
    , "id":"${i}"
    , "name":"name-${i}"
    }
JSONEOF

    ${CURL} -X PUT "${USERBASEURL}/${SUBPATH}" -d "${JSON}" | jq '.' &
  done

  sleep 2

### modify ###
  for i in $(echo ${UUIDS}); do
    read -r -d '' JSON <<-JSONEOF
    {
      "description":"new description"
    }
JSONEOF

    ${CURL} -X POST "${USERBASEURL}/${SUBPATH}/${i}" -d "${JSON}" | jq '.' &
  done

  sleep 2

### delete ####
  for i in $(echo ${UUIDS}); do
    ${CURL} -X DELETE "${USERBASEURL}/${SUBPATH}/${i}" | jq '.' &
  done

  sleep 2
fi

#########################
### NODE (properties) ###
#########################

if ${DO_NODE_PROPERTIES}; then
  SUBPATH="nodes/root"

### create ###
  for i in $(echo ${UUIDS}); do
    read -r -d '' JSON <<-JSONEOF
    {
      "properties": [
        {
          "name": "${i}",
          "value": "value-${i}"
        }
      ]
    }
JSONEOF

    ${CURL} -X POST "${USERBASEURL}/${SUBPATH}" -d "${JSON}" | jq '.' &
  done

  sleep 2

### modify ###
  for i in $(echo ${UUIDS}); do
    read -r -d '' JSON <<-JSONEOF
    {
      "properties": [
        {
          "name": "${i}",
          "value": "modify-${i}"
        }
      ]
    }
JSONEOF

    ${CURL} -X POST "${USERBASEURL}/${SUBPATH}" -d "${JSON}" | jq '.' &
  done

  sleep 2

### delete ####
  for i in $(echo ${UUIDS}); do
    read -r -d '' JSON <<-JSONEOF
    {
      "properties": [
        {
          "name": "${i}",
          "value": ""
        }
      ]
    }
JSONEOF
    ${CURL} -X POST "${USERBASEURL}/${SUBPATH}" -d "${JSON}" | jq '.' &
  done

  sleep 2
fi
