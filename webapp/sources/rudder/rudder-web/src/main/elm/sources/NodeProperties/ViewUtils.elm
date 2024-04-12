module NodeProperties.ViewUtils exposing (..)

import Html exposing (..)
import Html.Attributes exposing (id, class, href, type_, attribute, disabled, for, checked, selected, value, title, placeholder, style, tabindex    )
import Html.Events exposing (onClick, onInput)
import Json.Decode exposing (decodeValue)
import Maybe.Extra exposing (isJust)
import List.Extra
import Dict exposing (Dict)
import Json.Encode exposing (..)
import NaturalOrdering as N exposing (compare)
import SyntaxHighlight exposing (useTheme, gitHub, json, toInlineHtml)

import NodeProperties.DataTypes exposing (..)
import NodeProperties.ApiCalls exposing (deleteProperty)
import Json.Decode exposing (decodeString)
import Set exposing (Set)


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


checkExistingFormatConflict : Model -> String -> Bool
checkExistingFormatConflict model propertyName =
  let
    ui = model.ui
    editedFormat = 
      case Dict.get propertyName ui.editedProperties of
        Just p -> [p.format]
        Nothing -> []
      
    valueFormatToString f = 
      case f of
        StringFormat -> "string"
        JsonFormat -> "json"
  in
    getPossibleFormatsFromPropertyName model propertyName
      |> List.append editedFormat
      |> List.Extra.uniqueBy valueFormatToString >> List.length >> (\l -> l > 1)


getFormatTxt : ValueFormat -> String
getFormatTxt format =
    case format of
      StringFormat -> "String"
      JsonFormat -> "JSON"

getParentPropertyDisplay : ParentProperty -> String
getParentPropertyDisplay pp =
  case pp of
    ParentGlobal { valueType } -> "<span>Value of type: <strong>" ++ valueType ++ "</strong> for global parameter</span>"
    ParentGroup { id, name, valueType } -> "<span>Value of type: <strong>" ++ valueType ++ "</strong> in group " ++ "\"" ++ name ++ "\" with id <em>" ++ id ++ "</em></span>"
    ParentNode { id, name, valueType } -> "<span>Value of type: <strong>" ++ valueType ++ "</strong> in node " ++ "\"" ++ name ++ "\" with id <em>" ++ id ++ "</em></span>"


