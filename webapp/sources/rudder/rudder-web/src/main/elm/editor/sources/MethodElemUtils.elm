module MethodElemUtils exposing (..)

import DataTypes exposing (..)
import List.Extra

getAllCalls: MethodElem -> List MethodElem
getAllCalls call =
  call ::
  case call of
    Block _ b -> List.concatMap getAllCalls b.calls
    _ -> []

setIdRec : String -> List MethodElem -> (List MethodElem, Bool)
setIdRec  newId elems =
  case elems of
    [] -> ([], False)
    head :: tail ->
      if ((getId head ) == CallId "") then
        ((setId (CallId newId) head ) :: tail, True)
      else
        case head of
          Block p b ->
            case setIdRec newId b.calls of
              (calls, True) ->
                ((Block p {b | calls = calls} ) :: tail, True)
              _ ->
                Tuple.mapFirst ((::) head ) (setIdRec newId tail)
          _ -> Tuple.mapFirst ((::) head ) (setIdRec newId tail)

updateElemIf : (MethodElem -> Bool) -> ( MethodElem -> MethodElem) -> List MethodElem -> List MethodElem
updateElemIf predicate updateFun list =
  let
    updateLowerLevel = List.Extra.updateIf predicate updateFun list
  in
    List.map (\ x ->
      case x of
        Block p b -> Block p {b | calls = updateElemIf predicate updateFun b.calls}
        Call _ _ -> x
    ) updateLowerLevel


removeElem : (MethodElem -> Bool) -> List MethodElem -> List MethodElem
removeElem predicate list =
  let
    updateLowerLevel = List.Extra.filterNot predicate list
  in
    List.map (\ x ->
      case x of
        Block p b -> Block p {b | calls = removeElem predicate b.calls}
        Call _ _ -> x
    ) updateLowerLevel

getComponent : MethodElem -> String
getComponent x =
  case x of
    Call _ c -> c.component
    Block _ b -> b.component

getId : MethodElem -> CallId
getId x =
  case x of
    Call _ c -> c.id
    Block _ b -> b.id

getParent : MethodElem -> Maybe CallId
getParent x =
  case x of
    Call p _ -> p
    Block p _ -> p

setId : CallId -> MethodElem -> MethodElem
setId newId x =
  case x of
    Call parent c -> Call parent {c | id = newId }
    Block parent b -> Block parent {b | id = newId }