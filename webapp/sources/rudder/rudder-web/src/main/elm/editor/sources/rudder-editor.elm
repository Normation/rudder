port module Editor exposing (..)

import ApiCalls exposing (..)
import Browser
import Browser.Dom
import DataTypes exposing (..)
import Dict exposing ( Dict )
import Either exposing ( Either(..) )
import File
import File.Download
import File.Select
import Json.Decode exposing ( Value )
import Json.Encode
import JsonEncoder exposing ( encodeTechnique, encodeExportTechnique )
import JsonDecoder exposing ( decodeTechnique)
import List.Extra
import MethodConditions exposing (..)
import Random
import Task
import UUID
import ViewTechnique exposing ( view, checkTechniqueName, checkTechniqueId )
import ViewMethod exposing ( accumulateErrorConstraint )

--
-- Port for interacting with external JS
--
port copy : String -> Cmd msg
port store : (String , Value) -> Cmd msg
port clear : String  -> Cmd msg
port get : () -> Cmd msg
port response: ((Value, Value, String) -> msg) -> Sub msg
port openManager: String -> Cmd msg
port updateResources : (() -> msg) -> Sub msg
port successNotification : String -> Cmd msg
port errorNotification   : String -> Cmd msg
port warnNotification   : String -> Cmd msg
port infoNotification    : String -> Cmd msg

updateResourcesResponse : Model -> Msg
updateResourcesResponse model =
  case model.mode of
    TechniqueDetails _ s _ -> CallApi ( getRessources s )
    _ -> Ignore

parseResponse: (Value, Value, String) -> Msg
parseResponse (json, optJson, internalId) =
  case Json.Decode.decodeValue decodeTechnique json of
    Ok tech ->
      let
        o =
          case  (Json.Decode.decodeValue (Json.Decode.nullable  decodeTechnique)) optJson of
            Ok (m) -> m
            _ -> Nothing
      in
        GetFromStore tech o (TechniqueId internalId)
    Err _ -> Ignore

mainInit : { contextPath : String, hasWriteRights : Bool  } -> ( Model, Cmd Msg )
mainInit initValues =
  let
    model =  Model [] Dict.empty [] Introduction initValues.contextPath "" (MethodListUI (MethodFilter "" False Nothing FilterClosed) []) False dndSystem.model Nothing initValues.hasWriteRights
  in
    (model, Cmd.batch ( [ getMethods model, getTechniquesCategories model ]) )

updatedStoreTechnique: Model -> Cmd msg
updatedStoreTechnique model =
  case model.mode of
    TechniqueDetails t o _ ->
      let
        storeOriginTechnique =
          case o of
            Edit origin -> [ store ("originTechnique", encodeTechnique origin), clear "internalId" ]
            Creation id -> [  clear "originTechnique", store ("internalId", Json.Encode.string id.value) ]
            Clone origin id -> [  store ("originTechnique", encodeTechnique origin), store ("internalId", Json.Encode.string id.value) ]
      in
        Cmd.batch ( store ("currentTechnique", encodeTechnique t) ::  storeOriginTechnique )
    _ -> Cmd.none

main =
  Browser.element
    { init = mainInit
    , update = update
    , view = view
    , subscriptions = subscriptions
    }

subscriptions : Model -> Sub Msg
subscriptions model =
    Sub.batch
        [ dndSystem.subscriptions model.dnd
        , response parseResponse
        , updateResources (always (updateResourcesResponse model))
        ]

defaultMethodUiInfo = MethodCallUiInfo Closed CallParameters Dict.empty

selectTechnique: Model -> Technique -> (Model, Cmd Msg)
selectTechnique model technique =
  let
    ui = TechniqueUiInfo General (Dict.fromList (List.map (\c -> (c.id.value, defaultMethodUiInfo)) technique.calls)) [] False ValidState ValidState
  in
    ({ model | mode = TechniqueDetails technique  (Edit technique) ui } )
      |> update OpenMethods
      |> Tuple.mapSecond ( always ( Cmd.batch [ updatedStoreTechnique model, getRessources (Edit technique) model ]  ))

