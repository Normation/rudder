module TechniqueVersion.ViewUtils exposing (..)

import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onClick)
import List

import TechniqueVersion.DataTypes exposing (..)


displayTechniqueRow : Model -> List (Html Msg)
displayTechniqueRow model =
  let
    techniques = model.techniques
    row : Technique -> Html Msg
    row technique =
      if technique.isDeprecated && not model.ui.displayDeprecated then
      text ""
      else
      tr[]
      [ td[]
        [ text technique.version
        , ( if technique.isDeprecated then
          span
          [ class "fa fa-info-circle text-danger deprecatedTechniqueIcon"
          , attribute "data-bs-toggle" "tooltip"
          , attribute "data-bs-placement" "top"
          , attribute "data-bs-html" "true"
          , title ("Deprecated: " ++ technique.deprecationMessage)
          ][]
          else
          text ""
          )
        ]
      , td[]
        [ text technique.multiVersionSupport
        , span
          [ class "fa fa-question-circle multiversion-icon"
          , attribute "data-bs-toggle" "tooltip"
          , attribute "data-bs-placement" "top"
          , attribute "data-bs-html" "true"
          , title technique.mvsMessage
          ][]
        ]
      , td[]
        [ ( if technique.classicSupport then
          span
          [ class "fa fa-gear"
          , attribute "data-bs-toggle" "tooltip"
          , attribute "data-bs-placement" "top"
          , attribute "data-bs-html" "true"
          , title "This Technique version is compatible with the <b>classic</b> agent."
          ][]
          else
          text ""
          )
        , ( if technique.dscSupport then
          span
          [ class "dsc-icon"
          , attribute "data-bs-toggle" "tooltip"
          , attribute "data-bs-placement" "top"
          , attribute "data-bs-html" "true"
          , title "This Technique version is compatible with the <b class='dsc'>Window</b> agent."
          ][]
          else
          text ""
          )
        ]
      , td[]
        [ text technique.acceptationDate
        ]
      , if model.ui.hasWriteRights then
        td[]
        [ button [type_ "button", class "btn btn-success new-icon btn-xs", onClick (Create technique.version)]
          [ text "Create" ]
        ]
        else
        text ""
      ]
  in
    techniques |> List.map row
