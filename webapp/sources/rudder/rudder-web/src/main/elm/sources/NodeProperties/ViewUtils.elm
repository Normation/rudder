module NodeProperties.ViewUtils exposing (..)

import Html exposing (..)
import Html.Attributes exposing (id, class, href, type_, attribute, disabled, for, checked, selected, value, title)
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

        valueFormat = case p.value of
          JsonString s  -> "String"
          _ -> "JSON"

      in
        tr []
        [ td [] -- ng-class "{'is-edited':isEdited(property.name)}"
          [ div[] -- ng-if="!isEdited(property.name)"
            [ text p.name, providerBadge ]
            {--
            , div [] -- ng-if="isEdited(property.name)">
              [ input [type_ "text", class "form-control input-sm" ][] -- ng-model="editedProperties[property.name].new.name">
              , small [class "text-danger"][ text "This name is already used by another property" ] -- ng-if="editedProperties[property.name].new.alreadyUsed">
              ]
            --}
          ]
        , td [] -- ng-class "{'is-edited':isEdited(property.name)}"
          [ div [] -- ng-if="!isEdited(property.name)"
            [ text valueFormat ]
            ]
            {--
          , div [] -- ng-if="isEdited(property.name)" class "format-container">
            [ button [type_ "button", class "btn btn-default btn-sm dropdown-toggle", attribute "data-toggle" "dropdown"]
              [ text "{{editedProperties[property.name].new.checkJson ? 'JSON' : 'String'}}"
              , span [ class "caret"][]
              ]
            , ul [class "dropdown-menu"]
              [ li[][a[][ text "String" ]] -- ng-click="changeFormat(property.name, false)"
              , li[][a[][ text "JSON"   ]] -- ng-click="changeFormat(property.name, true)"
              ]
            ]
            --}
        , td [] -- ng-class "{'property-value':!isEdited(property.name), 'is-edited':isEdited(property.name)}"
          [ div [] -- ng-if="!isEdited(property.name)">
            [ div [class "{{'value-container container-' + $index}}"] --  onclick="$(this).toggleClass('toggle')" ng-class "{'show-more':isTooLong(property)}">
              [ pre [class "json-beautify"][ text (displayJsonValue p.value) ]
              ]
            , span [class "toggle-icon"][]
            , button [class "btn btn-xs btn-default btn-clipboard", attribute "data-clipboard-text" "{{formatContent(property)}}", attribute "data-toggle" "tooltip", attribute "data-placement" "left", attribute "data-container" "html" , title "Copy to clipboard"]
              [ i [class "ion ion-clipboard"][]
              ]
            ]
          {--
          , div [] -- ng-if="isEdited(property.name)"
            [ textarea [placeholder "Value", attribute "msd-elastic" "", attribute "rows" "1", class "form-control input-sm input-value"][] -- ng-model="editedProperties[property.name].new.value"
            , small [class "text-danger"] -- ng-if="!editedProperties[property.name].new.isValid"
              [ text "JSON check is enabled, but the value format is invalid" ]
            ]
          --}
          ]
        , td [class "text-center"] -- ng-if="$parent.hasEditRight" ng-class "{'default-actions':!isEdited(property.name), 'edit-actions is-edited':isEdited(property.name)}"
          [ div [] -- ng-if="!isEdited(property.name) && property.rights !== 'read-only' && (property.provider === undefined || property.provider === 'overridden')">
            [ span [ class "action-icon fa fa-pencil", title "Edit"][] -- ng-click="editProperty(property)"
            , span [ class "action-icon fa fa-times text-danger", title "Delete", onClick (CallApi (deleteProperty (EditProperty p.name "" StringFormat True True)))][] -- ng-click="popupDeletion(property.name, $index)"
            ]
          {--
          , div [] -- ng-if="isEdited(property.name) && property.rights !== 'read-only'">
            [ span [ class "action-icon glyphicon glyphicon-share-alt cancel-icon", title "Cancel"][] -- ng-click="cancelEdit(property.name)"
            , span [ class "action-icon fa fa-check text-success", title "Save"][] -- ng-click="saveEdit(property.name, $index)"
            ]
          --}
          ]
        ]
  in
    properties
    |> List.map (\p -> propertyRow p)