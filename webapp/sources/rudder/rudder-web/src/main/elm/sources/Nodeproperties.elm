port module Nodeproperties exposing (..)

import Browser
import Http exposing (..)
import Result
import Json.Encode exposing (encode, string, object)
import Json.Decode exposing (decodeValue, value)
import Dict exposing (..)
import List.Extra exposing (remove)

import NodeProperties.ApiCalls exposing (..)
import NodeProperties.DataTypes exposing (..)
import NodeProperties.Init exposing (init)
import NodeProperties.JsonDecoder exposing (..)
import NodeProperties.View exposing (view)

import Debug

-- PORTS / SUBSCRIPTIONS
port errorNotification   : String -> Cmd msg
port successNotification : String -> Cmd msg
port initTooltips        : String -> Cmd msg
port copy                : String -> Cmd msg

subscriptions : Model -> Sub Msg
subscriptions _ =
  Sub.none

main =
  Browser.element
    { init = init
    , view = view
    , update = update
    , subscriptions = subscriptions
    }

--
-- update loop --
--
update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
  case msg of
    Ignore ->
      (model, Cmd.none)

    Copy value ->
      (model, copy value)

    CallApi apiCall ->
      ( model , apiCall model)

    UpdateNewProperty newProperty ->
      ({model | newProperty = newProperty}, Cmd.none)

    UpdateProperty key newProperty ->
      let
        ui = model.ui
        newProperties = Dict.update key (always (Just newProperty)) ui.editedProperties
      in
        ( { model | ui = {ui | editedProperties = newProperties} }, Cmd.none )

    SaveProperty res ->
      case  res of
        Ok p ->
          let
            ui  = model.ui
            newModel = { model
              | newProperty = (EditProperty "" "" model.newProperty.format True True)
              , ui = { ui | loading = False }
              }
          in
            ( newModel
            , Cmd.batch [ initTooltips "" , successNotification "", getNodeProperties newModel]
            )
        Err err ->
          processApiError "saving node properties" err model

    GetNodeProperties res ->
      case  res of
        Ok properties ->
          let
            modelUi  = model.ui
          in
            ( { model | properties = properties, ui = { modelUi | loading = False } }
              , initTooltips ""
            )
        Err err ->
          processApiError "Getting node properties" err model


    AddProperty ->
      let
        cmd = case model.newProperty.format of
          StringFormat -> saveProperty [model.newProperty] model
          JsonFormat   ->
            let
              --checkJsonFormat = (Json.Decode.dict (Json.Decode.lazy (\_ -> decodePropertyValue)) |> Json.Decode.map JsonObject) encodedValue
              checkJsonFormat = decodeValue decodePropertyValue (string model.newProperty.value) -- NOT WORKING
            in
              case checkJsonFormat of
                Ok s  ->
                  Debug.log (Debug.toString checkJsonFormat)
                  saveProperty [model.newProperty] model
                Err _ -> errorNotification "JSON check is enabled, but the value format is invalid."
      in
        (model, cmd)

    DeleteProperty key ->
          (model, Cmd.none)

    ToggleEditPopup modalState ->
      let
        ui = model.ui
      in
        ( { model | ui = {ui | modalState = modalState} }, Cmd.none )

    ToggleEditProperty key property save ->
      if Dict.member key model.ui.editedProperties then -- If the property is being edited
        let
          ui = model.ui
          editedProperties = ui.editedProperties
            |> Dict.remove key
          cmd = if save then -- If we want to save changes
            saveProperty [property] model
            else
            Cmd.none
          in
            ( { model | ui = {ui | editedProperties = editedProperties} }, cmd )
      else
        let
          ui = model.ui
          editedProperties = ui.editedProperties
            |> Dict.insert key property
        in
          ( { model | ui = {ui | editedProperties = editedProperties} }, Cmd.none )

    UpdateTableFilters tableFilters ->
      let
        ui = model.ui
      in
        ({model | ui = { ui | filters = tableFilters}}, Cmd.none)

    ShowMore id ->
      let
        ui = model.ui
        showMore = if List.member id ui.showMore then List.Extra.remove id ui.showMore else id :: ui.showMore
      in
        ({model | ui = { ui | showMore = showMore}}, Cmd.none)

    ClosePopup callback ->
      let
        ui = model.ui
        (nm,cmd) = update callback { model | ui = { ui | modalState = NoModal } }
      in
        (nm , cmd)

processApiError : String -> Error -> Model -> ( Model, Cmd Msg )
processApiError apiName err model =
  let
    message =
      case err of
        Http.BadUrl url ->
            "The URL " ++ url ++ " was invalid"
        Http.Timeout ->
            "Unable to reach the server, try again"
        Http.NetworkError ->
            "Unable to reach the server, check your network connection"
        Http.BadStatus 500 ->
            "The server had a problem, try again later"
        Http.BadStatus 400 ->
            "Verify your information and try again"
        Http.BadStatus _ ->
            "Unknown error"
        Http.BadBody errorMessage ->
            errorMessage
  in
    (model, errorNotification ("Error when " ++ apiName ++ ", details: \n" ++ message ) )