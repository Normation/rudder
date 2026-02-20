port module Editor exposing (..)

import Browser
import Dict exposing ( Dict )
import Dict.Extra
import Dom.DragDrop as DragDrop
import Either exposing (Either(..))
import File
import File.Download
import File.Select
import Http.Detailed as Detailed
import Json.Decode exposing ( Value )
import Json.Encode
import List.Extra
import Maybe.Extra
import Random
import Task
import Time
import UUID

import Editor.ApiCalls exposing (..)
import Editor.AgentValueParser exposing (..)
import Editor.DataTypes exposing (..)
import Editor.JsonEncoder exposing (encodeDraft, encodeExportTechnique)
import Editor.JsonDecoder exposing (decodeDraft, decodeErrorDetails, decodeTechnique)
import Editor.MethodConditions exposing (..)
import Editor.MethodElemUtils exposing (..)
import Editor.ViewMethod exposing ( accumulateErrorConstraint )
import Editor.ViewTechnique exposing ( view, checkTechniqueUiState )
import Editor.ViewTechniqueList exposing (allMethodCalls)


--
-- Port for interacting with external JS
--
port copy                : String -> Cmd msg
port storeDraft          : Value -> Cmd msg
port clearDraft          : String  -> Cmd msg
port getDrafts           : () -> Cmd msg
port draftsResponse      : (Value -> msg) -> Sub msg
port openManager         : String -> Cmd msg
port updateResources     : (() -> msg) -> Sub msg
port successNotification : String -> Cmd msg
port errorNotification   : String -> Cmd msg
port infoNotification    : String -> Cmd msg
port pushUrl             : String -> Cmd msg
port getUrl              : () -> Cmd msg
port readUrl             : (String -> msg) -> Sub msg
port clearTooltips       : String -> Cmd msg
port scrollMethod        : (Bool , String) -> Cmd msg
port initInputs          : String -> Cmd msg
port initTooltips        : () -> Cmd msg

-- utility to write a understandable debug message from a get response
debugHttpErr : Detailed.Error String -> String
debugHttpErr error =
    case error of
        Detailed.BadUrl url ->
            "The URL " ++ url ++ " was invalid"
        Detailed.Timeout ->
            "Unable to reach the server, try again"
        Detailed.NetworkError ->
            "Unable to reach the server, check your network connection"
        Detailed.BadStatus metadata body ->
          let
            (title, errors) = decodeErrorDetails body
          in
          title ++ "\n" ++ errors
        Detailed.BadBody metadata body msg ->
            msg

updateResourcesResponse : Model -> Msg
updateResourcesResponse model =
  case model.mode of
    TechniqueDetails _ s _ editInfo -> CallApi ( getRessources s )
    _ -> Ignore

parseDraftsResponse: Value -> Msg
parseDraftsResponse json =
  case Json.Decode.decodeValue (Json.Decode.dict decodeDraft) json  of
    Ok drafts ->
      let
        invalidDrafts = Dict.keys (Dict.filter (\ _ v -> Maybe.Extra.isNothing v) drafts)
        validDrafts = Dict.Extra.filterMap (\ _ v -> v) drafts
      in

        GetDrafts validDrafts invalidDrafts
    Err e -> Notification errorNotification "Invalid drafts in local storage, please clean your local storage"

mainInit : { contextPath : String, hasWriteRights : Bool  } -> ( Model, Cmd Msg )
mainInit initValues =
  let
    model =  Model [] [] Dict.empty (TechniqueCategory "" "" "" (SubCategories [])) Dict.empty [] Introduction initValues.contextPath (TreeFilters "" []) (MethodListUI (MethodFilter "" False Nothing FilterClosed) []) False DragDrop.initialState Nothing initValues.hasWriteRights Nothing Nothing True []
  in
    (model, Cmd.batch ( [ getDrafts (), getMethods model, getTechniquesCategories model, getDirectives model]) )

updatedStoreTechnique: Model -> (Model, Cmd Msg)
updatedStoreTechnique model =
  case model.mode of
    TechniqueDetails t o _ editInfo ->
      let
        (drafts, action) =
          case o of
            Edit origin -> if t == origin then
                             (Dict.remove t.id.value model.drafts, clearDraft t.id.value)
                           else
                             let
                               draft = Draft  t (Just origin) origin.id (Time.millisToPosix 0)
                             in
                               (Dict.insert draft.id.value draft model.drafts, Cmd.batch[initInputs "", clearTooltips "", storeDraft (encodeDraft draft)] )
            Creation id ->
              let
               draft = Draft  t Nothing id (Time.millisToPosix 0)
              in
                (Dict.insert draft.id.value draft model.drafts, Cmd.batch[initInputs "", clearTooltips "", storeDraft (encodeDraft draft)] )
            Clone _ _ id ->
              let
               draft = Draft  t Nothing id (Time.millisToPosix 0)
              in
                (Dict.insert draft.id.value draft model.drafts, Cmd.batch[initInputs "", clearTooltips "", storeDraft (encodeDraft draft)] )
      in
         ({ model | drafts = drafts }, action)
    _ -> (model, Cmd.batch[initInputs "", clearTooltips ""])

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
        , readUrl (navigateToTechnique model)
        ]

