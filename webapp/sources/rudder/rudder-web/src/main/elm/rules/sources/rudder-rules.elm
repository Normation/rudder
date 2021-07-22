module RulesManagement exposing (update)

import Browser
import DataTypes exposing (..)
import Http exposing (Error)
import Init exposing (init, subscriptions)
import View exposing (view)
import Result
import ApiCalls exposing (getRuleDetails, getRulesTree)
import List.Extra exposing (remove)

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
      case  res of
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

    GetTechniquesTreeResult res ->
      case res of
        Ok t ->
          ( { model | techniquesTree = t }
            , Cmd.none
          )
        Err err ->
          processApiError err model

    ChangeTabFocus newTab ->
      case model.mode of
        EditRule details ->
          ({model | mode = EditRule {details | tab = newTab}}, Cmd.none)
        _   -> (model, Cmd.none)

    EditDirectives flag ->
      case model.mode of
        EditRule details ->
          ({model | mode = EditRule {details | editDirectives = flag}}, Cmd.none)
        _   -> (model, Cmd.none)

    EditGroups flag ->
      case model.mode of
        EditRule details ->
          ({model | mode = EditRule {details | editGroups = flag}}, Cmd.none)
        _   -> (model, Cmd.none)

    GetRuleDetailsResult res ->
      case res of
        Ok r ->
          ({model | mode = EditRule (EditRuleDetails r Information False False (Tag "" ""))}, Cmd.none)
        Err err ->
          (model, Cmd.none)

    OpenRuleDetails rId ->
      (model, (getRuleDetails model rId))

    CloseRuleDetails ->
      ( { model | mode  = RuleTable } , Cmd.none )

    GetRulesComplianceResult res ->
      case res of
        Ok r ->
          ( { model | rulesCompliance  = r } , Cmd.none )
        Err err ->
          (model, Cmd.none)

    SelectGroup groupId includeBool->
      case model.mode of
        EditRule details ->
          let
            r = details.rule
            (include, exclude) = case r.targets of
                [Composition (Or i) (Or e)] -> (i,e)
                targets -> (targets,[])
            isIncluded = List.member groupId include
            isExcluded = List.member groupId exclude
            (newInclude, newExclude)  = case (includeBool, isIncluded, isExcluded) of
                (True, True, _)  -> (remove groupId include,exclude)
                (True, _, True)  -> (groupId :: include, remove groupId exclude)
                (False, True, _) -> (remove groupId include, groupId :: exclude)
                (False, _, True) -> (include,  remove groupId exclude)
                (True, False, False)  -> ( groupId :: include, exclude)
                (False, False, False) -> (include, groupId :: exclude)

            newSelectedRule = {r | targets = [Composition (Or newInclude) (Or newExclude)]}
          in
            ({model | mode = EditRule {details | rule = newSelectedRule}}, Cmd.none)
        _   -> (model, Cmd.none)

    UpdateRule rule ->
      case model.mode of
        EditRule details ->
          ({model | mode = EditRule {details | rule = rule}}, Cmd.none)
        _   -> (model, Cmd.none)
    UpdateNewTag tag ->
      case model.mode of
        EditRule details ->
          ({model | mode = EditRule {details | newTag = tag}}, Cmd.none)
        _   -> (model, Cmd.none)
    SaveRuleDetails (Ok ruleDetails) ->
      case model.mode of
        EditRule details ->

      -- TODO // Update Rules Tree
      -- TODO // Display success notifications
          ({model | mode = EditRule {details | rule = ruleDetails}}, Cmd.none)
        _   -> (model, Cmd.none)
    SaveRuleDetails (Err err) ->
      processApiError err model

processApiError : Error -> Model -> ( Model, Cmd Msg )
processApiError err model =
  (model, Cmd.none)