generator : Random.Generator String
generator = Random.map (UUID.toString) UUID.generator

--
-- update loop --
--
update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
  case msg of
-- utility methods
    -- generate random id
    GenerateId nextMsg ->
      (model, Random.generate nextMsg generator)
    -- do an API call
    CallApi apiCall ->
      ( model , apiCall model)
    -- neutral element
    Ignore ->
      ( model , Cmd.none)
    -- copy value to js
    Copy value ->
      (model, copy value)

-- UI high level stuff: list/filter techniques, create/import/select technique

    GetCategories (Ok categories) ->
      ({ model | categories = List.sortBy .name categories},  get () )
    GetCategories (Err _) ->
      ( model , Cmd.none )

    GetTechniques (Ok  techniques) ->
      ({ model | techniques = techniques},  get () )
    GetTechniques (Err _) ->
      ( model , Cmd.none )

    OpenTechniques ->
      ( { model | genericMethodsOpen = False } , Cmd.none )

    UpdateTechniqueFilter newFilter ->
      ( { model | techniqueFilter = newFilter } , Cmd.none )

    SelectTechnique technique ->
      case model.mode of
        TechniqueDetails t _ _ ->
          if t.id == technique.id then
             ( { model | mode = Introduction }, clear "storedTechnique")
          else
            selectTechnique model technique
        _ ->
          selectTechnique model technique

    NewTechnique id ->
      let
        ui = TechniqueUiInfo General Dict.empty [] False Untouched Untouched
        t = Technique (TechniqueId "") "1.0" "" "" "ncf_techniques" [] [] []
        newModel =  { model | mode = TechniqueDetails t (Creation id) ui}
      in
        (newModel, updatedStoreTechnique newModel )

    -- import a technique from a JSON file
    StartImport ->
        (model, File.Select.file [ "text/plain", "application/json" ]  ImportFile )
    ImportFile file ->
      (model, Task.perform (ParseImportedFile file) (File.toString file) )
    ParseImportedFile file content ->
      case Json.Decode.decodeString (Json.Decode.at ["data"] decodeTechnique ) content of
        Ok t ->
          let
            mode = TechniqueDetails t (Creation t.id) (
                     TechniqueUiInfo General (Dict.fromList (List.map (\c -> (c.id.value, defaultMethodUiInfo)) t.calls)) [] False (checkTechniqueName t model) (checkTechniqueId (Creation t.id) t model)
                   )
            (newModel, cmd) = (update (CallApi ( getRessources (Creation t.id) ))  {model | mode = mode })
          in
            ( newModel, Cmd.batch [ cmd, infoNotification ("Technique '"++ t.id.value ++ "' successfully imported, please save to create technique") ] )
        Err _ ->
         (model, errorNotification ("Error when importing technique from file " ++ (File.name file) ))