navigateToTechnique : Model -> String -> Msg
navigateToTechnique model id =
  let
    technique =
      List.Extra.find (.id >> .value >> (==) id ) model.techniques

    error =
      List.Extra.find (.id >> .value >> (==) id ) model.errors

  in
  case ( technique, error ) of
    ( Just t, _ ) -> SelectTechnique (Left t)
    ( _, Just e ) -> SelectTechniqueError e
    _ -> Ignore

defaultMethodUiInfo : Maybe MethodCall -> MethodCallUiInfo
defaultMethodUiInfo call =
  let
    (foreachName, foreach) = case call of
      Just c -> (c.foreachName, c.foreach)
      Nothing -> (Nothing, Nothing)
  in
    MethodCallUiInfo Closed CallParameters Unchanged (ForeachUI False False (defaultNewForeach foreachName foreach))

defaultBlockUiInfo : MethodBlock -> MethodBlockUiInfo
defaultBlockUiInfo block =
  MethodBlockUiInfo Closed Children Unchanged False (ForeachUI False False (defaultNewForeach block.foreachName block.foreach))

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
                   Just o -> Clone d.technique Nothing o.id
                   Nothing -> Creation (TechniqueId d.id.value)
        in
        (d.technique, st, Cmd.none)
    callState = (Dict.fromList (List.map (\c -> (c.id.value, (defaultMethodUiInfo (Just c)))) (List.concatMap getAllCalls effectiveTechnique.elems)))
    blockState = (Dict.fromList (List.map (\c -> (c.id.value, (defaultBlockUiInfo c))) (List.concatMap getAllBlocks effectiveTechnique.elems)))
    defaultUi = TechniqueUiInfo General callState blockState [] False Unchanged Unchanged Nothing
    -- Revalidate state when technique is a Draft
    validateDraftUi s = checkTechniqueUiState state s (List.map techniqueCheckState model.techniques) defaultUi
    ui = Either.unpack (\_ -> defaultUi) (draftCheckState >> validateDraftUi) technique
    editInfo = TechniqueEditInfo "" False (Ok ())
  in
    ({ model | mode = TechniqueDetails effectiveTechnique  state ui editInfo } )
      |> update OpenMethods
      |> Tuple.mapSecond ( always ( Cmd.batch [ initInputs "", getRessources state model, action  ]  ))


