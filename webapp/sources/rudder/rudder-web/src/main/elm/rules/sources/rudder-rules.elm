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

    GetRulesComplianceResult res ->
      case res of
        Ok r ->
          ( { model | rulesCompliance  = r } , Cmd.none )
        Err err ->
          (model, Cmd.none)

    SelectDirective directiveId ->
      let
        selectedRule = model.selectedRule
        newModel    = case selectedRule of
          Nothing -> model
          Just r  ->
            let
              isSelected = List.member directiveId r.directives
              listDirectives =
                if isSelected == True then
                  remove directiveId r.directives
                else
                  directiveId :: r.directives
              newSelectedRule = {r | directives = listDirectives}
            in
              {model | selectedRule = Just newSelectedRule}
      in
        ( newModel , Cmd.none )

    SelectGroup groupId includeBool->
      let
        selectedRule = model.selectedRule
        newModel     = case selectedRule of
          Nothing -> model
          Just r  ->
            let
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
              {model | selectedRule = Just newSelectedRule}
      in
        ( newModel , Cmd.none )

    UpdateRuleName name ->
      let
        selectedRule = model.selectedRule
        newModel    = case selectedRule of
          Nothing -> model
          Just r  ->
            let
              newSelectedRule = {r | name = name}
            in
              {model | selectedRule = Just newSelectedRule}
      in
        ( newModel , Cmd.none )

    UpdateRuleCategory category ->
      let
        selectedRule = model.selectedRule
        newModel    = case selectedRule of
          Nothing -> model
          Just r  ->
            let
              newSelectedRule = {r | categoryId = category}
            in
              {model | selectedRule = Just newSelectedRule}
      in
        ( newModel , Cmd.none )

    UpdateRuleShortDesc desc ->
      let
        selectedRule = model.selectedRule
        newModel    = case selectedRule of
          Nothing -> model
          Just r  ->
            let
              newSelectedRule = {r | shortDescription = desc}
            in
              {model | selectedRule = Just newSelectedRule}
      in
        ( newModel , Cmd.none )

    UpdateRuleLongDesc desc ->
      let
        selectedRule = model.selectedRule
        newModel    = case selectedRule of
          Nothing -> model
          Just r  ->
            let
              newSelectedRule = {r | longDescription = desc}
            in
              {model | selectedRule = Just newSelectedRule}
      in
        ( newModel , Cmd.none )

    UpdateTagKey val ->
      let
        selectedRule = model.selectedRule
        --ruleUI       = model.ruleUI
        --tag          = ruleUI.newTag
        newModel     = case selectedRule of
          Nothing -> model
          Just r  ->
            --let
              --newTag    = {tag    | key    = val   }
              --newRuleUI = {ruleUI | newTag = newTag}
            --in
              model
      in
        ( newModel , Cmd.none )

    UpdateTagVal val ->
      let
        selectedRule = model.selectedRule
        --ruleUI       = model.ruleUI
        --tag          = ruleUI.newTag
        newModel     = case selectedRule of
          Nothing -> model
          Just r  ->
          --  let
          --    newTag    = {tag    | value  = val   }
          --    newRuleUI = {ruleUI | newTag = newTag}
          --  in
            model
              --{model | ruleUI = newRuleUI}
      in
        ( newModel , Cmd.none )

    AddTag ->
      let
        selectedRule = model.selectedRule
        --ruleUI       = model.ruleUI
        --tag          = ruleUI.newTag
        newModel     = case selectedRule of
          Nothing -> model
          Just r  ->
          {-  let
              newRule = {r | tags = tag :: r.tags}
              newTag      = {tag    | key  = "", value = ""}
              newRuleUI   = {ruleUI | newTag = newTag}
            in
              {model | ruleUI = newRuleUI, selectedRule = Just newRule}-}
              model
      in
        ( newModel , Cmd.none )


    SaveRuleDetails (Ok ruleDetails) ->
      let
        newSelectedRule = Just ruleDetails
        newModel        = {model | selectedRule = newSelectedRule}
      in
      -- TODO // Update Rules Tree
      -- TODO // Display success notifications
        ( model , Cmd.none)
    SaveRuleDetails (Err err) ->
      processApiError err model

processApiError : Error -> Model -> ( Model, Cmd Msg )
processApiError err model =
  (model, Cmd.none)