getFormatConflictWarning : Property -> Html Msg
getFormatConflictWarning property =
  case property.hierarchyStatus of
    Just hs ->
      if hs.hasChildTypeConflicts then
        span
        [  
            class "px-1"
          , attribute "data-bs-toggle" "tooltip"
          , attribute "data-bs-placement" "top"
          , title (buildTooltipContent
            "Conflicting types along the hierarchy of properties"
            (String.join "<br>" (List.map getParentPropertyDisplay hs.fullHierarchy))
          )
        ] [ i [class "fa fa-exclamation-triangle text-danger"][] ]
      else
        text ""
    Nothing -> text ""

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
  case decodeValue Json.Decode.string value of
    -- Properties are not typed, so we use this heuristic to chose between strand and JSON types 
    Ok s  -> s
    Err _ -> encode 2 value

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
        origVal = Maybe.withDefault p.value p.origval
        formatConflict = getFormatConflictWarning p
        defaultEditProperty = EditProperty p.name (displayJsonValue origVal) format True True False

        editedProperty = Dict.get p.name model.ui.editedProperties
        (providerBadge, editRight) = case p.provider of
          Just pr ->
            let
              pTitle = case pr of
                "inherited" -> "<h4 class='tags-tooltip-title'>Inherited</h4> <div class='tooltip-inner-content'>This property is inherited " ++ (Maybe.withDefault "" p.hierarchy) ++ "</div>."
                "overridden" -> "<h4 class='tags-tooltip-title'>Overridden</h4> <div>This property is overridden on this object and was inherited " ++ (Maybe.withDefault "" p.hierarchy) ++ "</div>."
                _ -> "<h4 class='tags-tooltip-title'>" ++ pr ++ "</h4> <div class='tooltip-inner-content'>This property is managed by its provider ‘<b>" ++ pr ++ "</b>’ and can not be modified manually. Check Rudder’s settings to adjust this provider’s configuration.</div>"
            in
              (span
              [ class "rudder-label label-provider label-sm"
              , attribute "data-bs-toggle" "tooltip"
              , attribute "data-bs-placement" "right"
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
            [ td [class "property-name"]
              [ div[]
                [ text p.name
                , providerBadge
                ]
              ]
            , td []
              [ span [class "ion ion-clipboard copy-actions", onClick (Copy  p.name)][]
              ]
            , td [class "property-type"]
              [ div []
                [ text formatTxt
                , formatConflict
                ]
              ]
            , td [class "property-value"]
              [ div []
                [ div [class ("value-container" ++ (if List.member p.name model.ui.showMore then " toggle" else "") ++ (if isTooLong p.value then " show-more" else "") ), onClick (ShowMore p.name) ]
                  [ if (format == JsonFormat) then
                      pre [class "json-beautify"]
                      [ useTheme gitHub,
                        json (displayJsonValue p.value)
                           |> Result.map toInlineHtml
                           |> Result.withDefault ( text (displayJsonValue p.value) )
                      ]
                    else
                      pre [class "json-beautify"][text (displayJsonValue p.value)]
                  ]
                , span [class "toggle-icon"][]
                ]
              ]
            , td []
              [ span [class "ion ion-clipboard copy-actions", onClick (Copy (displayJsonValue p.value))][]
              ]
            , td [class "text-center default-actions"]
              [ (if (editRight) then
                div []
                [ span [ class "action-icon fa fa-pencil", title "Edit", onClick (ToggleEditProperty p.name defaultEditProperty False)][]
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
              checkFormatConflict  = checkExistingFormatConflict model trimmedName
            in
            tr []
            [ td [class "is-edited"]
              [ div []
                [ input [type_ "text", class "form-control input-sm", value eP.name, onInput (\s -> UpdateProperty p.name {eP | name = s, pristineName = False }) ][]
                , ( if checkAlreadyUsedName then small [class "text-danger"][ text "This name is already used by another property" ] else text "" )
                , ( if (checkEmptyName && checkPristineName) then small [class "text-danger"][text "Name is required"] else text "" )
                , ( if checkFormatConflict then small [class "text-danger"][text "The selected format will conflict with some existing format of the same property"] else text "" )
                ]
              ]
            , td [][] -- empty column to clipboard icon for property name
            , td [class "is-edited"]
              [ div [class "format-container"]
                [ button [type_ "button", class "btn btn-default dropdown-toggle", attribute "data-bs-toggle" "dropdown"]
                  [ text (if eP.format == JsonFormat then "JSON" else "String")
                  ]
                , ul [class "dropdown-menu"]
                  [ li[][ a[ class "dropdown-item", onClick (UpdateProperty p.name {eP | format = StringFormat}) ][ text "String" ]]
                  , li[][ a[ class "dropdown-item", onClick (UpdateProperty p.name {eP | format = JsonFormat  }) ][ text "JSON"   ]]
                  ]
                ]
              ]
            , td [class "is-edited"]
              [ div [class "value-container"]
                [ textarea [placeholder "Value", attribute "msd-elastic" "", attribute "rows" "1", class "form-control input-sm input-value auto-resize code", value eP.value, onInput (\s -> UpdateProperty p.name {eP | value = s, pristineValue = False}) ][]
                , (if (checkEmptyVal && checkPristineVal)  then small [class "text-danger"][text "Value is required"] else text "")
                ]
              ]
            , td [class "text-center edit-actions is-edited" ]
              [ div []
                [ span [ class "action-icon fa fa-share-square cancel-icon", title "Cancel", onClick (ToggleEditProperty p.name eP False)][]
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
      div [ tabindex -1, class "modal fade show", style "display" "block" ]
      [ div [class "modal-backdrop fade show", onClick (ClosePopup Ignore)][]
      , div [ class "modal-dialog" ]
        [ div [ class "modal-content" ]
          [ div [ class "modal-header ng-scope" ]
            [ h5 [ class "modal-title" ] [ text "Delete property"]
            , button [type_ "button", class "btn-close", onClick (ClosePopup Ignore), attribute "aria-label" "Close"][]
            ]
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

-- WARNING:
--
-- Here the content is an HTML so it need to be already escaped.
buildTooltipContent : String -> String -> String
buildTooltipContent title content =
  let
    headingTag = "<h4 class='tags-tooltip-title'>"
    contentTag = "</h4><div class='tooltip-inner-content'>"
    closeTag   = "</div>"
  in
    headingTag ++ title ++ contentTag ++ content ++ closeTag
