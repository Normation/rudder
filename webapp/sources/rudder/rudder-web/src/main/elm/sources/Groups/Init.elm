module Groups.Init exposing (..)

import Compliance.Html exposing (buildComplianceBar)
import Compliance.Utils exposing (defaultComplianceFilter, getAllComplianceValues)
import Dict exposing (Dict)

import Groups.ViewUtils exposing (getCategoryName)
import Html exposing (Html, div, span, text)
import Html.Attributes.Extra exposing (role)
import Html.Events exposing (onClick)
import List.Nonempty as NonEmptyList

import Groups.ApiCalls exposing (..)
import Groups.DataTypes exposing (..)

import Html.Attributes exposing (class)
import Ordering exposing (Ordering)
import Rudder.Table exposing (Column, ColumnName(..), FilterOptionsType(..), OutMsg(..), buildConfig, buildOptions)
import Rudder.Filters exposing (byValues)
import Ui.Datatable exposing (defaultTableFilters, Category, SubCategories(..))


init : { contextPath : String, hasGroupToDisplay : Bool, hasWriteRights : Bool } -> ( Model, Cmd Msg )
init flags =
  let
    initCategory = Category "" "" "" (SubCategories []) []
    initTreeFilters   = (TreeFilters "" [])
    initFilters       = Filters initTreeFilters
    initUI       = UI initFilters NoModal flags.hasWriteRights True
    initModel    = Model flags.contextPath Loading initUI initCategory Dict.empty initTable
    listInitActions =
      [ getGroupsTree initModel (not flags.hasGroupToDisplay)
      ]
  in
    ( initModel
    , Cmd.batch listInitActions
    )


initTable : Rudder.Table.Model GroupWithCompliance Msg
initTable =
    let
        categoryToString category = Maybe.withDefault "Groups" category
        complianceToHtml compliance =
            case compliance of
                Just c ->
                    buildComplianceBar defaultComplianceFilter c.complianceDetails
                Nothing ->
                    div
                        [class "skeleton-loading", role "status"]
                        [ span [] []
                        , span [class "visually-hidden"] [text "Loading..."]]

        compareCompliance : Ordering (Maybe ComplianceSummaryValue)
        compareCompliance =
            Ordering.byRank
                (\compliance ->
                    case compliance of
                        Just _ -> 2
                        Nothing -> 1
                    )
                (\compliance1 compliance2 ->
                    case (compliance1, compliance2) of
                        (Just c1, Just c2) ->
                            case (complianceDataAvailable c1, complianceDataAvailable c2) of
                                (True,True) -> compare c1.compliance c2.compliance
                                (True, False) -> GT
                                (False, True) -> LT
                                (False,False) -> EQ
                        _ -> Ordering.noConflicts
                )

        customizations =
            { tableContainerAttrs = [class "table-container"]
            , tableAttrs = [class "no-footer dataTable"]
            , optionsHeaderAttrs = [class "d-none"]
            , theadAttrs = []
            , tbodyAttrs = []
            , trAttrs = \row -> [ onClick (OpenGroupDetails row.id)]
            , thAttrs = \(ColumnName name) ->
                case name of
                    "Global compliance" -> [class "compliance-col"]
                    "Targeted compliance" -> [class "compliance-col"]
                    _ -> []
            , tdAttrs = \(ColumnName name) ->
                case name of
                    "Global compliance" -> [class "compliance-col"]
                    "Targeted compliance" -> [class "compliance-col"]
                    _ -> []
            }
        options =
            buildOptions.newOptions
                |> buildOptions.withCustomizations customizations
                |> buildOptions.withCsvExport
                    { entryToStringList = entryToStringList
                    , btnAttributes=[]}
                |> buildOptions.withFilter
                    (SearchInputFilter { predicate = filterPredicate, state = Rudder.Filters.empty })

        columns : NonEmptyList.Nonempty (Column GroupWithCompliance msg)
        columns =
            (NonEmptyList.Nonempty
                { name = (ColumnName "Name"), renderHtml = .name >> text, ordering = Ordering.byField (.name >> String.toLower) }
                [ { name = (ColumnName "Category"), renderHtml = .category >> categoryToString >> text, ordering = Ordering.byField (.category >> categoryToString >> String.toLower) }
                , { name = (ColumnName "Global compliance"), renderHtml = .globalCompliance >> complianceToHtml, ordering = Ordering.byFieldWith compareCompliance .globalCompliance  }
                , { name = (ColumnName "Targeted compliance"), renderHtml = .targetedCompliance >> complianceToHtml, ordering = Ordering.byFieldWith compareCompliance .targetedCompliance }])

        config = buildConfig.newConfig columns |> buildConfig.withOptions options
    in
        Rudder.Table.init config []


complianceDataAvailable : ComplianceSummaryValue -> Bool
complianceDataAvailable compliance =
    let allComplianceValues = getAllComplianceValues compliance.complianceDetails in
    if ( allComplianceValues.okStatus.value
        + allComplianceValues.nonCompliant.value
        + allComplianceValues.error.value
        + allComplianceValues.unexpected.value
        + allComplianceValues.pending.value
        + allComplianceValues.reportsDisabled.value
        + allComplianceValues.noReport.value == 0 ) then False else True

filterPredicate : (String -> Bool) -> (GroupWithCompliance -> Bool)
filterPredicate filter data = data |> entryToStringList |> List.any filter

entryToStringList : GroupWithCompliance -> List String
entryToStringList group =
    [ group.name
    , group.category |> Maybe.withDefault "Groups"
    , group.globalCompliance |> complianceToString
    , group.targetedCompliance |> complianceToString
    ]

complianceToString : Maybe ComplianceSummaryValue -> String
complianceToString complianceOpt =
    case complianceOpt of
        Just compliance ->
            if (complianceDataAvailable compliance)
            then String.fromFloat compliance.compliance ++ "%"
            else "No data available"
        Nothing ->
            "Loading..."
