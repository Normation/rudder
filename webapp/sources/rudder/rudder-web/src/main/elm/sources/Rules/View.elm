module Rules.View exposing (..)

import Html exposing (..)
import Html.Attributes exposing (checked, class, disabled, for, href, id, placeholder, style, tabindex, type_, value, attribute)
import Html.Events exposing (onClick, onInput)
import Html.Events.Extra exposing (onClickPreventDefault)
import List
import List.Extra
import Maybe.Extra exposing (isNothing)
import String
import NaturalOrdering as N

import Rules.ApiCalls exposing (..)
import Rules.DataTypes exposing (..)
import Rules.ViewCategoryDetails exposing (..)
import Rules.ViewRulesTable exposing (..)
import Rules.ViewRuleDetails exposing (..)
import Rules.ViewUtils exposing (..)
import Rules.ChangeRequest exposing (ChangeRequestSettings)

import Ui.Datatable exposing (filterSearch, Category, getSubElems, generateLoadingTable)


view : Model -> Html Msg
view model =
  let
    rulesList = getListRules model.rulesTree
    allMissingCategoriesId = List.map .id (getAllMissingCats model.rulesTree)
    ruleTreeElem : Rule -> Html Msg
    ruleTreeElem item =
      let
        (classDisabled, badgeDisabled) = if item.enabled /= True then
            (" item-disabled", span[ class "badge-disabled"][])
          else
            ("", text "")
        classFocus =
          case model.mode of
            RuleForm rDetails ->
              if (rDetails.rule.id.value == item.id.value) then " focused " else ""
            _ -> ""
      in
        li [class "jstree-node jstree-leaf"]
        [ i[class "jstree-icon jstree-ocl"][]
        , a[class ("jstree-anchor"++classDisabled++classFocus), href (model.contextPath ++ "/secure/configurationManager/ruleManagement/rule/" ++ item.id.value), onClickPreventDefault (OpenRuleDetails item.id True)]
          [ i [class "jstree-icon jstree-themeicon fa fa-sitemap jstree-themeicon-custom"][]
          , span [class "treeGroupName"]
            [ badgePolicyMode model.policyMode item.policyMode
            , text item.name
            , badgeDisabled
            , buildTagsTree item.tags
            ]
          ]
        ]

    ruleTreeCategory : (Category Rule) -> Maybe (Html Msg)
    ruleTreeCategory item =
      let
        missingCat = getSubElems item
          |> List.filter (\c -> c.id == missingCategoryId)
          |> List.sortWith (\c1 c2 -> N.compare c1.name c2.name)
          |> List.filterMap ruleTreeCategory

        categories = getSubElems item
          |> List.filter (\c -> c.id /= missingCategoryId)
          |> List.sortWith (\c1 c2 -> N.compare c1.name c2.name)
          |> List.filterMap ruleTreeCategory

        rules = item.elems
          |> List.filter (\r -> filterSearch model.ui.ruleFilters.treeFilters.filter (searchFieldRules r model))
          |> List.filter (\r -> filterTags r.tags model.ui.ruleFilters.treeFilters.tags)
          |> List.sortWith (\r1 r2 -> N.compare r1.name r2.name)
          |> List.map ruleTreeElem

        childsList  = ul[class "jstree-children"] (List.concat [categories, rules, missingCat])

        mainMissingCat =
          if(item.id == missingCategoryId) then
            " main-missing-cat "
          else
            ""
        icons =
          if(item.id == missingCategoryId) then
            " fas fa-exclamation-triangle " ++ mainMissingCat
          else
            " fa fa-folder "
        classFocus =
          case model.mode of
            CategoryForm cDetails ->
              if (cDetails.category.id == item.id) then " focused " else ""
            _ -> ""
        missingCatClass = if(List.member item.id allMissingCategoriesId) then " missing-cat " else ""
        treeItem =
          if item.id /= "rootRuleCategory" then
            if (String.isEmpty model.ui.ruleFilters.treeFilters.filter && List.isEmpty model.ui.ruleFilters.treeFilters.tags) || ((List.length rules > 0) || (List.length categories > 0)) then
              Just (
                li[class ("jstree-node" ++ foldedClass model.ui.ruleFilters.treeFilters item.id)]
                [ i [class "jstree-icon jstree-ocl", onClick (UpdateRuleFilters (foldUnfoldCategory model.ui.ruleFilters item.id))][]
                , a [class ("jstree-anchor" ++ classFocus), href ("/rudder/secure/configurationManager/ruleManagement/ruleCategory/" ++ item.id), onClickPreventDefault (OpenCategoryDetails item.id True)]
                  [ i [class ("jstree-icon jstree-themeicon jstree-themeicon-custom" ++ icons)][]
                  , span [class ("treeGroupCategoryName " ++ missingCatClass ++ mainMissingCat)][text item.name]
                  ]
                , childsList
                ]
              )
            else
              Nothing
          else
            Just childsList
      in
        treeItem

    ruleFilters = model.ui.ruleFilters
    treeFilters = ruleFilters.treeFilters
    newTag      = ruleFilters.treeFilters.newTag

    templateMain = case model.mode of
      Loading -> generateLoadingTable False 5
      RuleTable   ->
        div [class "main-table"]
        [ div [class "table-container"]
          [ table [ class "no-footer dataTable"]
            [ thead [] [rulesTableHeader model.ui.ruleFilters]
            , tbody [] (buildRulesTable model rulesList)
            ]
          ]
        ]

      RuleForm details ->
        (editionTemplate model details)

      CategoryForm details ->
        (editionTemplateCat model details)

    changeAuditForm : ChangeRequestSettings -> Html Msg
    changeAuditForm crSettings =
      if crSettings.enableChangeMessage || crSettings.enableChangeRequest then
        div []
        [ h4 [class "audit-title"] [text "Change audit log"]
        , ( if crSettings.enableChangeRequest then
          div[class "form-group"]
          [ label[]
             [ text "Change request title"
            ]
            , input [type_ "text", class "form-control", onInput (\s -> UpdateCrSettings {crSettings | changeRequestName = s}), value crSettings.changeRequestName ][]
          ]
          else
          text ""
          )
        , div[class "form-group"]
          [ label[]
            [ text "Change audit message"
            , text (if crSettings.enableChangeMessage && crSettings.mandatoryChangeMessage then " (required)" else "")
            ]
            , textarea [class "form-control", placeholder crSettings.changeMessagePrompt, onInput (\s -> UpdateCrSettings {crSettings | message = s}), value crSettings.message ][]
          ]
        ]
      else
        text ""

    modal = case model.ui.modal of
      NoModal -> text ""
      DeletionValidation rule crSettings ->
        let
          (auditForm, btnDisabled) = case crSettings of
            Just s  ->
              ( changeAuditForm s
              , (s.mandatoryChangeMessage && String.isEmpty s.message)
              )
            Nothing ->
              ( text ""
              , False
              )
        in
          div [ tabindex -1, class "modal fade show", style "display" "block" ]
          [ div [class "modal-backdrop fade show", onClick (ClosePopup Ignore)][]
          , div [ class "modal-dialog" ] [
              div [ class "modal-content" ] [
                div [ class "modal-header" ] [
                  h5 [ class "modal-title" ] [ text "Delete Rule"]
                , button [type_ "button", class "btn-close", onClick (ClosePopup Ignore), attribute "aria-label" "Close"][]
                ]
              , div [ class "modal-body" ]
                [ h4 [class "text-center"][text ("Are you sure you want to Delete rule '"++ rule.name ++"'?")]
                , auditForm
                ]
              , div [ class "modal-footer" ] [
                  button [ class "btn btn-default", onClick (ClosePopup Ignore) ]
                  [ text "Cancel " ]
                , button [ class "btn btn-danger", onClick (ClosePopup (CallApi False (deleteRule rule))), disabled btnDisabled ]
                  [ text "Delete "
                  , i [ class "fa fa-times-circle" ] []
                  ]
                ]
              ]
            ]
          ]
      DeactivationValidation rule crSettings ->
        let
          (txtDisabled, iconDisabled) = if rule.enabled then ("Disable", "fa fa-ban") else ("Enable", "fa fa-check-circle")
          (auditForm, btnDisabled) = case crSettings of
            Just s  ->
              ( changeAuditForm s
              , (s.mandatoryChangeMessage && String.isEmpty s.message)
              )
            Nothing ->
              ( text ""
              , False
              )

        in
          div [ tabindex -1, class "modal fade show", style "display" "block" ]
          [ div [class "modal-backdrop fade show", onClick (ClosePopup Ignore)][]
          , div [ class "modal-dialog" ] [
              div [ class "modal-content" ]  [
                div [ class "modal-header" ] [
                  h5[ class "modal-title" ] [ text (txtDisabled ++" Rule")]
                , button [type_ "button", class "btn-close", onClick (ClosePopup Ignore), attribute "aria-label" "Close"][]
                ]
              , div [ class "modal-body" ]
                [ h4 [class "text-center"][text ("Are you sure you want to "++ String.toLower txtDisabled ++" rule '"++ rule.name ++"'?")]
                , auditForm
                ]
              , div [ class "modal-footer" ] [
                  button [ class "btn btn-default", onClick (ClosePopup Ignore) ]
                  [ text "Cancel "
                  ]
                , button [ class "btn btn-primary", onClick (ClosePopup DisableRule), disabled btnDisabled ]
                  [ text (txtDisabled ++ " ")
                  , i [ class iconDisabled ] []
                  ]
                ]
              ]
            ]
          ]
      DeletionValidationCat category ->
        div [ tabindex -1, class "modal fade show", style "display" "block" ]
         [ div [class "modal-backdrop fade show", onClick (ClosePopup Ignore)][]
         , div [ class "modal-dialog" ] [
             div [ class "modal-content" ] [
               div [ class "modal-header" ] [
                 h5 [ class "modal-title" ] [ text "Delete category"]
               , button [type_ "button", class "btn-close", onClick (ClosePopup Ignore), attribute "aria-label" "Close"][]
               ]
             , div [ class "modal-body" ] [
                 h4 [class "text-center"][text ("Are you sure you want to delete category '"++ category.name ++"'?")]
               ]
             , div [ class "modal-footer" ] [
                 button [ class "btn btn-default", onClick (ClosePopup Ignore) ]
                 [ text "Cancel " ]
               , button [ class "btn btn-danger", onClick (ClosePopup (CallApi False (deleteCategory category))) ]
                 [ text "Delete "
                 , i [ class "fa fa-times-circle" ] []
                 ]
               ]
             ]
           ]
         ]
      SaveAuditMsg creation rule originRule crSettings ->
        let
          action = if creation then "Create" else "Update"
        in
          div [ tabindex -1, class "modal fade show", style "display" "block" ]
          [ div [class "modal-backdrop fade show", onClick (ClosePopup Ignore)][]
          , div [ class "modal-dialog" ]
            [ div [ class "modal-content" ]
              [ div [ class "modal-header" ]
                [ h5 [ class "modal-title" ] [ text (action ++" Rule")]
                , button [type_ "button", class "btn-close", onClick (ClosePopup Ignore), attribute "aria-label" "Close"][]
                ]
              , div [ class "modal-body" ]
                [ h4 [class "text-center"]
                  [ text "Are you sure that you want to "
                  , text (String.toLower action)
                  , text " this rule ?"
                  ]
                , changeAuditForm crSettings
                ]
              , div [ class "modal-footer" ]
                [ button [ class "btn btn-default", onClick (ClosePopup Ignore) ]
                  [ text "Cancel "
                  ]
                , button [ class "btn btn-primary", onClick (ClosePopup (CallApi True (saveRuleDetails rule (isNothing originRule)))), disabled (crSettings.mandatoryChangeMessage && String.isEmpty crSettings.message) ]
                  ( if crSettings.enableChangeRequest then
                    [ text "Create change request "
                    , i [ class "fa fa-plus" ] []
                    ]
                  else
                    [ text "Confirm "
                    , i [ class "fa fa-check" ] []
                    ]
                  )
                ]
              ]
            ]
          ]
  in
    div [class "rudder-template"]
    [ div [class "template-sidebar sidebar-left"]
      [ div [class "sidebar-header"]
        [ div [class "header-title"]
          [ h1[]
            [ span[][text "Rules"]
            ]
          , ( if model.ui.hasWriteRights then
              div [class "header-buttons"]
              [ button [class "btn btn-default", type_ "button", onClick (GenerateId (\s -> NewCategory s      ))][text "Add category"]
              , button [class "btn btn-success", type_ "button", onClick (GenerateId (\s -> NewRule (RuleId s) ))][text "Create", i[class "fa fa-plus-circle"][]]
              ]
            else
              text ""
            )
          ]
        , div [class "header-filter"]
          [ div [class "input-group"]
            [ button [class "btn btn-default", type_ "button", onClick (FoldAllCategories model.ui.ruleFilters) ][span [class "fa fa-folder fa-folder-open"][]]
            , input[type_ "text", value model.ui.ruleFilters.treeFilters.filter ,placeholder "Filter", class "form-control", onInput (\s -> UpdateRuleFilters {ruleFilters | treeFilters = {treeFilters | filter = s}})][]
            , button [class "btn btn-default", type_ "button", onClick (UpdateRuleFilters {ruleFilters | treeFilters = {treeFilters | filter = ""}})] [span [class "fa fa-times"][]]
            ]
          , label [class "btn btn-default more-filters", for "toggle-filters"][]
          ]
        , input [type_ "checkbox", id "toggle-filters", class "toggle-filters", checked True][]
        , div[class "filters-container"]
          [ div[]
            [ div [class "form-group"]
            [ label[for "tag-key"][text "Tags"]
            , div [class "input-group"]
              [ input[type_ "text", value model.ui.ruleFilters.treeFilters.newTag.key, placeholder "key", class "form-control", id "tag-key", onInput (\s -> UpdateRuleFilters {ruleFilters | treeFilters = {treeFilters | newTag = {newTag | key = s}}})][]
              , span [class "input-group-text addon-json"][text " = "]
              , input[type_ "text", value model.ui.ruleFilters.treeFilters.newTag.value, placeholder "value", class "form-control", onInput (\s -> UpdateRuleFilters {ruleFilters | treeFilters = {treeFilters | newTag = {newTag | value = s}}})][]
              , button
                [ type_ "button"
                , class "btn btn-default"
                , onClick ( UpdateRuleFilters {ruleFilters | treeFilters = {treeFilters | tags = ( Tag (String.trim newTag.key) (String.trim newTag.value) ) :: treeFilters.tags , newTag = Tag "" ""}})
                , disabled ((String.isEmpty newTag.key && String.isEmpty newTag.value) || List.member newTag treeFilters.tags)
                ] [ span[class "fa fa-plus"][]] ]
            ]
          , (if List.isEmpty treeFilters.tags then
            text ""
          else
            div [class "only-tags"]
            [ button [ class "btn btn-default btn-xs pull-right clear-tags", onClick ( UpdateRuleFilters {ruleFilters | treeFilters = {treeFilters | tags = []}})]
              [ text "Clear all tags"
              , i [class "fa fa-trash"][]
              ]
            ]
          )
          , (if List.isEmpty treeFilters.tags then
            text ""
          else
            div [class "tags-container"]
            ( List.map (\t ->
              div [class "btn-group btn-group-xs"]
              [ button [class "btn btn-default tags-label", onClick (UpdateRuleFilters {ruleFilters | treeFilters = {treeFilters | newTag = {newTag | key = t.key, value = t.value}}})]
                [ i [class "fa fa-tag"][]
                , span [class "tag-key"]
                  [( if String.isEmpty t.key then i [class "fa fa-asterisk"][] else span[][text t.key] )
                  ]
                , span [class "tag-separator"][text "="]
                , span [class "tag-value"]
                  [( if String.isEmpty t.value then i [class "fa fa-asterisk"][] else span[][text t.value] )
                  ]
                ]
                , button [class "btn btn-default", type_ "button", onClick ( UpdateRuleFilters {ruleFilters | treeFilters = {treeFilters | tags = (List.Extra.remove t treeFilters.tags)}})][ span [class "fa fa-times"][] ]
                ]
            ) treeFilters.tags |> List.reverse)
          )]
          ]
        ]
      , div [class "sidebar-body"]
        [ div [class "sidebar-list"][(
          if model.ui.loadingRules then
            generateLoadingList
          else
            div [class "jstree jstree-default"]
            [ ul[class "jstree-container-ul jstree-children"]
              [(case ruleTreeCategory model.rulesTree of
                Just html -> html
                Nothing   -> div [class "alert alert-warning"]
                  [ i [class "fa fa-exclamation-triangle"][]
                  , text  "No rules match your filter."
                  ]
              )]
            ]
          )]
        ]
     ]
    , div [class "template-main"]
      [ templateMain ]
    , modal
    ]
