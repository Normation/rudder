port module Editor exposing (..)

import ApiCalls exposing (..)
import Browser
import Browser.Dom
import DataTypes exposing (..)
import Dict exposing ( Dict )
import Dict.Extra
import Dom.DragDrop as DragDrop
import Either exposing (Either(..))
import File
import File.Download
import File.Select
import Json.Decode exposing ( Value )
import Json.Encode
import JsonEncoder exposing (encodeDraft, encodeExportTechnique, encodeTechnique)
import JsonDecoder exposing (decodeDraft, decodeTechnique)
import List.Extra
import MethodConditions exposing (..)
import Random
import Task
import Time
import UUID
import ViewTechnique exposing ( view, checkTechniqueName, checkTechniqueId )
import ViewMethod exposing ( accumulateErrorConstraint )
import ViewTechniqueList exposing (allMethodCalls)
import Maybe.Extra
import MethodElemUtils exposing (..)
import Http exposing ( Error )
import AgentValueParser exposing (..)

--
-- Port for interacting with external JS
--
port copy : String -> Cmd msg
port storeDraft : Value -> Cmd msg
port clearDraft : String  -> Cmd msg
port getDrafts : () -> Cmd msg
port draftsResponse: (Value -> msg) -> Sub msg
port openManager: String -> Cmd msg
port updateResources : (() -> msg) -> Sub msg
port successNotification : String -> Cmd msg
port errorNotification   : String -> Cmd msg
--port warnNotification    : String -> Cmd msg
port infoNotification    : String -> Cmd msg
port pushUrl             : String -> Cmd msg
port getUrl             : () -> Cmd msg

port readUrl : (String -> msg) -> Sub msg


-- utility to write a understandable debug message from a get response
debugHttpErr : Http.Error -> String
debugHttpErr error =
    case error of
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

updateResourcesResponse : Model -> Msg
updateResourcesResponse model =
  case model.mode of
    TechniqueDetails _ s _ -> CallApi ( getRessources s )
    _ -> Ignore

parseDraftsResponse: Value -> Msg
parseDraftsResponse json =
  case Json.Decode.decodeValue (Json.Decode.dict decodeDraft) json  of
    Ok drafts ->
        GetDrafts drafts
    Err e -> Notification errorNotification (Json.Decode.errorToString e)

mainInit : { contextPath : String, hasWriteRights : Bool  } -> ( Model, Cmd Msg )
mainInit initValues =
  let
    model =  Model [] Dict.empty (TechniqueCategory "" "" "" (SubCategories [])) Dict.empty Introduction initValues.contextPath "" (MethodListUI (MethodFilter "" False Nothing FilterClosed) []) False DragDrop.initialState Nothing initValues.hasWriteRights
  in
    (model, Cmd.batch ( [ getDrafts (), getMethods model, getTechniquesCategories model]) )

updatedStoreTechnique: Model -> (Model, Cmd msg)
updatedStoreTechnique model =
  case model.mode of
    TechniqueDetails t o _ ->
      let
        (drafts, action) =
          case o of
            Edit origin -> if t == origin then
                             (Dict.remove t.id.value model.drafts, clearDraft t.id.value)
                           else
                             let
                               draft = Draft  t (Just origin) origin.id.value (Time.millisToPosix 0)
                             in
                               (Dict.insert draft.id draft model.drafts,  storeDraft (encodeDraft draft))
            Creation id ->
              let
               draft = Draft  t Nothing id.value (Time.millisToPosix 0)
              in
                (Dict.insert draft.id draft model.drafts,  storeDraft (encodeDraft draft))
            Clone origin id ->
              let
               draft = Draft  t Nothing id.value (Time.millisToPosix 0)
              in
                (Dict.insert draft.id draft model.drafts,  storeDraft (encodeDraft draft))
      in
         ({ model | drafts = drafts }, action)
    _ -> (model, Cmd.none)

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
        [ draftsResponse parseDraftsResponse
        , updateResources (always (updateResourcesResponse model))
        , readUrl (\s -> case List.Extra.find (.id >> .value >> (==) s ) model.techniques of
                    Just t -> SelectTechnique (Left t)
                    Nothing -> Ignore
                  )
        ]


