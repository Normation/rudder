module Groups.ApiCalls exposing (..)

import Url.Builder exposing (string, QueryParameter)
import Http exposing (emptyBody, expectJson, request)

import Groups.DataTypes exposing (..)
import Groups.JsonDecoder exposing (..)
import GroupRelatedRules.DataTypes exposing (GroupId)
--
-- This files contains all API calls for the Group compliance UI
--

getUrl: Model -> List String -> List QueryParameter -> String
getUrl m url p=
  Url.Builder.relative (m.contextPath :: "secure" :: "api"  :: url) p

getGroupsCompliance : Bool -> List GroupId -> Model -> Cmd Msg
getGroupsCompliance keepGroups groupIds model =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model [ "compliance", "summary", "groups"] [string "groups" (String.join "," (List.map (.value) groupIds))]

        , body    = emptyBody
        , expect  = expectJson (GetGroupsComplianceResult keepGroups) decodeGetGroupsCompliance
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getGroupsTree : Model -> Bool -> Cmd Msg
getGroupsTree model chainInitTable =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model ["groupsinternal", "categorytree"] []
        , body    = emptyBody
        , expect  = expectJson (\r -> GetGroupsTreeResult r chainInitTable) decodeGetGroupsTree 
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

-- Reset the group compliance summary and fetch the first batch. Needs the tree to be populated in order to fetch group ids
getInitialGroupCompliance : Model -> Cmd Msg
getInitialGroupCompliance model = getGroupsCompliance False (nextGroupIds model) model