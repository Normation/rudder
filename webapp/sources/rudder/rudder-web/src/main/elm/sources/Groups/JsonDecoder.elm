module Groups.JsonDecoder exposing (..)

import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)

import Groups.DataTypes exposing (..)
import GroupCompliance.DataTypes exposing (GroupId)
import Compliance.JsonDecoder exposing (decodeComplianceDetails)


decodeGetGroupsCompliance : Decoder (List GroupComplianceSummary)
decodeGetGroupsCompliance =
  at [ "data" , "nodeGroups" ] (list decodeGroupCompliance)

decodeGroupCompliance : Decoder GroupComplianceSummary
decodeGroupCompliance =
  succeed GroupComplianceSummary
    |> required "id" (map GroupId string)
    |> required "targeted" decodeComplianceSummaryValue
    |> required "global" decodeComplianceSummaryValue

decodeComplianceSummaryValue : Decoder ComplianceSummaryValue
decodeComplianceSummaryValue =
  succeed ComplianceSummaryValue
    |> required "compliance" float
    |> required "complianceDetails" decodeComplianceDetails

decodeGetGroupsTree : Decoder (Category Group)
decodeGetGroupsTree =
  at ["data", "groupCategories"] decodeCategoryGroupTarget

decodeCategoryGroupTarget : Decoder (Category Group)
decodeCategoryGroupTarget =
  succeed ( \id name description categories groups targets ->
    let
      elems = if id == rootGroupCategoryId then groups else (List.append groups targets)
    in
      Category id name description categories elems
    )
    |> required "id"          string
    |> required "name"        string
    |> required "description" string
    |> required "categories"  (map SubCategories  (list (lazy (\_ -> decodeCategoryGroupTarget))))
    |> required "groups"      (list decodeGroup)
    |> required "targets"     (list decodeTarget)


decodeGroup : Decoder Group
decodeGroup =
  succeed Group
    |> required "id"          (map GroupId string)
    |> required "displayName" string
    |> required "description" string
    |> optional "category"    (map Just string) Nothing
    |> required "nodeIds"    (list string)
    |> required "dynamic"     bool
    |> required "enabled"     bool
    |> required "target"      string

decodeTarget : Decoder Group
decodeTarget =
  succeed Group
    |> required "id"          (map GroupId string)
    |> required "displayName" string
    |> required "description" string
    |> optional "category"    (map Just string) Nothing
    |> hardcoded []
    |> hardcoded True
    |> required "enabled"     bool
    |> required "target"      string