-- Edit a technique: high level action: save/update, clone, export, switch tab

    SwitchTab tab ->
      let
        newMode =
          case model.mode of
           TechniqueDetails technique o ui-> TechniqueDetails technique o { ui | tab = tab }
           m -> m
      in
        ({ model | mode = newMode}, Cmd.none )

    SwitchTabMethod callId newTab ->
      let
        newMode =
          case model.mode of
            TechniqueDetails t o ui ->
              let
                newUi = { ui | callsUI = Dict.update callId.value (Maybe.map (\mui -> {mui | tab = newTab })) ui.callsUI  }
              in
                TechniqueDetails t o newUi
            m -> m
      in
        ({ model | mode = newMode}, Cmd.none )

    UpdateTechnique technique ->
      let
        newModel =
          case model.mode of
            TechniqueDetails _ o ui ->

              { model | mode = TechniqueDetails technique o {ui |  nameState = checkTechniqueName technique model, idState = checkTechniqueId o technique model } }
            _ -> model
      in
        (newModel, updatedStoreTechnique newModel )

    Store _ _ ->
      (model, updatedStoreTechnique model )

    GetFromStore technique originTechnique newId ->
            let
              notification = case (List.Extra.find (.id >> (==) technique.id) model.techniques,originTechnique) of
                (Nothing, Just _) -> warnNotification "Technique reloaded from cache was deleted, Saving will recreate it"
                (Just _ , Nothing) -> warnNotification "Technique from cache was created, change name/id before saving"
                (Just t , Just o) -> if t /= o then warnNotification "Technique reloaded from cache since you modified it, saving will overwrite current changes" else infoNotification "Technique reloaded from cache"
                (Nothing, Nothing) -> infoNotification "Technique reloaded from cache"
              state = Maybe.withDefault (Creation newId)  (Maybe.map Edit originTechnique)

              ui = TechniqueUiInfo General (Dict.fromList (List.map (\c -> (c.id.value, defaultMethodUiInfo)) technique.calls)) [] False (checkTechniqueName technique model) (checkTechniqueId state technique model)
            in
              ({ model | mode = TechniqueDetails technique state ui } )
                |> update OpenMethods
                |>  Tuple.mapSecond (\c -> Cmd.batch [c, notification ])

    CloneTechnique technique internalId ->
      let
        ui = TechniqueUiInfo General (Dict.fromList (List.map (\c -> (c.id.value, defaultMethodUiInfo)) technique.calls)) [] False Untouched Untouched
      in
        ({ model | mode = TechniqueDetails technique  (Clone technique internalId) ui } )
          |> update OpenMethods
          |> Tuple.first
          |> update (Store "storedTechnique" (encodeTechnique technique))

    SaveTechnique (Ok technique) ->
      let
        techniques = if (List.any (.id >> (==) technique.id) model.techniques) then
           List.Extra.updateIf (.id >> (==) technique.id ) (always technique) model.techniques
         else
           technique :: model.techniques
        newMode = case model.mode of
                    TechniqueDetails t _ ui -> TechniqueDetails t (Edit technique) ui
                    m -> m
      in
        ({ model | techniques = techniques, mode = newMode}, successNotification "Technique saved!" )

    SaveTechnique (Err _) ->
      ( model , errorNotification "Error when saving technique")

    StartSaving ->
     case model.mode of
          TechniqueDetails t o ui ->
            case o of
              Edit _ ->
               update (CallApi (saveTechnique t False)) { model | mode = TechniqueDetails t o ui }
              _ ->
               update (CallApi (saveTechnique t True)) { model | mode = TechniqueDetails t o ui }
          _ -> (model, Cmd.none)

    DeleteTechnique (Ok (techniqueId, _)) ->
      let
        techniques = List.filter (.id >> (/=) techniqueId) model.techniques
        newMode = case model.mode of
                     TechniqueDetails t _ _ -> if t.id == techniqueId then Introduction else model.mode
                     _ -> model.mode

      in
        ({ model | mode = newMode, techniques = techniques}, infoNotification ("Successfully deleted technique '" ++ techniqueId.value ++  "'"))

    DeleteTechnique (Err _) ->
      ( model , errorNotification "Error when deleting technique")

    OpenDeletionPopup technique ->
      ( { model | modal = Just (DeletionValidation technique)}  , Cmd.none )

    ClosePopup callback ->
      let
        (nm,cmd) = update callback { model | modal = Nothing}
      in
        (nm , Cmd.batch [  clear "storedTechnique", clear "originTechnique", cmd ] )

    ResetTechnique  ->
      let
        newModel =
          case model.mode of
            TechniqueDetails base s ui ->
              let
                technique =
                  case s of
                    Edit t -> t
                    Clone t _ -> t
                    Creation _ -> base
              in
                { model | mode = TechniqueDetails technique s ui }
            _ -> model
      in
        (newModel, updatedStoreTechnique newModel )

    ResetMethodCall call  ->
      let
        newModel =
          case model.mode of
            TechniqueDetails base s ui ->
              let
                technique =
                  case s of
                    Edit t -> t
                    Clone t _ -> t
                    Creation _ -> base
                (updatedTechnique, needCheck) = case List.Extra.find (.id >> (==) call.id) technique.calls of
                         Just resetCall -> ({ base | calls = List.Extra.updateIf (.id >> (==) call.id) (always resetCall) base.calls }, Just resetCall)
                         Nothing -> (base,Nothing)
                callUi =
                  case needCheck of
                    Just realCall ->
                      let
                        constraints = case Dict.get call.methodName.value model.methods of
                           Just m -> Dict.fromList (List.map (\p -> (p.name.value, p.constraints))  m.parameters)
                           Nothing -> Dict.empty

                        updateCallUi = \optCui ->
                          let
                            b = case optCui of
                              Nothing -> MethodCallUiInfo Closed CallParameters Dict.empty
                              Just cui -> cui


                            newValidation = List.foldl ( \param val ->
                               Dict.update param.id.value (always (Just (accumulateErrorConstraint  param (Maybe.withDefault [] (Dict.get param.id.value constraints))  )))  val ) b.validation realCall.parameters
                          in
                            Just { b | validation = newValidation }
                      in
                        Dict.update call.id.value updateCallUi  ui.callsUI
                    Nothing -> ui.callsUI

              in
                { model | mode = TechniqueDetails updatedTechnique s { ui | callsUI = callUi } }
            _ -> model
      in
        (newModel, updatedStoreTechnique newModel )

    -- export a technique to its JSON descriptor
    Export ->
      let
        action = case model.mode of
                   TechniqueDetails t _ _ ->
                     let
                       data =  encodeExportTechnique t
                       content = Json.Encode.encode 2 data
                     in
                       File.Download.string (t.id.value ++ ".json") "application/json" content
                   _ -> Cmd.none
      in
        (model, action)

