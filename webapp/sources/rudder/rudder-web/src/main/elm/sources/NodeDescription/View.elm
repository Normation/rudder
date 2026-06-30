module NodeDescription.View exposing (view)

import Html exposing (Html, br, div, input, label, span, text, textarea)
import Html.Attributes exposing (class, disabled, for, id, maxlength, name, readonly, type_, value)
import Html.Events exposing (onClick, onInput)
import NodeDescription.DataTypes exposing (..)


view : Model -> Html Msg
view model =
    div
        []
        [ descriptionTextArea model
        , br [] []
        , saveDescriptionButton model
        ]


descriptionTextArea : Model -> Html Msg
descriptionTextArea model =
    let
        inputClass =
            "form-control col-xs-12"

        attributes =
            [ name "node-description"
            , id "node-description-label"
            ]
                ++ (if model.nodeWrite then
                        [ maxlength 255
                        , onInput EditNodeDescription
                        , class inputClass
                        ]

                    else
                        [ class inputClass
                        , disabled True
                        , readonly True
                        ]
                   )
    in
    div
        [ id "node-description" ]
        [ div
            [ class "row" ]
            [ label
                [ for "node-description-label", class "col-xs-12" ]
                [ span [ class "fw-normal" ] [ text "Description" ] ]
            , div
                [ class "col-xs-12" ]
                [ textarea attributes [ text model.newDescription ] ]
            ]
        ]


saveDescriptionButton : Model -> Html Msg
saveDescriptionButton model =
    if model.nodeWrite then
        let
            attrList =
                [ value "Update"
                , class "btn btn-default"
                , type_ "button"
                , onClick SubmitDescription
                , disabled <| (model.currentDescription == model.newDescription)
                ]
        in
        div [ id "save-node-description" ]
            [ input
                attrList
                [ text "Update" ]
            ]

    else
        div [ id "save-node-description" ] []