selectTechniqueError: Model -> TechniqueError -> (Model, Cmd Msg)
selectTechniqueError model error =
  ( { model | mode = TechniqueErrorDetails error }, Cmd.none )

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

    GetCategories (Ok (_, categories)) ->
      ({ model | categories = categories}, Cmd.none )
    GetCategories (Err _) ->
      ( model , Cmd.none )

    GetTechniques (Ok  (_, techniquesOrError)) ->
      let
        ( errors, techniques ) =
          Either.partition techniquesOrError

      in
      ({ model | techniques = techniques, errors = errors, loadingTechniques = False},  getUrl () )
    GetTechniques (Err err) ->
      ({ model | loadingTechniques = False} , errorNotification  ("Error when getting techniques: " ++ debugHttpErr err  ) )


    GetDirectives (Ok  (_, directives)) ->
      ({ model | directives = directives, loadingTechniques = False}, Cmd.batch [getUrl (), initTooltips ()] )
    GetDirectives (Err err) ->
      ({ model | loadingTechniques = False} , errorNotification  ("Error when getting directives: " ++ debugHttpErr err  ) )

    OpenTechniques ->
      ( { model | genericMethodsOpen = False } , Cmd.none )

    UpdateTechniqueFilter treeFilter ->
      ( { model | techniqueFilter = treeFilter } , Cmd.none )
    CopyResources (Ok ()) ->
      ( model, Cmd.none )
    CopyResources (Err err) ->
      ( model ,  errorNotification  ("Error when copying technique resources to draft" ) )
    SelectTechnique technique ->
      case model.mode of
        TechniqueDetails t _ _ editInfo ->
          if t.id == (Either.unpack .id (.technique >> .id) technique) then
             ( { model | mode = Introduction }, initInputs "")
          else
            selectTechnique model technique
        _ ->
          selectTechnique model technique
    SelectTechniqueError error ->
      selectTechniqueError model error

    NewTechnique internalId ->
      let
        ui = TechniqueUiInfo General Dict.empty Dict.empty [] False Unchanged Unchanged Nothing
        t = Technique (TechniqueId "") "1.0" "" "" "" "ncf_techniques" [] [] [] [] Nothing
        editInfo = TechniqueEditInfo "" False (Ok ())
        newModel =  { model | mode = TechniqueDetails t (Creation internalId) ui editInfo}
      in
        updatedStoreTechnique newModel

    -- import a technique from a JSON file
    StartImport ->
        (model, File.Select.file [ "text/plain", "application/json", ".yml", ".yaml" ]  ImportFile )
    ImportFile file ->
      (model, Task.perform (ParseImportedFile file) (File.toString file) )
    ParseImportedFile file content ->
      (model, checkTechnique (Import content) model)
      {-
      case Json.Decode.decodeString (Json.Decode.at ["data"] decodeTechnique ) content of
        Ok t ->
          let
            callsState = (Dict.fromList (List.map (\c -> (c.id.value, defaultMethodUiInfo)) (List.concatMap allMethodCalls t.elems)))
            bloksState = (Dict.fromList (List.map (\c -> (c.id.value, defaultBlockUiInfo)) (List.concatMap getAllBlocks t.elems)))

            editInfo = TechniqueEditInfo "" False (Ok ())
            mode = TechniqueDetails t (Creation t.id) (
                     TechniqueUiInfo General callsState bloksState [] False (checkTechniqueName t model) (checkTechniqueId (Creation t.id) t model) Nothing
                   ) editInfo
            (newModel, cmd) = (update (CallApi ( getRessources (Creation t.id) ))  {model | mode = mode })
          in
            ( newModel, Cmd.batch [ cmd, infoNotification ("Technique '"++ t.id.value ++ "' successfully imported, please save to create technique") ] )
        Err err ->
         (model, errorNotification ("Error when importing technique from file " ++ (File.name file) ++ ": " ++ (Json.Decode.errorToString err)))
      -}

