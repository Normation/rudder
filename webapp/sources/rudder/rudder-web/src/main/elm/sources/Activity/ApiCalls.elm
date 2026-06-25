module Activity.ApiCalls exposing (..)

import Activity.DataTypes exposing (ActivityMsg(..), ContextPath(..), Search)
import Activity.JsonDecoder exposing (decodeErrorDetails, decodeGetActivities)
import Activity.JsonEncoder exposing (encodeRestEventLogFilter)
import Http exposing (header, jsonBody, request)
import Http.Detailed as Detailed
import Url.Builder exposing (QueryParameter)


getActivities : Search -> List String -> ContextPath -> Cmd ActivityMsg
getActivities search filterType (ContextPath contextPath) =
    let
        req =
            request
                { method = "POST"
                , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
                , url = Url.Builder.relative (contextPath :: "secure" :: "api" :: [ "eventlog" ]) []
                , body = encodeRestEventLogFilter search filterType |> jsonBody
                , expect = Detailed.expectJson GetActivities decodeGetActivities
                , timeout = Nothing
                , tracker = Nothing
                }
    in
    req
