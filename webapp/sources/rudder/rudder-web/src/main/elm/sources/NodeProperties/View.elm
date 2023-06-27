module NodeProperties.View exposing (..)

import Html exposing (..)
import Html.Attributes exposing (id, class, href, type_, disabled, for, checked, selected, value, style, attribute, placeholder, name, title, colspan)
import Html.Events exposing (onClick, onInput)
import Maybe.Extra exposing (isJust)
import Dict

import NodeProperties.DataTypes exposing (..)
import NodeProperties.ViewUtils exposing (..)
import NodeProperties.ApiCalls exposing (getNodeProperties)


view : Model -> Html Msg
view model =
  if model.ui.hasReadRights then
    let
      newProperty = model.newProperty
      isJson      = newProperty.format == JsonFormat
      checkPristineName = not newProperty.pristineName
      checkEmptyName   = String.isEmpty newProperty.name
      checkUsedName    = List.member newProperty.name (List.map .name model.properties)
      checkEmptyVal    = String.isEmpty newProperty.value
      checkPristineVal = not newProperty.pristineValue
      checks  = [checkEmptyName, checkUsedName, checkEmptyVal]
      filters = model.ui.filters
    in
      div[][
      div [class "row", id "nodeProp"]
      [ ( if model.ui.hasWriteRights then
        div[class "col-lg-7 col-md-8 col-xs-12 add-prop-form"]
        [ label[for "newPropName"][text "Add a new property:"]
        , table[id "addPropTable"]
          [ tbody[]
            [ tr[]
              [ td [class "form-group"] -- ng-class="{'has-error': alreadyUsed || newPropForm.newPropName.$error.mandatory && newPropForm.newPropName.$dirty}"
                [ input
                  [ placeholder "Name"
                  , class "form-control input-sm input-key"
                  , id "newPropName"
                  , name "newPropName"
                  , value newProperty.name
                  , onInput (\s -> UpdateNewProperty { newProperty | name = s, pristineName = False})
                  ][]
                ]
              , td []
                [ span [class "input-group-addon addon-json"][text "="]
                ]
              , td [class "form-group"] -- ng-class="{'has-error': (newPropForm.newPropValue.$error.mandatory && newPropForm.newPropValue.$dirty) || !isValid}"
                [ textarea
                  [ placeholder "Value"
                  , class "form-control input-sm input-value"
                  , name "newPropValue"
                  , value newProperty.value
                  , onInput (\s -> UpdateNewProperty { newProperty | value = s, pristineValue = False})
                  ][]
                ]
              , td [class "json-check-col"]
                [ div[]
                  [ button [type_ "button", class "btn btn-default dropdown-toggle", attribute "data-toggle" "dropdown"]
                    [ text (if isJson then "JSON " else "String ")
                  , span [class "caret"][]
                    ]
                  , ul [class "dropdown-menu"]
                    [ li[][ a[onClick (UpdateNewProperty { newProperty | format = StringFormat })] [text "String"] ]
                    , li[][ a[onClick (UpdateNewProperty { newProperty | format = JsonFormat   })] [text "JSON"  ] ]
                    ]
                  ]
                ]
              , td[]
                [ button [type_ "button",  class "btn btn-success btn-sm", disabled (List.any (\c -> c == True) checks), onClick AddProperty] -- ng-click="addProperty()"
                  [ span [class "fa fa-plus"] []
                  ]
                ]
              ]
            ]
          ]
        , div[class "errors"]
          [ (if (checkEmptyName && checkPristineName) then div [class "text-danger"][text "Name is required"] else text "")
          , (if checkUsedName then div [class "text-danger"][text "This name is already used by another property"] else text "")
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