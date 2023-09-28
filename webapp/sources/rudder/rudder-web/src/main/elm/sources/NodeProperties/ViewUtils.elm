module NodeProperties.ViewUtils exposing (..)

import Html exposing (..)
import Html.Attributes exposing (id, class, href, type_, attribute, disabled, for, checked, selected, value, title, placeholder, style, tabindex    )
import Html.Events exposing (onClick, onInput)
import Json.Decode exposing (decodeValue)
import Maybe.Extra exposing (isJust)
import Dict exposing (Dict)
import Json.Encode exposing (..)
import NaturalOrdering as N exposing (compare)
import SyntaxHighlight exposing (useTheme, gitHub, json, toInlineHtml)

import NodeProperties.DataTypes exposing (..)
import NodeProperties.ApiCalls exposing (deleteProperty)


searchString : String -> String
searchString str = str
  |> String.toLower
  |> String.trim

filterSearch : String -> List String -> Bool
filterSearch filterString searchFields =
  let
    -- Join all the fields into one string to simplify the search
    stringToCheck = searchFields
      |> String.join "|"
      |> String.toLower
  in
    String.contains (searchString filterString) stringToCheck

thClass : TableFilters -> SortBy -> String
thClass tableFilters sortBy =
  if sortBy == tableFilters.sortBy then
    case  tableFilters.sortOrder of
      Asc  -> "sorting_asc"
      Desc -> "sorting_desc"
  else
    "sorting"

sortTable : TableFilters -> SortBy -> TableFilters
sortTable tableFilters sortBy =
  let
    order =
      case tableFilters.sortOrder of
        Asc -> Desc
        Desc -> Asc
  in
    if sortBy == tableFilters.sortBy then
      { tableFilters | sortOrder = order}
    else
      { tableFilters | sortBy = sortBy, sortOrder = Asc}


getFormat : Property -> ValueFormat
getFormat pr =
  case decodeValue Json.Decode.string pr.value of
    Ok _  ->  StringFormat
    Err _ -> JsonFormat

getFormatTxt : ValueFormat -> String
getFormatTxt format =
    case format of
      StringFormat -> "String"
      JsonFormat -> "JSON"

getSortFunction : Model -> Property -> Property -> Order
getSortFunction model p1 p2 =
  let
    order = case model.ui.filters.sortBy of
      Name    -> N.compare p1.name p2.name
      Format  ->
        let
          formatP1 = getFormat p1
          formatP2 = getFormat p2
        in
          case (formatP1,formatP2) of
            (StringFormat,StringFormat) -> EQ
            (JsonFormat, JsonFormat) -> EQ
            (StringFormat, JsonFormat) -> GT
            (JsonFormat, StringFormat) -> LT
      Value   -> N.compare (Json.Encode.encode 0 p1.value) (Json.Encode.encode 0 p2.value)
  in
    if model.ui.filters.sortOrder == Asc then
      order
    else
      case order of
        LT -> GT
        EQ -> EQ
        GT -> LT

searchField : Property -> List String
searchField property =
  [ property.name
  , (Json.Encode.encode 0 property.value)
  ]

checkUsedName : String -> List Property -> Bool
checkUsedName name properties =
  properties
    |> List.any (\p -> case p.provider of
      Just provider -> provider /= "inherited" && p.name == name
      Nothing -> p.name == name
    )

displayJsonValue : Value -> String
displayJsonValue value =
    encode 2 value

