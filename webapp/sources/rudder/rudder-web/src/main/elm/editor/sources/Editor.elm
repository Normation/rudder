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
import Http.Detailed as Detailed
import Json.Decode exposing ( Value )
import Json.Encode
import JsonEncoder exposing (encodeDraft, encodeExportTechnique, encodeTechnique)
import JsonDecoder exposing (decodeDraft, decodeErrorDetails, decodeTechnique)
import List.Extra
import Maybe.Extra
import MethodConditions exposing (..)
import Random
import Task
import Time
import UUID
import ViewTechnique exposing ( view, checkTechniqueName, checkTechniqueId )
import ViewMethod exposing ( accumulateErrorConstraint )
import ViewTechniqueList exposing (allMethodCalls)
import MethodElemUtils exposing (..)
import Http exposing ( Error )
import AgentValueParser exposing (..)

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
    TechniqueDetails _ s _ -> CallApi ( getRessources s )
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
    model =  Model [] Dict.empty (TechniqueCategory "" "" "" (SubCategories [])) Dict.empty Introduction initValues.contextPath (TreeFilters "" []) (MethodListUI (MethodFilter "" False Nothing FilterClosed) []) False DragDrop.initialState Nothing initValues.hasWriteRights Nothing Nothing True
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
                               (Dict.insert draft.id draft model.drafts, Cmd.batch[clearTooltips "", storeDraft (encodeDraft draft)] )
            Creation id ->
              let
               draft = Draft  t Nothing id.value (Time.millisToPosix 0)
              in
                (Dict.insert draft.id draft model.drafts, Cmd.batch[clearTooltips "", storeDraft (encodeDraft draft)] )
            Clone origin id ->
              let
               draft = Draft  t Nothing id.value (Time.millisToPosix 0)
              in
                (Dict.insert draft.id draft model.drafts, Cmd.batch[clearTooltips "", storeDraft (encodeDraft draft)] )
      in
         ({ model | drafts = drafts }, action)
    _ -> (model, clearTooltips "")

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
  MethodCallUiInfo Closed CallParameters Unchanged
