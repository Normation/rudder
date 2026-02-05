module Groups.Init exposing (..)

import Compliance.Html exposing (buildComplianceBar)
import Compliance.Utils exposing (defaultComplianceFilter, getAllComplianceValues)
import Dict exposing (Dict)

import Html exposing (Html, div, span, text)
import Html.Attributes.Extra exposing (role)
import Html.Events exposing (onClick)
import List.Nonempty as NonEmptyList

import Groups.ApiCalls exposing (..)
import Groups.DataTypes exposing (..)

import Html.Attributes exposing (class)
import Ordering exposing (Ordering)
import Rudder.Filters
import Rudder.Table exposing (Column, ColumnName(..), FilterOptionsType(..), OutMsg(..), buildConfig, buildCustomizations, buildOptions)
import Ui.Datatable exposing (Category, SubCategories(..))


init : { contextPath : String, hasGroupToDisplay : Bool, hasWriteRights : Bool } -> ( Model, Cmd Msg )
init flags =
  let
    initCategory = Category "" "" "" (SubCategories []) []
    initFilters       = (Filters Rudder.Filters.empty [])
    initUI       = UI initFilters NoModal flags.hasWriteRights True

    exportCsvOptions =
        buildOptions.newOptions
            |> buildOptions.withCsvExport
                { entryToStringList = entryToStringList, btnAttributes=[]}

    initModel    = Model flags.contextPath Loading initUI initCategory Dict.empty initTable exportCsvOptions.csvExport
    listInitActions =
      [ getGroupsTree initModel (not flags.hasGroupToDisplay)
      ]
  in
    ( initModel |> updateGroupsTableData
    , Cmd.batch listInitActions
    )


initTable : Rudder.Table.Model GroupWithCompliance Msg
initTable =
    let
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
            buildCustomizations.newCustomizations
                |> buildCustomizations.withTableContainerAttrs [class "table-container"]
                |> buildCustomizations.withTableAttrs [class "no-footer dataTable"]
                |> buildCustomizations.withTrAttrs (\row -> [ onClick (OpenGroupDetails row.id)])
                |> buildCustomizations.withThAttrs
                    (\(ColumnName name) ->
                        case name of
                            "Global compliance" -> [class "compliance-col"]
                            "Targeted compliance" -> [class "compliance-col"]
                            _ -> []
                    )
                |> buildCustomizations.withTdAttrs
                    (\(ColumnName name) ->
                        case name of
                            "Global compliance" -> [class "compliance-col"]
                            "Targeted compliance" -> [class "compliance-col"]
                            _ -> []
                    )

        options =
            buildOptions.newOptions
                |> buildOptions.withCustomizations customizations
                |> buildOptions.withCsvExport
                    { entryToStringList = entryToStringList, btnAttributes=[class "d-none"]}

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