module TechniqueVersion.ViewUtils exposing (..)

import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onClick, onInput, custom, onCheck)
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
          [ class "glyphicon glyphicon-info-sign text-danger deprecatedTechniqueIcon bsTooltip"
          , attribute "data-toggle" "tooltip"
          , attribute "data-placement" "top"
          , attribute "data-html" "true"
          , title ("Deprecated: " ++ technique.deprecationMessage)
          ][]
          else
          text ""
          )
        ]
      , td[]
        [ text technique.multiVersionSupport
        , span
          [ class "fa fa-question-circle bsTooltip multiversion-icon"
          , attribute "data-toggle" "tooltip"
          , attribute "data-placement" "top"
          , attribute "data-html" "true"
          , title technique.mvsMessage
          ][]
        ]
      , td[]
        [ ( if technique.classicSupport then
          span
          [ class "fa fa-gear bsTooltip"
          , attribute "data-toggle" "tooltip"
          , attribute "data-placement" "top"
          , attribute "data-html" "true"
          , title "This Technique version is compatible with the <b>classic</b> agent."
          ][]
          else
          text ""
          )
        , ( if technique.dscSupport then
          span
          [ class "dsc-icon bsTooltip"
          , attribute "data-toggle" "tooltip"
          , attribute "data-placement" "top"
          , attribute "data-html" "true"
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
          [ text "Create Directive" ]
        ]
        else
        text ""
      ]
  in
    techniques |> List.map row

-- WARNING:
--
-- Here we are building an html snippet that will be placed inside an attribute, so
-- we can't easily use the Html type as there is no built-in way to serialize it manually.
-- This means it will be vulnerable to XSS on its parameters (here the description).
--
-- We resort to escaping it manually here.
buildTooltipContent : String -> String -> String
buildTooltipContent title content =
  let
    headingTag = "<h4 class='tags-tooltip-title'>"
    contentTag = "</h4><div class='tooltip-inner-content'>"
    closeTag   = "</div>"
    escapedTitle = htmlEscape title
    escapedContent = htmlEscape content
  in
    headingTag ++ escapedTitle ++ contentTag ++ escapedContent ++ closeTag

htmlEscape : String -> String
htmlEscape s =
  String.replace "&" "&amp;" s
    |> String.replace ">" "&gt;"
    |> String.replace "<" "&lt;"
    |> String.replace "\"" "&quot;"
    |> String.replace "'" "&#x27;"
    |> String.replace "\\" "&#x2F;"
