module Tags.View exposing (..)

import Html exposing (..)
import Html.Attributes exposing (class, disabled, for, id, list, placeholder, type_, value)
import Html.Events exposing (onClick, onInput)
import List
import List.Extra
import String
import Tags.Model exposing (Completion(..), CompletionValue, Model, Tag)
import Tags.Update exposing (Action(..), Msg(..))


displayTags : Tag -> List Tag -> (Completion -> Tag -> msg) -> (Action -> msg) -> Bool -> Bool -> List Tag -> Html msg
displayTags newTag tags addAction removeAction editForm isFilter filterTags =
    let
        displayTag tag =
            let
                alreadyExist =
                    if newTag == tag then
                        " already-exist"

                    else
                        ""

                matchClass =
                    if isFilter then
                        ""

                    else
                        let
                            check =
                                case List.Extra.find (\t -> (String.isEmpty t.key && t.value == tag.value) || (t.key == tag.key && (t.value == tag.value || String.isEmpty t.value))) filterTags of
                                    Just tt ->
                                        " match"

                                    Nothing ->
                                        ""
                        in
                        check
            in
            div [ class ("btn-group btn-group-xs" ++ matchClass) ]
                [ button [ type_ "button", class ("btn btn-default tags-label" ++ alreadyExist), onClick (addAction Key tag) ]
                    [ i [ class "fa fa-tag" ] []
                    , span [ class "tag-key" ]
                        [ if String.isEmpty tag.key then
                            i [ class "fa fa-asterisk" ] []

                          else
                            text tag.key
                        ]
                    , span [ class "tag-separator" ] [ text "=" ]
                    , span [ class "tag-value" ]
                        [ if String.isEmpty tag.value then
                            i [ class "fa fa-asterisk" ] []

                          else
                            text tag.value
                        ]
                    , span [ class "fa fa-search-plus" ] []
                    ]
                , if editForm then
                    button [ type_ "button", class "btn btn-default", onClick (removeAction (Remove tag)) ]
                        [ span [ class "fa fa-times" ] []
                        ]

                  else
                    text ""
                ]
    in
    div
        [ class
            ("tags-container form-group col-sm-12"
                ++ (if List.isEmpty tags then
                        " noTags"

                    else
                        ""
                   )
            )
        ]
        (tags |> List.map displayTag)


type alias TagForm msg =
    { newTag : Tag
    , tags : List Tag
    , completionKeys : List CompletionValue
    , completionValues : List CompletionValue
    , updateAction : Completion -> Tag -> msg
    , addAction : Action -> msg
    , disableBtn : Bool
    }


displayTagForm : TagForm msg -> Html msg
displayTagForm { newTag, tags, completionKeys, completionValues, updateAction, addAction, disableBtn } =
    let
        alreadyExist =
            List.member newTag tags
    in
    div [ class "form-group" ]
        [ label [ for "newTagKey", class "col-sm-12 row" ]
            [ span [] [ text "Tags" ]
            ]
        , div [ class "input-group input-sm col-sm-6" ]
            [ input [ id "newTagKey", class "form-control input-key", list "completion-key-list", placeholder "key", value newTag.key, onInput (\s -> updateAction Key { newTag | key = s }) ] []
            , datalist [ id "completion-key-list" ]
                (List.map (\c -> option [ value c.value ] []) completionKeys)
            , span [ class "input-group-text addon-json" ] [ text "=" ]
            , input [ id "newTagValue", class "form-control input-value", list "completion-val-list", placeholder "value", value newTag.value, onInput (\s -> updateAction Val { newTag | value = s }) ] []
            , datalist [ id "completion-val-list" ]
                (List.map (\c -> option [ value c.value ] []) completionValues)
            , button [ type_ "button", class "btn btn-default", onClick (addAction (Add newTag)), disabled (alreadyExist || disableBtn) ]
                [ span [ class "fa fa-plus" ] []
                ]
            ]
        ]


view : Model -> Html Msg
view { newTag, tags, ui } =
    let
        addBtnDisabled =
            String.isEmpty newTag.key || String.isEmpty newTag.value

        tagForm : TagForm Msg
        tagForm =
            { newTag = newTag
            , tags = tags
            , completionKeys = ui.completionKeys
            , completionValues = ui.completionValues
            , updateAction = UpdateTag
            , addAction = UpdateTags
            , disableBtn = addBtnDisabled
            }
    in
    div [ id "tagApp" ]
        [ div [ id "tagForm" ]
            [ displayTagForm tagForm ]
        , displayTags newTag tags AddToFilter UpdateTags ui.isEditForm False ui.filterTags
        ]
