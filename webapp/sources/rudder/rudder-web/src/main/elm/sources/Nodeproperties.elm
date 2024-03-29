port module Nodeproperties exposing (..)

import Browser
import Http exposing (..)
import Result
import Json.Decode exposing (value)
import Dict exposing (..)
import List.Extra
import String
import NodeProperties.ApiCalls exposing (..)
import NodeProperties.DataTypes exposing (..)
import NodeProperties.Init exposing (init)
import NodeProperties.View exposing (view)
import Ui.Datatable exposing (SortOrder(..))

-- PORTS / SUBSCRIPTIONS
port errorNotification   : String -> Cmd msg
port successNotification : String -> Cmd msg
port initTooltips        : String -> Cmd msg
port initInputs          : String -> Cmd msg
port copy                : String -> Cmd msg

subscriptions : Model -> Sub Msg
subscriptions _ =
  Sub.none

main =
  Browser.element
    { init = init
    , view = view
    , update = update
    , subscriptions = subscriptions
    }

--
-- update loop --
--
update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
  case msg of
    Ignore ->
      (model, Cmd.none)

    Copy value ->
      (model, copy value)

    CallApi apiCall ->
      ( model , apiCall model)

    UpdateNewProperty newProperty ->
      let
        possibleFormats = getPossibleFormatsFromPropertyName model newProperty.name
        prop = 
          if newProperty.format /= model.newProperty.format then
            { newProperty | format = newProperty.format }
          else
            -- infer format from existing properties
            if List.length possibleFormats <= 1 then
              case List.head possibleFormats of
                Just format -> { newProperty | format = format }
                Nothing -> newProperty
            else
              newProperty
      in
        ({model | newProperty = prop}, Cmd.none)

    UpdateProperty key newProperty ->
      let
        ui = model.ui
        newProperties = Dict.update key (always (Just newProperty)) ui.editedProperties
      in
        ( { model | ui = {ui | editedProperties = newProperties} }, Cmd.none )
    UpdateTableSize size ->
      let
        pagination = model.ui.filtersOnUsage.pagination
        ui = model.ui
        filtersModelUsage = model.ui.filtersOnUsage
        newPagination = TablePagination 1 1 (if (size <= 0) then pagination.totalRow else size) pagination.totalRow
        newModel = { model | ui = {ui | filtersOnUsage = {filtersModelUsage | pagination = newPagination }} }
      in
        (newModel, Cmd.batch [ initInputs "", initTooltips ""])
    GoToPage page ->
      let
        pagination = model.ui.filtersOnUsage.pagination
        ui = model.ui
        filtersModelUsage = model.ui.filtersOnUsage
        pageOnDir = pagination.pageDirective
        pageOnTech = pagination.pageTechnique
        newPageDir =
          case filtersModelUsage.findUsageIn of
            Directives -> if(page > (getPageMax pagination) || page < 1) then 1 else page
            _ -> pageOnDir
        newPageTech =
          case filtersModelUsage.findUsageIn of
            Techniques -> if(page > (getPageMax pagination) || page < 1) then 1 else page
            _ -> pageOnTech
        newModel = { model | ui = {ui | filtersOnUsage = {filtersModelUsage | pagination = { pagination | pageDirective = newPageDir, pageTechnique = newPageTech} }} }
      in
       (newModel, Cmd.none)
    FirstPage ->
      let
        pagination = model.ui.filtersOnUsage.pagination
        ui = model.ui
        filtersModelUsage = model.ui.filtersOnUsage
        pageOnDir = pagination.pageDirective
        pageOnTech = pagination.pageTechnique
        newPageDir =
          case filtersModelUsage.findUsageIn of
            Directives -> 1
            _ -> pageOnDir
        newPageTech =
          case filtersModelUsage.findUsageIn of
            Techniques -> 1
            _ -> pageOnTech
        newModel = { model | ui = {ui | filtersOnUsage = {filtersModelUsage | pagination = { pagination | pageDirective = newPageDir, pageTechnique = newPageTech} }} }
      in
       (newModel, Cmd.none)
    LastPage ->
      let
        pagination = model.ui.filtersOnUsage.pagination
        ui = model.ui
        filtersModelUsage = model.ui.filtersOnUsage
        pageOnDir = pagination.pageDirective
        pageOnTech = pagination.pageTechnique
        pageMax = getPageMax pagination
        newPageDir =
          case filtersModelUsage.findUsageIn of
            Directives -> pageMax
            _ -> pageOnDir
        newPageTech =
          case filtersModelUsage.findUsageIn of
            Techniques -> pageMax
            _ -> pageOnTech
        newModel = { model | ui = {ui | filtersOnUsage = {filtersModelUsage | pagination = { pagination | pageDirective = newPageDir, pageTechnique = newPageTech} }} }
      in
       (newModel, Cmd.none)
    NexPage ->
      let
        pagination = model.ui.filtersOnUsage.pagination
        ui = model.ui
        filtersModelUsage = model.ui.filtersOnUsage
        pageOnDir = pagination.pageDirective
        pageOnTech = pagination.pageTechnique
        pageMax = getPageMax pagination
        newPageDir =
          case filtersModelUsage.findUsageIn of
            Directives -> min (pageOnDir + 1) pageMax
            _ -> pageOnDir
        newPageTech =
          case filtersModelUsage.findUsageIn of
            Techniques -> min (pageOnTech + 1) pageMax
            _ -> pageOnTech
        newModel = { model | ui = {ui | filtersOnUsage = {filtersModelUsage | pagination = { pagination | pageDirective = newPageDir, pageTechnique = newPageTech} }} }
      in
       (newModel, Cmd.none)
    PreviousPage ->
      let
        pagination = model.ui.filtersOnUsage.pagination
        ui = model.ui
        filtersModelUsage = model.ui.filtersOnUsage
        pageOnDir = model.ui.filtersOnUsage.pagination.pageDirective
        pageOnTech = model.ui.filtersOnUsage.pagination.pageTechnique
        newPageDir =
          case filtersModelUsage.findUsageIn of
            Directives -> max (pageOnDir - 1) 1
            _ -> pageOnDir
        newPageTech =
          case filtersModelUsage.findUsageIn of
            Techniques -> max (pageOnTech - 1) 1
            _ -> pageOnTech
        newModel = { model | ui = {ui | filtersOnUsage = {filtersModelUsage | pagination = { pagination | pageDirective = newPageDir, pageTechnique = newPageTech} }} }
      in
       (newModel, Cmd.none)
    FindPropertyUsage pName res ->
      case res of
        Ok found ->
          let
            propertyUsage = extractUsage found
            maxNbRow = getSearchResultLength propertyUsage Directives
            pagi = TablePagination 1 1 10 maxNbRow
            ui = model.ui
            filtersModel = model.ui.filtersOnUsage
            newModel = { model | ui = {ui | modalState = Usage pName propertyUsage, filtersOnUsage = {filtersModel | findUsageIn = Directives, pagination = pagi}} }
          in
            (newModel, Cmd.batch [ initInputs "", initTooltips ""])
        Err err ->
          processApiError "Find node property usage" err model
    ChangeViewUsage ->
      let
        switchView = if(model.ui.filtersOnUsage.findUsageIn == Directives) then Techniques else Directives
        ui = model.ui
        filtersModelUsage = model.ui.filtersOnUsage
        nbDirectives = case model.ui.modalState of
          Usage propName findUsage -> getSearchResultLength findUsage Directives
          _ -> 0
        nbTechniques = case model.ui.modalState of
          Usage propName findUsage -> getSearchResultLength findUsage Techniques
          _ -> 0
        nbRow = case switchView of
          Techniques -> nbTechniques
          Directives -> nbDirectives
        pageOnDir = model.ui.filtersOnUsage.pagination.pageDirective
        pageOnTech = model.ui.filtersOnUsage.pagination.pageTechnique
        pagination = model.ui.filtersOnUsage.pagination
        newModel = { model | ui = {ui | filtersOnUsage = {filtersModelUsage | findUsageIn = switchView, pagination = { pagination | totalRow = nbRow} }} }
      in
      (newModel, Cmd.none)
    SaveProperty successMsg res ->
      case res of
        Ok p ->
          let
            ui  = model.ui
            newModel ={ model
              | newProperty = (EditProperty "" "" model.newProperty.format True True False)
              , properties  = p
              , ui = { ui | loading = False }
              }
          in
            ( newModel
            , Cmd.batch [ initInputs "", initTooltips "" , successNotification successMsg, getNodeProperties newModel]
            )
        Err err ->
          processApiError "Saving node properties" err model

    GetNodeProperties res ->
      case  res of
        Ok properties ->
          let
            modelUi  = model.ui
          in
            ( { model | properties = properties, ui = { modelUi | loading = False } }
              , Cmd.batch [initTooltips "", initInputs ""]
            )
        Err err ->
          processApiError "Getting node properties" err model


    AddProperty ->
      let
        successMsg = ("property '" ++ (String.trim model.newProperty.name) ++ "' has been added")
        ( newModel , cmd ) = case model.newProperty.format of
          StringFormat -> (model, saveProperty [model.newProperty] model successMsg)
          JsonFormat   ->
            let
              checkJsonFormat = Json.Decode.decodeString Json.Decode.value model.newProperty.value
            in
              case checkJsonFormat of
                Ok s  ->
                  (model, saveProperty [model.newProperty] model successMsg)
                Err _ ->
                  let
                    newProperty = model.newProperty
                  in
                    ({model | newProperty = {newProperty | errorFormat = True}}, errorNotification "JSON check is enabled, but the value format is invalid.")
      in
        (newModel, cmd)

    DeleteProperty key ->
      (model, Cmd.none)

    ToggleEditPopup modalState ->
      let
        ui = model.ui
      in
        ( { model | ui = {ui | modalState = modalState} }, Cmd.none )

    ToggleEditProperty key property save ->
      if Dict.member key model.ui.editedProperties then -- If the property is being edited
        let
          ui = model.ui
          editedProperties = ui.editedProperties
            |> Dict.remove key
          oldName = String.trim key
          newName = String.trim property.name
          (successMsg, updatedProperty) =
            if (oldName == newName) then
              ("property '" ++ newName ++ "' has been updated", [property])
            else
              ( "property '" ++ oldName ++ "' has been updated and renamed to '" ++ newName ++ "'", [property, {property | name = key, value = ""} ] )
          updatedModel = { model | ui = {ui | editedProperties = editedProperties} }
          (newModel, cmd) = if save then -- If we want to save changes
            case property.format of
              StringFormat -> (updatedModel, saveProperty updatedProperty model successMsg)
              JsonFormat   ->
                let
                  checkJsonFormat = Json.Decode.decodeString Json.Decode.value property.value
                in
                  case checkJsonFormat of
                    Ok s  ->
                      (updatedModel, Cmd.batch [saveProperty [property] model successMsg, initInputs ""])
                    Err _ -> (model, errorNotification "JSON check is enabled, but the value format is invalid.")

            else
              (updatedModel, Cmd.none)
        in
          (newModel, cmd)
      else
        let
          ui = model.ui
          editedProperties = ui.editedProperties
            |> Dict.insert key property
        in
          ( { model | ui = {ui | editedProperties = editedProperties} }, initInputs "" )

    UpdateTableFiltersProperty tableFilters ->
      let
        ui = model.ui
      in
        ({model | ui = { ui | filtersOnProperty = tableFilters}}, Cmd.none)

    UpdateTableFiltersUsage tableFilters ->
      let
        ui = model.ui
      in
        ({model | ui = { ui | filtersOnUsage = tableFilters}}, Cmd.none)
    ShowMore id ->
      let
        ui = model.ui
        showMore = if List.member id ui.showMore then List.Extra.remove id ui.showMore else id :: ui.showMore
      in
        ({model | ui = { ui | showMore = showMore}}, Cmd.none)

    ClosePopup callback ->
      let
        ui = model.ui
        filtersModel = model.ui.filtersOnUsage
        initPagination = TablePagination 1 1 10 0
        (nm,cmd) = update callback { model | ui = { ui | modalState = NoModal, filtersOnUsage = {filtersModel | pagination = initPagination, findUsageIn = Directives, filter = "", sortBy = Name, sortOrder = Asc} } }
      in
        (nm , cmd)

processApiError : String -> Error -> Model -> ( Model, Cmd Msg )
processApiError apiName err model =
  let
    message =
      case err of
        Http.BadUrl url ->
            "The URL " ++ url ++ " was invalid"
        Http.Timeout ->
            "Unable to reach the server, try again"
        Http.NetworkError ->
            "Unable to reach the server, check your network connection"
        Http.BadStatus 500 ->
            "The server had a problem, try again later"
        Http.BadStatus 400 ->
            "Verify your information and try again"
        Http.BadStatus _ ->
            "Unknown error"
        Http.BadBody errorMessage ->
            errorMessage
  in
    (model, errorNotification ("Error when " ++ apiName ++ ", details: \n" ++ message ) )
