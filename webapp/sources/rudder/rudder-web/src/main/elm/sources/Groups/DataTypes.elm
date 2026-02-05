module Groups.DataTypes exposing (..)

import Compliance.Utils exposing (getAllComplianceValues)
import Dict exposing (Dict)
import Html exposing (Attribute)
import Html.Attributes exposing (attribute, class, disabled, style)
import Http exposing (Error)
import NaturalOrdering as N

import Compliance.DataTypes exposing (ComplianceDetails)
import GroupRelatedRules.DataTypes exposing (GroupId)
import Round
import Rudder.Filters exposing (SearchFilterState)
import Set

import Time
import Ui.Datatable exposing (TableFilters, Category, SubCategories(..), getAllElems)
import Rudder.Table exposing (Model, buildOptions)

type alias Model =
  { contextPath : String
  , mode        : Mode
  , ui          : UI
  , groupsTree  : Category Group
  , groupsCompliance : Dict String GroupComplianceSummary
  , groupsTable : Rudder.Table.Model GroupWithCompliance Msg
  , csvExportOptions : Rudder.Table.CsvExportOptions GroupWithCompliance Msg
  }

type Mode
  = Loading
  | LoadingTable
  | GroupTable
  | ExternalTemplate

type alias GroupComplianceSummary =
  { id       : GroupId
  , targeted : ComplianceSummaryValue
  , global   : ComplianceSummaryValue
  }

type alias ComplianceSummaryValue =
  { compliance        : Float
  , complianceDetails : ComplianceDetails
  }

type alias Group =
  { id          : GroupId
  , name        : String
  , description : String
  , category    : Maybe String
  , dynamic     : Bool
  , enabled     : Bool
  , target      : String
  }

type alias GroupWithCompliance =
  { id : GroupId
  , name : String
  , category : Maybe String
  , globalCompliance : Maybe ComplianceSummaryValue
  , targetedCompliance : Maybe ComplianceSummaryValue
  }

-- Get all groups for which the compliance summary is defined
getElemsWithCompliance: Model -> List Group
getElemsWithCompliance model =
  let
    getElemsWithComplianceRec : Category Group -> List Group
    getElemsWithComplianceRec category =
      let
        subElems = case category.subElems of SubCategories l -> l
      in
        List.append
          (List.filter (\e -> Dict.member e.id.value model.groupsCompliance) category.elems)
          (List.concatMap getElemsWithComplianceRec subElems)
  in
    getElemsWithComplianceRec model.groupsTree

getSubElems: Category a -> List (Category a)
getSubElems cat =
  case cat.subElems of
    SubCategories subs -> subs

type alias UI =
  { groupFilters   : Filters
  , modal          : ModalState
  , hasWriteRights : Bool
  , loadingGroups  : Bool
  }

type ModalState = NoModal | ExternalModal

type alias Filters =
  { filter : SearchFilterState
  , folded : List String
  }



-- The fixed group category ID for the root group category
rootGroupCategoryId : String
rootGroupCategoryId = "GroupRoot"
  
type Msg
  = OpenModal
  | CloseModal
  | LoadGroupTable
  | LoadMore
  | OpenGroupDetails GroupId
  | OpenCategoryDetails String
  | FoldAllCategories Filters
  | GetGroupsTreeResult (Result Error (Category Group)) Bool
  | GetGroupsComplianceResult Bool (Result Error (List GroupComplianceSummary))
  | UpdateGroupFoldedFilters String
  | UpdateGroupSearchFilters SearchFilterState
  | RudderTableMsg (Rudder.Table.Msg Msg)
  | ExportCsvWithCurrentDate Time.Posix

groupTableBatchSize : number
groupTableBatchSize = 50

-- Get all group ids in the tree, sorted the way they are top-down in the tree view : by name
treeIds : Category Group -> List GroupId
treeIds category =
  let
    sortByName = List.sortWith (\c1 c2 -> N.compare c1.name c2.name)
    subElems = case category.subElems of SubCategories l -> sortByName l
  in
    category.elems
        |> sortByName
        |> List.map .id
        |> List.append (subElems |> List.concatMap treeIds)

