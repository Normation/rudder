module Groups.View exposing (..)

import Groups.DataTypes exposing (..)
import Groups.ViewUtils exposing (..)
import Html exposing (..)
import Html.Attributes exposing (attribute, class, disabled, href, id, placeholder, style, title, type_, value)
import Html.Events exposing (onClick, onInput)
import Html.Events.Extra exposing (onClickPreventDefault)
import List
import NaturalOrdering as N
import Rudder.Filters
import Rudder.Table exposing (CsvExportType(..))
import String
import Tenants.SecurityTag exposing (badgeSecurityTags)
import Ui.Datatable exposing (Category, filterSearch, generateLoadingTable)
import Utils.TooltipUtils exposing (buildTooltipContent)


view : Model -> Html Msg
view model =
    let
        groupsList =
            getElemsWithCompliance model

        groupTreeElem : Group -> Html Msg
        groupTreeElem item =
            let
                ( classDisabled, badgeDisabled ) =
                    if item.enabled /= True then
                        ( " item-disabled", span [ class "badge-disabled" ] [] )

                    else
                        ( "", text "" )

                -- TODO: uncomment when group details will be implemented in Elm
                -- classFocus =
                --   case model.mode of
                --     GroupForm rDetails ->
                --       if (rDetails.group.id.value == item.id.value) then " focused " else ""
                --     _ -> ""
            in
            li [ class "jstree-node jstree-leaf" ]
                [ i [ class "jstree-icon jstree-ocl" ] []
                , a [ class ("jstree-anchor" ++ classDisabled {- ++classFocus -}), href (getGroupLink model.contextPath item.id.value), onClickPreventDefault (OpenGroupDetails item.id) ]
                    [ i [ class "jstree-icon jstree-themeicon fa fa-sitemap jstree-themeicon-custom" ] []
                    , span
                        (class "treeGroupName"
                            :: (if String.isEmpty item.description then
                                    []

                                else
                                    [ attribute "data-bs-toggle" "tooltip"
                                    , attribute "data-bs-placement" "right"
                                    , title (buildTooltipContent item.name item.description)
                                    ]
                               )
                        )
                        [ text item.name
                        , small [ class "text-secondary" ]
                            [ text
                                (" - "
                                    ++ (if item.dynamic then
                                            "Dynamic"

                                        else
                                            "Static"
                                       )
                                )
                            ]
                        , badgeSecurityTags item.security
                        , badgeDisabled
                        ]
                    ]
                ]

        groupTreeCategory : Category Group -> Maybe (Html Msg)
        groupTreeCategory item =
            let
                categories =
                    getSubElems item
                        |> List.sortWith (\c1 c2 -> N.compare c1.name c2.name)
                        |> List.filterMap groupTreeCategory

                groups =
                    item.elems
                        |> List.filter (\r -> filterSearch (Rudder.Filters.getTextValue model.ui.groupFilters.filter) (searchFieldGroups r model))
                        |> List.sortWith (\r1 r2 -> N.compare r1.name r2.name)
                        |> List.map groupTreeElem

                childsList =
                    ul [ class "jstree-children" ] (List.concat [ categories, groups ])

                icons =
                    " fa fa-folder "

                -- TODO: uncomment when group details will be implemented in Elm
                -- classFocus =
                --   case model.mode of
                --     CategoryForm cDetails ->
                --       if (cDetails.category.id == item.id) then " focused " else ""
                --     _ -> ""
                treeItem =
                    if item.id /= rootGroupCategoryId then
                        if String.isEmpty (Rudder.Filters.getTextValue model.ui.groupFilters.filter) || ((List.length groups > 0) || (List.length categories > 0)) then
                            Just
                                (li [ class ("jstree-node" ++ foldedClass model.ui.groupFilters item.id) ]
                                    [ i [ class "jstree-icon jstree-ocl", onClick (UpdateGroupFoldedFilters item.id) ] []
                                    , a [ class ("jstree-anchor" {- ++ classFocus -}), onClickPreventDefault (OpenCategoryDetails item.id) ]
                                        [ i [ class ("jstree-icon jstree-themeicon jstree-themeicon-custom" ++ icons) ] []
                                        , span [ class "treeGroupCategoryName" ] [ text item.name ]
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

        templateMain =
            case model.mode of
                Loading ->
                    generateLoadingTable False 5

                LoadingTable ->
                    generateLoadingTable False 5

                GroupTable ->
                    div [ class "main-table" ]
                        [ Html.map RudderTableMsg (Rudder.Table.view model.groupsTable) ]

                ExternalTemplate ->
                    text ""

        modal =
            case model.ui.modal of
                NoModal ->
                    text ""

                ExternalModal ->
                    div [ class "modal-backdrop fade show", style "height" "100%" ] []

        externalTemplate =
            model.mode == ExternalTemplate

        hideWhen : Bool -> String
        hideWhen shouldHide =
            if shouldHide then
                " d-none"

            else
                ""

        {-
           For some reason, table data is not loaded if the user loads the elm app in the state where
           a given group's details are displayed on the right pane, e.g.
           by opening the link rudder/secure/nodeManager/groups#{"groupId":"all-nodes-with-cfengine-agent"}
           (i.e. without visiting rudder/secure/nodeManager/groups, and clicking on the group from the table or the tree).
           The "isDisabled" condition ensures that the export button is enabled if and only if table data is available.
        -}
        isDisabled =
            ((model.groupsTable |> Rudder.Table.getRows |> List.length) == 0) && (model.mode /= GroupTable)

        csvExportButton =
            if isDisabled then
                disabledCsvExportButton

            else
                Html.map RudderTableMsg (Rudder.Table.viewCsvExportButton model.csvExportOptions)
    in
    div [ class "rudder-template" ]
        [ div [ class "template-sidebar sidebar-left" ]
            [ div [ class "sidebar-header" ]
                [ div [ class "header-title" ]
                    [ h1 []
                        [ span [] [ text "Groups" ]
                        ]
                    , if model.ui.hasWriteRights then
                        div [ class "header-buttons" ]
                            --[ button [class "btn btn-default", type_ "button", onClick (GenerateId (\s -> NewCategory s      ))][text "Add category"]
                            --, button [class "btn btn-success", type_ "button", onClick (GenerateId (\s -> NewGroup (GroupId s) ))][text "Create", i[class "fa fa-plus-circle"][]]
                            --]
                            [ button [ id "newItem", class "btn btn-success", type_ "button", onClick OpenModal ] [ text "Create", i [ class "fa fa-plus-circle" ] [] ]
                            , csvExportButton
                            ]

                      else
                        text ""
                    ]
                , div [ class "header-filter" ]
                    [ div [ class "input-group flex-nowrap" ]
                        [ button [ class "input-group-text btn btn-default", type_ "button", onClick (FoldAllCategories model.ui.groupFilters) ] [ span [ class "fa fa-folder fa-folder-open" ] [] ]
                        , input
                            [ type_ "text"
                            , value (Rudder.Filters.getTextValue model.ui.groupFilters.filter)
                            , placeholder "Filter"
                            , class "form-control"
                            , onInput (\s -> UpdateGroupSearchFilters (Rudder.Filters.substring s))
                            ]
                            []
                        , button [ class "input-group-text btn btn-default", type_ "button", onClick (UpdateGroupSearchFilters Rudder.Filters.empty) ] [ span [ class "fa fa-times" ] [] ]
                        ]
                    ]
                ]
            , div [ class "sidebar-body" ]
                [ div [ class "sidebar-list" ]
                    [ if model.mode == Loading then
                        generateLoadingList

                      else
                        div [ class "jstree jstree-default" ]
                            [ ul [ class "jstree-container-ul jstree-children" ]
                                [ case groupTreeCategory model.groupsTree of
                                    Just html ->
                                        html

                                    Nothing ->
                                        div [ class "alert alert-warning" ]
                                            [ i [ class "fa fa-exclamation-triangle" ] []
                                            , text "No groups match your filters."
                                            ]
                                ]
                            ]
                    ]
                ]
            ]
        , div [ class ("template-main" ++ hideWhen externalTemplate) ]
            [ templateMain
            ]

        -- "ajaxItemContainer" is filled by the Lift template (group and category details). It must
        -- stay childless here: two virtual DOMs over the same node desynchronize Elm's patching,
        -- which then crashes on nodes it does not own. Elm only owns its visibility.
        , div [ class ("template-main" ++ hideWhen (not externalTemplate)), id "ajaxItemContainer" ] []
        , modal
        ]


disabledCsvExportButton : Html msg
disabledCsvExportButton =
    div
        [ class "btn-group" ]
        [ button
            [ disabled True
            , attribute "data-bs-toggle" "tooltip"
            , attribute "data-bs-placement" "bottom"
            , attribute
                "data-bs-original-title"
                "The groups tree was not loaded. Close the group details view on the right pane in order to load the groups tree."
            , style "pointer-events" "auto"
            , attribute "aria-label" "Export CSV"
            , class "btn btn-primary export-dropdown-toggle dropdown-toggle"
            ]
            [ span [ class "me-2 fa fa-file-download" ] []
            , span [ class "me-2" ] [ text "Export CSV" ]
            ]
        ]