-- Edit a technique: parameter tab

    TechniqueParameterModified paramId newValue ->
      let
        newModel =
          case model.mode of
            TechniqueDetails t o ui->
              let
                parameters = List.Extra.updateIf (\c -> paramId == c.id ) (always newValue)  t.parameters
                technique = { t | parameters = parameters }
              in
                { model | mode = TechniqueDetails technique o ui }
            _ -> model
      in
       (newModel , updatedStoreTechnique newModel )

    TechniqueParameterRemoved paramId ->
      let
        newModel =
          case model.mode of
            TechniqueDetails t o ui->
              let
                parameters = List.Extra.filterNot (\c -> paramId == c.id ) t.parameters
                newUI = {ui | openedParameters = List.Extra.remove paramId ui.openedParameters }
                technique = { t | parameters = parameters }
              in
                { model | mode = TechniqueDetails technique o newUI }
            _ -> model
      in
        ( newModel , updatedStoreTechnique newModel )

    TechniqueParameterAdded paramId ->
      let
        newMode =
          case model.mode of
            TechniqueDetails t o ui->
              let
                parameters = List.append t.parameters [  TechniqueParameter paramId "" "" False ]
              in
                TechniqueDetails { t | parameters = parameters } o ui
            _ -> model.mode
      in
        ({ model | mode = newMode}, Cmd.none )

    TechniqueParameterToggle paramId ->
      let
        newMode =
          case model.mode of
            TechniqueDetails t o ui->
              let
                newUI = {ui | openedParameters = (if List.member paramId ui.openedParameters then List.Extra.remove else (::) ) paramId ui.openedParameters }
              in
                TechniqueDetails t o newUI
            _ -> model.mode
      in
        ({ model | mode = newMode}, Cmd.none )


