module Groups.Init exposing (..)

import Compliance.Html exposing (buildComplianceBar)
import Compliance.Utils exposing (defaultComplianceFilter, getAllComplianceValues)
import Dict exposing (Dict)

import Groups.ViewUtils exposing (getCategoryName)
import Html exposing (text)
import List.Nonempty as NonEmptyList

import Groups.ApiCalls exposing (..)
import Groups.DataTypes exposing (..)

import Html.Attributes exposing (class)
import Ordering
import Rudder.Table exposing (Column, ColumnName(..), FilterOptionsType(..), buildConfig, buildOptions)
import Rudder.Filters exposing (byValues)
import Ui.Datatable exposing (defaultTableFilters, Category, SubCategories(..))


init : { contextPath : String, hasGroupToDisplay : Bool, hasWriteRights : Bool } -> ( Model, Cmd Msg )
init flags =
  let
    initCategory = Category "" "" "" (SubCategories []) []
    initTableFilters  = defaultTableFilters Name
    initTreeFilters   = (TreeFilters "" [])
    initFilters       = Filters initTableFilters initTreeFilters
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
                    text ""

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

        compareCompliance : Maybe ComplianceSummaryValue -> Maybe ComplianceSummaryValue -> Order
        compareCompliance compliance1 compliance2 =
            case (compliance1,compliance2) of
                (Just _, Nothing) -> GT
                (Nothing, Just _) -> LT
                (Nothing, Nothing) -> EQ
                (Just c1, Just c2) ->
                    case (complianceDataAvailable c1, complianceDataAvailable c2) of
                        (True,True) -> compare c1.compliance c2.compliance
                        (True, False) -> GT
                        (False, True) -> LT
                        (False,False) -> EQ
        fileName =
            -- TODO get current date
            "rudder_groups_" ++ "" -- todo Time.now
        customizations =
            { tableContainerAttrs = [class "table-container"]
            , tableAttrs = [class "no-footer dataTable"]
            , optionsHeaderAttrs = [class "d-none"]
            , theadAttrs = []
            , tbodyAttrs = []
            , rowAttrs = \_ -> []
            }
        options =
            buildOptions.newOptions
                |> buildOptions.withCustomizations customizations
                |> buildOptions.withCsvExport
                    { fileName = fileName
                    , entryToStringList = entryToStringList
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


filterPredicate : (String -> Bool) -> (GroupWithCompliance -> Bool)
filterPredicate filter data = data |> entryToStringList |> List.any filter

entryToStringList : GroupWithCompliance -> List String
entryToStringList group =
    [ group.name
    , group.category |> Maybe.withDefault ""
    , group.globalCompliance |> complianceToString
    , group.targetedCompliance |> complianceToString
    ]

complianceToString : Maybe ComplianceSummaryValue -> String
complianceToString complianceOpt =
    case complianceOpt of
        Just compliance ->
            String.fromFloat compliance.compliance ++ "%"
        Nothing -> "" -- todo