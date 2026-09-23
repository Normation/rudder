module EventLogs.ApiCalls exposing (..)

import EventLogs.DataTypes exposing (ContextPath(..), EventLogsMsg(..), ObjectId, Search)
import EventLogs.JsonDecoder exposing (decodeErrorDetails, decodeEventLogs)
import EventLogs.JsonEncoder exposing (encodeRestEventLogFilter)
import Http exposing (header, jsonBody, request)
import Http.Detailed as Detailed
import Url.Builder exposing (QueryParameter)


getEventLogs : ObjectId -> Int -> ContextPath -> Maybe String -> Cmd EventLogsMsg
getEventLogs id nbEventLogs (ContextPath contextPath) resourceTypeOpt =
    let
        url =
            case resourceTypeOpt of
                Just resourceType ->
                    [ contextPath, "secure", "api", "eventlog", resourceType ]

                Nothing ->
                    [ contextPath, "secure", "api", "eventlog" ]

        req =
            request
                { method = "POST"
                , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
                , url = Url.Builder.relative url []
                , body = encodeRestEventLogFilter id nbEventLogs |> jsonBody
                , expect = Detailed.expectJson GetEventLogs decodeEventLogs
                , timeout = Nothing
                , tracker = Nothing
                }
    in
    req


processEventLogsApiError : String -> Detailed.Error String -> (String -> Cmd msg) -> Cmd msg
processEventLogsApiError apiName err errorNotification =
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