-- Edit a technique: high level action: save/update, clone, export, switch tab

    SwitchTab tab ->
      let
        newMode =
          case model.mode of
           TechniqueDetails technique o ui editInfo -> TechniqueDetails technique o { ui | tab = tab } editInfo
           m -> m
      in
        ({ model | mode = newMode}, Cmd.none )

    UpdateTechnique technique ->
      let
        techniqueId =
          if(List.any (\s -> s == technique.id.value) (Dict.keys model.methods)) then
            {- To avoid technique with the same name as generic method that cause error -}
            TechniqueId (technique.id.value ++ "_technique")
          else
            technique.id
        newModel =
          case model.mode of
            TechniqueDetails _ o ui editInfo ->

              { model | mode = TechniqueDetails {technique | id = techniqueId} o (checkTechniqueUiState o (techniqueCheckState technique) (List.map techniqueCheckState model.techniques) ui) editInfo }
            _ -> model
      in
        updatedStoreTechnique newModel

    GetDrafts drafts invalidDraftsToClean->
      ({ model | drafts = drafts }, Cmd.batch (List.map clearDraft invalidDraftsToClean))

    CloneTechnique technique optDraftId internalId ->
      let
        copiedName = technique.name ++ " (Copy)"
        newId = canonifyHelper (Value (String.toLower copiedName))
        callState =  Dict.fromList (List.map (\c -> (c.id.value, (defaultMethodUiInfo (Just c)))) (List.concatMap allMethodCalls technique.elems))
        blockState =  Dict.fromList (List.map (\c -> (c.id.value, defaultBlockUiInfo c)) (List.concatMap getAllBlocks technique.elems))
        ui = TechniqueUiInfo General callState  blockState [] False Unchanged Unchanged Nothing
        editInfo = TechniqueEditInfo "" False (Ok ())
        newModel = { model | mode = TechniqueDetails {technique | name = copiedName, id = TechniqueId newId}  (Clone technique optDraftId internalId) ui editInfo }
        (nm,cmd) = updatedStoreTechnique newModel
      in
        (nm,(Cmd.batch [ copyResourcesToDraft internalId.value technique optDraftId model , cmd]) )

    SaveTechnique (Ok (metadata, technique)) ->
      let
        techniques = if (List.any (.id >> (==) technique.id) model.techniques) then
           List.Extra.updateIf (.id >> (==) technique.id ) (always technique) model.techniques
         else
           technique :: model.techniques
        (newMode, idToClean) =
          case model.mode of
            TechniqueDetails _ (Edit _) ui editInfo ->
              (TechniqueDetails technique (Edit technique) {ui | saving = False} editInfo, technique.id)
            TechniqueDetails _ (Creation id) ui editInfo ->
              (TechniqueDetails technique (Edit technique) {ui | saving = False} editInfo, id)
            TechniqueDetails _ (Clone _ _ id) ui editInfo ->
              (TechniqueDetails technique (Edit technique) {ui | saving = False} editInfo, id)
            m -> (m, technique.id)
        drafts = Dict.remove idToClean.value model.drafts
      in
        ({ model | techniques = techniques, mode = newMode, drafts = drafts}, Cmd.batch [ clearDraft idToClean.value, successNotification "Technique saved!", pushUrl technique.id.value] )

    SaveTechnique (Err err) ->
      let
        newModel = case model.mode of
          TechniqueDetails t s ui editInfo -> {model | mode = TechniqueDetails t s {ui | saving = False} editInfo}
          _ -> model
      in
        ( newModel , errorNotification ("Error when saving technique: " ++ debugHttpErr err ) )

    StartSaving ->
      case model.mode of
        TechniqueDetails t o ui editInfo ->
          let
            newUi = {ui | saving = True}
          in
            case o of
              Edit _ ->
                update (CallApi (saveTechnique t False Nothing)) { model | mode = TechniqueDetails t o newUi editInfo }
              Creation internalId ->
                update (CallApi (saveTechnique t True (Just internalId))) { model | mode = TechniqueDetails t o newUi editInfo }
              Clone _ _ internalId ->
                update (CallApi (saveTechnique t True (Just internalId))) { model | mode = TechniqueDetails t o newUi editInfo }
        _ -> (model, Cmd.none)

    DeleteTechnique (Ok (metadata, techniqueId)) ->
      case model.mode of
                     TechniqueDetails t (Edit _) _ _->
                       let
                         techniques = List.filter (.id >> (/=) techniqueId) model.techniques
                         newMode = if t.id == techniqueId then Introduction else model.mode
                       in
                         ({ model | mode = newMode, techniques = techniques}, infoNotification ("Successfully deleted technique '" ++ techniqueId.value ++  "'"))
                     TechniqueDetails t (Creation id) _ _->
                       let
                         drafts = Dict.remove techniqueId.value model.drafts
                         newMode = if id == techniqueId then Introduction else model.mode
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
            TechniqueDetails base s ui editInfo ->
              let
                (technique, drafts) =
                  case s of
                    Edit t -> (t, Dict.remove t.id.value model.drafts)
                    Clone t _ _ -> (t, model.drafts)
                    Creation _ -> (base, model.drafts)
              in
                { model | mode = TechniqueDetails technique s ui editInfo, drafts = drafts }
            _ -> model
      in
        updatedStoreTechnique newModel

    ResetMethodCall call  ->
      let
        newModel =
          case model.mode of
            TechniqueDetails technique s ui editInfo ->
              let
                origin =
                  case s of
                    Edit t -> t
                    Clone t _ _ -> t
                    Creation _ -> technique
                (updatedTechnique, needCheck) =
                  case findElemIf  (getId >> (==) (getId call)) origin.elems of
                         Just resetCall -> ({ technique | elems = updateElemIf (getId >> (==) (getId call)) (always resetCall) technique.elems }, Just resetCall)
                         Nothing -> (technique,Nothing)
                newUi =
                  case needCheck of
                    Just (Call _ realCall )->
                      let
                        constraints = case Dict.get realCall.methodName.value model.methods of
                           Just m -> Dict.fromList (List.map (\p -> (p.name.value, p.constraints))  m.parameters)
                           Nothing -> Dict.empty

                        updateCallUi = \optCui ->
                          let
                            b = case optCui of
                              Nothing -> (defaultMethodUiInfo (Just realCall))
                              Just cui -> cui


                            newValidation =
                                 List.foldl ( \param val ->
                                   accumulateErrorConstraint  param (Maybe.withDefault defaultConstraint (Dict.get param.id.value constraints)) val
                                 ) b.validation realCall.parameters

                          in
                            Just { b | validation = newValidation }
                      in
                        { ui | callsUI = Dict.update realCall.id.value updateCallUi  ui.callsUI }
                    Just (Block _ b)  ->
                        let
                          updateCallUi = \optCui ->
                            case optCui of
                              Nothing -> Just (defaultBlockUiInfo b)
                              Just cui -> Just { cui | validation = Unchanged }
                        in
                          { ui | blockUI = Dict.update b.id.value updateCallUi  ui.blockUI }
                    Nothing -> ui

              in
                { model | mode = TechniqueDetails updatedTechnique s newUi editInfo }
            _ -> model
      in
        updatedStoreTechnique newModel

    -- export a technique to its JSON descriptor
    Export ->
      let
        action = case model.mode of
                   TechniqueDetails t _ _ _ ->
                       getTechniqueYaml model t
                   _ -> Cmd.none
      in
        (model, action)

