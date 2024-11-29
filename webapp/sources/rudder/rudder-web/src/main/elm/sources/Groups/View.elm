module Groups.View exposing (..)

import Html exposing (..)
import Html.Attributes exposing (attribute, class, href, id, placeholder, style, title, type_, value)
import Html.Events exposing (onClick, onInput)
import NaturalOrdering as N
import List
import String

import Groups.DataTypes exposing (..)
import Groups.ViewGroupsTable exposing (..)
import Groups.ViewUtils exposing (..)

view : Model -> Html Msg
view model = 
  let
    groupsList = getElemsWithCompliance model
    groupTreeElem : Group -> Html Msg
    groupTreeElem item =
      let
        (classDisabled, badgeDisabled) = if item.enabled /= True then
            (" item-disabled", span[ class "badge-disabled"][])
          else
            ("", text "")
        -- TODO: uncomment when group details will be implemented in Elm
        -- classFocus =
        --   case model.mode of
        --     GroupForm rDetails ->
        --       if (rDetails.group.id.value == item.id.value) then " focused " else ""
        --     _ -> ""
      in
        li [class "jstree-node jstree-leaf"]
        [ i[class "jstree-icon jstree-ocl"][]
        , a[class ("jstree-anchor"++classDisabled {- ++classFocus -}), href (getGroupLink model.contextPath item.id.value), onClick (OpenGroupDetails item.id)]
          [ i [class "jstree-icon jstree-themeicon fa fa-sitemap jstree-themeicon-custom"][]
          , span
            ((
              class "treeGroupName"
            ) :: (
              if String.isEmpty item.description
              then []
              else [
                attribute "data-bs-toggle" "tooltip"
                , attribute "data-bs-placement" "right"
                , title (buildTooltipContent item.name item.description)
              ]
            ))
            [ text item.name
            , small [class "greyscala"] [text (" - " ++ if item.dynamic then "Dynamic" else "Static")]
            , badgeDisabled
            ]
          ]
        ]

    groupTreeCategory : (Category Group) -> Maybe (Html Msg)
    groupTreeCategory item =
      let
        categories = getSubElems item
          |> List.sortWith (\c1 c2 -> N.compare c1.name c2.name)
          |> List.filterMap groupTreeCategory

        groups = item.elems
          |> List.filter (\r -> filterSearch model.ui.groupFilters.treeFilters.filter (searchFieldGroups r model))
          |> List.sortWith (\r1 r2 -> N.compare r1.name r2.name)
          |> List.map groupTreeElem

        childsList  = ul[class "jstree-children"] (List.concat [categories, groups])

        icons = " fa fa-folder "
        -- TODO: uncomment when group details will be implemented in Elm
        -- classFocus =
        --   case model.mode of
        --     CategoryForm cDetails ->
        --       if (cDetails.category.id == item.id) then " focused " else ""
        --     _ -> ""
        treeItem =
          if item.id /= rootGroupCategoryId then
            if (String.isEmpty model.ui.groupFilters.treeFilters.filter) || ((List.length groups > 0) || (List.length categories > 0)) then
              Just (
                li[class ("jstree-node" ++ foldedClass model.ui.groupFilters.treeFilters item.id)]
                [ i [class "jstree-icon jstree-ocl", onClick (UpdateGroupFilters (foldUnfoldCategory model.ui.groupFilters item.id))][]
                , a [class ("jstree-anchor"{- ++ classFocus -}), onClick (OpenCategoryDetails item.id)]
                  [ i [class ("jstree-icon jstree-themeicon jstree-themeicon-custom" ++ icons)][]
                  , span [class "treeGroupCategoryName"][text item.name]
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

    groupFilters = model.ui.groupFilters
    treeFilters = groupFilters.treeFilters

    templateMain = case model.mode of
      Loading -> generateLoadingTable
      LoadingTable -> generateLoadingTable
      GroupTable   ->
        div [class "main-table"]
        [ div [class "table-container"]
          [ table [ class "no-footer dataTable"]
            [ thead [] [groupsTableHeader model.ui.groupFilters]
            , tbody [] (buildGroupsTable model groupsList)
            ]
            , if hasMoreGroups model then
                div [ class "d-flex justify-content-center py-2" ] 
                [ button [class "btn btn-default btn-icon load-more", onClick LoadMore] [text "Load more...", i [class "fa fa-plus-circle"] []]
                ]
              else text ""
          ]
        ]
      ExternalTemplate -> text ""

    modal = case model.ui.modal of
      NoModal -> text ""
      ExternalModal -> div [class "modal-backdrop fade show", style "height" "100%"] []
  in
    div [class "rudder-template"]
    [ div [class "template-sidebar sidebar-left"]
      [ div [class "sidebar-header"]
        [ div [class "header-title"]
          [ h1[]
            [ span[][text "Groups"]
            ]
          , ( if model.ui.hasWriteRights then
              div [class "header-buttons"]
              --[ button [class "btn btn-default", type_ "button", onClick (GenerateId (\s -> NewCategory s      ))][text "Add category"]
              --, button [class "btn btn-success", type_ "button", onClick (GenerateId (\s -> NewGroup (GroupId s) ))][text "Create", i[class "fa fa-plus-circle"][]]
              --]
              [ button [id "newItem", class "btn btn-success", type_ "button", onClick OpenModal][text "Create", i[class "fa fa-plus-circle"][]]
              ]
            else
              text ""
            )
          ]
        , div [class "header-filter"]
          [ div [class "input-group flex-nowrap"]
            [ button [class "input-group-text btn btn-default", type_ "button", onClick (FoldAllCategories model.ui.groupFilters) ][span [class "fa fa-folder fa-folder-open"][]]
              , input[type_ "text", value model.ui.groupFilters.treeFilters.filter, placeholder "Filter", class "form-control", onInput (\s -> UpdateGroupFilters {groupFilters | treeFilters = {treeFilters | filter = s}})][]
              , button [class "input-group-text btn btn-default", type_ "button", onClick (UpdateGroupFilters {groupFilters | treeFilters = {treeFilters | filter = ""}})] [span [class "fa fa-times"][]]
            ]
          ]
        ]
      , div [class "sidebar-body"]
        [ div [class "sidebar-list"][(
          if model.mode == Loading then
            generateLoadingList
          else
            div [class "jstree jstree-default"]
            [ ul[class "jstree-container-ul jstree-children"]
              [(case groupTreeCategory model.groupsTree of
                Just html -> html
                Nothing   -> div [class "alert alert-warning"]
                  [ i [class "fa fa-exclamation-triangle"][]
                  , text  "No groups match your filter."
                  ]
              )]
            ]
          )]
        ]
     ]
     -- The content of "ajaxItemContainer" can be replaced with external template, when model has the ExternalTemplate mode
    , div [class "template-main", id "ajaxItemContainer"]
      [ 
        templateMain
      ]
    , modal
    ]
