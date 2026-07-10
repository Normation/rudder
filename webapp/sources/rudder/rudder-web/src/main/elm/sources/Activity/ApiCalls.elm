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


processApiError : String -> Detailed.Error String -> (String -> Cmd msg) -> Cmd msg
processApiError apiName err errorNotification =
    let
        message =
            case err of
                Detailed.BadUrl url ->
                    "The URL " ++ url ++ " was invalid"

                Detailed.Timeout ->
                    "Unable to reach the server, try again"

                Detailed.NetworkError ->
                    "Unable to reach the server, check your network connection"

                Detailed.BadStatus _ body ->
                    let
                        ( title, errors ) =
                            decodeErrorDetails body
                    in
                    title ++ "\n" ++ errors

                Detailed.BadBody _ _ msg ->
                    msg
    in
    errorNotification ("Error when " ++ apiName ++ ", details: \n" ++ message)
