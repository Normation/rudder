module TechniqueVersion.View exposing (..)

import Html exposing (..)
import Html.Attributes exposing (class, type_, value, checked, id, disabled, for, attribute, title)
import Html.Events exposing (onClick, onCheck)
import List
import List.Extra exposing (last)
import String

import TechniqueVersion.DataTypes exposing (..)
import TechniqueVersion.ViewUtils exposing (..)


view : Model -> Html Msg
view model =
  div [id "techniqueVersion"]
  [ h4[][text "Available versions"]
  , table [id "versionTable"]
    [ thead[]
      [ tr[]
        [ th[][text "Version"]
        , th[][text "Instances"]
        , th[][text "Agent type"]
        , th[][text "Last updated on"]
        , if model.ui.hasWriteRights then th[][text "Use this version"] else text ""
        ]
      ]
    , tbody[](displayTechniqueRow model)
    ]
  , div [class "checkbox-group"]
    [ input [id "displayDeprecation", type_ "checkbox", checked model.ui.displayDeprecated , onCheck (\b -> ToggleDeprecated b)][]
    , label [for "displayDeprecation"][text "Display deprecated Technique versions"]
    ]
  , ( if model.ui.hasWriteRights then
    let
      createAction = case last model.techniques of
        Just t  -> (Create t.version)
        Nothing -> (Ignore "Unknwown technique version")
    in
      div [class "space-top"]
      [ button [type_ "button", id "addButton", class "btn btn-success new-icon", onClick createAction ] -- ng-click "techniques[techniques.length-1].action()"
        [ text "Create with latest version"]
      ]
    else
    text ""
    )
  ]