-- Compute next ids to load in the group table
nextGroupIds : Model -> List GroupId
nextGroupIds model =
  let
    allGroupIds = treeIds model.groupsTree |> List.map (.value)
    knownGroupIds = model.groupsCompliance |> Dict.keys
    groupById = model.groupsTree |> getAllElems |> List.map (\g -> (g.id.value, g)) |> Dict.fromList
    getGroupNameById id = case Dict.get id groupById of
      Just g -> g.name
      Nothing -> ""
    diff = Set.diff (Set.fromList allGroupIds) (Set.fromList knownGroupIds) |> Set.toList
    res =
      diff
        |> List.sortWith (\c1 c2 -> N.compare (getGroupNameById c1) (getGroupNameById c2))
        |> List.take groupTableBatchSize
        |> List.map GroupId
  in
    res

{-| Update both the groupsTable and csvExportOptions when the model's groupsCompliance is fetched and updated. -}
updateGroupsTableData : Model -> Model
updateGroupsTableData model =
    let
        groupsList = getElemsWithCompliance model
        data =
            List.map
                (toGroupWithCompliance model.groupsCompliance)
                groupsList

        size =  model.groupsTable |> Rudder.Table.getRows |> List.length
        {-
            For some reason, table data is not loaded if the user loads the elm app in the state where
            a given group's details are displayed on the right pane, e.g.
            by opening the link rudder/secure/nodeManager/groups#{"groupId":"all-nodes-with-cfengine-agent"}
            (i.e. without visiting rudder/secure/nodeManager/groups, and clicking on the group from the table or the tree).
            The "isDisabled" condition ensures that the export button is enabled if and only if table data is available.
        -}
        isDisabled = (size == 0) && (model.mode /= GroupTable)
        options =
            buildOptions.newOptions
            |> buildOptions.withCsvExport
                { entryToStringList = entryToStringList
                , btnAttributes = (if isDisabled then disabledCsvExportButtonAttributes else [class "btn-primary"])}

    in
    {model | groupsTable = Rudder.Table.updateData data model.groupsTable, mode = (if model.mode == LoadingTable then GroupTable else model.mode), csvExportOptions = options.csvExport }


disabledCsvExportButtonAttributes : List (Attribute msg)
disabledCsvExportButtonAttributes =
    [ disabled True
    , attribute "data-bs-toggle" "tooltip"
    , attribute "data-bs-placement" "bottom"
    , attribute
        "data-bs-original-title"
        "The groups tree was not loaded. Close the group details view on the right pane in order to load the groups tree."
    , style "pointer-events" "auto"]


toGroupWithCompliance : Dict String GroupComplianceSummary -> Group -> GroupWithCompliance
toGroupWithCompliance groupsCompliance group =
    let
        compliance =
            Dict.get group.id.value groupsCompliance
    in
    { id = group.id
    , name = group.name
    , category = group.category
    , globalCompliance = Maybe.map .global compliance
    , targetedCompliance = Maybe.map .targeted compliance
    }

entryToStringList : GroupWithCompliance -> List String
entryToStringList group =
    [ group.name
    , group.category |> categoryToString
    , group.globalCompliance |> complianceToString
    , group.targetedCompliance |> complianceToString
    ]

complianceToString : Maybe ComplianceSummaryValue -> String
complianceToString complianceOpt =
    case complianceOpt of
        Just compliance ->
            if (complianceDataAvailable compliance)
            then (Round.round 2 compliance.compliance) ++ "%"
            else "No data available"
        Nothing ->
            "Loading..."

categoryToString : Maybe String -> String
categoryToString category =
    case category of
        Nothing -> "Groups"
        Just "SystemGroups" -> "System groups"
        Just "GroupRoot" -> "Root of the groups and group categories"
        Just cat -> cat

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
