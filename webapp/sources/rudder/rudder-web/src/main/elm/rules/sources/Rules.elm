port module Rules exposing (..)

import Browser
import Browser.Navigation as Nav
import Dict
import Dict.Extra
import DataTypes exposing (..)
import Http exposing (..)
import Init exposing (init)
import View exposing (view)
import Result
import ApiCalls exposing (..)
import ViewUtils exposing (..)
import List.Extra
import Random
import UUID

-- PORTS / SUBSCRIPTIONS
port successNotification : String -> Cmd msg
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
    CallApi call ->
      (model, call model)
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
              , Cmd.none
            )
        Err err ->
          processApiError "Getting Policy Mode" err model

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
            getChanges = case Dict.get r.id.value model.changes of
                           Nothing -> []
                           Just changes ->
                             case List.Extra.last changes of
                               Nothing -> []
                               Just lastChanges -> [ getRepairedReports newModel r.id lastChanges.start lastChanges.end ]
          in
            (newModel, Cmd.batch (getRulesComplianceDetails r.id newModel :: getRuleNodesDirectives r.id newModel :: getChanges) )
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
          ( { model | rulesCompliance  =  Dict.Extra.fromListBy (.id >> .value)  r } , Cmd.none )
        Err err ->
          processApiError "Getting compliance" err model

    GetRuleChanges res ->
      case res of
        Ok r ->
          ( { model | changes = r} , Cmd.none )
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
                 ({model | mode = RuleForm newDetails }, Cmd.none)
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
                ({model | mode = RuleForm newDetails }, Cmd.none)
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
                ({model | mode = RuleForm newDetails }, Cmd.none)
            _ ->
              (model, Cmd.none)
        Err err ->
          processApiError ("Getting changes  of Rule "++ id.value) err model



    UpdateCategoryForm details ->
      case model.mode of
        CategoryForm _   ->
          ({model | mode = CategoryForm details }, Cmd.none)
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
            ({model | mode = RuleForm   {details | rule = (updateTargets details.rule)}}, Cmd.none)
          _   -> (model, Cmd.none)

    UpdateRuleForm details ->
      let
        cmd = initTooltips ""
      in
        case model.mode of
          RuleForm _ ->
            ({model | mode = RuleForm  details}, cmd)
          _ -> (model, cmd)

    DisableRule ->
      case model.mode of
        RuleForm details ->
          let
            rule     = details.originRule
            cmdAction = case rule of
              Just oR -> saveDisableAction {oR | enabled = not oR.enabled} model
              Nothing -> Cmd.none
          in
            (model, cmdAction)
        _   -> (model, Cmd.none)

    NewRule id ->
      let
        rule        = Rule id "" "rootRuleCategory" "" "" True False [] [] "" (RuleStatus "" Nothing) []
        ruleDetails = RuleDetails Nothing rule Information {defaultRulesUI | editGroups = True, editDirectives = True} Nothing Nothing Nothing []
      in
        ({model | mode = RuleForm ruleDetails}, Cmd.none)

    NewCategory id ->
      let
        category        = Category id "" "" (SubCategories []) []
        categoryDetails = CategoryDetails Nothing category "rootRuleCategory" Information
      in
        ({model | mode = CategoryForm categoryDetails}, Cmd.none )

    SaveRuleDetails (Ok ruleDetails) ->
      case model.mode of
        RuleForm details ->
          let
            action = case details.originRule of
              Just _ -> "saved"
              Nothing -> "created"
            ui = details.ui
            newModel = {model | mode = RuleForm {details | originRule = Just ruleDetails, rule = ruleDetails, ui = {ui | editDirectives = False, editGroups = False }}}
          in
            (newModel, Cmd.batch
              [ successNotification ("Rule '"++ ruleDetails.name ++"' successfully " ++ action)
              , getRulesTree newModel
              , getRulesComplianceDetails ruleDetails.id newModel
              , getRuleNodesDirectives ruleDetails.id newModel
              ]
            )
        _   -> (model, Cmd.none)


    SaveRuleDetails (Err err) ->
      processApiError "Saving Rule" err model

    SaveDisableAction (Ok ruleDetails) ->
      case model.mode of
        RuleForm details ->
          let
            txtDisable = if ruleDetails.enabled then "enabled" else "disabled"
          in
            ({model | mode = RuleForm {details | originRule = Just ruleDetails, rule = ruleDetails}}, (Cmd.batch [successNotification ("Rule '"++ ruleDetails.name ++"' successfully "++ txtDisable), (getRulesTree model)]))
        _   -> (model, Cmd.none)

    SaveDisableAction (Err err) ->
      processApiError "Changing rule state" err model

    SaveCategoryResult (Ok category) ->
      case model.mode of
        CategoryForm details ->
          let
            oldCategory = details.category
            action      = case details.originCategory of
              Just _ -> "saved"
              Nothing -> "created"
            newCategory = {category | subElems = oldCategory.subElems, elems = oldCategory.elems}
            newModel    = {model | mode = CategoryForm {details | originCategory = Just newCategory, category = newCategory}}
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
            newModel = { model | mode = newMode }
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
        RuleForm _ ->
          let ui = model.ui
          in
            ( { model | ui = {ui | modal = DeletionValidation rule} } , Cmd.none )
        _ -> (model, Cmd.none)

    OpenDeletionPopupCat category ->
      case model.mode of
        CategoryForm _ ->
          let ui = model.ui
          in
            ( { model | ui = {ui | modal = DeletionValidationCat category} } , Cmd.none )
        _ -> (model, Cmd.none)

    OpenDeactivationPopup rule ->
      case model.mode of
        RuleForm _ ->
          let ui = model.ui
          in
            ( { model | ui = {ui | modal = DeactivationValidation rule}} , Cmd.none )
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
            (newModel, Cmd.none)
        _ ->
            (model, Cmd.none)
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
            (newModel, Cmd.none)
        _ ->
            (model, Cmd.none)

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
    ({model | mode = if model.mode == Loading then RuleTable else model.mode, ui = { modelUi | loadingRules = False}}, errorNotification ("Error when "++apiName ++",details: \n" ++ message ) )

getUrl : Model -> String
getUrl model = model.contextPath ++ "/secure/configurationManager/ruleManagement"
