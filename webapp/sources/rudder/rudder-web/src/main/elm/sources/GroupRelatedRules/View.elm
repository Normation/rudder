module GroupRelatedRules.View exposing (..)

import Html exposing (..)
import Html.Attributes exposing (checked, class, disabled, for, href, id, placeholder, type_, value)
import Html.Events exposing (onClick, onInput)
import Html.Events.Extra exposing (onClickPreventDefault)
import List.Extra
import NaturalOrdering as N
import Maybe.Extra

import GroupRelatedRules.DataTypes exposing (..)
import GroupRelatedRules.ViewUtils exposing (..)

import Rules.DataTypes exposing (missingCategoryId)
import Ui.Datatable exposing (filterSearch, emptyCategory, Category, getSubElems)


view : Model -> Html Msg
view model =
  let
    allMissingCategoriesId = List.map .id (getAllMissingCats model.ruleTree)
    treeFilters = model.ui.ruleTreeFilters
    newTag      = treeFilters.newTag
    ruleTreeElem : Rule -> Html Msg
    ruleTreeElem item =
      let
        (classDisabled, badgeDisabled) = if item.enabled /= True then
            (" item-disabled", span[ class "badge-disabled"][])
          else
            ("", text "")
      in
        li [class "jstree-node jstree-leaf"]
        [ i[class "jstree-icon jstree-ocl"][]
        , a[class ("jstree-anchor"++classDisabled), href (getRuleLink model.contextPath item.id), onClickPreventDefault (GoTo (getRuleLink model.contextPath item.id))]
          [ i [class "jstree-icon jstree-themeicon fa fa-sitemap jstree-themeicon-custom"][]
          , span [class "treeGroupName"]
            [ badgeIncludedExcluded model item.id
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
          |> List.filter (\r -> filterSearch model.ui.ruleTreeFilters.filter (searchFieldRules r model))
          |> List.filter (\r -> filterTags r.tags model.ui.ruleTreeFilters.tags)
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
        missingCatClass = if(List.member item.id allMissingCategoriesId) then " missing-cat " else ""
        treeItem =
          if item.id /= "rootRuleCategory" then
            if (String.isEmpty model.ui.ruleTreeFilters.filter && List.isEmpty model.ui.ruleTreeFilters.tags) || ((List.length rules > 0) || (List.length categories > 0)) then
              Just (
                li[class ("jstree-node" ++ foldedClass model.ui.ruleTreeFilters item.id)]
                [ i [class "jstree-icon jstree-ocl", onClick (UpdateRuleFilters (foldUnfoldCategory model.ui.ruleTreeFilters item.id))][]
                , a [class ("jstree-anchor"), href (getRuleCategoryLink model.contextPath item.id), onClickPreventDefault (GoTo (getRuleCategoryLink model.contextPath item.id))]
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
        Maybe.Extra.filter (\_ -> model.ruleTree /= emptyCategory) treeItem
  
  in 
    div [class "shadow-none col-6 col-lg-8 col-xl-7"]
    [ div [class "template-main-header"]
      [ div [class "header-title"]
        [ label[][text "Related rules tree"]
        ]
      , div [class "header-filter"]
        [ div [class "input-group flex-nowrap"]
          [ button [class "input-group-text btn btn-default", type_ "button", onClick (FoldAllCategories model.ui.ruleTreeFilters) ][span [class "fa fa-folder fa-folder-open"][]]
          , input[type_ "text", value model.ui.ruleTreeFilters.filter ,placeholder "Filter", class "form-control", onInput (\s -> UpdateRuleFilters {treeFilters | filter = s})][]
          , button [class "input-group-text btn btn-default", type_ "button", onClick (UpdateRuleFilters {treeFilters | filter = ""})] [span [class "fa fa-times"][]]
          ]
        , label [class "btn btn-default more-filters", for "toggle-filters"][]
        ]
      , input [type_ "checkbox", id "toggle-filters", class "toggle-filters", checked True][]
      , div[class "filters-container"]
        [ div[]
          [ div [class "form-group"]
          [ label[for "tag-key"][text "Tags"]
          , div [class "input-group flex-nowrap"]
            [ input[type_ "text", value model.ui.ruleTreeFilters.newTag.key, placeholder "key", class "form-control", id "tag-key", onInput (\s -> UpdateRuleFilters {treeFilters | newTag = {newTag | key = s}})][]
            , span [class "input-group-text"][text " = "] 
            , input[type_ "text", value model.ui.ruleTreeFilters.newTag.value, placeholder "value", class "form-control", onInput (\s -> UpdateRuleFilters {treeFilters | newTag = {newTag | value = s}})][]
            , button 
              [ type_ "button"
              , class "input-group-text btn btn-default"
              , onClick ( UpdateRuleFilters {treeFilters | tags = ( Tag (String.trim newTag.key) (String.trim newTag.value) ) :: treeFilters.tags , newTag = Tag "" ""})
              , disabled ((String.isEmpty newTag.key && String.isEmpty newTag.value) || List.member newTag treeFilters.tags)
              ] [ span[class "fa fa-plus"][]]
            ]
          ]
        , (if List.isEmpty treeFilters.tags then
          text ""
        else
          div [class "only-tags"]
          [ button [ class "btn btn-default btn-xs pull-right clear-tags", onClick ( UpdateRuleFilters {treeFilters | tags = []})]
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
            [ button [class "btn btn-default tags-label", onClick (UpdateRuleFilters {treeFilters | newTag = {newTag | key = t.key, value = t.value}})]
              [ i [class "fa fa-tag"][]
              , span [class "tag-key"]
                [( if String.isEmpty t.key then i [class "fa fa-asterisk"][] else span[][text t.key] )
                ]
              , span [class "tag-separator"][text "="]
              , span [class "tag-value"]
                [( if String.isEmpty t.value then i [class "fa fa-asterisk"][] else span[][text t.value] )
                ]
              ]
              , button [class "btn btn-default", type_ "button", onClick ( UpdateRuleFilters {treeFilters | tags = (List.Extra.remove t treeFilters.tags)})][ span [class "fa fa-times"][] ]
              ]
          ) treeFilters.tags |> List.reverse)
        )]
        ]
      ]
    , div []
      [(
        if model.ui.loading then
          generateLoadingList
        else
          div [class "jstree jstree-default"]
          [ ul[class "jstree-container-ul jstree-children"]
            [(case ruleTreeCategory model.ruleTree of
              Just html -> html
              Nothing   -> div [class "alert alert-warning"]
                [ i [class "fa fa-exclamation-triangle"][]
                , text  ("No rules " ++ if model.ruleTree == emptyCategory then "is related to the group." else "match your filter.")
                ]
            )]
          ]
      )]
    ]
  