-- Edit a technique: parameter tab

    TechniqueParameterModified paramId newValue ->
      let
        newModel =
          case model.mode of
            TechniqueDetails t o ui editInfo->
              let
                parameters = List.Extra.updateIf (\c -> paramId == c.id ) (always newValue)  t.parameters
                technique = { t | parameters = parameters }
              in
                { model | mode = TechniqueDetails technique o ui editInfo }
            _ -> model
      in
       updatedStoreTechnique newModel

    TechniqueParameterRemoved paramId ->
      let
        newModel =
          case model.mode of
            TechniqueDetails t o ui editInfo->
              let
                parameters = List.Extra.filterNot (\c -> paramId == c.id ) t.parameters
                technique = { t | parameters = parameters }
              in
                { model | mode = TechniqueDetails technique o ui editInfo }
            _ -> model
      in
        updatedStoreTechnique newModel

    TechniqueParameterAdded paramId ->
      let
        newMode =
          case model.mode of
            TechniqueDetails t o ui editInfo->
              let
                parameters = List.append t.parameters [  TechniqueParameter paramId "" Nothing Nothing False defaultConstraint]
              in
                TechniqueDetails { t | parameters = parameters } o ui editInfo
            _ -> model.mode
      in
        ({ model | mode = newMode}, Cmd.none )



-- Edit a technique: resource tab, file manager, etc.

    OpenFileManager ->
      let
        cmd = case model.mode of
                TechniqueDetails t s _ editInfo ->
                  let
                    url = case s of
                            Edit _ ->  t.id.value ++ "/" ++ t.version ++ "/" ++ t.category
                            Creation internalId -> "draft/" ++ internalId.value ++ "/" ++ t.version
                            Clone _ _ internalId -> "draft/" ++ internalId.value ++ "/" ++ t.version
                  in
                    openManager (model.contextPath ++ "/secure/api/resourceExplorer/"  ++ url)
                _ -> Cmd.none
      in
      (model, cmd)

    GetTechniqueResources (Ok  (metadata, resources)) ->
      let
        mode = case model.mode of
                 TechniqueDetails t s ui editInfo ->
                   TechniqueDetails {t | resources = resources } s ui editInfo
                 _ -> model.mode
      in
        ({ model | mode = mode },  Cmd.none )
    GetTechniqueResources (Err e) ->
      ( model , errorNotification("An error occurred while getting resources: " ++ (debugHttpErr e) ))

-- Edit a technique: generic method high level action (list/etc)

    OpenMethods ->
      ( { model | genericMethodsOpen = True } , Cmd.none )

    GetMethods (Ok  (metadata, methods)) ->
      ({ model | methods = methods}, getTechniques model  )

    GetMethods (Err err) ->
      ({ model | loadingTechniques = False}, errorNotification ("Error when getting methods: " ++ debugHttpErr err ) )

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
        (model, scrollMethod ((not model.genericMethodsOpen) , category) )

    ToggleDoc methodId ->
      let
        ui = model.methodsUI
        newDocs = if List.member methodId ui.docsOpen then List.Extra.remove methodId ui.docsOpen else methodId :: ui.docsOpen
      in
        ( { model | methodsUI = { ui | docsOpen = newDocs }, genericMethodsOpen = True } , scrollMethod ((not model.genericMethodsOpen) , methodId.value))

    ShowDoc methodId ->
      let
        ui = model.methodsUI
        newDocs = if List.member methodId ui.docsOpen then ui.docsOpen else methodId :: ui.docsOpen
      in
      ( { model | methodsUI = { ui | docsOpen = newDocs }, genericMethodsOpen = True } , Cmd.batch [clearTooltips "", scrollMethod ((not model.genericMethodsOpen) , methodId.value)])

    AddMethod method newId ->
      if model.hasWriteRights then
      let
        disableReporting = False
        newCall = MethodCall newId method.id (List.map (\p -> CallParameter p.name [Value ""]) method.parameters) (Condition Nothing "") "" disableReporting Nothing Nothing Nothing
        newModel =
          case model.mode of
            TechniqueDetails t o ui editInfo ->
              let
                technique =  { t | elems = t.elems ++ [Call Nothing newCall] }
                defaultUiMethod = defaultMethodUiInfo (Just newCall)
                newUi = { ui | callsUI = Dict.update newId.value (always (Just ({defaultUiMethod | mode = Opened} ))) ui.callsUI }
              in
              { model | mode = TechniqueDetails technique o newUi editInfo }
            _ -> model
      in
        updatedStoreTechnique newModel
      else
        (model,Cmd.none)

    AddBlock newId ->
      if model.hasWriteRights then
      let
        newCall = MethodBlock newId "" (Condition Nothing "") WeightedReport [] Nothing Nothing Nothing
        newModel =
          case model.mode of
            TechniqueDetails t o ui editInfo ->
              let
                technique =  { t | elems =  t.elems ++ [Block Nothing newCall]  }
                newUi = { ui | blockUI = Dict.update newId.value (always (Just (MethodBlockUiInfo Opened Children (InvalidState [EmptyComponent]) False (ForeachUI False False (defaultNewForeach newCall.foreachName newCall.foreach)))) ) ui.blockUI }
              in
              { model | mode = TechniqueDetails technique o newUi editInfo }
            _ -> model
      in
        updatedStoreTechnique newModel
      else
        (model,Cmd.none)


    SetCallId newId ->
      let
        newMode =
          case model.mode of
            TechniqueDetails t o ui editInfo ->
              let
                technique = { t | elems = updateElemIf (getId >> .value >> (==) "") (setId newId) t.elems }
                newUi = { ui | callsUI = Dict.insert newId.value (defaultMethodUiInfo Nothing) ui.callsUI}
              in
                TechniqueDetails technique o newUi editInfo
            m -> m
      in
        ( { model | mode = newMode } , Cmd.none )

