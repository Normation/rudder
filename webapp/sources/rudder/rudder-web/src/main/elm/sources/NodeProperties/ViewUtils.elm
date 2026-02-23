module NodeProperties.ViewUtils exposing (..)

import Html exposing (..)
import Html.Attributes exposing (attribute, class, colspan, href, id, name, placeholder, selected, style, tabindex, title, type_, value)
import Html.Attributes.Extra exposing (role)
import Html.Events exposing (onClick, onInput)
import Json.Decode exposing (decodeValue)
import List.Extra
import Dict exposing (Dict)
import Json.Encode exposing (..)
import Maybe.Extra
import NaturalOrdering as N
import QuickSearch.Datatypes exposing (SearchResult, SearchResultItem)
import String exposing (toInt)
import SyntaxHighlight exposing (useTheme, gitHub, json, toInlineHtml)
import NodeProperties.DataTypes exposing (..)
import NodeProperties.ApiCalls exposing (deleteProperty, findPropertyUsage)

import Utils.TooltipUtils exposing (buildTooltipContent)


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

thClassOnProperty : TableFiltersOnProperty -> SortBy -> String
thClassOnProperty tableFilters sortBy =
  if sortBy == tableFilters.sortBy then
    case  tableFilters.sortOrder of
      Asc  -> "sorting_asc"
      Desc -> "sorting_desc"
  else
    "sorting"

thClassOnUsage : TableFiltersOnUsage -> SortBy -> String
thClassOnUsage tableFilters sortBy =
  if sortBy == tableFilters.sortBy then
    case  tableFilters.sortOrder of
      Asc  -> "sorting_asc"
      Desc -> "sorting_desc"
  else
    "sorting"

sortTableOnProperty : TableFiltersOnProperty -> SortBy -> TableFiltersOnProperty
sortTableOnProperty tableFilters sortBy =
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

sortTableOnUsage : TableFiltersOnUsage -> SortBy -> TableFiltersOnUsage
sortTableOnUsage tableFilters sortBy =
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

getParentPropertyDisplay : ParentProperty ->  List String
getParentPropertyDisplay pp =
  case pp of
    ParentGlobal { valueType } -> [ "<span>Value of type: <strong>" ++ valueType ++ "</strong> for global parameter</span>" ]
    ParentGroup { id, name, valueType, parent } -> ("<span>Value of type: <strong>" ++ valueType ++ "</strong> in group " ++ "\"" ++ name ++ "\" with id <em>" ++ id ++ "</em></span>") :: (Maybe.withDefault [] <| Maybe.map getParentPropertyDisplay parent)
    ParentNode { id, name, valueType, parent } -> ("<span>Value of type: <strong>" ++ valueType ++ "</strong> in node " ++ "\"" ++ name ++ "\" with id <em>" ++ id ++ "</em></span>") :: (Maybe.withDefault [] <| Maybe.map getParentPropertyDisplay parent)

getInheritedPropertyWarning : Property -> Html Msg
getInheritedPropertyWarning property =
  -- ignore error message when there is already a format conflict warning, it duplicates the error
  case property.hierarchyStatus of
    Just hs ->
      case (hs.errorMessage, hs.hasChildTypeConflicts) of
        (Just err, False) ->
          span
          [
              class "px-2"
            , attribute "data-bs-toggle" "tooltip"
            , attribute "data-bs-placement" "top"
            , title (buildTooltipContent "Inherited property error" err)
          ] [ i [class "fa fa-exclamation-triangle text-danger"][] ]
        _ -> text ""
    Nothing -> text ""

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
            (String.join "<br>" (getParentPropertyDisplay hs.fullHierarchy))
          )
        ] [ i [class "fa fa-exclamation-triangle text-danger"][] ]
      else
        text ""
    Nothing -> text ""

getSortFunctionUsage : Model -> SearchResultItem -> SearchResultItem -> Order
getSortFunctionUsage model p1 p2 =
  let
    order = N.compare p1.name p2.name
  in
    if model.ui.filtersOnUsage.sortOrder == Asc then
      order
    else
      case order of
        LT -> GT
        EQ -> EQ
        GT -> LT

getSortFunction : Model -> Property -> Property -> Order
getSortFunction model p1 p2 =
  let
    order = case model.ui.filtersOnProperty.sortBy of
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
    if model.ui.filtersOnProperty.sortOrder == Asc then
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

