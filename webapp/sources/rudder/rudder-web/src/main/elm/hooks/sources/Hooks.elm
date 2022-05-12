module Hooks exposing (..)

import Browser
import DataTypes exposing (..)
import Http exposing (..)
import Http.Detailed as Detailed
import Init exposing (..)
import View exposing (view)
import ApiCalls exposing (..)
import JsonDecoder exposing (decodeErrorDetails)

main = Browser.element
  { init          = init
  , view          = view
  , update        = update
  , subscriptions = subscriptions
  }

--
-- update loop --
--
update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
  case msg of
    Copy s -> (model, copy s)

    -- Do an API call
    CallApi call ->
      (model, call model)

    -- neutral element
    Ignore ->
      (model , successNotification "")

    UpdateFilters filters ->
      let
        ui = model.ui
      in
        ({model | ui = { ui | filters = filters}}, Cmd.none)

    GetHooksResult res ->
      case  res of
        Ok (metadata, apiResult) ->
          let
            modelUi  = model.ui
          in
            ( { model | root = apiResult.root, categories = apiResult.categories, ui = { modelUi | loading = False } }
              , initTooltips ""
            )
        Err err ->
          processApiError "Getting Hooks list" err model


processApiError : String -> Detailed.Error String -> Model -> ( Model, Cmd Msg )
processApiError apiName err model =
  let
    modelUi = model.ui
    message =
      case err of
        Detailed.BadUrl url ->
          "The URL " ++ url ++ " was invalid"
        Detailed.Timeout ->
          "Unable to reach the server, try again"
        Detailed.NetworkError ->
          "Unable to reach the server, check your network connection"
        Detailed.BadStatus metadata body ->
          let
            (title, errors) = decodeErrorDetails body
          in
            title ++ "\n" ++ errors
        Detailed.BadBody metadata body msg ->
          msg
  in
    ({ model | ui = { modelUi | loading = False}}, errorNotification ("Error when "++ apiName ++", details: \n" ++ message ))
