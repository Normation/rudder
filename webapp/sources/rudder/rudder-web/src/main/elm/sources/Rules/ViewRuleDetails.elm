module Rules.ViewRuleDetails exposing (..)

import Html exposing (Html, button, div, i, span, text, h1, ul, li,  a, p)
import Html.Attributes exposing (id, class, type_,  style, attribute, disabled, title)
import Html.Events exposing (onClick)
import List.Extra
import List exposing (filter, length, member)
import String
import Maybe.Extra

import Rules.ApiCalls exposing (..)
import Rules.DataTypes exposing (..)
import Rules.ViewTabContent exposing (tabContent)
import Rules.ViewUtils exposing (badgePolicyMode, countRecentChanges, getRuleNbGroups, getRuleNbNodes, getNbResourcesBadge, getGroupsNbResourcesBadge, btnSave)


--
-- This file contains all methods to display the details of the selected rule.
--


editionTemplate : Model -> RuleDetails -> Html Msg
editionTemplate model details =
  let
    originRule = details.originRule

    rule = details.rule
    (ruleTitle, isNewRule) = case originRule of
      Nothing -> (span[style "opacity" "0.4"][text "New rule"], True)
      Just r  -> (text r.name, False)
    policyModeTitle =
      if isNewRule then
        text ""
      else
        badgePolicyMode model.policyMode rule.policyMode
    (classDisabled, badgeDisabled) = if (Maybe.withDefault True (Maybe.map .enabled originRule)) then
        ("", text "")
      else
        ("item-disabled", span[ class "badge-disabled"][])

    (topButtons, badgePolicy) =
      case originRule of
        Just or ->
          let
            txtDisabled = if rule.enabled then "Disable" else "Enable"
          in
            (
            [ button [ class "btn btn-default dropdown-toggle" , attribute "data-bs-toggle" "dropdown" ]
              [ text "Actions "
              , i [ class "caret" ] []
              ]
            , ul [ class "dropdown-menu" ]
              [ li []
                [ a [ class "dropdown-item", onClick (GenerateId (\r -> CloneRule or (RuleId r)))]
                  [ i [ class "fa fa-clone"] []
                  , text "Clone"
                  ]
                ]
              , li []
                [ a [ class "dropdown-item", onClick (OpenDeactivationPopup rule)]
                  [ i [ class "fa fa-ban"] []
                  , text txtDisabled
                  ]
                ]
              , li []
                [ a [ class "dropdown-item action-danger", onClick (OpenDeletionPopup rule)]
                  [ i [ class "fa fa-times-circle"] []
                  , text "Delete"
                  ]
                ]
              ]
            ], badgePolicyMode model.policyMode or.policyMode
            )
        Nothing -> ([ text "" ], text "")

    isNotMember : List a -> a -> Bool
    isNotMember listIds id =
      List.Extra.notMember id listIds

    getDiffList : List a -> List a -> (Int, Int)
    getDiffList listA listB =
      let
        originLength   = List.length listA
        selectedLength = List.length listB
      in
        if selectedLength == originLength then
          let
            diff = List.length (List.Extra.findIndices (isNotMember listB) listA)
          in
            (diff, diff)
        else if selectedLength > originLength then
          let
            diff = List.length (List.Extra.findIndices (isNotMember listB) listA)
            lengthDiff = selectedLength - originLength
          in
            (lengthDiff + diff, diff)
        else
          let
            diff = List.length (List.Extra.findIndices (isNotMember listA) listB)
            lengthDiff = originLength - selectedLength
          in
            (diff, lengthDiff + diff)


    (diffDirectivesPos, diffDirectivesNeg) = getDiffList (Maybe.Extra.unwrap [] .directives originRule) rule.directives

    getTargetExcludedIds : List RuleTarget -> List String
    getTargetExcludedIds listTargets =
      List.concatMap (\target -> case target of
         NodeGroupId groupId -> [groupId]
         Composition _ e     -> getTargetExcludedIds[ e ]
         Special spe         -> [spe]
         Node node           -> [node]
         Or t                -> getTargetExcludedIds t
         And t               -> getTargetExcludedIds t
      ) listTargets

    getTargetIncludedIds : List RuleTarget -> List String
    getTargetIncludedIds listTargets =
      List.concatMap (\target -> case target of
         NodeGroupId groupId -> [groupId]
         Composition i _     -> getTargetIncludedIds[ i ]
         Special spe         -> [spe]
         Node node           -> [node]
         Or t                -> getTargetIncludedIds t
         And t               -> getTargetIncludedIds t
      ) listTargets
    getDiffGroups : List String -> List String -> List String -> List String -> (Int, Int)
    getDiffGroups origineExcludedGroup origineIncludedGroup targetExcludedGroup targetIncludedGroup =
      let
        (diffPos, diffNeg) =  getDiffList (origineExcludedGroup ++ origineIncludedGroup) (targetExcludedGroup ++ targetIncludedGroup)
        -- here we search for group who changed from excluded or included (vice versa) to count it
        -- for example if there is 2 swaps, we consider that there is two additions and two deletions (+2 / -2)
        fromExldToIncld =
          origineExcludedGroup
            |> filter(\g -> member g targetIncludedGroup)
            |> length
        fromIncldToExcld =
          origineIncludedGroup
            |> filter(\g -> member g targetExcludedGroup)
            |> length
        totalSwap = (fromIncldToExcld + fromExldToIncld)
      in
      (diffPos + totalSwap, diffNeg + totalSwap)

    (originRuleGroupsExcld, originRuleGroupsIncld) = (getTargetExcludedIds (Maybe.Extra.unwrap [] .targets originRule), getTargetIncludedIds (Maybe.Extra.unwrap [] .targets originRule))
    (diffGroupsPos, diffGroupsNeg) = getDiffGroups originRuleGroupsExcld originRuleGroupsIncld (getTargetExcludedIds rule.targets ) ( getTargetIncludedIds rule.targets)

    nbDirectives = case originRule of
      Just oR -> Maybe.withDefault 0 details.numberOfDirectives
      Nothing -> 0

    (saveAction, enabledCR) =
      let
        defaultAction = checkAction (CallApi True (saveRuleDetails rule (Maybe.Extra.isNothing details.originRule)))
        checkAction action =
          if String.isEmpty (String.trim rule.name) then
            Ignore
          else
            action
      in
        case model.ui.crSettings of
          Just cr ->
            if cr.enableChangeMessage || cr.enableChangeRequest then
              ( checkAction (OpenSaveAuditMsgPopup rule cr)
              , cr.enableChangeRequest
              )
            else
              ( defaultAction
              , cr.enableChangeRequest
              )
          Nothing ->
            ( defaultAction
            , False
            )

  in
    div [class "main-container"]
    [ div [class "main-header "]
      [ div [class "header-title"]
        [ h1[class classDisabled]
          [ policyModeTitle
          , ruleTitle
          , badgeDisabled
          ]
        , div[class "header-buttons"]
          ( button [class "btn btn-default", type_ "button", onClick CloseDetails][text "Close", i [ class "fa fa-times"][]]
          :: (
            if model.ui.hasWriteRights then
              [ div [ class "btn-group" ]  topButtons
              , btnSave model.ui.saving (String.isEmpty (String.trim rule.name)) saveAction enabledCR
              ]
            else
              []
            )
          )
        ]
      , div [class "header-description"]
        [ p[][text (Maybe.Extra.unwrap "" .shortDescription originRule)] ]
      ]
    , div [class "main-navbar" ]
      [ ul[class "nav nav-underline"]
        [ li[class "nav-item"]
          [ button
            [ attribute "role" "tab", type_ "button", class ("nav-link" ++ (if details.tab == Information then " active" else "")), onClick (UpdateRuleForm {details | tab = Information})]
            [ text "Information" ]
          ]
        , li[class "nav-item"]
          [ button
            [ attribute "role" "tab", type_ "button", class ("nav-link" ++ (if details.tab == Directives then " active" else "")), onClick (UpdateRuleForm {details | tab = Directives})]
            [ text "Directives"
            , span[class "badge badge-secondary badge-resources"]
              [ getNbResourcesBadge nbDirectives "This rule does not apply any directive"
              , ( if diffDirectivesPos /= 0 then span [class "nb-resources new"] [ text (String.fromInt diffDirectivesPos)] else text "")
              , ( if diffDirectivesNeg /= 0 then span [class "nb-resources del"] [ text (String.fromInt diffDirectivesNeg)] else text "")
              ]
            ]
          ]
        , ( if isNewRule then
          text ""
        else
          li[class "nav-item"]
          [ button
            [ attribute "role" "tab", type_ "button", class ("nav-link" ++ (if details.tab == Nodes then " active" else "")), onClick (UpdateRuleForm {details | tab = Nodes})]
            [ text "Nodes"
            , span[class "badge badge-secondary badge-resources"]
              [ getNbResourcesBadge (Maybe.withDefault 0 (getRuleNbNodes details)) "This rule is not applied on any node"
              ]
            ]
          ]
        )
        , li[class "nav-item"]
          [ button
            [ attribute "role" "tab", type_ "button", class ("nav-link" ++ (if details.tab == Groups then " active" else "")), onClick (UpdateRuleForm {details | tab = Groups})]
            [ text "Groups"
            , span[class "badge badge-secondary badge-resources"]
              [ getGroupsNbResourcesBadge (getRuleNbGroups originRule) (List.length (getTargetIncludedIds (Maybe.Extra.unwrap [] .targets originRule))) "This rule is not applied on any group"
              , ( if diffGroupsPos /= 0 then span [class "nb-resources new"] [ text (String.fromInt diffGroupsPos)] else text "")
              , ( if diffGroupsNeg /= 0 then span [class "nb-resources del"] [ text (String.fromInt diffGroupsNeg)] else text "")
              ]
            ]
          ]
        , ( if isNewRule then
          text ""
        else
          li[class "nav-item"]
          [ button
            [ attribute "role" "tab", type_ "button", class ("nav-link" ++ (if details.tab == TechnicalLogs then " active" else "")), onClick (UpdateRuleForm {details | tab = TechnicalLogs})]
            [ text "Recent changes"
            , span[class "badge badge-secondary badge-resources"]
              [ span [class "nb-resources"] [text (String.fromFloat (countRecentChanges details.rule.id model.changes))]
              ]
            ]
          ]
        )
        ]
      ]
    , div [class "main-details"]
      [ tabContent model details ]
    ]
