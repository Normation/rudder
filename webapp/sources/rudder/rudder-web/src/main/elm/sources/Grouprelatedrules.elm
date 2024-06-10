port module Grouprelatedrules exposing (..)

import Browser
import Browser.Navigation as Nav
import Http exposing (..)

import GroupRelatedRules.ApiCalls exposing (..)
import GroupRelatedRules.DataTypes exposing (..)
import GroupRelatedRules.Init exposing (init)
import GroupRelatedRules.View exposing (view)
import GroupRelatedRules.ViewUtils exposing (..)

import Ui.Datatable exposing (Category, emptyCategory, getAllCats)


-- PORTS / SUBSCRIPTIONS
port errorNotification    : String -> Cmd msg
port initTooltips         : () -> Cmd msg
port loadRelatedRulesTree : (List String -> msg) -> Sub msg

subscriptions : Model -> Sub Msg
subscriptions _ =
  loadRelatedRulesTree (LoadRelatedRulesTree << List.map RuleId)

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
    GoTo link -> (model, Nav.load link)
    GetRulesResult relatedRules res ->
      let 
        ui = model.ui
        ruleIds = List.map .value relatedRules.value
        filterRuleTree r =  filterRuleElemsByIds ruleIds r
      in                     
        case res of         
          Ok r -> ({ model | ruleTree = filterRuleTree r, ui = { ui | loading = False } }, initTooltips ())
          Err err -> processApiError "Getting Rules tree" err model
    LoadRelatedRulesTree []   -> 
      let                    
        ui = model.ui
      in 
        ({ model | ruleTree = emptyCategory, ui = { ui | loading = False } }, Cmd.none)
    LoadRelatedRulesTree ruleIds -> 
      let
        ui = model.ui
      in 
        ({ model | ui = { ui | loading = True } }, getRulesTree model (RelatedRules ruleIds))
    UpdateRuleFilters filters ->
      let
        ui = model.ui
      in
        ({model | ui = { ui | ruleTreeFilters = filters}}, initTooltips ())
    FoldAllCategories filters ->
      let
        -- remove "rootRuleCategory" because we can't fold/unfold root category
        catIds =
          getAllCats model.ruleTree
            |> List.map .id
            |> List.filter (\id -> id /= "rootRuleCategory")
        foldedCat =
          filters.folded
            |> List.filter (\id -> id /= "rootRuleCategory")
        ui = model.ui
        newState =
          if(List.length foldedCat == (List.length catIds)) then
            False
          else
            True
        foldedList = {filters | folded = if(newState) then catIds else []}
      in
        ({model | ui = { ui | isAllCatFold = newState, ruleTreeFilters = foldedList}}, initTooltips ())
    

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

getUrl : Model -> String
getUrl model = model.contextPath ++ "/secure/configurationManager/ruleManagement"