module Editor.ViewTechniqueCategory exposing (..)

import Editor.ApiCalls exposing (..)
import Editor.DataTypes exposing (..)
import Editor.ViewUtils exposing (categoryIconClass, viewBtnSave)
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (..)
import String.Extra



--
-- This file deals with the UI of one technique category: the right panel shown when a category
-- is selected in the tree on the left.
--


{-| Techniques held by a category and all its sub-categories, the ones that failed to parse
included: they are directories that a delete would take away.
-}
techniquesBelow : Model -> TechniqueCategory -> List String
techniquesBelow model category =
    let
        paths =
            List.map .path (allCategories category)

        held categoryPath =
            List.member categoryPath paths
    in
    List.map .name (List.filter (.category >> held) model.techniques)
        ++ List.map (.id >> .value) (List.filter (.category >> held) model.errors)


subCategoriesBelow : TechniqueCategory -> List TechniqueCategory
subCategoriesBelow category =
    List.filter (\c -> c.path /= category.path) (allCategories category)


deletionCategory : TechniqueCategory -> DeletionCategory
deletionCategory category =
    DeletionCategory category.path category.name (List.map .name (subCategoriesBelow category))


showTechniqueCategory : Model -> CategoryForm -> Html Msg
showTechniqueCategory model catForm =
    let
        parent =
            categoryFormParent catForm.state

        ( creation, category ) =
            case catForm.state of
                EditCategory c ->
                    ( False, c )

                NewSubCategory _ ->
                    ( True, parent )

        kind =
            categoryKind category

        writeRights =
            model.hasWriteRights && categoryKind parent /= StandardCategory

        headerDescription =
            case kind of
                StandardCategory ->
                    "This category is part of the technique library provided by Rudder: it can not be modified."

                UserTechniquesRoot ->
                    "The techniques written with the editor live here. Add sub-categories to organize them."

                UserCategory ->
                    ""

        title =
            if creation then
                [ i [] [ text ("New category in " ++ parent.name) ] ]

            else
                [ text category.name ]

        categoryId =
            if creation then
                categoryDirName catForm.name

            else
                category.id

        -- where the category lives in the configuration repository
        categoryPath =
            if creation then
                parent.path ++ "/" ++ categoryId

            else
                category.path

        heldTechniques =
            techniquesBelow model category

        emptySubCategories =
            subCategoriesBelow category

        isUnchanged =
            not creation && catForm.name == category.name && catForm.description == category.description

        saveChecks =
            [ ( isUnchanged, "There are no modifications to save" )
            , ( String.isEmpty (String.trim catForm.name), "Category name is required" )
            , ( String.isEmpty categoryId, "Category name must have at least one letter, digit, '.', '-' or '_'" )
            ]

        deleteChecks =
            [ ( kind /= UserCategory, "Only the categories you created can be deleted" )
            , ( not (List.isEmpty heldTechniques)
              , String.Extra.pluralize "technique is" "techniques are" (List.length heldTechniques) ++ " still defined in this category"
              )
            ]

        deleteTitle =
            deleteChecks
                |> List.filter (\( check, _ ) -> check)
                |> List.map (\( _, txt ) -> txt)
                |> String.join ".\n"

        idHelp =
            if creation then
                "Derived from the name. It will be the directory of the category: " ++ categoryPath

            else if String.isEmpty category.path then
                -- the library root is the `techniques` directory itself
                "The root of the technique library"

            else
                "The directory of the category in the configuration repository: " ++ categoryPath

        countBadge count =
            span [ class "badge badge-secondary" ]
                [ span [] [ text (String.fromInt count) ] ]

        headerButtons =
            if writeRights then
                [ if creation then
                    text ""

                  else
                    button
                        [ class "btn btn-danger"
                        , type_ "button"
                        , disabled (List.any (\( check, _ ) -> check) deleteChecks || catForm.saving)
                        , Html.Attributes.title deleteTitle
                        , onClick (OpenCategoryDeletionPopup (deletionCategory category))
                        ]
                        [ text "Delete "
                        , i
                            [ class
                                (if catForm.saving then
                                    "fa fa-spinner fa-pulse"

                                 else
                                    "fa fa-times-circle"
                                )
                            ]
                            []
                        ]
                , viewBtnSave catForm.saving saveChecks StartSavingCategory
                ]

            else
                []
    in
    div [ class "main-container" ]
        [ div [ class "main-header" ]
            [ div [ class "header-title" ]
                [ h1 [] (i [ class ("title-icon fa fa-folder" ++ categoryIconClass category) ] [] :: title)
                , div [ class "header-buttons btn-technique" ] headerButtons
                ]
            , if String.isEmpty headerDescription then
                text ""

              else
                div [ class "header-description" ] [ p [] [ text headerDescription ] ]
            ]
        , div [ class "main-details", id "details" ]
            [ div [ class "editForm" ]
                [ div [ class "tab tab-category" ]
                    [ div [ class "row form-group" ]
                        [ label [ for "category-name", class "col-sm-12 control-label" ]
                            [ text "Name"
                            , span [ class "mandatory-param" ] [ text " *" ]
                            ]
                        , div [ class "col-md-8" ]
                            [ input
                                [ readonly (not writeRights)
                                , type_ "text"
                                , id "category-name"
                                , name "name"
                                , class "form-control"
                                , placeholder "Category name"
                                , value catForm.name
                                , onInput (\s -> UpdateCategoryForm { catForm | name = s })
                                ]
                                []
                            ]
                        ]
                    , div [ class "row form-group" ]
                        [ label [ for "category-description", class "col-sm-12 control-label fw-normal" ] [ text "Description" ]
                        , div [ class "col-md-8" ]
                            [ input
                                [ readonly (not writeRights)
                                , type_ "text"
                                , id "category-description"
                                , name "description"
                                , class "form-control"
                                , placeholder "Category description"
                                , value catForm.description
                                , onInput (\s -> UpdateCategoryForm { catForm | description = s })
                                ]
                                []
                            ]
                        ]
                    , div [ class "row form-group" ]
                        [ label [ for "category-id", class "col-sm-12 control-label" ] [ text "Category ID" ]
                        , div [ class "col-md-8" ]
                            [ input [ readonly True, id "category-id", name "category_id", class "form-control", value categoryId ] []
                            , small [ class "form-text text-muted" ] [ text idHelp ]
                            ]
                        ]
                    ]
                , if creation then
                    text ""

                  else
                    -- section titles, like the technique panel "Methods" one
                    div []
                        [ -- the editor only knows its own techniques, so a Rudder category would read 0
                          if kind == StandardCategory then
                            text ""

                          else
                            h5 [] [ text "Techniques", countBadge (List.length heldTechniques) ]
                        , h5 [] [ text "Sub-categories", countBadge (List.length emptySubCategories) ]
                        ]
                , if writeRights && not creation then
                    -- `mt-4`: the button follows the rule drawn by the section title above
                    div [ class "text-center btn-manage mt-4" ]
                        [ button
                            [ class "btn btn-success btn-outline"
                            , type_ "button"
                            , disabled catForm.saving
                            , onClick (StartNewSubCategory category)
                            ]
                            [ text "Create sub-category "
                            , i [ class "fa fa-plus-circle" ] []
                            ]
                        ]

                  else
                    text ""
                ]
            ]
        ]


