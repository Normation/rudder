module Groups.DataTypes exposing (..)

import Dict exposing (Dict)
import Http exposing (Error)
import NaturalOrdering as N

import Compliance.DataTypes exposing (ComplianceDetails)
import GroupRelatedRules.DataTypes exposing (GroupId)
import Rudder.Filters exposing (SearchFilterState)
import Set

import Time
import Ui.Datatable exposing (TableFilters, Category, SubCategories(..), getAllElems)
import Rudder.Table exposing (Model)

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
  { treeFilters  : TreeFilters }

type alias TreeFilters =
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
  | UpdateGroupFoldedFilters Filters
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