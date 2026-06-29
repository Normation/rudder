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
       ({ model | mode = Loading, groupsCompliance = Dict.empty, ui = { ui | loadingGroups = True } }, createGroupModal ())
    CloseModal -> 
      let
        ui = model.ui
      in
        ({ model | ui = { ui | loadingGroups = True } }, Cmd.batch [ getGroupsTree model False ])
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
              newModel
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
          in 
            ( { model | 
                  groupsCompliance = (Dict.Extra.fromListBy (.id >> .value) r) |> Dict.union currentGroups
                  , mode = if (model.mode == LoadingTable) then GroupTable else model.mode
                  , ui = { modelUi | loadingGroups = False }
              }
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
    UpdateGroupFilters filters ->
      let
        ui = model.ui
        newUi = { ui | groupFilters = filters }
      in
        ({model | ui = newUi}, initTooltips ())
    FoldAllCategories filters ->
      let
        -- remove rootGroupCategoryId because we can't fold/unfold root category
        catIds =
          getAllCats model.groupsTree
            |> List.map .id
            |> List.filter (\id -> id /= rootGroupCategoryId)
        foldedCat =
          filters.treeFilters.folded
            |> List.filter (\id -> id /= rootGroupCategoryId)
        ui = model.ui
        newState =
          if(List.length foldedCat == (List.length catIds)) then
            False
          else
            True
        treeFilters = filters.treeFilters
        foldedList = {filters | treeFilters = {treeFilters | folded = if(newState) then catIds else []}}
      in
        ({model | ui = { ui | groupFilters = foldedList}}, initTooltips ())

    

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
