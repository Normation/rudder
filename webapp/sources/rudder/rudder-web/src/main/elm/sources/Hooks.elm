port module Hooks exposing (..)

import Browser
import Http exposing (..)
import Http.Detailed as Detailed

import Hooks.ApiCalls exposing (..)
import Hooks.DataTypes exposing (..)
import Hooks.Init exposing (..)
import Hooks.JsonDecoder exposing (decodeErrorDetails)
import Hooks.View exposing (view)


main = Browser.element
  { init          = init
  , view          = view
  , update        = update
  , subscriptions = subscriptions
  }

port errorNotification   : String -> Cmd msg
port successNotification : String -> Cmd msg
port copy                : String -> Cmd msg
port scroll              : String -> Cmd msg


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
        Ok (metadata, categoriesResult) ->
          let
            modelUi  = model.ui
            rootHooks = "/opt/rudder/etc/hooks.d"
            catResult =
              List.map (\c ->
                if String.startsWith "node-" c.name then
                  {c | kind = Node}
                else if String.startsWith "policy-" c.name then
                  {c | kind = Policy}
                else
                  {c | kind = Other}
             ) categoriesResult
          in
            ( { model | root = rootHooks, categories = catResult, ui = { modelUi | loading = False } }
              , initJs ""
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