categoryDeletionPopup : DeletionCategory -> Html Msg
categoryDeletionPopup category =
    div [ class "modal fade show d-block" ]
        [ div [ class "modal-backdrop fade show" ] []
        , div [ class "modal-dialog" ]
            [ div [ class "modal-content" ]
                [ div [ class "modal-header" ]
                    [ h5 [ class "modal-title" ] [ text "Delete category" ]
                    ]
                , div [ class "modal-body" ]
                    [ p []
                        [ text "Are you sure you want to delete category '"
                        , b [] [ text category.name ]
                        , text "' ?"
                        ]
                    , if List.isEmpty category.subCategories then
                        text ""

                      else
                        div []
                            [ p []
                                [ text "The following empty "
                                , text (String.Extra.pluralize "sub-category" "sub-categories" (List.length category.subCategories))
                                , text " will be deleted with it:"
                                ]
                            , ul [] (List.map (\c -> li [] [ text c ]) category.subCategories)
                            ]
                    ]
                , div [ class "modal-footer" ]
                    [ button [ class "btn btn-primary", onClick (ClosePopup Ignore) ]
                        [ text "Cancel "
                        , i [ class "fa fa-arrow-left" ] []
                        ]
                    , button [ class "btn btn-danger", onClick (ClosePopup (StartDeletingCategory category)) ]
                        [ text "Delete "
                        , i [ class "fa fa-times-circle" ] []
                        ]
                    ]
                ]
            ]
        ]
