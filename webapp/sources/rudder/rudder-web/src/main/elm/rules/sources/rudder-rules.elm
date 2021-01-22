module RulesManagement exposing (update)

import Browser
import DataTypes exposing (..)
import Http exposing (Error)
import Init exposing (init, subscriptions)
import View exposing (view)
import Result
import ApiCalls exposing (getRuleDetails)

main =
  Browser.element
    { init = init
    , view = view
    , update = update
    , subscriptions = subscriptions
    }

update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
  case msg of
    CallApi call ->
      (model, call model)

    GetRulesResult res ->
      case res of
        Ok r ->
            ( { model |
                  rulesTree = r
              }
              , Cmd.none
             )
        Err err ->
          processApiError err model

    GetTechniquesResult res ->
      case res of
        Ok t ->
            ( { model | techniques  = t }
              , Cmd.none
            )
        Err err ->
          processApiError err model

    GetDirectivesResult res ->
      case res of
        Ok d ->
            ( { model | directives = d }
              , Cmd.none
            )
        Err err ->
          processApiError err model

    GetPolicyModeResult res ->
      case res of
        Ok p ->
            ( { model | policyMode = p }
              , Cmd.none
            )
        Err err ->
          processApiError err model

    GetGroupsTreeResult res ->
      case res of
        Ok t ->
          ( { model | groupsTree = t }
            , Cmd.none
          )
        Err err ->
          processApiError err model

    ChangeTabFocus newTab ->
      if model.tab == newTab then
        (model, Cmd.none)
      else
        ({model | tab = newTab}, Cmd.none)

    EditDirectives mode ->
      ({model | editDirectives = mode}, Cmd.none)

    EditGroups mode ->
      ({model | editGroups = mode}, Cmd.none)

    GetRuleDetailsResult res ->
      case res of
        Ok r ->
          ( { model |
                selectedRule  = Just r
            }
            , Cmd.none
           )
        Err err ->
          (model, Cmd.none)

    OpenRuleDetails rId ->
        (model, (getRuleDetails model rId))

    CloseRuleDetails ->
        ( { model | selectedRule  = Nothing } , Cmd.none )

processApiError : Error -> Model -> ( Model, Cmd Msg )
processApiError err model =
  (model, Cmd.none)