defaultMethodUiInfo  =
    MethodCallUiInfo Closed Nothing Dict.empty True

selectTechnique: Model -> (Either Technique Draft) -> (Model, Cmd Msg)
selectTechnique model technique =
  let
    (effectiveTechnique, state, action) = case technique  of
      Left t ->
        let
          tech = case Dict.get t.id.value model.drafts of
                   Just d -> d.technique
                   Nothing -> t
        in
          (tech, Edit t, pushUrl t.id.value)
      Right d ->
        let
         st = case d.origin of
                   Just o -> Clone d.technique o.id
                   Nothing -> Creation (TechniqueId d.id)
        in
        (d.technique, st, Cmd.none)
    ui = TechniqueUiInfo General (Dict.fromList (List.map (\c -> ((getId c).value, defaultMethodUiInfo)) (List.concatMap getAllCalls effectiveTechnique.elems))) [] False ValidState ValidState
  in
    ({ model | mode = TechniqueDetails effectiveTechnique  state ui } )
      |> update OpenMethods
      |> Tuple.mapSecond ( always ( Cmd.batch [ getRessources state model, action  ]  ))

generator : Random.Generator String
generator = Random.map (UUID.toString) UUID.generator

updateParameter: ParameterId -> String -> MethodElem -> MethodElem
updateParameter paramId newValue x =
  case x of
    Call p c -> Call p { c | parameters =  List.Extra.updateIf (.id >> (==) paramId) (\param -> { param | value = (getAgentValue newValue) } ) c.parameters }
    Block p b -> Block p { b | calls = List.map (updateParameter paramId newValue) b.calls}
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
      ({ model | categories = categories}, Cmd.none )
    GetCategories (Err _) ->
      ( model , Cmd.none )

    GetTechniques (Ok  techniques) ->
      ({ model | techniques = techniques},  getUrl () )
    GetTechniques (Err err) ->
      ( model , errorNotification  ("Error when getting techniques: " ++ debugHttpErr err  ) )

    OpenTechniques ->
      ( { model | genericMethodsOpen = False } , Cmd.none )

    UpdateTechniqueFilter newFilter ->
      ( { model | techniqueFilter = newFilter } , Cmd.none )

    SelectTechnique technique ->
      case model.mode of
        TechniqueDetails t _ _ ->
          if t.id == (Either.unpack .id (.technique >> .id) technique) then
             ( { model | mode = Introduction }, Cmd.none)
          else
            selectTechnique model technique
        _ ->
          selectTechnique model technique

    NewTechnique id ->
      let
        ui = TechniqueUiInfo General Dict.empty [] False Unchanged Unchanged
        t = Technique (TechniqueId "") "1.0" "" "" "ncf_techniques" [] [] []
        newModel =  { model | mode = TechniqueDetails t (Creation id) ui}
      in
        updatedStoreTechnique newModel

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
                     TechniqueUiInfo General (Dict.fromList (List.map (\c -> (c.id.value, defaultMethodUiInfo)) (List.concatMap allMethodCalls t.elems))) [] False (checkTechniqueName t model) (checkTechniqueId (Creation t.id) t model)
                   )
            (newModel, cmd) = (update (CallApi ( getRessources (Creation t.id) ))  {model | mode = mode })
          in
            ( newModel, Cmd.batch [ cmd, infoNotification ("Technique '"++ t.id.value ++ "' successfully imported, please save to create technique") ] )
        Err err ->
         (model, errorNotification ("Error when importing technique from file " ++ (File.name file) ++ ": " ++ (Json.Decode.errorToString err)))


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
                newUi = { ui | callsUI = Dict.update callId.value (Maybe.map (\mui -> {mui | tab = Just newTab })) ui.callsUI  }
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
        updatedStoreTechnique newModel

    GetDrafts drafts ->
      ({ model | drafts = drafts }, Cmd.none)

    CloneTechnique technique internalId ->
      let
        ui = TechniqueUiInfo General (Dict.fromList (List.map (\c -> (c.id.value, defaultMethodUiInfo)) (List.concatMap allMethodCalls technique.elems))) [] False Unchanged Unchanged
        (newModel,_) = update OpenMethods { model | mode = TechniqueDetails technique  (Clone technique internalId) ui }
      in
        updatedStoreTechnique newModel

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

    SaveTechnique (Err err) ->
      ( model , errorNotification ("Error when saving technique: " ++ debugHttpErr err ) )

    StartSaving ->
     case model.mode of
          TechniqueDetails t o ui ->
            case o of
              Edit _ ->
               update (CallApi (saveTechnique t False)) { model | mode = TechniqueDetails t o ui }
              _ ->
               update (CallApi (saveTechnique t True)) { model | mode = TechniqueDetails t o ui }
          _ -> (model, Cmd.none)

    DeleteTechnique (Ok techniqueId) ->
      case model.mode of
                     TechniqueDetails t (Edit _) _ ->
                       let
                         techniques = List.filter (.id >> (/=) techniqueId) model.techniques
                         newMode = if t.id == techniqueId then Introduction else model.mode
                       in
                         ({ model | mode = newMode, techniques = techniques}, infoNotification ("Successfully deleted technique '" ++ techniqueId.value ++  "'"))
                     TechniqueDetails t (Creation id) _ ->
                       let
                         drafts = Dict.remove techniqueId.value model.drafts
                         newMode = if t.id == techniqueId then Introduction else model.mode
                       in
                         ({ model | mode = newMode, drafts = drafts}, clearDraft techniqueId.value)
                     _ -> (model, Cmd.none)


    DeleteTechnique (Err err) ->
      ( model , errorNotification ("Error when deleting technique: " ++ debugHttpErr err))

    OpenDeletionPopup technique ->
      ( { model | modal = Just (DeletionValidation technique)}  , Cmd.none )

    ClosePopup callback ->
      let
        (nm,cmd) = update callback { model | modal = Nothing}
      in
        (nm , Cmd.batch [ cmd ] )

    ResetTechnique  ->
      let
        newModel =
          case model.mode of
            TechniqueDetails base s ui ->
              let
                (technique, drafts) =
                  case s of
                    Edit t -> (t, Dict.remove t.id.value model.drafts)
                    Clone t _ -> (t, model.drafts)
                    Creation _ -> (base, model.drafts)
              in
                { model | mode = TechniqueDetails technique s ui, drafts = drafts }
            _ -> model
      in
        updatedStoreTechnique newModel

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
                (updatedTechnique, needCheck) = case List.Extra.find (getId >> (==) call.id) technique.elems of
                         Just resetCall -> ({ base | elems = updateElemIf (getId >> (==) call.id) (always resetCall) base.elems }, Just resetCall)
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
                              Nothing -> MethodCallUiInfo Closed Nothing Dict.empty True
                              Just cui -> cui


                            newValidation =
                             case realCall of
                               Block _ _ -> Dict.empty
                               Call _ c ->
                                 List.foldl ( \param val ->
                                   Dict.update param.id.value (always (Just (accumulateErrorConstraint  param (Maybe.withDefault [] (Dict.get param.id.value constraints))  )))  val ) b.validation c.parameters
                          in
                            Just { b | validation = newValidation }
                      in
                        Dict.update call.id.value updateCallUi  ui.callsUI
                    Nothing -> ui.callsUI

              in
                { model | mode = TechniqueDetails updatedTechnique s { ui | callsUI = callUi } }
            _ -> model
      in
        updatedStoreTechnique newModel

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
       updatedStoreTechnique newModel

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
        updatedStoreTechnique newModel

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

    GetMethods (Err err) ->
      ( model , errorNotification ("Error when getting methods: " ++ debugHttpErr err ) )

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
        disableReporting = String.contains "variable" method.name || String.contains "condition" method.name
        newCall = MethodCall newId method.id (List.map (\p -> CallParameter p.name [Value ""]) method.parameters) (Condition Nothing "") "" disableReporting
        newModel =
          case model.mode of
            TechniqueDetails t o ui ->
              let
                technique =  { t | elems = t.elems ++ [Call Nothing newCall] }
                newUi = { ui | callsUI = Dict.update newId.value (always (Just (defaultMethodUiInfo ))) ui.callsUI }
              in
              { model | mode = TechniqueDetails technique o newUi }
            _ -> model
      in
        updatedStoreTechnique newModel
      else
        (model,Cmd.none)

    AddBlock newId ->
      if model.hasWriteRights then
      let
        newCall = MethodBlock newId "" (Condition Nothing "") SumReport []
        newModel =
          case model.mode of
            TechniqueDetails t o ui ->
              let
                technique =  { t | elems =  t.elems ++ [Block Nothing newCall]  }
                newUi = { ui | callsUI = Dict.update newId.value (always (Just (defaultMethodUiInfo)) ) ui.callsUI }
              in
              { model | mode = TechniqueDetails technique o newUi }
            _ -> model
      in
        updatedStoreTechnique newModel
      else
        (model,Cmd.none)


    SetCallId newId ->
      let
        newMode =
          case model.mode of
            TechniqueDetails t o ui ->
              let
                technique = { t | elems = updateElemIf (getId >> .value >> (==) "") (setId newId) t.elems }
                newUi = { ui | callsUI = Dict.insert newId.value defaultMethodUiInfo ui.callsUI}
              in
                TechniqueDetails technique o newUi
            m -> m
      in
        ( { model | mode = newMode } , Cmd.none )

