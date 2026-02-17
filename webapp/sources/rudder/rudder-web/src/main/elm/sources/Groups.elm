port module Groups exposing (..)

import Browser
import Dict
import Dict.Extra
import Http exposing (..)


import GroupRelatedRules.DataTypes exposing (GroupId)
import Groups.ApiCalls exposing (..)
import Groups.DataTypes exposing (..)
import Groups.Init exposing (init)
import Groups.View exposing (view)
import Groups.ViewUtils exposing (..)

import Maybe.Extra
import Rudder.Filters as Filters
import Rudder.Table exposing (OutMsg(..))
import Task
import Time
import Time.DateTime
import Time.Iso8601
import Ui.Datatable exposing (getAllCats)


-- PORTS / SUBSCRIPTIONS
port errorNotification      : String -> Cmd msg
port initTooltips           : () -> Cmd msg
port pushUrl                : (String, String) -> Cmd msg
port displayCategoryDetails : String -> Cmd msg
port displayGroupDetails    : String -> Cmd msg
port adjustComplianceCols   : () -> Cmd msg
port createGroupModal       : () -> Cmd msg
port closeModal             : (() -> msg) -> Sub msg
port loadGroupTable         : (() -> msg) -> Sub msg
port readUrl                : (String -> msg) -> Sub msg

subscriptions : Model -> Sub Msg
subscriptions _ = Sub.batch
  [ closeModal (\_ -> CloseModal)
  , loadGroupTable (\_ -> LoadGroupTable)
  , readUrl (\s -> OpenGroupDetails (GroupId s))
  ]

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
    OpenModal -> 
      let
        ui = model.ui
        -- we need to reload to make sure we are not in a details page, or else some external elements will replace our Elm dom elements
      in
       ({ model | mode = Loading, groupsCompliance = Dict.empty, ui = { ui | modal = ExternalModal, loadingGroups = True } }, createGroupModal ())
    CloseModal -> 
      let
        ui = model.ui
      in
        ({ model | ui = { ui | modal = NoModal, loadingGroups = True } }, Cmd.batch [ getGroupsTree model False ])
    LoadGroupTable -> 
      let
        ui = model.ui
        newModel = 
          if Dict.isEmpty model.groupsCompliance then 
            { model | mode = LoadingTable, ui = { ui | loadingGroups = True } }
          else
            { model | mode = GroupTable, ui = { ui | loadingGroups = False } }
        actions = 
          if Dict.isEmpty model.groupsCompliance then 
            getGroupsCompliance False (nextGroupIds model) model
          else
            Cmd.none
      in
        (newModel, actions)
    OpenGroupDetails groupId ->
      ({ model | mode = ExternalTemplate }, Cmd.batch [pushUrl (getIdAnchorKey groupId.value, groupId.value), displayGroupDetails groupId.value])
    OpenCategoryDetails catId ->
      ({ model | mode = ExternalTemplate }, Cmd.batch [pushUrl ("", ""), displayCategoryDetails catId])
    GetGroupsTreeResult res chainInitTable ->
      case res of
        Ok r ->
          let
            ui = model.ui
            newUi = { ui | loadingGroups = True }
            newModel = { model | groupsTree = r, mode = if (model.mode == Loading) then LoadingTable else model.mode, ui = newUi }
          in
            ( 
              newModel |> updateGroupsTableData
              , Cmd.batch (initTooltips () :: (if chainInitTable then [ getInitialGroupCompliance newModel ] else []))  -- reload the table each time we have a new groups tree
            )
        Err err ->
          processApiError "Getting groups tree" err model
    GetGroupsComplianceResult keepGroups res ->
      case res of
        Ok r ->
          let
            modelUi = model.ui
            currentGroups = if keepGroups then model.groupsCompliance else Dict.empty
            groupsCompliance = (Dict.Extra.fromListBy (.id >> .value) r) |> Dict.union currentGroups
          in
            ( { model | 
                  groupsCompliance = groupsCompliance
                  , mode = if (model.mode == LoadingTable) then GroupTable else model.mode
                  , ui = { modelUi | loadingGroups = False }
              } |> updateGroupsTableData
              , Cmd.batch [adjustComplianceCols (), initTooltips ()]
            )
        Err err ->
          processApiError "Getting compliance" err model
    LoadMore ->
      let
        ui = model.ui
        newUi = { ui | loadingGroups = True }
        nextIds = nextGroupIds model
      in
        ({model | mode = LoadingTable, ui = newUi}, getGroupsCompliance True nextIds model)
    UpdateGroupFoldedFilters categoryId ->
      let
        ui = model.ui
        filters = (foldUnfoldCategory model.ui.groupFilters categoryId)
        newUi = { ui | groupFilters = filters }
      in
        ({model | ui = newUi}, initTooltips ())

    UpdateGroupSearchFilters search ->
      let
        ui = model.ui
        groupFilters = ui.groupFilters
        newGroupFilters = { groupFilters | filter = search }
        newUi = { ui | groupFilters = newGroupFilters }
      in
        ({model | ui = newUi} |> updateGroupsTableFilter, initTooltips ())
    FoldAllCategories filters ->
      let
        -- remove rootGroupCategoryId because we can't fold/unfold root category
        catIds =
          getAllCats model.groupsTree
            |> List.map .id
            |> List.filter (\id -> id /= rootGroupCategoryId)
        foldedCat =
          filters.folded
            |> List.filter (\id -> id /= rootGroupCategoryId)
        ui = model.ui
        newState =
          if(List.length foldedCat == (List.length catIds)) then
            False
          else
            True
        foldedList = {filters | folded = if(newState) then catIds else []}
      in
        ({model | ui = { ui | groupFilters = foldedList}}, initTooltips ())

    RudderTableMsg tableMsg ->
      let
          (groupsTable, tabMsg, outMsgOpt) =
              Rudder.Table.update tableMsg model.groupsTable
      in
      handleOutMsg model groupsTable tabMsg outMsgOpt

    ExportCsvWithCurrentDate time ->
        let
            timeStr =
                time
                |> Time.DateTime.fromPosix
                |> Time.Iso8601.fromDateTime
                -- remove millis
                |> String.toList |> List.take 10 |> String.fromList
            filename = "rudder_groups_" ++ timeStr
            (groupsTable, tabMsg, outMsgOpt) =
                Rudder.Table.updateExportToCsv model.groupsTable filename
        in
        handleOutMsg model groupsTable tabMsg outMsgOpt