defaultBlockUiInfo =
  MethodBlockUiInfo Closed Children Unchanged False

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
    callState = (Dict.fromList (List.map (\c -> (c.id.value, defaultMethodUiInfo)) (List.concatMap getAllCalls effectiveTechnique.elems)))
    blockState = (Dict.fromList (List.map (\c -> (c.id.value, defaultBlockUiInfo)) (List.concatMap getAllBlocks effectiveTechnique.elems)))
    ui = TechniqueUiInfo General callState blockState [] False Unchanged Unchanged Nothing
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

    GetCategories (Ok (metadata, categories)) ->
      ({ model | categories = categories}, Cmd.none )
    GetCategories (Err _) ->
      ( model , Cmd.none )

    GetTechniques (Ok  (metadata, techniques)) ->
      ({ model | techniques = techniques, loadingTechniques = False},  getUrl () )
    GetTechniques (Err err) ->
      ({ model | loadingTechniques = False} , errorNotification  ("Error when getting techniques: " ++ debugHttpErr err  ) )

    OpenTechniques ->
      ( { model | genericMethodsOpen = False } , Cmd.none )

    UpdateTechniqueFilter treeFilter ->
      ( { model | techniqueFilter = treeFilter } , Cmd.none )

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
        ui = TechniqueUiInfo General Dict.empty Dict.empty [] False Unchanged Unchanged Nothing
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
            callsState = (Dict.fromList (List.map (\c -> (c.id.value, defaultMethodUiInfo)) (List.concatMap allMethodCalls t.elems)))
            bloksState = (Dict.fromList (List.map (\c -> (c.id.value, defaultBlockUiInfo)) (List.concatMap getAllBlocks t.elems)))
            mode = TechniqueDetails t (Creation t.id) (
                     TechniqueUiInfo General callsState bloksState [] False (checkTechniqueName t model) (checkTechniqueId (Creation t.id) t model) Nothing
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

    UpdateTechnique technique ->
      let
        newModel =
          case model.mode of
            TechniqueDetails _ o ui ->

              { model | mode = TechniqueDetails technique o {ui |  nameState = checkTechniqueName technique model, idState = checkTechniqueId o technique model } }
            _ -> model
      in
        updatedStoreTechnique newModel

    GetDrafts drafts invalidDraftsToClean->
      ({ model | drafts = drafts }, Cmd.batch (List.map clearDraft invalidDraftsToClean))

    CloneTechnique technique internalId ->
      let
        copiedName = technique.name ++ " (Copy)"
        newId = canonifyHelper (Value (String.toLower copiedName))
        callState =  Dict.fromList (List.map (\c -> (c.id.value, defaultMethodUiInfo)) (List.concatMap allMethodCalls technique.elems))
        blockState =  Dict.fromList (List.map (\c -> (c.id.value, defaultBlockUiInfo)) (List.concatMap getAllBlocks technique.elems))
        ui = TechniqueUiInfo General callState  blockState [] False Unchanged Unchanged Nothing
        newModel = { model | mode = TechniqueDetails {technique | name = copiedName, id = TechniqueId newId}  (Clone technique internalId) ui }
      in
        updatedStoreTechnique newModel

    SaveTechnique (Ok (metadata, technique)) ->
      let
        techniques = if (List.any (.id >> (==) technique.id) model.techniques) then
           List.Extra.updateIf (.id >> (==) technique.id ) (always technique) model.techniques
         else
           technique :: model.techniques
        (newMode, idToClean) =
          case model.mode of
            TechniqueDetails _ (Edit _) ui ->
              (TechniqueDetails technique (Edit technique) ui, technique.id)
            TechniqueDetails _ (Creation id) ui ->
              (TechniqueDetails technique (Edit technique) ui, id)
            TechniqueDetails _ (Clone _ id) ui ->
              (TechniqueDetails technique (Edit technique) ui, id)
            m -> (m, technique.id)
        drafts = Dict.remove idToClean.value model.drafts
      in
        ({ model | techniques = techniques, mode = newMode, drafts = drafts}, Cmd.batch [ clearDraft idToClean.value, successNotification "Technique saved!", pushUrl technique.id.value] )

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

    DeleteTechnique (Ok (metadata, techniqueId)) ->
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
            TechniqueDetails technique s ui ->
              let
                origin =
                  case s of
                    Edit t -> t
                    Clone t _ -> t
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
                              Nothing -> MethodCallUiInfo Closed CallParameters Unchanged
                              Just cui -> cui


                            newValidation =
                                 List.foldl ( \param val ->
                                   accumulateErrorConstraint  param (Maybe.withDefault [] (Dict.get param.id.value constraints)) val
                                 ) b.validation realCall.parameters

                          in
                            Just { b | validation = newValidation }
                      in
                        { ui | callsUI = Dict.update realCall.id.value updateCallUi  ui.callsUI }
                    Just (Block _ b)  ->
                        let
                          updateCallUi = \optCui ->
                            case optCui of
                              Nothing -> Just defaultBlockUiInfo
                              Just cui -> Just { cui | validation = Unchanged }
                        in
                          { ui | blockUI = Dict.update b.id.value updateCallUi  ui.blockUI }
                    Nothing -> ui

              in
                { model | mode = TechniqueDetails updatedTechnique s newUi }
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

    GetTechniqueResources (Ok  (metadata, resources)) ->
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
        newCall = MethodBlock newId "" (Condition Nothing "") WeightedReport []
        newModel =
          case model.mode of
            TechniqueDetails t o ui ->
              let
                technique =  { t | elems =  t.elems ++ [Block Nothing newCall]  }
                newUi = { ui | blockUI = Dict.update newId.value (always (Just (MethodBlockUiInfo Closed Children (InvalidState [EmptyComponent]) False)) ) ui.blockUI }
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


    UIBlockAction callId newBlockUi ->
      let
        newMode =
          case model.mode of
           TechniqueDetails t o ui->
             let
               newUi = {ui | blockUI = Dict.update  callId.value (Maybe.map (always newBlockUi )) ui.blockUI }
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

    CloneElem call newId ->
      let
        clone = case setId newId call of
                  Call p c -> Call p c
                  Block p b -> Block p {b | calls = [] }
        (newModel, msgs) =
          case model.mode of
            TechniqueDetails t o ui ->
              let
                newMethods =
                   case getParent clone of
                     Nothing ->
                        let
                          (heads, tails) = Maybe.withDefault ([],[]) (List.Extra.splitWhen (\e -> getId e == getId call) t.elems)
                        in
                          List.append heads (clone :: tails)
                     Just pid -> updateElemIf (getId >> (==) pid) ( \child -> case child of
                                                                                Call _  _ -> child
                                                                                Block p b ->
                                                                                  let
                                                                                    (heads, tails) = Maybe.withDefault ([],[]) (List.Extra.splitWhen (\e -> getId e == getId call) b.calls)
                                                                                  in
                                                                                  Block p {b | calls = List.append heads  ( clone :: tails ) }
                                                                  ) t.elems
                technique = { t |  elems = newMethods}
                newUi = case call of
                          Call _ _  -> { ui | callsUI = Dict.update newId.value (always (Just defaultMethodUiInfo)) ui.callsUI }
                          Block _ _  -> { ui | blockUI = Dict.update newId.value (always (Just defaultBlockUiInfo)) ui.blockUI }
                m = { model | mode = TechniqueDetails technique o newUi }
              in
                case call of
                          Call _ _  -> (m, [])
                          Block _ b  ->

                            let
                              res = List.map (\child  ->
                                                     let
                                                       elem = case child of
                                                                Call _ c -> Call (Just (getId clone)) c
                                                                Block _ c -> Block (Just (getId clone)) c
                                                     in
                                                       Tuple.second (update (GenerateId (\s -> CloneElem  elem (CallId s))) m)
                                                   ) b.calls
                            in
                              (m,res)
            _ -> (model, [])
      in
            Tuple.mapSecond (\c -> ( c :: (List.reverse msgs)) |> Cmd.batch) (updatedStoreTechnique newModel)

    MethodCallModified method ->
      case model.mode of
        TechniqueDetails t s ui ->
          let
            newUi =
              case method of
                Block id block ->
                 let
                   blockState = checkBlockConstraint block
                   updateBlockState = \originUiBlock -> case originUiBlock of
                                                          Just uiBlock -> Just { uiBlock | validation = blockState}
                                                          Nothing -> Just (MethodBlockUiInfo Closed Children blockState False)
                 in
                  { ui | blockUI = Dict.update block.id.value updateBlockState ui.blockUI  }
                Call _ _ -> ui
            newModel = {model | mode = TechniqueDetails {t | elems = updateElemIf (getId >> (==) (getId method) ) (always method) t.elems} s newUi}
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
                            Nothing -> MethodCallUiInfo Closed CallParameters Unchanged
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
            (_, False) -> updatedStoreTechnique model
            (newCalls, True) -> update (GenerateId SetMissingIds) { model | mode = TechniqueDetails {t  | elems = newCalls} e newUi }

    MoveStarted draggedItemId ->
      ( { model | dnd = DragDrop.startDragging model.dnd draggedItemId, dropTarget =  Nothing }, clearTooltips "" )

    MoveTargetChanged dropTargetId ->
      ( { model | dnd = DragDrop.updateDropTarget model.dnd dropTargetId, dropTarget = Just dropTargetId }, Cmd.none  )

    MoveCanceled ->
      ( { model | dnd = DragDrop.stopDragging model.dnd }, Cmd.none )

    MoveCompleted draggedItemId dropTarget ->
      case model.mode of
        Introduction -> (model, Cmd.none)
        TechniqueDetails t u e ->
          let
            (baseCalls, newElem) =
              case draggedItemId of
                Move b ->  ( removeElem (getId >> (==) (getId b)) t.elems, b)
                NewBlock -> (t.elems, Block Nothing (MethodBlock (CallId "") "" (Condition Nothing "") WeightedReport []))
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
                                                    Block p k -> Block p { k | calls = newElem :: k.calls }
                                                    _ -> x
                                                  ) baseCalls
            updateTechnique = { t | elems = updatedCalls}
            newModel = { model | mode = TechniqueDetails updateTechnique u e , dnd = DragDrop.initialState}
          in
            update (GenerateId SetMissingIds ) newModel

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
        Introduction -> (model, Cmd.none)
        TechniqueDetails t e u ->
          ({model | mode = TechniqueDetails t e {u | enableDragDrop = Nothing} }, Cmd.none )
    EnableDragDrop id ->
      case model.mode of
        Introduction -> (model, Cmd.none)
        TechniqueDetails t e u ->
          ({model | mode = TechniqueDetails t e {u | enableDragDrop = Just id} }, Cmd.none )
    HoverMethod id ->
      ({model | isMethodHovered = id} , Cmd.none)
