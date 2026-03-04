module Rules.ViewTabGroups exposing (..)

import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onClick, onInput)
import List
import Maybe.Extra
import NaturalOrdering as N

import Rules.DataTypes exposing (..)
import Rules.ViewUtils exposing (..)

import Ui.Datatable exposing (SortOrder(..), filterSearch, Category, getSubElems)
import Html.Events.Extra exposing (onClickPreventDefault)


groupsTab : Model -> RuleDetails -> Html Msg
groupsTab model details =
  let
    isNewRule  = Maybe.Extra.isNothing details.originRule
    rule       = details.rule
    originRule = details.originRule
    ui = details.ui
    (includedTargets, excludedTargets) =
      case rule.targets of
        [Composition (Or include) (Or exclude)] -> (include, exclude)
        _ -> (rule.targets, [])
  in
    if not details.ui.editGroups then
      div [class "row lists"]
      [ div[class "list-edit d-flex flex-column col-sm-12 col-md-6"]
        [ div[class "list-container"]
          [ div[class "list-heading align-items-center"]
            [ h4[class "mb-0"][text "Applied to Nodes in any of these Groups"]
            , ( if model.ui.hasWriteRights then
                button [class "btn btn-default btn-icon", onClick (UpdateRuleForm {details | ui = {ui | editGroups = True}})]
                [ text "Select", i[class "fa fa-plus-circle" ][]]
              else
                text ""
              )
            ]
          , ul[class "groups applied-list"]
            ( if(List.isEmpty includedTargets ) then
              [ li [class "empty"]
                [ span [] [text "There is no group included."]
                , span [class "warning-sign"][i [class "fa fa-info-circle"][]]
                , span [class "warning-sign"][i [class "fa fa-info-circle"][]]
                ]
              ]
            else
              List.map (buildIncludeList originRule model.groupsTree model details.ui.editGroups True) includedTargets
            )
          ]
        ]
      , div[class "list-edit d-flex flex-column col-sm-12 col-md-6"]
        [ div[class "list-container"]
          [ div[class "list-heading except align-items-center"]
            [ h4[class "mb-0"][text "Except to Nodes in any of these Groups"]
            ]
          , ul[class "groups applied-list"]
            ( if(List.isEmpty excludedTargets) then
              [ li [class "empty"]
                [ span [] [text "There is no group excluded."]
                , span [class "warning-sign"][i [class "fa fa-info-circle"][]]
                ]
              ]
            else
              List.map (buildIncludeList originRule model.groupsTree model details.ui.editGroups False) excludedTargets
            )
          ]
        ]
      ]
    else
      let
        groupTreeElem : Group -> Html Msg
        groupTreeElem item =
          let
            checkIncludeOrExclude : List RuleTarget -> Bool
            checkIncludeOrExclude lst =
              let
                getTargetIds : List RuleTarget -> List String
                getTargetIds listTargets =
                  List.concatMap (\target -> case target of
                     NodeGroupId groupId -> [groupId]
                     Composition i e ->
                       let
                         included = getTargetIds[ i ]
                         excluded = getTargetIds[ e ]
                       in
                         included |>
                           List.filter (\t -> List.member t excluded)
                     Special spe -> [spe]
                     Node node -> [node]
                     Or t  -> getTargetIds t
                     And t -> getTargetIds t -- TODO : Need to be improved - we need to keep targets that are member of all targets list in t
                  ) listTargets
              in
                List.member item.id (getTargetIds lst)
            includeClass =
              if checkIncludeOrExclude includedTargets then " item-selected"
              else if checkIncludeOrExclude excludedTargets then " item-selected excluded"
              else ""
            (disabledClass, disabledLabel) =
              if item.enabled then
                ("", text "")
              else
                (" is-disabled", span[class "badge-disabled"][])
          in
            li [class ("jstree-node jstree-leaf" ++ disabledClass)]
            [ i [class "jstree-icon jstree-ocl"][]
            , a [class ("jstree-anchor" ++ includeClass), onClickPreventDefault (SelectGroup item.target True)]
              [ i [class "jstree-icon jstree-themeicon fa fa-sitemap jstree-themeicon-custom"][]
              , span [class "item-name"][text item.name, (if item.dynamic then (small [class "text-secondary"][text "- Dynamic"]) else (text ""))]
              , disabledLabel
              , div [class "treeActions-container"]
                [ span [class "treeActions"][ span [class "fa action-icon ms-1 accept"][]]
                , span [class "treeActions"][ span [class "fa action-icon ms-1 except", onCustomClick (SelectGroup item.target False)][]]
                ]
              , goToBtn (getGroupLink model.contextPath item.id)
              ]
            ]
        groupTreeCategory : Category Group -> Maybe (Html Msg)
        groupTreeCategory item =
          let
            categories = getSubElems item
              |> List.sortWith (\c1 c2 -> N.compare c1.name c2.name)
              |> List.filterMap groupTreeCategory
            groups = item.elems
              |> List.filter (\g -> (filterSearch model.ui.groupFilters.treeFilters.filter (searchFieldGroups g)))
              |> List.sortWith (\g1 g2 -> N.compare g1.name g2.name)
              |> List.map groupTreeElem
            children  = categories ++ groups
          in
            if not (List.isEmpty children) then
              Just (li[class ("jstree-node" ++ foldedClass model.ui.groupFilters.treeFilters item.id)]
              [ i [class "jstree-icon jstree-ocl", onClick (UpdateGroupFilters (foldUnfoldCategory model.ui.groupFilters item.id))][]
              , a [class "jstree-anchor"]
                [ i [class "jstree-icon jstree-themeicon fa fa-folder jstree-themeicon-custom"][]
                , span [class "treeGroupCategoryName"][text item.name]
                ]
              , ul[class "jstree-children"](children)
              ])
            else
              Nothing

        cancelTargets = case details.originRule of
          Just oR -> oR.targets
          Nothing -> []

        (cancelUi, confirmBtn) =
          if isNewRule then
            ( ui
            , text ""
            )
          else
            ( {ui | editGroups = False}
            , button[class "btn btn-default btn-icon", onClick (UpdateRuleForm {details | ui = {ui | editGroups = False}} )][text "Confirm", i[class "fa fa-check"][]]
            )
      in
        div[class "row flex-container"]
        [ div[class "list-edit d-flex flex-column col-sm-12 col-md-6 col-xl-7"]
          [ div[class "list-container"]
            [ div[class "list-heading align-items-center"]
              [ h4[class "mb-0"][text "Apply to Nodes in any of these Groups"]
              , div [class "btn-actions"]
                [ button[class "btn btn-default btn-icon", onClick ( UpdateRuleForm { details | rule = {rule | targets = cancelTargets}, ui = cancelUi })]
                  [text "Cancel", i[class "fa fa-undo-alt"][]]
                , confirmBtn
                ]
              ]
            , ul[class "groups applied-list"]
              ( if(List.isEmpty includedTargets ) then
                [ li [class "empty"]
                  [ span [] [text "There is no group included."]
                  , span [class "warning-sign"][i [class "fa fa-info-circle"][]]
                  ]
                ]
              else
                List.map (buildIncludeList originRule model.groupsTree model details.ui.editGroups True) includedTargets
              )
            ]
          , div[class "list-container"]
            [ div[class "list-heading align-items-center except"]
              [ h4[class "mb-0"][text "Except to Nodes in any of these Groups"]
              ]
            , ul[class "groups applied-list"]
              ( if(List.isEmpty excludedTargets) then
                [ li [class "empty"]
                  [ span [] [text "There is no group excluded."]
                  , span [class "warning-sign"][i [class "fa fa-info-circle"][]]
                  ]
                ]
              else
                List.map (buildIncludeList originRule model.groupsTree model details.ui.editGroups False) excludedTargets
              )
            ]
          ]
        , div [class "tree-edit col-sm-12 col-md-6 col-xl-5"]
          [ div [class "tree-container"]
            [ div [class "tree-heading"]
              [ h4 [][ text "Select groups" ]
              , i [class "fa fa-bars"][]
              ]
              , div [class "header-filter"]
                [ div [class "input-group"]
                  [ button [class "btn btn-default", type_ "button"][span [class "fa fa-folder fa-folder-open"][]]
                  , input [type_ "text", placeholder "Filter", class "form-control", value model.ui.groupFilters.treeFilters.filter
                    , onInput (\s ->
                      let
                        groupFilters = model.ui.groupFilters
                        treeFilters  = groupFilters.treeFilters
                      in
                        UpdateGroupFilters {groupFilters | treeFilters = {treeFilters | filter = s}}
                    )][]
                  , button [class "btn btn-default", type_ "button" , onClick (
                      let
                        groupFilters = model.ui.groupFilters
                        treeFilters  = groupFilters.treeFilters
                      in
                        UpdateGroupFilters {groupFilters | treeFilters = {treeFilters | filter = ""}}
                    )]
                    [span [class "fa fa-times"][]]
                  ]
                ]
            , div [class "jstree jstree-default jstree-groups"]
              [ ul[class "jstree-container-ul jstree-children"]
                [(case groupTreeCategory model.groupsTree of
                  Just html -> html
                  Nothing   -> div [class "alert alert-warning"]
                    [ i [class "fa fa-exclamation-triangle"][]
                    , text  "No groups match your filter."
                    ]
                )]
              ]
            ]
          ]
        ]