-- Edit a technique: edit one generic method

    UpdateCallAndUi uiInfo ->
      let
        (uiAction, callAction)  = case uiInfo of
          CallUiInfo methodCallUiInfo call ->
            ( UIMethodAction call.id methodCallUiInfo
            , MethodCallModified (Call (Just call.id) call) (Just uiInfo)
            )
          BlockUiInfo methodBlockUiInfo block ->
            ( UIBlockAction block.id methodBlockUiInfo
            , MethodCallModified (Block (Just block.id) block) (Just uiInfo)
            )
        (newModel, newMsg) = update uiAction model
        (finalModel, finalMsg) = update callAction newModel
      in
        (finalModel, Cmd.batch[newMsg, finalMsg])

    UIMethodAction callId newMethodUi ->
      let
        newMode =
          case model.mode of
            TechniqueDetails t o ui editInfo->
              let
                newUi = {ui | callsUI = Dict.update callId.value (Maybe.map (always newMethodUi )) ui.callsUI }
              in
                TechniqueDetails t o newUi editInfo
            m -> m
      in
        ({ model | mode = newMode}, initInputs "" )

    UIBlockAction callId newBlockUi ->
      let
        newMode =
          case model.mode of
           TechniqueDetails t o ui editInfo->
             let
               newUi = {ui | blockUI = Dict.update  callId.value (always (Just newBlockUi) ) ui.blockUI }
             in
               TechniqueDetails t o newUi editInfo
           m -> m
      in
        ({ model | mode = newMode}, initInputs "" )


    RemoveMethod callId ->
      let
        newMode =
          case model.mode of
           TechniqueDetails t o ui editInfo ->
            let
              technique = { t |  elems = removeElem (getId >> (==) callId) t.elems }
            in
            TechniqueDetails technique o ui editInfo
           m -> m
        newModel = { model | mode = newMode}
      in
        updatedStoreTechnique newModel

    CloneElem call newId ->
      let
        clone = case setId newId call of
                  Call p c -> Call p c
                  Block p b -> Block p {b | calls = [] }
        newModel =
          case model.mode of
            TechniqueDetails t o ui editInfo ->
              let
                newMethods =
                   case getParent clone of
                     Nothing ->
                        let
                          (heads, tails) = Maybe.withDefault (t.elems,[]) (List.Extra.splitWhen(\e -> getId e == getId call) t.elems)
                        in
                          List.append heads (clone :: tails)
                     Just pid -> updateElemIf (getId >> (==) pid) ( \child -> case child of
                                                                                Call _  _ -> child
                                                                                Block p b ->
                                                                                  let
                                                                                    (heads, tails) = Maybe.withDefault (b.calls,[]) (List.Extra.splitWhen (\e -> getId e == getId call) b.calls)
                                                                                  in
                                                                                  Block p {b | calls = List.append heads  ( clone :: tails ) }
                                                                  ) t.elems
                technique = { t |  elems = newMethods}
                newUi = case call of
                          Call _ c  -> { ui | callsUI = Dict.update newId.value (always (Just (defaultMethodUiInfo (Just c)))) ui.callsUI }
                          Block _ b  -> { ui | blockUI = Dict.update newId.value (always (Just (defaultBlockUiInfo b))) ui.blockUI }
                m = { model | mode = TechniqueDetails technique o newUi editInfo }
              in
                case call of
                          Call _ _  -> m
                          Block _ b  ->

                            let
                              res = List.map (\ child  ->
                                                     let
                                                       elem = case child of
                                                                Call _ c -> Call (Just (getId clone)) c
                                                                Block _ c -> Block (Just (getId clone)) c
                                                     in
                                                       GenerateId (\s -> CloneElem  elem (CallId s))
                                                   )  b.calls
                            in
                              {m| recClone = List.append m.recClone res}
            _ -> model
      in
        case newModel.recClone of
          [] -> updatedStoreTechnique newModel
          h :: t ->
            update h {newModel  | recClone = t}

    MethodCallModified method uiInfo ->
      case model.mode of
        TechniqueDetails t s ui editInfo ->
          let
            newUi =
              case (method, uiInfo) of
                ( Block id block, (Just (BlockUiInfo methodBlockUiInfo _))) ->
                     let
                       blockState = checkBlockConstraint block
                       updateBlockState = \originUiBlock -> case originUiBlock of
                                                              Just _ -> Just { methodBlockUiInfo | validation = blockState}
                                                              Nothing -> Just (MethodBlockUiInfo Closed Children blockState False (ForeachUI False False (defaultNewForeach block.foreachName block.foreach)))
                     in
                      { ui | blockUI = Dict.update block.id.value updateBlockState ui.blockUI  }
                ( Block id block, Nothing) ->
                     let
                       blockState = checkBlockConstraint block
                       updateBlockState = \originUiBlock -> case originUiBlock of
                                                              Just uiBlock -> Just { uiBlock | validation = blockState}
                                                              Nothing -> Just (MethodBlockUiInfo Closed Children blockState False (ForeachUI False False (defaultNewForeach block.foreachName block.foreach)))
                     in
                      { ui | blockUI = Dict.update block.id.value updateBlockState ui.blockUI  }

                (Call _ _, (Just (CallUiInfo methodCallUiInfo call))) ->
                    {ui | callsUI = Dict.update call.id.value (Maybe.map (always methodCallUiInfo )) ui.callsUI }
                _ -> ui


            newModel = {model | mode = TechniqueDetails {t | elems = updateElemIf (getId >> (==) (getId method) ) (always method) t.elems} s newUi editInfo}
          in
            updatedStoreTechnique newModel
        _ -> (model,Cmd.none)

    MethodCallParameterModified call paramId newValue ->
      let
        newModel =
          case model.mode of
            TechniqueDetails t o ui editInfo->
              let

                calls = updateElemIf (getId >> (==) call.id )  (updateParameter paramId newValue) t.elems
                constraints = case Dict.get call.methodName.value model.methods of
                           Just m -> Maybe.withDefault defaultConstraint (Maybe.map (.constraints) (List.Extra.find (.name >> (==) paramId) m.parameters))
                           Nothing -> defaultConstraint

                updateCallUi = \optCui ->
                  let
                    base = case optCui of
                            Nothing -> (defaultMethodUiInfo (Just call))
                            Just cui -> cui
                    filterValidation = case base.validation of
                                         InvalidState err -> let
                                                               newErrors = List.filter (\(ConstraintError v) -> v.id /= paramId ) err
                                                             in
                                                               if (List.isEmpty newErrors) then ValidState else InvalidState newErrors
                                         _ -> base.validation
                    newValidation =  accumulateErrorConstraint  (CallParameter paramId (getAgentValue newValue)) constraints filterValidation
                  in
                    Just { base | validation = newValidation }
                callUi  =
                  Dict.update call.id.value updateCallUi  ui.callsUI
                technique = { t | elems = calls }
              in
                { model | mode = TechniqueDetails technique o {ui | callsUI = callUi } editInfo }
            _ -> model
      in
        updatedStoreTechnique newModel

    SetMissingIds newId ->
      case model.mode of
        TechniqueDetails t e u editInfo->
          let
           newUi = { u | callsUI = Dict.update newId (always (Just (defaultMethodUiInfo Nothing)) ) u.callsUI }
          in
          case setIdRec newId t.elems of
            (_, False) -> updatedStoreTechnique model
            (newCalls, True) -> update (GenerateId SetMissingIds) { model | mode = TechniqueDetails {t  | elems = newCalls} e newUi editInfo }
        _ ->
          (model, Cmd.none)

    MoveStarted draggedItemId ->
      ( { model | dnd = DragDrop.startDragging model.dnd draggedItemId, dropTarget =  Nothing }, clearTooltips "" )

    MoveTargetChanged dropTargetId ->
      ( { model | dnd = DragDrop.updateDropTarget model.dnd dropTargetId, dropTarget = Just dropTargetId }, Cmd.none  )

    MoveCanceled ->
      ( { model | dnd = DragDrop.stopDragging model.dnd }, Cmd.none )

    MoveCompleted draggedItemId dropTarget ->
      case model.mode of
        TechniqueDetails t u e editInfo ->
          let
            (baseCalls, newElem) =
              case draggedItemId of
                Move b ->  ( removeElem (getId >> (==) (getId b)) t.elems, b)
                NewBlock -> (t.elems, Block Nothing (MethodBlock (CallId "") "" (Condition Nothing "") WeightedReport [] Nothing Nothing Nothing))
                NewMethod method ->
                 let
                   disableReporting = False
                 in
                   (t.elems, Call Nothing (MethodCall (CallId "") method.id (List.map (\p -> CallParameter p.name [Value ""]) method.parameters) (Condition Nothing "") "" disableReporting  Nothing Nothing Nothing))
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
                                                    Block p k -> Block p { k | calls = newElem :: k.calls }
                                                    _ -> x
                                                  ) baseCalls
            updateTechnique = { t | elems = updatedCalls}
            newModel = { model | mode = TechniqueDetails updateTechnique u e editInfo , dnd = DragDrop.initialState}
          in
            update (GenerateId SetMissingIds ) newModel
        _ ->
          (model, Cmd.none)

    CompleteMove ->
      let
        newMsg =
          case (model.dropTarget, DragDrop.currentlyDraggedObject model.dnd) of
            (Just drop, Just drag) -> MoveCompleted drag  drop
            _ -> MoveCanceled
      in
        update newMsg model

    Notification notif notifMsg ->
      (model, notif notifMsg)
    DisableDragDrop ->
      case model.mode of
        TechniqueDetails t e u editInfo ->
          ({model | mode = TechniqueDetails t e {u | enableDragDrop = Nothing} editInfo }, Cmd.none )
        _ ->
          (model, Cmd.none)
    EnableDragDrop id ->
      case model.mode of
        TechniqueDetails t e u editInfo ->
          ({model | mode = TechniqueDetails t e {u | enableDragDrop = Just id} editInfo }, Cmd.none )
        _ ->
          (model, Cmd.none)
    HoverMethod id ->
      ({model | isMethodHovered = id} , Cmd.none)

    GetYaml result ->
      case result of
          Ok (_, content) ->
             case model.mode of

               TechniqueDetails t o ui editInfo->
                   (model, File.Download.string (t.id.value ++ ".yml") "application/json" content)
               _ -> (model, Cmd.none)
          Err e -> (model, Cmd.none)

    CheckOutJson mode result ->
       case result of
          Ok (_, technique) ->
            case mode of
              Import _ ->
                let
                  callState = (Dict.fromList (List.map (\c -> (c.id.value, (defaultMethodUiInfo (Just c)))) (List.concatMap getAllCalls technique.elems)))
                  blockState = (Dict.fromList (List.map (\c -> (c.id.value, (defaultBlockUiInfo c))) (List.concatMap getAllBlocks technique.elems)))
                  ui = TechniqueUiInfo General callState blockState [] False Unchanged Unchanged Nothing
                  editInfo = TechniqueEditInfo "" False (Ok ())
                in
                  update (GenerateId FinalizeImport) { model | mode = TechniqueDetails technique (Creation (TechniqueId "")) ui editInfo}
              EditYaml _->
                case model.mode of
                  TechniqueDetails t o ui oldEdit ->
                    ({model | mode = TechniqueDetails technique o ui oldEdit} , Cmd.none)
                  _ ->
                    (model, Cmd.none)
              CheckJson  _->
                  (model, Cmd.none)


          Err e -> (model, Cmd.none)

    CheckOutYaml mode result ->
       case result of
          Ok (_, yaml) ->
            case mode of
              Import _ ->
                (model,Cmd.none)
              CheckJson _ ->
                case model.mode of
                  TechniqueDetails t o ui oldEdit ->
                    ({model | mode = TechniqueDetails t o ui {oldEdit | value = yaml }} , Cmd.none)
                  _ ->
                    (model, Cmd.none)
              EditYaml _ ->
                (model, Cmd.none)

          Err e -> (model, Cmd.none)

    FinalizeImport draftId ->
      case model.mode of
        TechniqueDetails t _ ui editInfo ->
          updatedStoreTechnique {model | mode = TechniqueDetails t (Creation (TechniqueId draftId)) ui editInfo }
        _ ->
          (model, Cmd.none)

    UpdateEdition edition ->
      case model.mode of
        TechniqueDetails t o ui oldEdit->
            let
              newModel =  { model | mode = TechniqueDetails t o ui edition }
              cmd = if edition.open && not oldEdit.open then checkTechnique (CheckJson t) newModel
                    else if edition.value /= oldEdit.value then checkTechnique (EditYaml edition.value) newModel
                    else Cmd.none
            in
            ( newModel , cmd)
        _ -> (model,Cmd.none)





