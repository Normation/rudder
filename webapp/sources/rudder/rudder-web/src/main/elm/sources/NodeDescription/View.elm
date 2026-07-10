module NodeDescription.View exposing (view)

import Dom exposing (addClass, appendNodeList, element)
import Html exposing (Html, div, i, label, span, text, textarea)
import Html.Attributes exposing (class, for, id, maxlength, name, title)
import Html.Events exposing (onClick, onInput)
import Markdown
import NodeDescription.DataTypes exposing (..)


view : Model -> Html Msg
view model =
    div [] [ descriptionTextArea model ]


descriptionTextArea : Model -> Html Msg
descriptionTextArea model =
    let
        inputClass =
            "form-control col-xs-12"

        textArea =
            case model.editMode of
                HasNodeWriteAuth Write ->
                    textarea
                        [ name "node-description"
                        , id "node-description"
                        , maxlength 255
                        , onInput EditNodeDescription
                        , class inputClass
                        ]
                        [ text model.newDescription ]

                _ ->
                    Dom.render
                        (element "div"
                            |> addClass "markdown"
                            |> appendNodeList (Markdown.toHtml Nothing model.newDescription)
                        )

        editButtons =
            case model.editMode of
                NoNodeWriteAuth ->
                    text ""

                HasNodeWriteAuth Read ->
                    i
                        [ class "fa fa-pencil ms-2 text-primary cursorPointer edit-node-documentation"
                        , onClick ToggleMode
                        , title "Edit node documentation"
                        ]
                        []

                HasNodeWriteAuth Write ->
                    i
                        [ class "fa fa-check ms-2 text-primary cursorPointer save-node-documentation"
                        , onClick ToggleMode
                        , title "Save node documentation"
                        ]
                        []
    in
    div
        [ id "node-description" ]
        [ div
            [ class "row" ]
            [ label
                [ for "node-description-label", class "col-xs-12" ]
                [ span [ class "fw-normal" ]
                    [ text "Documentation"
                    , editButtons
                    ]
                ]
            , div
                [ class "col-xs-12" ]
                [ textArea ]
            ]
        ]