handleOutMsg : Model -> Rudder.Table.Model GroupWithCompliance Msg -> Cmd Msg -> Maybe (OutMsg Msg) -> (Model, Cmd Msg)
handleOutMsg model groupsTable tabMsg outMsgOpt =
    case outMsgOpt of
        Just (OnHtml parentMsg) ->
            let
                (newModel, newMsg) = update parentMsg ({model | groupsTable = groupsTable})
            in
            (newModel, Cmd.batch [tabMsg, newMsg])

        Just CsvExportRequested ->
            ({model | groupsTable = groupsTable}, Task.perform ExportCsvWithCurrentDate Time.now)

        _ ->
            ( {model | groupsTable = groupsTable}, tabMsg )


updateGroupsTableFilter : Model -> Model
updateGroupsTableFilter model =
    let
        searchFieldGroups g =
            [ g.id.value
            , g.name
            ] ++ Maybe.Extra.toList g.category ++ Maybe.Extra.toList (Maybe.map (getCategoryName model) g.category)
        search = model.ui.groupFilters.filter
        predicate = Filters.applyString search (Filters.byValues searchFieldGroups)
    in
    {model | groupsTable = Rudder.Table.updateDataWithFilter predicate model.groupsTable}

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
    (model, errorNotification ("Error when "++apiName ++", details: \n" ++ message ) )
