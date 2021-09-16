module ViewUtilsRules exposing (..)

import DataTypes exposing (..)
import List.Extra
import List
import String


getListRules : Category Rule -> List (Rule)
getListRules r = getAllElems r

getListCategories : Category Rule  -> List (Category Rule)
getListCategories r = getAllCats r

getCategoryName : Model -> String -> String
getCategoryName model id =
  let
    cat = List.Extra.find (.id >> (==) id  ) (getListCategories model.rulesTree)
  in
    case cat of
      Just c -> c.name
      Nothing -> id

filterRules : Model -> Rule -> Bool
filterRules model r =
  let
    -- List of fields that will be checked during a search
    searchFields =
      [ r.id.value
      , r.name
      , r.categoryId
      , getCategoryName model r.categoryId
      ]
    -- Join all these fields into one string to simplify the search
    stringToCheck = String.join "|" searchFields
      |> String.toLower

    searchString  = model.ui.ruleFilters.filter
      |> String.toLower
      |> String.trim
  in
    String.contains searchString stringToCheck