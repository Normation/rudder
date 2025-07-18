port module Nodecompliance exposing (..)

import Browser
import Browser.Navigation as Nav
import Dict
import Http exposing (..)

import NodeCompliance.ApiCalls exposing (..)
import NodeCompliance.DataTypes exposing (..)
import NodeCompliance.Init exposing (init)
import NodeCompliance.View exposing (view)

import Ui.Datatable exposing (SortOrder(..))


-- PORTS / SUBSCRIPTIONS
port errorNotification   : String -> Cmd msg
port initTooltips        : String -> Cmd msg
port loadCompliance      : (String -> msg) -> Sub msg


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

    CallApi apiCall ->
      ( model , apiCall model)

    Ignore ->
      ( model , Cmd.none)

    Refresh ->
      let
        ui       = model.ui
        newUi    = { ui | loading = True }
        newModel = { model | ui = newUi }
      in
        (newModel, getNodeCompliance newModel)

    UpdateFilters newFilters ->
      let
        ui = model.ui
        newUi = { ui | tableFilters = newFilters}
      in
        ({model | ui = newUi}, initTooltips "")

    UpdateComplianceFilters newFilters ->
      let
        ui = model.ui
        newUi = { ui | complianceFilters = newFilters }
      in
        ({model | ui = newUi}, initTooltips "")

    GoTo link -> (model, Nav.load link)

    ToggleRow rowId defaultSortId ->
      let
        ui = model.ui
        filters = ui.tableFilters
        newFilters =
          { filters | openedRows = if Dict.member rowId filters.openedRows then
            Dict.remove rowId filters.openedRows
          else
            Dict.insert rowId (defaultSortId, Asc) filters.openedRows
          }
        newUi = { ui | tableFilters = newFilters}
        newModel = { model | ui = newUi }
      in
        (newModel, initTooltips "")

    ToggleRowSort rowId sortId order ->
      let
        ui = model.ui
        tableFilters = ui.tableFilters
        newFilters   = { tableFilters | openedRows = Dict.update rowId (always (Just (sortId,order))) tableFilters.openedRows }
        newUi = { ui | tableFilters = newFilters}
        newModel = { model | ui = newUi }
      in
        (newModel, initTooltips "")

    GetPolicyModeResult res ->
      case res of
        Ok p ->
            ( { model | policyMode = p }
              , Cmd.none
            )
        Err err ->
          processApiError "Getting Policy Mode" err model

    GetNodeComplianceResult res ->
      let
        ui = model.ui
        newModel = {model | ui = {ui | loading = False}}
      in
        case res of
          Ok compliance ->
            ( { newModel | nodeCompliance = Just compliance }
              , initTooltips ""
            )
          Err err ->
            processApiError "Getting node compliance" err newModel

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
    (model, errorNotification ("Error when "++apiName ++", details: \n" ++ message ) )