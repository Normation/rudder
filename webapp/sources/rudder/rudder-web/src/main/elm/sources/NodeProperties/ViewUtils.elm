module NodeProperties.ViewUtils exposing (..)

import Html exposing (..)
import Html.Attributes exposing (id, class, href, type_, attribute, disabled, for, checked, selected, value, title, placeholder)
import Html.Events exposing (onClick, onInput)
import Maybe.Extra exposing (isJust)
import Dict exposing (Dict)
import Json.Encode exposing (..)

import NodeProperties.DataTypes exposing (..)
import NodeProperties.ApiCalls exposing (deleteProperty)

displayJsonValue : JsonValue -> String
displayJsonValue value  =
  case value of
    JsonString s  -> s
    _ -> encode 2 (encodeJsonValue value)

encodeJsonValue : JsonValue -> Value
encodeJsonValue value =
  case value of
    JsonString s  -> string s
    JsonInt i     -> int i
    JsonFloat f   -> float f
    JsonBoolean b -> bool b
    JsonNull      -> null
    JsonArray a   -> list encodeJsonValue a
    JsonObject o  -> (dict identity encodeJsonValue o)

displayNodePropertyRow : Model -> List (Html Msg)
displayNodePropertyRow model =
  let
    properties = model.properties

    propertyRow : Property -> Html Msg
    propertyRow p =
      let
        (format, formatTxt) = case p.value of
            JsonString s  -> (StringFormat, "String")
            _ -> (JsonFormat, "JSON")

        defaultEditProperty = EditProperty p.name (displayJsonValue p.value) format True True

        editedProperty = Dict.get p.name model.ui.editedProperties
        providerBadge = case p.provider of
          Just pr ->
            let
              pTitle = case pr of
                "inherited" -> "This property is inherited from these group(s) or global parameter: <div>" ++ (Maybe.withDefault "" p.hierarchy) ++ "</div>."
                "overriden" -> "This property is overridden on this object and was inherited from these group(s) or global parameter: <div>" ++ (Maybe.withDefault "" p.hierarchy) ++ "</div>."
                _ -> "This property is managed by its provider <b>‘" ++ pr ++ "</b>’, and can not be modified manually. Check Rudder’s settings to adjust this provider’s configuration."
            in
              span
              [ class "rudder-label label-provider label-sm"
              , attribute "data-toggle" "tooltip"
              , attribute "data-placement" "right"
              , attribute "data-html" "true"
              , attribute "data-container" "body"
              , title pTitle
              ] [ text pr ]
          Nothing -> text ""
      in
        case editedProperty of
          Nothing ->
            tr []
            [ td []
              [ div[]
                [ text p.name
                , providerBadge
                ]
              ]
            , td []
              [ div []
                [ text formatTxt ]
              ]
            , td [class "property-value"]
              [ div []
                [ div [class "{{'value-container container-' + $index}}"] --  onclick="$(this).toggleClass('toggle')" ng-class "{'show-more':isTooLong(property)}">
                  [ pre [class "json-beautify"][ text (displayJsonValue p.value) ]
                  ]
                , span [class "toggle-icon"][]
                , button [class "btn btn-xs btn-default btn-clipboard", attribute "data-clipboard-text" "{{formatContent(property)}}", attribute "data-toggle" "tooltip", attribute "data-placement" "left", attribute "data-container" "html" , title "Copy to clipboard"]
                  [ i [class "ion ion-clipboard"][]
                  ]
                ]
              ]
            , td [class "text-center default-actions"] -- ng-if="$parent.hasEditRight"
              [ div [] -- ng-if="!isEdited(property.name) && property.rights !== 'read-only' && (property.provider === undefined || property.provider === 'overridden')">
                [ span [ class "action-icon fa fa-pencil", title "Edit", onClick (ToggleEditProperty p.name defaultEditProperty False)][] -- ng-click="editProperty(property)"
                , span [ class "action-icon fa fa-times text-danger", title "Delete", onClick (CallApi (deleteProperty (EditProperty p.name "" StringFormat True True)))][] -- ng-click="popupDeletion(property.name, $index)"
                ]
              ]
            ]
          Just eP ->
            tr []
            [ td [class "is-edited"]
              [ div []
                [ input [type_ "text", class "form-control input-sm", value eP.name, onInput (\s -> UpdateProperty p.name {eP | name = s}) ][]
                -- , small [class "text-danger"][ text "This name is already used by another property" ] -- ng-if="editedProperties[property.name].new.alreadyUsed">
                ]
              ]
            , td [class "is-edited"]
              [ div [class "format-container"]
                [ button [type_ "button", class "btn btn-default btn-sm dropdown-toggle", attribute "data-toggle" "dropdown"]
                  [ text (if eP.format == JsonFormat then "JSON" else "String")
                  , span [ class "caret"][]
                  ]
                , ul [class "dropdown-menu"]
                  [ li[][ a[ onClick (UpdateProperty p.name {eP | format = StringFormat}) ][ text "String" ]] -- ng-click="changeFormat(property.name, false)"
                  , li[][ a[ onClick (UpdateProperty p.name {eP | format = JsonFormat  }) ][ text "JSON"   ]] -- ng-click="changeFormat(property.name, true)"
                  ]
                ]
              ]
            , td [class "is-edited"]
              [ div []
                [ textarea [placeholder "Value", attribute "msd-elastic" "", attribute "rows" "1", class "form-control input-sm input-value", value eP.value, onInput (\s -> UpdateProperty p.name {eP | value = s}) ][]
                -- , small [class "text-danger"] [ text "JSON check is enabled, but the value format is invalid" ] -- ng-if="!editedProperties[property.name].new.isValid"
                ]
              ]
            , td [class "text-center edit-actions is-edited" ] -- ng-if="$parent.hasEditRight"
              [ div [] -- ng-if="isEdited(property.name) && property.rights !== 'read-only'">
                [ span [ class "action-icon glyphicon glyphicon-share-alt cancel-icon", title "Cancel", onClick (ToggleEditProperty p.name eP False)][]
                , span [ class "action-icon fa fa-check text-success", title "Save", onClick (ToggleEditProperty p.name eP True)][]
                ]
              ]
            ]
  in
    properties
    |> List.map (\p -> propertyRow p)