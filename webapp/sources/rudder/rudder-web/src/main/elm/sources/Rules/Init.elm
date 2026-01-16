module Rules.Init exposing (..)

import Compliance.Html exposing (buildComplianceBar)
import Dict

import Html exposing (Html, div, i, span, text)
import Html.Attributes exposing (attribute, class, title)
import List.Nonempty as NonEmptyList
import Ordering exposing (Ordering)
import Rudder.Filters
import Rudder.Table exposing (Column, ColumnName(..), FilterOptionsType(..), buildConfig, buildOptions)
import Rules.ApiCalls exposing (..)
import Rules.DataTypes exposing (..)

import Compliance.Utils exposing (defaultComplianceFilter)
import Rules.ViewUtils exposing (badgePolicyMode, buildTagsTree, buildTooltipContent)
import Ui.Datatable exposing (defaultTableFilters, Category, SubCategories(..))


init : { contextPath : String, hasWriteRights : Bool, canReadChanqeRequest : Bool } -> ( Model, Cmd Msg )
init flags =
  let
    initCategory = Category "" "" "" (SubCategories []) []
    initFilters  = Filters (defaultTableFilters Name) (TreeFilters "" [] (Tag "" "") [])
    initUI       = UI initFilters initFilters initFilters defaultComplianceFilter NoModal flags.hasWriteRights flags.canReadChanqeRequest True False False Nothing
    initModel    = Model flags.contextPath Loading "" initCategory initCategory initCategory Dict.empty Dict.empty Dict.empty Dict.empty initUI (initTable "")

    listCRActions =
      if flags.canReadChanqeRequest then
        [ getCrSettingsEnableCr initModel
        , getCrSettingsEnabledMsg initModel
        , getCrSettingsMandatoryMsg initModel
        , getCrSettingsChangeMsgPrompt initModel
        ]
      else []
    listInitActions =
      [ getPolicyMode      initModel
      , getNodesList       initModel
      , getRulesCompliance initModel
      , getGroupsTree      initModel
      , getTechniquesTree  initModel
      , getRulesTree       initModel
      , getRuleChanges     initModel
      ]

  in

    ( initModel
    , Cmd.batch (listInitActions ++  listCRActions)
    )

initTable : String -> Rudder.Table.Model RuleWithCompliance Msg
initTable initPolicyMode =
    let
        complianceToString compliance =
            case compliance of
                Just co -> (String.fromFloat co.compliance) ++ "%"
                Nothing -> "No report"

        entryToStringList r =
            [ r.name
            , r.category |> Maybe.withDefault "Rules"
            , r.status |> (\{value, details} -> value ++ (details |> (Maybe.map (\det -> ": " ++ det) ) |> Maybe.withDefault ""))
            , r.compliance |> complianceToString
            , r.changes |> String.fromFloat
            ]
        customizations =
            { tableContainerAttrs = [class "table-container"]
            , tableAttrs = [class "no-footer dataTable"]
            , optionsHeaderAttrs = [class "d-none"]
            , theadAttrs = []
            , tbodyAttrs = []
            , trAttrs = \_ -> []
            , thAttrs = \(ColumnName _) -> []
            , tdAttrs = \(ColumnName _) -> []
            }
        options =
            buildOptions.newOptions
                |> buildOptions.withCustomizations customizations
                |> buildOptions.withCsvExport
                    { entryToStringList = entryToStringList, btnAttributes=[]}
                |> buildOptions.withFilter
                    (SearchInputFilter
                      { predicate = (\filter data -> data |> entryToStringList |> List.any filter)
                      , state = Rudder.Filters.empty })
                -- todo
                --|> buildOptions.withFilter
                --    (HtmlFilter (Debug.todo "html filter"))

        columns : NonEmptyList.Nonempty (Column RuleWithCompliance Msg)
        columns =
            (NonEmptyList.Nonempty
                { name = (ColumnName "Name")
                , renderHtml = (\rule ->
                    div [] [ badgePolicyMode initPolicyMode rule.policyMode
                           , text rule.name
                           , buildTagsTree rule.tags] )
                , ordering = Ordering.byField (.name >> String.toLower) }
                [ { name = (ColumnName "Category")
                  , renderHtml = .category >> (Maybe.withDefault "Rules") >> text
                  , ordering = Ordering.byField (.category >> Maybe.withDefault "Rules" >> String.toLower) }
                , { name = (ColumnName "Status")
                  , renderHtml = .status >> (\s ->
                      let status = text s.value in
                      case s.details of
                        Just ms ->
                         span
                           [ class "disabled"
                           , attribute "data-bs-toggle" "tooltip"
                           , attribute "data-bs-placement" "top"
                           , title (buildTooltipContent "Reason(s)" ms)]
                           [ status, i[class "fa fa-info-circle"][]]
                        Nothing -> span[][ status ]
                      )
                  , ordering = Ordering.byField (.status >> .value >> String.toLower) }
                , { name = (ColumnName "Compliance")
                  , renderHtml = (\rule ->
                      case rule.compliance of
                        Just co ->
                          buildComplianceBar defaultComplianceFilter co.complianceDetails
                        Nothing -> div[class "skeleton-loading"][span[][]]
                    )
                  , ordering = Ordering.byField (.compliance >> complianceToString >> String.toLower) }
                , { name = (ColumnName "Changes")
                  , renderHtml = .changes >> String.fromFloat >> text
                  , ordering = Ordering.byField (.changes) }])
        config = buildConfig.newConfig columns |> buildConfig.withOptions options
    in
    Rudder.Table.init config []
