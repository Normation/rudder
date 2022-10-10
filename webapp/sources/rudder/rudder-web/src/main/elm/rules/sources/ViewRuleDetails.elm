module ViewRuleDetails exposing (..)

import DataTypes exposing (..)
import Html exposing (Html, button, div, i, span, text, h1, ul, li,  a, p)
import Html.Attributes exposing (id, class, type_,  style, attribute, disabled, title)
import Html.Events exposing (onClick)
import List.Extra
import List exposing (filter, length, member)
import String
import ApiCalls exposing (..)
import ViewTabContent exposing (tabContent)
import Maybe.Extra
import ViewUtils exposing (badgePolicyMode, countRecentChanges, getRuleNbGroups, getRuleNbNodes, getNbResourcesBadge, getGroupsNbResourcesBadge)

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
            [ button [ class "btn btn-default dropdown-toggle" , attribute "data-toggle" "dropdown" ]
              [ text "Actions "
              , i [ class "caret" ] []
              ]
            , ul [ class "dropdown-menu" ]
              [ li []
                [ a [ onClick (GenerateId (\r -> CloneRule or (RuleId r)))]
                  [ i [ class "fa fa-clone"] []
                  , text "Clone"
                  ]
                ]
              , li []
                [ a [ onClick (OpenDeactivationPopup rule)]
                  [ i [ class "fa fa-ban"] []
                  , text txtDisabled
                  ]
                ]
              , li [class "divider"][]
              , li []
                [ a [ class ("action-danger"), onClick (OpenDeletionPopup rule)]
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

    getTargetExludedIds : List RuleTarget -> List String
    getTargetExludedIds listTargets =
      List.concatMap (\target -> case target of
         NodeGroupId groupId -> [groupId]
         Composition _ e     -> getTargetExludedIds[ e ]
         Special spe         -> [spe]
         Node node           -> [node]
         Or t                -> getTargetExludedIds t
         And t               -> getTargetExludedIds t
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

    (originRuleGroupsExcld, originRuleGroupsIncld) = (getTargetExludedIds (Maybe.Extra.unwrap [] .targets originRule), getTargetIncludedIds (Maybe.Extra.unwrap [] .targets originRule))
    (diffGroupsPos, diffGroupsNeg) = getDiffGroups originRuleGroupsExcld originRuleGroupsIncld (getTargetExludedIds rule.targets ) ( getTargetIncludedIds rule.targets)

    nbDirectives = case originRule of
      Just oR -> Maybe.withDefault 0 details.numberOfDirectives
      Nothing -> 0

    saveAction =
      if String.isEmpty (String.trim rule.name) then
        Ignore
      else
        (CallApi (saveRuleDetails rule (Maybe.Extra.isNothing details.originRule)))
  in
    div [class "main-container"]
    [ div [class "main-header "]
      [ div [class "header-title"]
        [ h1[class classDisabled]
          [ badgePolicy
          , ruleTitle
          , badgeDisabled
          ]
        , div[class "header-buttons"]
          ( button [class "btn btn-default", type_ "button", onClick CloseDetails][text "Close", i [ class "fa fa-times"][]]
          :: (
            if model.ui.hasWriteRights then
              [ div [ class "btn-group" ]  topButtons
              , button [class "btn btn-success", type_ "button", disabled (String.isEmpty (String.trim rule.name)), onClick saveAction][text "Save", i [ class "fa fa-download"] []]
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
      [ ul[class "ui-tabs-nav "]
        [ li[class ("ui-tabs-tab" ++ (if details.tab == Information   then " ui-tabs-active" else ""))]
          [ a[onClick (UpdateRuleForm {details | tab = Information })]
            [ text "Information" ]
          ]
        , li[class ("ui-tabs-tab" ++ (if details.tab == Directives    then " ui-tabs-active" else ""))]
          [ a[onClick (UpdateRuleForm {details | tab = Directives})]
            [ text "Directives"
            , span[class "badge badge-secondary badge-resources tooltip-bs"]
              [ getNbResourcesBadge nbDirectives "This rule does not apply any directive"
              , ( if diffDirectivesPos /= 0 then span [class "nb-resources new"] [ text (String.fromInt diffDirectivesPos)] else text "")
              , ( if diffDirectivesNeg /= 0 then span [class "nb-resources del"] [ text (String.fromInt diffDirectivesNeg)] else text "")
              ]
            ]
          ]
        , ( if isNewRule then
          text ""
        else
          li[class ("ui-tabs-tab" ++ (if details.tab == Nodes        then " ui-tabs-active" else ""))]
          [ a[onClick (UpdateRuleForm {details | tab = Nodes })]
            [ text "Nodes"
            , span[class "badge badge-secondary badge-resources tooltip-bs"]
              [ getNbResourcesBadge (Maybe.withDefault 0 (getRuleNbNodes details)) "This rule is not applied on any node"
              ]
            ]
          ]
        )
        , li[class ("ui-tabs-tab" ++ (if details.tab == Groups       then " ui-tabs-active" else ""))]
          [ a[onClick (UpdateRuleForm {details | tab = Groups })]
            [ text "Groups"
            , span[class "badge badge-secondary badge-resources tooltip-bs"]
              [ getGroupsNbResourcesBadge (getRuleNbGroups originRule) (List.length (getTargetIncludedIds (Maybe.Extra.unwrap [] .targets originRule))) "This rule is not applied on any group"
              , ( if diffGroupsPos /= 0 then span [class "nb-resources new"] [ text (String.fromInt diffGroupsPos)] else text "")
              , ( if diffGroupsNeg /= 0 then span [class "nb-resources del"] [ text (String.fromInt diffGroupsNeg)] else text "")
              ]
            ]
          ]
        , ( if isNewRule then
          text ""
        else
          li[class ("ui-tabs-tab" ++ (if details.tab == TechnicalLogs       then " ui-tabs-active" else ""))]
          [ a[onClick (UpdateRuleForm {details | tab = TechnicalLogs })]
            [ text "Recent Changes"
            , span[class "badge badge-secondary badge-resources tooltip-bs"]
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