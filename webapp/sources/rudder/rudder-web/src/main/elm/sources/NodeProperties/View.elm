module NodeProperties.View exposing (..)

import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onClick, onInput)

import NodeProperties.DataTypes exposing (..)
import NodeProperties.ViewUtils exposing (..)
import NodeProperties.ApiCalls exposing (getNodeProperties)


view : Model -> Html Msg
view model =
  if model.ui.hasNodeRead then
    let
      newProperty = model.newProperty
      isJson      = newProperty.format == JsonFormat
      trimmedName = String.trim newProperty.name
      trimmedVal  = String.trim newProperty.value
      checkPristineName    = not newProperty.pristineName
      checkEmptyName       = String.isEmpty trimmedName
      checkAlreadyUsedName = checkUsedName trimmedName model.properties
      checkEmptyVal        = String.isEmpty trimmedVal
      checkPristineVal     = not newProperty.pristineValue
      checkFormatVal       = newProperty.errorFormat
      checks  = [checkEmptyName, checkAlreadyUsedName, checkEmptyVal, checkFormatVal]
      filters = model.ui.filters
    in
      div[]
      [ div [class "row", id "nodeProp"]
        [ div [ class "col-xs-12" ] [
            div [ class "alert alert-info" ] [
                text "These are node properties that can be used in directive inputs with the "
              , b [ class "code" ] [ text "${node.properties[NAME]}" ]
              , text " syntax."
              ]
          ]
        , ( if model.ui.hasNodeWrite then
          div[class "col-lg-7 col-md-8 col-xs-12 add-prop-form"]
          [ label[for "newPropName"][text "Add a new property:"]
          , table[id "addPropTable"]
            [ tbody[]
              [ tr[]
                [ td [class ("form-group" ++ if ((checkEmptyName && checkPristineName) || checkAlreadyUsedName) then " has-error" else "")]
                  [ input
                    [ placeholder "Name"
                    , class "form-control input-key"
                    , id "newPropName"
                    , name "newPropName"
                    , value newProperty.name
                    , onInput (\s -> UpdateNewProperty { newProperty | name = s, pristineName = False})
                    -- to deactivate plugin "Grammarly" or "Language Tool" from
                    -- adding HTML that make disapear textarea (see  https://issues.rudder.io/issues/21172)
                    , attribute "data-gramm" "false"
                    , attribute "data-gramm_editor" "false"
                    , attribute "data-enable-grammarly" "false"
                    , spellcheck False
                    ][]
                  ]
                , td []
                  [ span [class "input-group-text addon-json"][text "="]
                  ]
                , td [class ("form-group" ++ if ((checkEmptyVal && checkPristineVal) || checkFormatVal) then " has-error" else "")]
                  [ textarea
                    [ placeholder "Value"
                    , class "form-control input-value auto-resize code"
                    , attribute "rows" "1"
                    , name "newPropValue"
                    , value newProperty.value
                    , onInput (\s -> UpdateNewProperty { newProperty | value = s, pristineValue = False, errorFormat = False})
                    , attribute "data-gramm" "false"
                    , attribute "data-gramm_editor" "false"
                    , attribute "data-enable-grammarly" "false"
                    , spellcheck False
                    ][]
                  ]
                , td [class "json-check-col"]
                  [ div[]
                    [ button [type_ "button", class "btn btn-default dropdown-toggle", attribute "data-bs-toggle" "dropdown"]
                      [ text (if isJson then "JSON " else "String ")
                      , span [class "caret"][]
                      ]
                    , ul [class "dropdown-menu"]
                      [ li[][ a[class "dropdown-item", onClick (UpdateNewProperty { newProperty | format = StringFormat })] [text "String"] ]
                      , li[][ a[class "dropdown-item", onClick (UpdateNewProperty { newProperty | format = JsonFormat   })] [text "JSON"  ] ]
                      ]
                    ]
                  ]
                , td[]
                  [ button [type_ "button",  class "btn btn-success", disabled (List.any (\c -> c == True) checks), onClick AddProperty]
                    [ span [class "fa fa-plus"] []
                    ]
                  ]
                ]
              ]
            ]
          , div[class "errors"]
            [ (if (checkEmptyName && checkPristineName) then div [class "text-danger"][text "Name is required"] else text "")
            , (if checkAlreadyUsedName then div [class "text-danger"][text "This name is already used by another property"] else text "")
            , (if (checkEmptyVal && checkPristineVal)  then div [ class "text-danger"][text "Value is required"] else text "")
            ]
          ]
          else
            div[][]
          )
        -- Properties Table
        , div [class "col-xs-12 tab-table-content"]
          [ div [class "table-header"]
            [ input [type_ "text", placeholder "Filter", class "input-sm form-control", onInput (\s -> UpdateTableFilters {filters | filter = s})][]
            , button [class "btn btn-default", onClick (CallApi getNodeProperties)] [ i [class "fa fa-refresh"][] ]
            ]
            , div [class "table-container"]
              [ table [class "no-footer dataTable", id "nodePropertiesTab"]
                [ thead[]
                  [ tr [class "head"]
                    [ th [class (thClass model.ui.filters Name   ), onClick (UpdateTableFilters (sortTable filters Name   ))][ text "Name"   ]
                    , th [class (thClass model.ui.filters Format ), onClick (UpdateTableFilters (sortTable filters Format ))][ text "Format" ]
                    , th [class (thClass model.ui.filters Value  ), onClick (UpdateTableFilters (sortTable filters Value  ))][ text "Value"  ]
                    , th[class "sorting"] [text "Actions" ]
                    ]
                  ]
                , tbody[]
                  ( if List.isEmpty model.properties then
                    [ tr[]
                      [ td [class "empty", colspan 4]
                        [ i [class "fa fa-exclamation-triangle"][]
                        , text "This node has no properties"
                        ]
                      ]
                    ]
                    else
                    displayNodePropertyRow model
                  )
                ]
              ]
            , div [class "accountGrid_pagination, paginatescala"]
              [ div [id "accountGrid_paginate_area"][]
              ]
            ]
          ]
    , modalDelete model
    ]
  else
    text "No rights"
