module Rules.ViewTabInformation exposing (..)

import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onClick, onInput)
import List.Extra
import List
import Maybe.Extra

import Rules.DataTypes exposing (..)
import Rules.ViewUtils exposing (..)
import Rules.ChangeRequest exposing (toLabel)

import Compliance.Utils exposing (defaultComplianceFilter)
import Compliance.Html exposing (buildComplianceBar)
import Ui.Datatable exposing (SortOrder(..), Category, getSubElems)


informationTab: Model -> RuleDetails  -> Html Msg
informationTab model details =
  let
    isNewRule = Maybe.Extra.isNothing details.originRule
    rule       = details.rule
    ui = details.ui
    newTag     = ui.newTag
    compliance =
      case getRuleCompliance model rule.id of
       Just co ->
          buildComplianceBar defaultComplianceFilter co.complianceDetails
       Nothing -> div [class "text-muted"] [ text "No data available" ]
    rightCol =
      if isNewRule then
        div [class "callout-fade callout-info"]
        [ div [class "marker"][span [class "fa fa-info-circle"][]]
        , div []
          [ p[][text "You are creating a new rule. You may already want to apply directives and groups to it."]
          , p[][text "To do so, please go to their corresponding tab, or use the shortcuts below:"]
          , div[class "action-btn"]
            [ button [class "btn btn-default", onClick (UpdateRuleForm {details | ui = { ui | editDirectives = True }, tab = Directives})][text "Select directives", span[class "fa fa-plus"][]]
            , button [class "btn btn-default", onClick (UpdateRuleForm {details | ui = { ui | editGroups     = True }, tab = Groups    })][text "Select groups"    , span[class "fa fa-plus"][]]
            ]
          ]
        ]
      else
        div [class "form-group show-compliance"]
        [ label[][text "Compliance"]
        , compliance
        , label[class "id-label"][text "Rule ID"]
        , div [class "id-container"]
          [ p [class "id-value", onClick (Copy rule.id.value)][text rule.id.value]
          , i [class "ion ion-clipboard clipboard-icon", onClick (Copy rule.id.value)][]
          ]
        ]
    getCategoryName : String -> String
    getCategoryName cId =
      let
        concatCategories : (Category a) -> List (Category a)
        concatCategories c =
          c :: (List.concatMap concatCategories (getSubElems c))
        findCategory = List.Extra.find (\c -> c.id == cId) (concatCategories model.rulesTree)
      in
        case findCategory of
          Just c  -> c.name
          Nothing -> "Category not found"
    allMissingCategoriesRulesId = List.map (\r -> r.id.value) (getAllMissingCatsRules model.rulesTree)
    originRuleMissingCatId = case details.originRule of
      Just oR -> oR.categoryId
      Nothing -> "<Error: category ID not found>"
    isMissingCatRule = List.member rule.id.value allMissingCategoriesRulesId
    defaultOptForMissingCatRule =
      if(isMissingCatRule) then
        option [disabled True, selected True][text "-- select a category --"]
      else
        div[][]
    msgMissingCat =
      if(isMissingCatRule) then
        div [class "msg-missing-cat callout-fade callout-warning"]
        [
          text "This rule is in a missing category which has for ID: "
        , b [] [text  originRuleMissingCatId]
        , br [][]
        , text "Please move this rule under a valid category"
        ]
      else
        div [][]
    ruleForm =
      ( if model.ui.hasWriteRights then
        Html.form[class "col-sm-12 col-md-6 col-xl-7"]
        [ div [class "form-group"]
          [ label[for "rule-name"][text "Name"]
          , div[]
            [ input[ id "rule-name", type_ "text", value rule.name, class "form-control", onInput (\s -> UpdateRuleForm {details | rule = {rule | name = s}} ) ][] ]
          ]
        , div [class "form-group"]
          [ label[for "rule-category"][text "Category"]
          , msgMissingCat
          , div[]
            [ select[ id "rule-category", class "form-select", onInput (\s -> UpdateRuleForm {details | rule = {rule | categoryId = s}} ) ]
               ( defaultOptForMissingCatRule :: (buildListCategories "" "" rule.categoryId model.rulesTree ))
            ]
          ]
        , div [class "tags-container"]
          [ label[for "rule-tags-key"][text "Tags"]
          , div[class "form-group"]
            [ div[class "input-group"]
              [ input[ id "rule-tags-key", type_ "text", placeholder "key", class "form-control", onInput (\s -> UpdateRuleForm {details | ui = {ui | newTag = {newTag | key = s}}} ), value newTag.key][]
              , span [ class "input-group-text addon-json"][ text "=" ]
              , input[ type_ "text", placeholder "value", class "form-control", onInput (\s -> UpdateRuleForm {details | ui = {ui | newTag = {newTag | value = s}}}), value newTag.value][]
              , button [ class "btn btn-default", type_ "button", onClick  (UpdateRuleForm {details | rule = {rule |  tags = newTag :: rule.tags }, ui = {ui | newTag = Tag "" ""}}), disabled (String.isEmpty details.ui.newTag.key || String.isEmpty details.ui.newTag.value) ][ span[class "fa fa-plus"][]]
              ]
            ]
          , buildTagsContainer rule True details
          ]
        , div [class "form-group"]
          [ label[for "rule-short-description"][text "Short description"]
          , div[]
            [ input[ id "rule-short-description", type_ "text", value rule.shortDescription, placeholder "There is no short description", class "form-control", onInput (\s -> UpdateRuleForm {details | rule = {rule | shortDescription = s}} )  ][] ]
          ]
        , div [class "form-group"]
          [ label[for "rule-long-description"][text "Long description"]
          , div[]
            [ textarea[ id "rule-long-description", value rule.longDescription, placeholder "There is no long description", class "form-control", onInput (\s -> UpdateRuleForm {details | rule = {rule | longDescription = s}} ) ][] ]
          ]
        ]
        else
        Html.form [class "col-sm-12 col-md-6 col-xl-7 readonly-form"]
        [ div [class "form-group"]
          [ label[for "rule-name"][text "Name"]
          , div[][text rule.name]
          ]
        , div [class "form-group"]
          [ label[for "rule-category"][text "Category"]
          , div[][text (getCategoryName rule.categoryId), span[class "half-opacity"][text (" (id: "++ rule.categoryId ++")")]]
          ]
        , div [class "tags-container"]
          [ label[for "rule-tags-key"][text "Tags"]
          , ( if List.length rule.tags > 0 then
              buildTagsContainer rule False details
            else
              div[class "half-opacity"][text "There is no tags"]
            )
          ]
        , div [class "form-group"]
          [ label[for "rule-short-description"][text "Short description"]
          , div[]
            ( if String.isEmpty rule.shortDescription then
              [ span[class "half-opacity"][text "There is no short description"] ]
            else
              [ text rule.shortDescription ]
            )
          ]
        , div [class "form-group"]
          [ label[for "rule-long-description"][text "Long description"]
          , div[]
            ( if String.isEmpty rule.longDescription then
              [ span[class "half-opacity"][text "There is no long description"] ]
            else
              [ text rule.longDescription ]
            )
          ]
        ]
      )

    pendingCrWarning = case model.ui.crSettings of
      Just settings ->
        if (settings.enableChangeRequest && not (List.isEmpty settings.pendingChangeRequests)) then
          div [class "col-sm-12"]
          [ div[class ("callout-fade callout-info callout-cr" ++ if settings.collapsePending then " list-hidden" else ""), id "accordionCR"]
            [ p[]
              [ b[]
                [ i[class "fa fa-info-circle"][]
                , text "Pending change requests"
                ]
              , span[class "fa fa-chevron-down", onClick (UpdateCrSettings {settings | collapsePending = not settings.collapsePending})][]
              ]
            , div[]
              [ p[][text "The following pending change requests affect this rule, you should check that your modification is not already pending:"]
              , ul[]
                ( settings.pendingChangeRequests
                  |> List.map (\cr ->
                    let
                      statusBadge = span[class "badge"][text (toLabel cr.status)]
                      tooltip =
                        if String.isEmpty cr.description then
                          text ""
                        else
                          span
                          [ class "fa fa-question-circle icon-info"
                          , attribute "data-bs-toggle" "tooltip"
                          , attribute "data-bs-placement" "top"
                          , title (buildTooltipContent "System updates" (buildTooltipContent "Description" cr.description))
                          ][]
                    in
                      li[]
                      [ a[href (model.contextPath ++ "/secure/configurationManager/changes/changeRequest/" ++ (String.fromInt cr.id))]
                        [ statusBadge
                        , text cr.name
                        , tooltip
                        ]
                      ]
                    )
                )
              ]
            ]
          ]
        else
          text ""
      Nothing -> text ""
  in
    div[class "row"]
    [ pendingCrWarning
    , ruleForm
    , div [class "col-sm-12 col-md-6 col-xl-5"][ rightCol ]
    ]

buildTagsContainer : Rule -> Bool -> RuleDetails -> Html Msg
buildTagsContainer rule editMode details =
  let
    tagsList = List.map (\t ->
      div [class "btn-group btn-group-xs delete-action"]
        [ button [class "btn btn-default tags-label", type_ "button"]
          [ i[class "fa fa-tag"][]
          , span[class "tag-key"][text t.key]
          , span[class "tag-separator"][text "="]
          , span[class "tag-value"][text t.value]
          ]
        , (if editMode then
            button [class "btn btn-default btn-delete", type_ "button", onClick  (UpdateRuleForm {details | rule = {rule |  tags = List.Extra.remove t rule.tags }})]
            [ span [class "fa fa-times text-danger"][] ]
          else
            text ""
          )
        ]
      ) rule.tags
  in
    div [class "tags-container form-group"](tagsList)