-- Edit a technique: resource tab, file manager, etc.

    OpenFileManager ->
      let
        cmd = case model.mode of
                TechniqueDetails t s _ ->
                  let
                    url = case s of
                            Edit _ ->  t.id.value ++ "/" ++ t.version ++ "/" ++ t.category
                            Creation internalId -> "draft/" ++ internalId.value ++ "/" ++ t.version
                            Clone _ internalId -> "draft/" ++ internalId.value ++ "/" ++ t.version
                  in
                    openManager (model.contextPath ++ "/secure/api/resourceExplorer/"  ++ url)
                _ -> Cmd.none
      in
      (model, cmd)

    GetTechniqueResources (Ok  resources) ->
      let
        mode = case model.mode of
                 TechniqueDetails t s ui ->
                   TechniqueDetails {t | resources = resources } s ui
                 _ -> model.mode
      in
        ({ model | mode = mode },  Cmd.none )
    GetTechniqueResources (Err _) ->
      ( model , Cmd.none )

-- Edit a technique: generic method high level action (list/etc)

    OpenMethods ->
      ( { model | genericMethodsOpen = True } , Cmd.none )

    GetMethods (Ok  methods) ->
      ({ model | methods = methods}, getTechniques model  )

    GetMethods (Err _) ->
      ( model , errorNotification "Error when getting methods" )

    ToggleFilter ->
      let
        ui = model.methodsUI
        filter = ui.filter
        newState = case filter.state of
                        FilterOpened ->  FilterClosed
                        FilterClosed -> FilterOpened
      in
        ({ model | methodsUI = { ui | filter = {filter | state = newState } } } ,Cmd.none)

    UpdateMethodFilter newFilter->
      let
        ui = model.methodsUI
      in
        ( { model | methodsUI = { ui | filter = newFilter } } , Cmd.none )


    ScrollCategory category ->
      let
        task = (Browser.Dom.getElement "methods-list-container")
            |> ((Browser.Dom.getViewportOf "methods-list-container")
            |> ((Browser.Dom.getElement category)
            |> Task.map3 (\elem viewport container -> viewport.viewport.y + elem.element.y - container.element.y ))  )
            |> Task.andThen (Browser.Dom.setViewportOf "methods-list-container" 0)
      in
        (model, Task.attempt (always Ignore) task )

    ToggleDoc methodId ->
      let
        ui = model.methodsUI
        newDocs = if List.member methodId ui.docsOpen then List.Extra.remove methodId ui.docsOpen else methodId :: ui.docsOpen
      in
        ( { model | methodsUI = { ui | docsOpen = newDocs } } , Cmd.none )

    AddMethod method newId ->
      if model.hasWriteRights then
      let
        newCall = MethodCall newId method.id (List.map (\p -> CallParameter p.name "") method.parameters) (Condition Nothing "") ""
        newModel =
          case model.mode of
            TechniqueDetails t o ui ->
              let
                technique =  { t | calls = newCall :: t.calls }
                newUi = { ui | callsUI = Dict.update newId.value (always (Just defaultMethodUiInfo) ) ui.callsUI }
              in
              { model | mode = TechniqueDetails technique o newUi }
            _ -> model
      in
        (  newModel , updatedStoreTechnique newModel )
      else
        (model,Cmd.none)

    DndEvent dndMsg ->
      if model.hasWriteRights then
            let
              ( newModel, c ) =
                case model.mode of
                   TechniqueDetails t o ui ->
                    let
                      (d, calls ) = dndSystem.update dndMsg model.dnd (List.append (List.map (Right) t.calls) (Dict.values model.methods |> List.map (Left)))
                      newMode = TechniqueDetails { t | calls = Either.rights calls } o ui
                    in
                      if (List.any (\call -> call.id.value == "") t.calls) then
                        update (GenerateId (\id -> SetCallId (CallId id))) { model | dnd = d, mode = newMode }
                      else
                        ( { model | dnd = d, mode = newMode }, Cmd.none)
                   _ -> (model, Cmd.none)
            in
               (newModel , Cmd.batch [  dndSystem.commands newModel.dnd, c ] )
      else
        (model,Cmd.none)

    SetCallId newId ->
      let
        newMode =
          case model.mode of
            TechniqueDetails t o ui ->
              let
                technique = { t | calls = List.Extra.updateIf (\c -> c.id.value == "") (\c -> { c | id = newId } ) t.calls }
                newUi = { ui | callsUI = Dict.insert newId.value defaultMethodUiInfo ui.callsUI}
              in
                TechniqueDetails technique o newUi
            m -> m
      in
        ( { model | mode = newMode } , Cmd.none )