displayPropertiesError : Model -> List (Html Msg)
displayPropertiesError model =
  case model.errorMessage of
    Just err -> -- error may contain new lines, separate them with <br/> tag 
      [ div [class "alert alert-warning"]
        ((i [class "fa fa-exclamation-triangle"][]) :: (List.intersperse (br [] []) (List.map text (String.lines err))))
      ]
    Nothing -> []

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
      |> List.filter (\pp -> filterSearch model.ui.filtersOnProperty.filter (searchField pp))
      |> List.sortWith (getSortFunction model)

    propertyRow : Property -> Html Msg
    propertyRow p =
      let
        format = getFormat p
        formatTxt = getFormatTxt format
        origVal = Maybe.withDefault p.value p.origval
        formatConflict = getFormatConflictWarning p
        inheritedPropertyError = getInheritedPropertyWarning p
        defaultEditProperty = EditProperty p.name (displayJsonValue origVal) format True True False

        editedProperty = Dict.get p.name model.ui.editedProperties
        (providerBadge, editRight, deleteRight) = case p.provider of
          Just pr ->
            let
              pTitle = case pr of
                "inherited" -> "<h4 class='tags-tooltip-title'>Inherited</h4> <div class='tooltip-inner-content'>This property is inherited " ++ (Maybe.withDefault "" p.hierarchy) ++ "</div>."
                "overridden" -> "<h4 class='tags-tooltip-title'>Overridden</h4> <div class='tooltip-inner-content'>This property is overridden on this object and was inherited " ++ (Maybe.withDefault "" p.hierarchy) ++ "</div>."
                _ -> "<h4 class='tags-tooltip-title'>" ++ pr ++ "</h4> <div class='tooltip-inner-content'>This property is managed by its provider ‘<b>" ++ pr ++ "</b>’ and can not be modified manually. Check Rudder’s settings to adjust this provider’s configuration.</div>"
            in
              (span
              [ class "rudder-label label-provider label-sm bs-tooltip ms-1"
              , attribute "data-bs-toggle" "tooltip"
              , attribute "data-bs-placement" "right"
              , attribute "data-bs-trigger" "click"
              , title pTitle
              ] [ text pr ]
              , pr == "overridden" || pr == "inherited"
              , pr == "overridden"
              )
          Nothing -> (text "", True, True)

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
                , inheritedPropertyError
                , providerBadge
                , span [class "find-action action-icon", title "Find usage of this property", onClick (CallApi (findPropertyUsage p.name ))]
                  [ i [class "fas fa-search"][]
                  ]
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
                ( (span [ class "action-icon fa fa-pencil", title "Edit", onClick (ToggleEditProperty p.name defaultEditProperty False)][]
                  ) :: if (deleteRight) then (
                    [ span [ class "action-icon fa fa-times text-danger", title "Delete", onClick (ToggleEditPopup (Deletion p.name))][] ]
                  ) else []
                )
                else text ""
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

