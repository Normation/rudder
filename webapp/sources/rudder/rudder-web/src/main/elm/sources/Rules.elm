port module Rules exposing (..)

import Browser
import Browser.Navigation as Nav
import Dict
import Dict.Extra
import Http exposing (..)
import Result
import List.Extra
import Maybe.Extra exposing (isNothing)
import Random
import Rules.ChangeRequest exposing (initCrSettings)
import UUID
import Json.Encode exposing (..)

import Rules.ApiCalls exposing (..)
import Rules.DataTypes exposing (..)
import Rules.Init exposing (init)
import Rules.View exposing (view)
import Rules.ViewUtils exposing (..)

import Ui.Datatable exposing (SortOrder(..), Category, SubCategories(..))


-- PORTS / SUBSCRIPTIONS
port linkSuccessNotification : Value -> Cmd msg
port successNotification : String -> Cmd msg
port warningNotification : String -> Cmd msg
port errorNotification   : String -> Cmd msg
port pushUrl             : (String,String) -> Cmd msg
port initTooltips        : String -> Cmd msg
port readUrl : ((String, String) -> msg) -> Sub msg
port copy : String -> Cmd msg

subscriptions : Model -> Sub Msg
subscriptions _ =
  Sub.batch
  [ readUrl ( \(kind,id) -> case kind of
              "rule" -> OpenRuleDetails (RuleId id) False
              "ruleCategory" -> OpenCategoryDetails id False
              _ -> Ignore
            )
  ]

main =
  Browser.element
    { init = init
    , view = view
    , update = update
    , subscriptions = subscriptions
    }

generator : Random.Generator String
generator = Random.map (UUID.toString) UUID.generator

defaultRulesUI = RuleDetailsUI False False (Tag "" "") Dict.empty
--
-- update loop --
--
update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
  case msg of
    Copy s -> (model, copy s)
