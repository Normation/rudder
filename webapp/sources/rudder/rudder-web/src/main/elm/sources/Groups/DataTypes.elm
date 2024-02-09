module Groups.DataTypes exposing (..)

import Dict exposing (Dict)
import Http exposing (Error)
import NaturalOrdering as N

import Compliance.DataTypes exposing (ComplianceDetails)
import GroupRelatedRules.DataTypes exposing (GroupId)
import Set

type alias Model =
  { contextPath : String
  , mode        : Mode
  , ui          : UI
  , groupsTree  : Category Group
  , groupsCompliance : Dict String GroupComplianceSummary
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

type alias Category a =
  { id          : String
  , name        : String
  , description : String
  , subElems    : SubCategories a
  , elems       : List a
  }

type SubCategories a = SubCategories (List (Category a))

type alias Group =
  { id          : GroupId
  , name        : String
  , description : String
  , category    : Maybe String
  , nodeIds     : List String
  , dynamic     : Bool
  , enabled     : Bool
  , target      : String
  }

getAllElems: Category a -> List a
getAllElems category =
  let
    subElems = case category.subElems of SubCategories l -> l
  in
    List.append category.elems (List.concatMap getAllElems subElems)

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
  { tableFilters : TableFilters
  , treeFilters  : TreeFilters
  }

type SortBy
  = Name
  | Parent
  | GlobalCompliance
  | TargetedCompliance

type alias TableFilters =
  { sortBy     : SortBy
  , sortOrder  : SortOrder
  , filter     : String
  }

type alias TreeFilters =
  { filter : String
  , folded : List String
  }

type SortOrder = Asc | Desc

-- The fixed group category ID for the root group category
rootGroupCategoryId : String
rootGroupCategoryId = "GroupRoot"
  
type Msg
  = OpenModal
  | CloseModal
  | LoadMore
  | OpenGroupDetails GroupId
  | OpenCategoryDetails String
  | FoldAllCategories Filters
  | GetGroupsTreeResult (Result Error (Category Group))
  | GetGroupsComplianceResult Bool (Result Error (List GroupComplianceSummary))
  | UpdateGroupFilters      Filters

groupTableBatchSize : number
groupTableBatchSize = 20

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