showModal : Model -> Html Msg
showModal model =
  case model.ui.modalState of
    NoModal -> text ""
    Usage pName found ->
      let
        pagination = model.ui.filtersOnUsage.pagination
        filters = model.ui.filtersOnUsage
        page = case filters.findUsageIn of
          Directives -> pagination.pageDirective
          Techniques -> pagination.pageTechnique
        tableSize = pagination.tableSize
        totalNbRow = pagination.totalRow
        start = (page - 1) * tableSize
        end = min (start + tableSize) totalNbRow
        displayStart = start + 1
        pagesOpt =
            List.range 1 (getPageMax pagination)
              |> List.map (\currentPage -> option [value (String.fromInt currentPage), selected (currentPage == page )][text (String.fromInt currentPage)])
        classNextBtn = if(page >= getPageMax pagination) then "disabled" else ""
        classPreviousBtn = if(page <= 1) then "disabled" else ""
        nbDirective = getSearchResultLength found Directives
        nbTechnique = getSearchResultLength found Techniques
        row = case filters.findUsageIn of
          Techniques ->
            case found.techniques of
              Just t ->
                t.items
                |> List.filter (\technique -> filterSearch model.ui.filtersOnUsage.filter [technique.name])
                |> List.drop start
                |> List.take (end - start)
                |> List.sortWith (getSortFunctionUsage model)
                |> List.map (\info -> tr [][td [] [a [href info.url][text info.name]]])
              Nothing -> [div [][text messageNoUsageFound]]
          Directives ->
            case found.directives of
              Just d ->
                d.items
                |> List.filter (\directive -> filterSearch model.ui.filtersOnUsage.filter [directive.name])
                |> List.drop start
                |> List.take (end - start)
                |> List.sortWith (getSortFunctionUsage model)
                |> List.map (\info -> tr [][td [] [a [href info.url][text info.name]]])
              Nothing -> [div [][]]
        activeClassTechniquesUsage =
          case filters.findUsageIn of
            Techniques -> "active"
            Directives -> ""
        activeClassDirectivesUsage =
          case filters.findUsageIn of
            Techniques -> ""
            Directives -> "active"
        hasNoUsage =
          case filters.findUsageIn of
            Techniques -> found.techniques |> Maybe.andThen (.items >> List.head) |> Maybe.Extra.isNothing
            Directives -> found.directives |> Maybe.andThen (.items >> List.head) |> Maybe.Extra.isNothing
        messageNoUsageFound =
          case filters.findUsageIn of
            Techniques -> "No usage found in Techniques"
            Directives -> "No usage found in Directives"
      in
      div [ class "modal fade show", style "display" "block"]
      [ div [class "modal-backdrop fade show", onClick (ClosePopup Ignore)][]
      , div [ class "modal-dialog modal-lg" ]
        [ div [ class "modal-content modal-find-prop" ]
          [ div [ class "modal-header ng-scope" ]
            [ h5 [ class "modal-title" ] [text ("Find usage of property '" ++ pName ++ "'")]
            , button [type_ "button", class "btn-close", onClick (ClosePopup Ignore), attribute "aria-label" "Close"][]
            ]
          , div [ class "modal-body" ]
            [ div [class "dataTables_wrapper no-footer"]
              [ ul [class "nav nav-underline nav-modal-usage"]
                [ li [class ("nav-item " ++ activeClassDirectivesUsage)]
                  [ button
                    [ attribute "role" "tab", type_ "button", class ("nav-link " ++ activeClassDirectivesUsage), onClick ChangeViewUsage]
                    [ text "In directives"
                    , span [class "badge badge-secondary badge-resources tooltip-bs"]
                      [ span [class "nb-resources"] [text (String.fromInt nbDirective)]
                      ]
                    ]
                  ]
                , li [class ("nav-item " ++ activeClassTechniquesUsage)]
                  [ button
                    [ attribute "role" "tab", type_ "button", class ("nav-link " ++ activeClassTechniquesUsage), onClick ChangeViewUsage]
                    [ text "In techniques"
                    , span [class "badge badge-secondary badge-resources tooltip-bs"]
                      [ span [class "nb-resources"] [text (String.fromInt nbTechnique)]
                      ]
                    ]
                  ]
                ]
              , div [class "dataTables_wrapper_top"]
                [ div [class "dataTables_filter"]
                  [ label []
                    [ input [type_ "text", placeholder "Filter", class "input-sm form-control", onInput (\s -> UpdateTableFiltersUsage {filters | filter = s})][]
                    ]
                  ]
                ]
              , table [class "table-find-prop dataTable"]
                [ thead []
                  [ tr [class "head"]
                    [ th
                      [ class ("th-find-prop " ++ (thClassOnUsage model.ui.filtersOnUsage Name))
                      , onClick (UpdateTableFiltersUsage (sortTableOnUsage filters Name))
                      ]
                      [ text "Name" ]
                    ]
                  ]
                , tbody []
                  ( if hasNoUsage then
                      [ tr [] [ td [colspan 2, class "dataTables_empty empty" ] [ i [class "fa fa-exclamation-triangle"][], text messageNoUsageFound ] ] ]
                    else
                      row
                  )
               ]
              , if hasNoUsage  then
                  text ""
                else
                  div [class "dataTables_wrapper_bottom"]
                  [ div [class "dataTables_length"]
                    [ label []
                      [ text "Show"
                      , select [onInput (\str -> UpdateTableSize (toInt str |> Maybe.withDefault 10))]
                        [ option [value "10"][text "10"]
                        , option [value "25"][text "25"]
                        , option [value "50"][text "50"]
                        , option [value "100"][text "100"]
                        , option [value "500"][text "500"]
                        , option [value "1000"][text "1000"]
                        , option [value "-1"][text "All"]
                        ]
                      , text "entries"
                      ]
                    ]
                  , div [class "dataTables_info"]
                    [ text ("Showing " ++ (String.fromInt displayStart) ++ " to " ++ (String.fromInt end) ++ " of " ++ (String.fromInt totalNbRow) ++ " entries")
                    ]
                  , div [id "props_paginate", class "dataTables_paginate paging_full_numbers"]
                    [ a [id "node_previous", class ("paginate_button first " ++ classPreviousBtn), role "link", onClick FirstPage][text "First"]
                    , a [id "node_previous", class ("paginate_button previous " ++ classPreviousBtn), role "link", onClick PreviousPage][text "Previous"]
                    , span []
                      [ select [class "page-find-prop", onInput (\str -> GoToPage (toInt str |> Maybe.withDefault 1))](pagesOpt)
                      ]
                    , a [class ("paginate_button next " ++ classNextBtn), onClick NexPage][text "Next"]
                    , a [class ("paginate_button last " ++ classNextBtn), onClick LastPage][text "Last"]
                    ]
                  ]
              ]
            ]
          , div [ class "modal-footer" ]
            [ button [ class "btn btn-default", onClick (ClosePopup Ignore) ][ text "Close " ]
            ]
          ]
        ]
      ]
    Deletion name ->
      div [ tabindex -1, class "modal fade show", style "display" "block" ]
      [ div [class "modal-backdrop fade show", onClick (ClosePopup Ignore)][]
      , div [ class "modal-dialog" ]
        [ div [ class "modal-content" ]
          [ div [ class "modal-header" ]
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