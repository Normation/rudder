port module Groupcompliance exposing (..)

import Browser
import Browser.Navigation as Nav
import Dict
import Http exposing (..)

import GroupCompliance.ApiCalls exposing (..)
import GroupCompliance.DataTypes exposing (..)
import GroupCompliance.Init exposing (init)
import GroupCompliance.View exposing (view)


-- PORTS / SUBSCRIPTIONS
port errorNotification   : String -> Cmd msg
port initTooltips        : String -> Cmd msg
port loadCompliance      : (() -> msg) -> Sub msg


subscriptions : Model -> Sub Msg
subscriptions _ =
  loadCompliance (\_ -> LoadCompliance GlobalCompliance) -- default to global compliance

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

    UpdateFilters newFilters ->
      let
        ui = model.ui
        newUi = case ui.viewMode of
          RulesView -> { ui | ruleFilters = newFilters}
          NodesView -> { ui | nodeFilters = newFilters}
      in
        ({model | ui = newUi}, initTooltips "")

    UpdateComplianceFilters newFilters ->
      let
        ui = model.ui
        newUi = { ui | complianceFilters = newFilters }
      in
        ({model | ui = newUi}, initTooltips "")

    ChangeViewMode mode ->
      let
        ui = model.ui
      in
        ({model | ui = { ui | viewMode = mode}}, initTooltips "")

    GoTo link -> (model, Nav.load link)

    ToggleRow rowId defaultSortId ->
      let
        ui = model.ui
        filters = case ui.viewMode of
          RulesView -> ui.ruleFilters
          NodesView -> ui.nodeFilters
        newFilters =
          { filters | openedRows = if Dict.member rowId filters.openedRows then
            Dict.remove rowId filters.openedRows
          else
            Dict.insert rowId (defaultSortId, Asc) filters.openedRows
          }
        newUi = case ui.viewMode of
          RulesView -> { ui | ruleFilters = newFilters}
          NodesView -> { ui | nodeFilters = newFilters}
        newModel = { model | ui = newUi }
      in
        (newModel, Cmd.none)

    ToggleRowSort rowId sortId order ->
      let
        ui = model.ui
        tableFilters = case ui.viewMode of
          RulesView -> ui.ruleFilters
          NodesView -> ui.nodeFilters
        newFilters   = { tableFilters | openedRows = Dict.update rowId (always (Just (sortId,order))) tableFilters.openedRows }
        newUi = case ui.viewMode of
          RulesView -> { ui | ruleFilters = newFilters}
          NodesView -> { ui | nodeFilters = newFilters}
        newModel = { model | ui = newUi }
      in
        (newModel, Cmd.none)

    GetPolicyModeResult res ->
      case res of
        Ok p ->
            ( { model | policyMode = p }
              , Cmd.none
            )
        Err err ->
          processApiError "Getting Policy Mode" err model

    GetGroupComplianceResult res ->
      let
        ui = model.ui
        newModel = {model | ui = {ui | loading = False}}
      in
        case res of
          Ok compliance ->
            ( { newModel | groupCompliance = Just compliance }
              , Cmd.none
            )
          Err err ->
            processApiError "Getting group compliance" err newModel

    --TODO later
    --Export res ->
    --  case res of
    --    Ok content ->
    --      (model, File.Download.string (model.directiveId.value ++ ".csv") "text/csv" content)
    --    Err err ->
    --      processApiError "Export directive compliance" err model

    LoadCompliance complianceScope ->
      let
        ui = model.ui
        shouldReload = not ui.loaded || complianceScope /= model.complianceScope
        getCompliance = case complianceScope of
          GlobalCompliance -> getGlobalGroupCompliance
          TargetedCompliance -> getTargetedGroupCompliance
        actions =
          if shouldReload then 
            Cmd.batch [ getCompliance model ]
          else
            Cmd.none
        newModel = {model | complianceScope = complianceScope, ui = {ui | loading = shouldReload, loaded = True}}
      in
      ( newModel
      , actions
      )
processApiError : String -> Error -> Model -> ( Model, Cmd Msg )
processApiError apiName err model =
  let
    modelUi = model.ui
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

getUrl : Model -> String
getUrl model = model.contextPath ++ "/secure/nodeManager/groups"