module ViewRuleDetails exposing (..)

import DataTypes exposing (..)
import Html exposing (Html, button, div, i, span, text, h1, ul, li,  a, p)
import Html.Attributes exposing (id, class, type_,  style, attribute)
import Html.Events exposing (onClick)
import List.Extra
import List
import String
import ApiCalls exposing (..)
import ViewTabContent exposing (tabContent)
import Maybe.Extra
import ViewUtils exposing (badgePolicyMode, getRuleNbNodes, getRuleNbGroups)

--
-- This file contains all methods to display the details of the selected rule.
--


editionTemplate : Model -> RuleDetails -> Html Msg
editionTemplate model details =
  let
    originRule = details.originRule

    rule = details.rule
    ruleTitle = case originRule of
      Nothing -> span[style "opacity" "0.4"][text "New rule"]
      Just r -> text r.name
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
                [ a [ class ("action-success"), onClick (GenerateId (\r -> CloneRule or (RuleId r)))]
                  [ i [ class "fa fa-clone"] []
                  , text "Clone"
                  ]
                ]
              , li []
                [ a [ class ("action-primary"), onClick (OpenDeactivationPopup rule)]
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

    nbDirectives = case originRule of
      Just oR -> String.fromInt (List.length oR.directives)
      Nothing -> "0"

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
              , button [class "btn btn-success", type_ "button", onClick (CallApi (saveRuleDetails rule (Maybe.Extra.isNothing details.originRule)))][text "Save", i [ class "fa fa-download"] []]
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
              [ span [class "nb-resources"] [ text nbDirectives]
              , ( if diffDirectivesPos /= 0 then span [class "nb-resources new"] [ text (String.fromInt diffDirectivesPos)] else text "")
              , ( if diffDirectivesNeg /= 0 then span [class "nb-resources del"] [ text (String.fromInt diffDirectivesNeg)] else text "")
              ]
            ]
          ]
        , li[class ("ui-tabs-tab" ++ (if details.tab == Nodes        then " ui-tabs-active" else ""))]
          [ a[onClick (UpdateRuleForm {details | tab = Nodes })]
            [ text "Nodes"
            , span[class "badge badge-secondary badge-resources tooltip-bs"]
              [ span [class "nb-resources"] [ text (String.fromInt(getRuleNbNodes details))]
              ]
            ]
          ]
        , li[class ("ui-tabs-tab" ++ (if details.tab == Groups       then " ui-tabs-active" else ""))]
          [ a[onClick (UpdateRuleForm {details | tab = Groups })]
            [ text "Groups"
            , span[class "badge badge-secondary badge-resources tooltip-bs"]
              [ span [class "nb-resources"] [ text (String.fromInt(getRuleNbGroups originRule))]
              ]
            ]
          ]
        , li[class ("ui-tabs-tab" ++ (if details.tab == TechnicalLogs       then " ui-tabs-active" else ""))]
          [ a[onClick (UpdateRuleForm {details | tab = TechnicalLogs })]
            [ text "Recent Changes" ]
          ]
        ]
      ]
    , div [class "main-details"]
      [ tabContent model details ]
    ]