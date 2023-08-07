module ApiCalls exposing (..)

import DataTypes exposing (..)
import Http exposing (..)
import JsonDecoder exposing (..)
import JsonEncoder exposing (..)
import Url.Builder exposing (QueryParameter)
import Http.Detailed as Detailed


--
-- This files contains all API calls for the Rules UI
-- Summary:
-- GET    /apiaccounts: get the api accounts list

getUrl: DataTypes.Model -> List String -> List QueryParameter -> String
getUrl m url p=
  Url.Builder.relative (m.contextPath :: "secure" :: url) p

getAccounts : Model -> Cmd Msg
getAccounts model =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model [ "apiaccounts" ] []
        , body    = emptyBody
        , expect  = Detailed.expectJson GetAccountsResult (decodeGetAccounts model.ui.datePickerInfo)
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

saveAccount : Account -> Model -> Cmd Msg
saveAccount account model =
  let
    (method, url) = case model.ui.modalState of
      NewAccount -> ("PUT",["apiaccounts"])
      _ -> ("POST", ["apiaccounts", account.id])
    req =
      request
        { method  = method
        , headers = []
        , url     = getUrl model url []
        , body    = encodeAccount model.ui.datePickerInfo account |> jsonBody
        , expect  = Detailed.expectJson SaveAccount (decodePostAccount model.ui.datePickerInfo)
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

deleteAccount : Account -> Model -> Cmd Msg
deleteAccount account model =
  let
    req =
      request
        { method  = "DELETE"
        , headers = []
        , url     = getUrl model ["apiaccounts", account.id] []
        , body    = emptyBody
        , expect  = Detailed.expectJson (ConfirmActionAccount Delete) (decodePostAccount model.ui.datePickerInfo)
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

regenerateToken : Account -> Model -> Cmd Msg
regenerateToken account model =
  let
    req =
      request
        { method  = "POST"
        , headers = []
        , url     = getUrl model ["apiaccounts", account.id, "regenerate"] []
        , body    = emptyBody
        , expect  = Detailed.expectJson (ConfirmActionAccount Regenerate) (decodePostAccount model.ui.datePickerInfo)
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req