displayNodePropertyRow : Model -> List (Html Msg)
displayNodePropertyRow model =
  let
    properties = model.properties

    filteredProperties = properties
      |> List.filter (\pp -> filterSearch model.ui.filters.filter (searchField pp))
      |> List.sortWith (getSortFunction model)

    propertyRow : Property -> Html Msg
    propertyRow p =
      let
        format = getFormat p
        formatTxt = getFormatTxt format
        defaultEditProperty = EditProperty p.name (displayJsonValue p.value) format True True False

        editedProperty = Dict.get p.name model.ui.editedProperties
        (providerBadge, editRight) = case p.provider of
          Just pr ->
            let
              pTitle = case pr of
                "inherited" -> "This property is inherited from these group(s) or global parameter: <div>" ++ (Maybe.withDefault "" p.hierarchy) ++ "</div>."
                "overridden" -> "This property is overridden on this object and was inherited from these group(s) or global parameter: <div>" ++ (Maybe.withDefault "" p.hierarchy) ++ "</div>."
                _ -> "This property is managed by its provider <b>‘" ++ pr ++ "</b>’, and can not be modified manually. Check Rudder’s settings to adjust this provider’s configuration."
            in
              (span
              [ class "rudder-label label-provider label-sm bs-tooltip"
              , attribute "data-toggle" "tooltip"
              , attribute "data-placement" "right"
              , attribute "data-html" "true"
              , attribute "data-container" "body"
              , title pTitle
              ] [ text pr ]
              , (pr == "overridden")
              )
          Nothing -> (text "", True)

        isTooLong : Value -> Bool
        isTooLong value =
          let
            str = displayJsonValue value
            nbLines = List.length (String.lines str)
          in
            nbLines > 3
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
                [ div [class ("value-container" ++ (if List.member p.name model.ui.showMore then " toggle" else "") ++ (if isTooLong p.value then " show-more" else "") ), onClick (ShowMore p.name) ]
                  [ pre [class "json-beautify"][ useTheme gitHub,
                                                 json (displayJsonValue p.value)
                                                       |> Result.map toInlineHtml
                                                       |> Result.withDefault ( text (displayJsonValue p.value) )
                                               ]
                  
                  ]
                , span [class "toggle-icon"][]
                , button [class "btn btn-xs btn-default btn-clipboard", title "Copy to clipboard", onClick (Copy (displayJsonValue p.value))]
                  [ i [class "ion ion-clipboard"][]
                  ]
                ]
              ]
            , td [class "text-center default-actions"]
              [ (if (editRight) then
                div [] -- ng-if="!isEdited(property.name) && property.rights !== 'read-only' && (property.provider === undefined || property.provider === 'overridden')">
                [ span [ class "action-icon fa fa-pencil", title "Edit", onClick (ToggleEditProperty p.name defaultEditProperty False)][] -- ng-click="editProperty(property)"
                , span [ class "action-icon fa fa-times text-danger", title "Delete", onClick (ToggleEditPopup (Deletion p.name))][]
                ]
                else
                text ""
                )
              ]
            ]
          Just eP ->
            let
              trimmedName = String.trim eP.name
              trimmedVal  = String.trim eP.value
              checkPristineName    = not eP.pristineName
              checkEmptyName       = String.isEmpty trimmedName
              checkAlreadyUsedName = trimmedName /= p.name && checkUsedName trimmedName model.properties
              checkEmptyVal        = String.isEmpty trimmedVal
              checkPristineVal     = not eP.pristineValue
            in
            tr []
            [ td [class "is-edited"]
              [ div []
                [ input [type_ "text", class "form-control input-sm", value eP.name, onInput (\s -> UpdateProperty p.name {eP | name = s, pristineName = False}) ][]
                , ( if checkAlreadyUsedName then small [class "text-danger"][ text "This name is already used by another property" ] else text "" )
                , ( if (checkEmptyName && checkPristineName) then small [class "text-danger"][text "Name is required"] else text "" )
                ]
              ]
            , td [class "is-edited"]
              [ div [class "format-container"]
                [ button [type_ "button", class "btn btn-default btn-sm dropdown-toggle", attribute "data-toggle" "dropdown"]
                  [ text (if eP.format == JsonFormat then "JSON" else "String")
                  , span [ class "caret"][]
                  ]
                , ul [class "dropdown-menu"]
                  [ li[][ a[ onClick (UpdateProperty p.name {eP | format = StringFormat}) ][ text "String" ]]
                  , li[][ a[ onClick (UpdateProperty p.name {eP | format = JsonFormat  }) ][ text "JSON"   ]]
                  ]
                ]
              ]
            , td [class "is-edited"]
              [ div []
                [ textarea [placeholder "Value", attribute "msd-elastic" "", attribute "rows" "1", class "form-control input-sm input-value auto-resize code", value eP.value, onInput (\s -> UpdateProperty p.name {eP | value = s, pristineValue = False}) ][]
                , (if (checkEmptyVal && checkPristineVal)  then small [class "text-danger"][text "Value is required"] else text "")
                ]
              ]
            , td [class "text-center edit-actions is-edited" ]
              [ div []
                [ span [ class "action-icon glyphicon glyphicon-share-alt cancel-icon", title "Cancel", onClick (ToggleEditProperty p.name eP False)][]
                , span [ class "action-icon fa fa-check text-success", title "Save", onClick (ToggleEditProperty p.name eP True)][]
                ]
              ]
            ]
  in
    filteredProperties
    |> List.map (\p -> propertyRow p)

modalDelete : Model -> Html Msg
modalDelete model =
  case model.ui.modalState of
    NoModal -> text ""
    Deletion name ->
      div [ tabindex -1, class "modal fade in", style "z-index" "1050", style "display" "block" ]
      [ div [class "modal-backdrop fade in"][]
      , div [ class "modal-dialog" ]
        [ div [ class "modal-content" ]
          [ div [ class "modal-header ng-scope" ]
            [ h3 [ class "modal-title" ] [ text "Delete property"] ]
          , div [ class "modal-body" ]
            [ text ("Are you sure you want to delete property '"++ name ++"'?") ]
          , div [ class "modal-footer" ]
            [ button [ class "btn btn-default", onClick (ClosePopup Ignore) ]
              [ text "Cancel " ]
            , button [ class "btn btn-danger", onClick (ClosePopup (CallApi (deleteProperty (EditProperty name "" StringFormat True True False)))) ]
              [ text "Delete "
              , i [ class "fa fa-times-circle" ] []
              ]
            ]
          ]
        ]
      ]