-- utility methods
    -- Generate random id
    GenerateId nextMsg ->
      (model, Random.generate nextMsg generator)
    -- Do an API call
    CallApi saving call ->
      let
        ui = model.ui
        newModel = {model | ui = {ui | saving = saving}}
      in
        (newModel, call model)
    -- neutral element
    Ignore ->
      ( model , Cmd.none)

    -- UI high level stuff: list rules and other elements needed (groups, directives...)
    GetRulesResult res ->
      case  res of
        Ok r ->
            ( { model |
                  rulesTree = r
                , mode = if (model.mode == Loading) then RuleTable else model.mode
              }
              , initTooltips ""
             )
        Err err ->
          processApiError "Getting Rules tree" err model
    GetPolicyModeResult res ->
      case res of
        Ok p ->
            ( { model | policyMode = p }
              , initTooltips ""
            )
        Err err ->
          processApiError "Getting Policy Mode" err model

    GetEnableChangeMsg res ->
      case res of
        Ok setting ->
          let
            ui = model.ui
            initCr = initCrSettings
            settings = case model.ui.crSettings of
              Just s -> Just {s | enableChangeMessage = setting}
              Nothing -> Just {initCr | enableChangeMessage = setting}
          in
            ( { model | ui = { ui | crSettings = settings } }
              , Cmd.none
            )
        Err err ->
          processApiError "Getting change request settings `enable_change_message`" err model

    GetMandatoryMsg res ->
      case res of
        Ok setting ->
          let
            ui = model.ui
            initCr = initCrSettings
            settings = case model.ui.crSettings of
              Just s -> Just {s | mandatoryChangeMessage = setting}
              Nothing -> Just {initCr | mandatoryChangeMessage = setting}
          in
            ( { model | ui = { ui | crSettings = settings } }
              , Cmd.none
            )
        Err err ->
          processApiError "Getting change request settings `mandatory_change_message`" err model

    GetMsgPrompt res ->
      case res of
        Ok setting ->
          let
            ui = model.ui
            crSettings = ui.crSettings
            initCr = initCrSettings
            settings = case model.ui.crSettings of
              Just s -> Just {s | changeMessagePrompt = setting}
              Nothing -> Just {initCr | changeMessagePrompt = setting}
          in
            ( { model | ui = { ui | crSettings = settings } }
              , Cmd.none
            )
        Err err ->
          processApiError "Getting change request settings `change_message_prompt`" err model

    GetEnableCr res ->
      case res of
        Ok settingEnabledCR ->
          let
            ui = model.ui
            getPendingCR = case (model.mode, settingEnabledCR) of
              (RuleForm details, True) -> getPendingChangeRequests model details.rule.id
              _ -> Cmd.none
            initCr = initCrSettings
            settings = case model.ui.crSettings of
              Just s -> Just {s | enableChangeRequest = settingEnabledCR}
              Nothing -> Just {initCr | enableChangeRequest = settingEnabledCR}
          in
            ( { model | ui = { ui | crSettings = settings } }
              , getPendingCR
            )
        Err err ->
          processApiError "Getting change request settings `enable_change_request`" err model

    GetPendingChangeRequests res ->
      case res of
        Ok cr ->
          let
            ui = model.ui
            initCr = initCrSettings
            newUi = case ui.crSettings of
              Just settings -> { ui | crSettings = Just { settings | pendingChangeRequests = cr } }
              Nothing -> { ui | crSettings = Just { initCr | pendingChangeRequests = cr } }
          in
            ( { model | ui = newUi } , Cmd.none )
        Err err ->
          processApiError "Getting pending change requests" err model

    GetGroupsTreeResult res ->
      case res of
        Ok t ->
          ( { model | groupsTree = t }
            , Cmd.none
          )
        Err err ->
          processApiError "Getting Groups tree" err model

    GetTechniquesTreeResult res ->
      case res of
        Ok (t,d) ->
          let
            modelUi = model.ui
          in
          ( { model | techniquesTree = t, directives = Dict.Extra.fromListBy (.id >> .value) (List.concatMap .directives d), ui = { modelUi | loadingRules = False} }
            , Cmd.none
          )
        Err err ->
          processApiError "Getting Directives tree" err model

    GetRuleDetailsResult res ->
      case res of
        Ok r ->
          let
            newModel = {model | mode = RuleForm (RuleDetails (Just r) r Information defaultRulesUI Nothing Nothing Nothing []) }
            getPendingCR = case model.ui.crSettings of
              Nothing -> Cmd.none
              Just settings -> if settings.enableChangeRequest then (getPendingChangeRequests newModel r.id) else Cmd.none
            getChanges = case Dict.get r.id.value model.changes of
                           Nothing -> []
                           Just changes ->
                             case List.Extra.last changes of
                               Nothing -> []
                               Just lastChanges -> [ getRepairedReports newModel r.id lastChanges.start lastChanges.end ]
          in
            (newModel, Cmd.batch (getRulesComplianceDetails r.id newModel :: getRuleNodesDirectives r.id newModel :: getPendingCR :: getChanges ) )
        Err err ->
          processApiError "Getting Rule details" err model

    GetCategoryDetailsResult res ->
      case res of
        Ok c ->
          ({model | mode = CategoryForm (CategoryDetails (Just c) c (getParentCategoryId (getListCategories model.rulesTree) c.id) Information)}, Cmd.none)
        Err err ->
          processApiError "Getting Rule category details" err model

    GetNodesList res ->
      case res of
        Ok nodes ->
          ({model | nodes =  Dict.Extra.fromListBy (.id) nodes}, Cmd.none)
        Err err  ->
          processApiError "Getting Nodes list" err model

    OpenRuleDetails rId True ->
      let
        modelUI = model.ui
      in
      (model, Cmd.batch [getRuleDetails model rId, pushUrl ("rule", rId.value)])

    OpenRuleDetails rId False ->
      (model, getRuleDetails model rId)

    OpenCategoryDetails category True ->
      (model, Cmd.batch [getRulesCategoryDetails model category, pushUrl ("ruleCategory" , category)])

    OpenCategoryDetails category False ->
      (model, getRulesCategoryDetails model category)

    CloseDetails ->
      ( { model | mode  = RuleTable } , pushUrl ("","")  )

    GetRulesComplianceResult res ->
      case res of
        Ok r ->
          ( { model | rulesCompliance  =  Dict.Extra.fromListBy (.id >> .value)  r } , initTooltips "" )
        Err err ->
          processApiError "Getting compliance" err model

    GetRuleChanges res ->
      case res of
        Ok r ->
          ( { model | changes = r} , initTooltips "" )
        Err err ->
          processApiError "Getting changes" err model


    GetRuleNodesDirectivesResult id res ->
      case res of
        Ok r ->
          case model.mode of
            RuleForm details   ->
              let
                 newDetails = {details | numberOfNodes  = Just r.numberOfNodes, numberOfDirectives = Just r.numberOfDirectives }
              in
                 ({model | mode = RuleForm newDetails }, initTooltips "")
            _ ->
               (model, Cmd.none)
        Err err ->
         processApiError ("Getting rule nodes and directives of Rule "++ id.value) err model


    GetRuleComplianceResult id res ->
      case res of
        Ok r ->
          case model.mode of
            RuleForm details   ->
              let
                newDetails = {details | compliance = Just r}
              in
                ({model | mode = RuleForm newDetails }, initTooltips "")
            _ ->
              (model, Cmd.none)
        Err err ->
          processApiError ("Getting compliance details of Rule "++ id.value) err model

    GetRepairedReportsResult id start end res ->
      case res of
        Ok r ->
          case model.mode of
            RuleForm details   ->
              let
                newDetails = {details | reports = r}
              in
                ({model | mode = RuleForm newDetails }, initTooltips "")
            _ ->
              (model, Cmd.none)
        Err err ->
          processApiError ("Getting changes  of Rule "++ id.value) err model



    UpdateCategoryForm details ->
      case model.mode of
        CategoryForm _   ->
          ({model | mode = CategoryForm details }, initTooltips "")
        _   -> (model, Cmd.none)

    SelectGroup targetId includeBool->
      let
        groupId = toRuleTarget targetId

        updateTargets : Rule -> Rule
        updateTargets r =
          let
            (include, exclude) = case r.targets of
                [Composition (Or i) (Or e)] -> (i,e)
                targets -> (targets,[])
            isIncluded = List.member groupId include
            isExcluded = List.member groupId exclude
            (newInclude, newExclude)  = case (includeBool, isIncluded, isExcluded) of
              (True, True, _)       -> (List.Extra.remove groupId include,exclude)
              (True, _, True)       -> (groupId :: include, List.Extra.remove groupId exclude)
              (False, True, _)      -> (List.Extra.remove groupId include, groupId :: exclude)
              (False, _, True)      -> (include,  List.Extra.remove groupId exclude)
              (True, False, False)  -> ( groupId :: include, exclude)
              (False, False, False) -> (include, groupId :: exclude)
          in
            {r | targets = [Composition (Or newInclude) (Or newExclude)]}
      in
        case model.mode of
          RuleForm details ->
            ({model | mode = RuleForm   {details | rule = (updateTargets details.rule)}}, initTooltips "")
          _   -> (model, Cmd.none)

    UpdateRuleForm details ->
      case model.mode of
        RuleForm _ ->
          ({model | mode = RuleForm  details}, initTooltips "")
        _ -> (model, Cmd.none)

    DisableRule ->
      case model.mode of
        RuleForm details ->
          let
            rule     = details.originRule
            cmdAction = case rule of
              Just oR -> saveDisableAction {oR | enabled = not oR.enabled} model
              Nothing -> initTooltips ""
          in
            (model, cmdAction)
        _   -> (model, Cmd.none)

    NewRule id ->
      let
        rule        = Rule id "" "rootRuleCategory" "" "" True False [] [] "" (RuleStatus "" Nothing) [] Nothing
        ruleDetails = RuleDetails Nothing rule Information {defaultRulesUI | editGroups = True, editDirectives = True} Nothing Nothing Nothing []
      in
        ({model | mode = RuleForm ruleDetails}, initTooltips "")

    NewCategory id ->
      let
        category        = Category id "" "" (SubCategories []) []
        categoryDetails = CategoryDetails Nothing category "rootRuleCategory" Information
      in
        ({model | mode = CategoryForm categoryDetails}, initTooltips "" )

    SaveRuleDetails unknownTargets (Ok ruleDetails) ->
      case model.mode of
        RuleForm details ->
          let
            action = case details.originRule of
              Just _ -> "saved"
              Nothing -> "created"
            ui = details.ui
            modelUi = model.ui
            defaultNotif = successNotification ("Rule '"++ ruleDetails.name ++"' successfully " ++ action)
            (crSettings, successNotif) = case modelUi.crSettings of
              Just cr ->
                ( Just { cr | message = "" }
                , ( if cr.enableChangeRequest then
                  case ruleDetails.changeRequestId of
                    Just id ->
                      let
                        message = "Change request #"++ id ++" successfully created."
                        linkUrl = model.contextPath ++ "/secure/configurationManager/changes/changeRequest/" ++ id
                        linkTxt = "See details of change request #" ++ id
                        encodeToastInfo =
                          object
                          [ ("message", string message)
                          , ("linkUrl", string linkUrl)
                          , ("linkTxt", string linkTxt)
                          ]
                      in
                        linkSuccessNotification encodeToastInfo
                    Nothing -> defaultNotif
                  else
                  defaultNotif
                  )
                )
              Nothing ->
                ( modelUi.crSettings
                , defaultNotif
                )

            unknownTargetsMsg = if unknownTargets then warningNotification "Unknown targets have been removed from this rule" else Cmd.none
            newModel = {model | mode = RuleForm {details | originRule = Just ruleDetails, rule = ruleDetails, ui = {ui | editDirectives = False, editGroups = False}}, ui = {modelUi | saving = False, crSettings = crSettings} }
          in
            (newModel, Cmd.batch
              [ successNotif
              , getRulesTree newModel
              , getRulesComplianceDetails ruleDetails.id newModel
              , getRuleNodesDirectives ruleDetails.id newModel
              , unknownTargetsMsg
              ]
            )
        _   -> (model, Cmd.none)


    SaveRuleDetails unknownTargets (Err err) ->
      let
        (errorModel, errorMsg) = processApiError "Saving Rule" err model
        unknownTargetsMsg = if unknownTargets then warningNotification "Unknown targets have been removed from this rule" else Cmd.none
      in
        ( errorModel, Cmd.batch [errorMsg, unknownTargetsMsg] )

    SaveDisableAction (Ok ruleDetails) ->
      case model.mode of
        RuleForm details ->
          let
            txtDisable = if ruleDetails.enabled then "enabled" else "disabled"
            ui = model.ui
            crSettings = case ui.crSettings of
              Just s  -> Just { s | message = ""}
              Nothing -> Nothing
          in
            ({model | mode = RuleForm {details | originRule = Just ruleDetails, rule = ruleDetails}, ui = { ui | crSettings = crSettings}}, (Cmd.batch [successNotification ("Rule '"++ ruleDetails.name ++"' successfully "++ txtDisable), (getRulesTree model)]))
        _   -> (model, Cmd.none)

    SaveDisableAction (Err err) ->
      processApiError "Changing rule state" err model

    SaveCategoryResult (Ok category) ->
      case model.mode of
        CategoryForm details ->
          let
            modelUi = model.ui
            oldCategory = details.category
            action      = case details.originCategory of
              Just _ -> "saved"
              Nothing -> "created"
            newCategory = {category | subElems = oldCategory.subElems, elems = oldCategory.elems}
            newModel    = {model | mode = CategoryForm {details | originCategory = Just newCategory, category = newCategory}, ui = {modelUi | saving = False} }
          in
            (newModel, Cmd.batch [(successNotification ("Category '"++ category.name ++"' successfully " ++ action)), (getRulesTree newModel)])
        _   -> (model, Cmd.none)

    SaveCategoryResult (Err err) ->
      processApiError "Saving Category" err model

    DeleteRule (Ok (ruleId, ruleName)) ->
      case model.mode of
        RuleForm r ->
          let
            newMode  = if r.rule.id == ruleId then RuleTable else model.mode
            ui = model.ui
            crSettings = case ui.crSettings of
              Just s  -> Just { s | message = ""}
              Nothing -> Nothing

            newModel = { model | mode = newMode, ui = { ui | crSettings = crSettings} }
          in
            (newModel, Cmd.batch [successNotification ("Successfully deleted rule '" ++ ruleName ++  "' (id: "++ ruleId.value ++")"), getRulesTree newModel, pushUrl ("", "") ])
        _ -> (model, Cmd.none)

    DeleteRule (Err err) ->
      processApiError "Deleting Rule" err model

    DeleteCategory (Ok (categoryId, categoryName)) ->
      case model.mode of
        CategoryForm c ->
          let
            newMode  = if c.category.id == categoryId then RuleTable else model.mode
            newModel = { model | mode = newMode }
          in
            (newModel, Cmd.batch [successNotification ("Successfully deleted category '" ++ categoryName ++  "' (id: "++ categoryId ++")"), getRulesTree newModel, pushUrl ("","") ])
        _ -> (model, Cmd.none)

    DeleteCategory (Err err) ->
      processApiError "Deleting category" err model

    CloneRule rule ruleId ->
      let
        newModel = case model.mode of
          RuleForm _ ->
            let
              newRule    = {rule | name = ("Clone of "++rule.name), id = ruleId}
              newRuleDetails = RuleDetails Nothing newRule Information defaultRulesUI Nothing Nothing Nothing []
            in
              { model | mode = RuleForm newRuleDetails }
          _ -> model
      in
        (newModel, Cmd.none)

    OpenDeletionPopup rule ->
      case model.mode of
        RuleForm rd ->
          let
            ui = model.ui
            crSettings = case ui.crSettings of
              Just cr -> Just { cr | changeRequestName = ("Delete Rule '" ++ rd.rule.name ++ "'") }
              Nothing -> Nothing
          in
            ( { model | ui = {ui | modal = DeletionValidation rule crSettings, crSettings = crSettings} } , Cmd.none )
        _ -> (model, Cmd.none)

    OpenDeletionPopupCat category ->
      case model.mode of
        CategoryForm _ ->
          let ui = model.ui
          in
            ( { model | ui = {ui | modal = DeletionValidationCat category } } , Cmd.none )
        _ -> (model, Cmd.none)

    OpenDeactivationPopup rule ->
      case model.mode of
        RuleForm rd ->
          let
            ui = model.ui
            crSettings = case ui.crSettings of
              Just cr -> Just { cr | changeRequestName = ("Deactivate Rule '" ++ rd.rule.name ++ "'")}
              Nothing -> Nothing
          in
            ( { model | ui = {ui | modal = DeactivationValidation rule crSettings, crSettings = crSettings}} , Cmd.none )
        _ -> (model, Cmd.none)

    OpenSaveAuditMsgPopup rule crSettings ->
      case model.mode of
        RuleForm rd ->
          let
            ui = model.ui
            creation = (isNothing rd.originRule)
            action = if creation then "Create" else "Update"
            newCrSettings = { crSettings | changeRequestName = (action ++ " Rule '" ++ rd.rule.name ++ "'")}
          in
            ( { model | ui = {ui | modal = SaveAuditMsg creation rule rd.originRule newCrSettings, crSettings = (Just newCrSettings)}} , Cmd.none )
        _ -> (model, Cmd.none)

    ClosePopup callback ->
      let
        ui = model.ui
        (nm,cmd) = update callback { model | ui = { ui | modal = NoModal } }
      in
        (nm , cmd)
    FoldAllCategories filters ->
      let
        -- remove "rootRuleCategory" because we can't fold/unfold root category
        catIds =
          getListCategories model.rulesTree
            |> List.map .id
            |> List.filter (\id -> id /= "rootRuleCategory")
        foldedCat =
          filters.treeFilters.folded
            |> List.filter (\id -> id /= "rootRuleCategory")
        ui = model.ui
        newState =
          if(List.length foldedCat == (List.length catIds)) then
            False
          else
            True
        treeFilters = filters.treeFilters
        foldedList = {filters | treeFilters = {treeFilters | folded = if(newState) then catIds else []}}
      in
        ({model | ui = { ui | isAllCatFold = newState, ruleFilters = foldedList}}, initTooltips "")
    UpdateRuleFilters filters ->
      let
        ui = model.ui
      in
        ({model | ui = { ui | ruleFilters = filters}}, initTooltips "")
    UpdateDirectiveFilters filters ->
      let
        ui = model.ui
      in
        ({model | ui = { ui | directiveFilters = filters}}, initTooltips "")

    UpdateGroupFilters filters ->
      let
        ui = model.ui
      in
        ({model | ui = { ui | groupFilters = filters}}, initTooltips "")

    UpdateComplianceFilters filters ->
      let
        ui = model.ui
      in
        ({model | ui = { ui | complianceFilters = filters }}, initTooltips "")

    ToggleRow rowId defaultSortId ->
      case model.mode of
        RuleForm r ->
          let
            ui = r.ui
            newDetails  = { ui | openedRows = if Dict.member rowId r.ui.openedRows then
                            Dict.remove rowId r.ui.openedRows
                          else
                            Dict.insert rowId (defaultSortId, Asc) ui.openedRows
                          }
            newMode = RuleForm {r | ui = newDetails }
            newModel = { model | mode = newMode }
          in
            (newModel, initTooltips "")
        _ ->
            (model, initTooltips "")
    GetRepairedReport ruleId idChange ->
      case Dict.get ruleId.value model.changes of
        Nothing -> (model, Cmd.none)
        Just changes ->
          case List.Extra.getAt idChange changes of
            Nothing -> (model, Cmd.none)
            Just c -> (model, getRepairedReports model ruleId c.start c.end)
    ToggleRowSort rowId sortId order ->
      case model.mode of
        RuleForm r ->
          let
            ui = r.ui
            newDetails  = { ui | openedRows = Dict.update rowId (always (Just (sortId,order)) )  r.ui.openedRows }
            newMode = RuleForm {r | ui = newDetails }
            newModel = { model | mode = newMode }
          in
            (newModel, initTooltips "")
        _ ->
            (model, initTooltips "")

    GoTo link -> (model, Nav.load link)

    RefreshComplianceTable ruleId ->
      (model, Cmd.batch [ getRulesComplianceDetails ruleId model, getRuleNodesDirectives ruleId model])

    RefreshReportsTable ruleId ->
      let
        getChanges = case Dict.get ruleId.value model.changes of
          Nothing -> []
          Just changes ->
            case List.Extra.last changes of
              Nothing -> []
              Just lastChanges -> [ getRepairedReports model ruleId lastChanges.start lastChanges.end ]
      in
        (model, Cmd.batch getChanges)

    UpdateCrSettings settings ->
      let
        ui = model.ui
        newModalState = case ui.modal of
          SaveAuditMsg c r oR s      -> SaveAuditMsg c r oR settings
          DeactivationValidation r s -> DeactivationValidation r (Just settings)
          DeletionValidation  r s    -> DeletionValidation r (Just settings)
          _ -> ui.modal
      in
        ({ model | ui = { ui | crSettings = Just settings, modal = newModalState } }, Cmd.none)

processApiError : String -> Error -> Model -> ( Model, Cmd Msg )
processApiError apiName err model =
  let
    modelUi = model.ui
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
    ({model | mode = if model.mode == Loading then RuleTable else model.mode, ui = { modelUi | loadingRules = False, saving = False}}, errorNotification ("Error when "++apiName ++", details: \n" ++ message ) )

getUrl : Model -> String
getUrl model = model.contextPath ++ "/secure/configurationManager/ruleManagement"