-- Edit a technique: edit one generic method

    UIMethodAction callId newMethodUi ->
      let
        newMode =
          case model.mode of
           TechniqueDetails t o ui->
             let
               newUi = {ui | callsUI = Dict.update  callId.value (Maybe.map (always newMethodUi )) ui.callsUI }
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
              technique = { t |  elems = removeElem (getId >> (==) callId) t.elems }
              newUi = {ui | callsUI = Dict.remove callId.value  ui.callsUI }
            in
            TechniqueDetails technique o newUi
           m -> m
        newModel = { model | mode = newMode}
      in
        updatedStoreTechnique newModel

    CloneMethod call newId ->
      let
        clone = {call | id = newId }
        newModel =
          case model.mode of
            TechniqueDetails t o ui ->
              let
                newMethods =
                  let
                   (end,beginning) = List.Extra.span (getId >> (==) call.id ) (List.reverse t.elems)
                   endParent = Maybe.Extra.join (Maybe.map getParent (List.Extra.last end))
                  in
                    List.reverse (List.append end ((Call endParent clone) :: beginning))
                technique = { t |  elems = newMethods}
                newUi =  { ui | callsUI = Dict.update newId.value (always (Just defaultMethodUiInfo)) ui.callsUI }
              in
                { model | mode = TechniqueDetails technique o newUi }
            _ -> model
      in
        updatedStoreTechnique newModel

    MethodCallModified method ->
      case model.mode of
        TechniqueDetails t s ui ->
          let
            newModel = {model | mode = TechniqueDetails {t | elems = updateElemIf (getId >> (==) (getId method) ) (always method) t.elems} s ui}
          in
          updatedStoreTechnique newModel
        _ -> (model,Cmd.none)


    MethodCallParameterModified call paramId newValue ->
      let
        newModel =
          case model.mode of
            TechniqueDetails t o ui->
              let

                calls = updateElemIf (getId >> (==) call.id )  (updateParameter paramId newValue) t.elems
                constraints = case Dict.get call.methodName.value model.methods of
                           Just m -> Maybe.withDefault [] (Maybe.map (.constraints) (List.Extra.find (.name >> (==) paramId) m.parameters))
                           Nothing -> []

                updateCallUi = \optCui ->
                  let
                    base = case optCui of
                            Nothing -> MethodCallUiInfo Closed Nothing Dict.empty True
                            Just cui -> cui
                    newValidation =  Dict.update paramId.value (always (Just (accumulateErrorConstraint  (CallParameter paramId (getAgentValue newValue)) constraints )))  base.validation
                  in
                    Just { base | validation = newValidation }
                callUi  =
                  Dict.update call.id.value updateCallUi  ui.callsUI
                technique = { t | elems = calls }
              in
                { model | mode = TechniqueDetails technique o {ui | callsUI = callUi } }
            _ -> model
      in
        updatedStoreTechnique newModel

    SetMissingIds newId ->
      case model.mode of
        Introduction -> (model, Cmd.none)
        TechniqueDetails t e u->
          let
           newUi = { u | callsUI = Dict.update newId (always (Just defaultMethodUiInfo) ) u.callsUI }
          in
          case setIdRec newId t.elems of
            (_, False) -> (model, Cmd.none)
            (newCalls, True) -> update (GenerateId SetMissingIds) { model | mode = TechniqueDetails {t  | elems = newCalls} e newUi }

    MoveStarted draggedItemId ->
      ( { model | dnd = DragDrop.startDragging model.dnd draggedItemId },Cmd.none )

    MoveTargetChanged dropTargetId ->
      ( { model | dnd = DragDrop.updateDropTarget model.dnd dropTargetId }, Cmd.none  )

    MoveCanceled ->
      ( { model | dnd = DragDrop.stopDragging model.dnd }, Cmd.none )

    MoveCompleted draggedItemId dropTarget ->
      case model.mode of
        Introduction -> (model, Cmd.none)
        TechniqueDetails t u e ->
          let
            (baseCalls, newElem) =
              case draggedItemId of
                Move b -> case getParent b of
                             Nothing -> (List.filter (getId >> (/=) (getId b) ) t.elems, b)
                             Just parent ->
                               (updateElemIf (getId >> (==) parent )
                                  (\x -> case x of
                                    Block p k -> Block p { k | calls = List.filter (getId >> (/=) (getId b) ) k.calls}
                                    _ -> x
                                  ) t.elems
                               , b)
                NewBlock -> (t.elems, Block Nothing (MethodBlock (CallId "") "" (Condition Nothing "") SumReport []))
                NewMethod method ->
                 let
                   disableReporting = String.contains "variable" method.name || String.contains "condition" method.name
                 in
                   (t.elems, Call Nothing (MethodCall (CallId "") method.id (List.map (\p -> CallParameter p.name [Value ""]) method.parameters) (Condition Nothing "") "" disableReporting))
            updatedCalls =
              case dropTarget of
                StartList ->
                  newElem :: baseCalls
                AfterElem parent call ->
                  case parent of
                    Nothing ->
                      case List.Extra.splitWhen (getId >> (==) (getId call)) baseCalls of
                        Nothing ->
                          newElem :: baseCalls
                        Just (head, c :: tail) -> head ++ (c :: newElem :: tail)
                          -- should not happen since if we got a Just then we should have matched a non empty list and empty case should be treated
                        Just (head, tail) -> head ++ (newElem :: tail)
                    Just parentId ->
                      (updateElemIf (getId >> (==) parentId )
                        (\x ->
                          case x of
                            Block p k ->
                              let
                                calls = case List.Extra.splitWhen (getId >> (==) (getId call)) k.calls of
                                  Nothing ->
                                    newElem :: k.calls
                                  Just (head, c :: tail) -> head ++ (c :: newElem :: tail)
                                  -- should not happen since if we got a Just then we should have matched a non empty list and empty case should be treated
                                  Just (head, tail) -> head ++ (newElem :: tail)
                              in
                                Block p { k | calls = calls }
                            _ -> x
                        )
                        baseCalls
                      )
                InBlock b ->
                  updateElemIf (getId >> (==) b.id ) (\x -> case x of
                                                    Block p k -> Block p { k | calls = newElem :: b.calls }
                                                    _ -> x
                                                  ) baseCalls
            updateTechnique = { t | elems = updatedCalls}
          in
            update (GenerateId SetMissingIds ) { model | mode = TechniqueDetails updateTechnique u e , dnd = DragDrop.initialState}


    Notification notif notifMsg ->
      (model, notif notifMsg)