-- Edit a technique: edit one generic method

    OpenMethod callId ->
      let
        newMode =
          case model.mode of
           TechniqueDetails t o ui->
             let
               newUi = {ui | callsUI = Dict.update  callId.value (Maybe.map (\mui -> {mui | mode = Opened } )) ui.callsUI }
             in
              TechniqueDetails t o newUi
           m -> m
      in
        ({ model | mode = newMode}, Cmd.none )

    CloseMethod callId ->
      let
        newMode =
          case model.mode of
           TechniqueDetails t o ui->
            let
              newUi = {ui | callsUI = Dict.update  callId.value (Maybe.map (\mui -> {mui | mode = Closed })) ui.callsUI }
            in
              TechniqueDetails t o newUi
           m -> m
      in
        ({ model | mode = newMode}, Cmd.none )

    RemoveMethod callId ->
      let
        newMode =
          case model.mode of
           TechniqueDetails t o ui ->
            let
              technique = { t |  calls = List.filter (\c -> c.id /= callId ) t.calls }
              newUi = {ui | callsUI = Dict.remove callId.value  ui.callsUI }
            in
            TechniqueDetails technique o newUi
           m -> m
        newModel = { model | mode = newMode}
      in
        (newModel, updatedStoreTechnique newModel )

    CloneMethod call newId ->
      let
        clone = {call | id = newId }
        newModel =
          case model.mode of
            TechniqueDetails t o ui ->
              let
                newMethods =
                  let
                   (end,beginning) = List.Extra.span (\c -> c.id == call.id ) (List.reverse t.calls)
                  in
                    List.reverse (List.append end (clone :: beginning))
                technique = { t |  calls = newMethods}
                newUi =  { ui | callsUI = Dict.update newId.value (always (Just defaultMethodUiInfo)) ui.callsUI }
              in
                { model | mode = TechniqueDetails technique o newUi }
            _ -> model
      in
        (newModel, updatedStoreTechnique newModel )

    UpdateCondition callId condition ->
      case model.mode of
        TechniqueDetails t s ui ->
          let
            newModel = {model | mode = TechniqueDetails {t | calls = List.Extra.updateIf (.id >> (==) callId ) (\c -> { c | condition = condition }) t.calls} s ui}
          in
          (newModel, Cmd.none )
        _ -> (model,Cmd.none)

    MethodCallParameterModified call paramId newValue ->
      let
        newModel =
          case model.mode of
            TechniqueDetails t o ui->
              let

                calls = List.Extra.updateIf (.id >> (==) call.id )  (\c -> { c | parameters =  List.Extra.updateIf (.id >> (==) paramId) (\p -> { p | value = newValue } ) c.parameters } ) t.calls
                constraints = case Dict.get call.methodName.value model.methods of
                           Just m -> Maybe.withDefault [] (Maybe.map (.constraints) (List.Extra.find (.name >> (==) paramId) m.parameters))
                           Nothing -> []

                updateCallUi = \optCui ->
                  let
                    base = case optCui of
                            Nothing -> MethodCallUiInfo Closed CallParameters Dict.empty
                            Just cui -> cui
                    newValidation =  Dict.update paramId.value (always (Just (accumulateErrorConstraint  (CallParameter paramId newValue) constraints )))  base.validation
                  in
                    Just { base | validation = newValidation }
                callUi  =
                  Dict.update call.id.value updateCallUi  ui.callsUI
                technique = { t | calls = calls }
              in
                { model | mode = TechniqueDetails technique o {ui | callsUI = callUi } }
            _ -> model
     in
       ( newModel , updatedStoreTechnique newModel  )
