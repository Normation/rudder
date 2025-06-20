module Editor.ViewTabForeach exposing (..)


import Json.Decode
import Html.Attributes exposing (..)
import Html.Events exposing (..)
import Dom exposing (..)
import Set
import Dict exposing (Dict)
import Dict.Extra exposing (keepOnly)
import List.Extra
import Maybe.Extra

import Rules.ViewUtils exposing (onCustomClick)
import Editor.DataTypes exposing (..)
import Editor.MethodElemUtils exposing (..)


foreachLabel : Maybe String -> Maybe (List (Dict String String)) -> Element msg -> Element msg
foreachLabel foreachName foreach =
  let
    nbForeach = case foreach of
      Just f -> String.fromInt (List.length f)
      Nothing -> "0"

    labelTxt = case foreachName of
      Nothing -> ""
      Just f  -> "foreach ${" ++ f ++ ".x}"
  in
    appendChildConditional
      ( element "div"
        |> addClass ("gm-label rudder-label gm-foreach d-inline-flex ps-0 overflow-hidden")
        |> appendChild
          ( element "span"
            |> addClass "counter px-1 me-1"
            |> appendText nbForeach
            |> appendChild
              ( element "i"
                |> addClass "fa fa-retweet ms-1"
              )
          )
        |> appendChild
          ( element "span"
            |> appendText labelTxt
          )
      )
      ( Maybe.Extra.isJust foreachName )

type alias UpdateUIMsg =
  { addNewKey           : Msg
  , removeKey           : String -> Msg
  , updateNewForeach    : String -> Msg
  , updateNewForeachKey : String -> Msg
  , resetNewForeach     : Msg
  , updateNewItem       : String -> String -> Msg
  , editForeachName     : Msg
  , editForeachKeys     : Msg
  }

type alias UpdateMsg =
  { addForeach          : Msg
  , addNewItem          : Msg
  , saveNewForeach      : Msg
  , saveEditKeys        : Msg
  , removeForeach       : Msg
  }

type alias CallForeach a = { a | foreachName : Maybe String, foreach : Maybe (List (Dict String String)) } -- a = MethodCall OR MethodBlock

getUIMessages : ForeachUI -> ( ForeachUI -> Msg ) -> CallForeach a -> UpdateUIMsg
getUIMessages f toMsg call =
  let
    newF = f.newForeach
  in
    { removeKey             = \k -> toMsg { f | newForeach = {newF | foreachKeys = (List.Extra.remove k newF.foreachKeys)} }
    , updateNewForeach      = \s -> toMsg { f | newForeach = {newF | foreachName = s}}
    , updateNewForeachKey   = \s -> toMsg { f | newForeach = {newF | newKey = s}}
    , addNewKey             = toMsg { f | newForeach = {newF | newKey = "", foreachKeys = (newF.newKey :: newF.foreachKeys)}}
    , resetNewForeach       = toMsg { f | newForeach = (defaultNewForeach call.foreachName call.foreach)}
    , updateNewItem         = \k -> \s -> toMsg { f | newForeach = {newF | newItem = Dict.update k (always (Just s) ) newF.newItem }}
    , editForeachName       = toMsg { f | newForeach = {newF | foreachName = (Maybe.withDefault "" call.foreachName)}, editName = True}
    , editForeachKeys       = toMsg { f | editKeys = True }
    }

getUpdateMessages : ForeachUI -> CallForeach a -> (ForeachUI -> CallForeach a -> Msg) -> UpdateMsg
getUpdateMessages foreachUI call toMsg =
  let
    newForeach = foreachUI.newForeach
    foreach = call.foreach

    newItems = case foreach of
      Just f -> List.append f [newForeach.newItem]
      Nothing -> [newForeach.newItem]

    newItem = newForeach.foreachKeys
      |> List.map (\k -> (k, ""))
      |> Dict.fromList

    newForeachItems =
      case foreach of
        Nothing -> Nothing
        Just items ->
          let
            newKeysList = newForeach.foreachKeys
            updatedForeach =
              items
                |> List.Extra.updateIf (\f -> (Dict.keys f) |> List.any (\k -> List.Extra.notMember k newKeysList) ) -- If an old key is not present anymore, remove it
                  (\f -> f |> keepOnly (Set.fromList newKeysList) )
                |> List.Extra.updateIf (\f -> newKeysList |> List.any (\k -> List.Extra.notMember k (Dict.keys f)) ) -- If a new key is detected, insert it
                  (\f ->
                    let
                      keysList  = Dict.keys f
                      currentList = Dict.toList f
                      newList = newKeysList
                        |> List.Extra.filterNot (\k -> List.member k keysList)
                        |> List.map (\k -> (k, ""))
                    in
                      currentList
                        |> List.append newList
                        |> Dict.fromList
                  )
          in
            Just updatedForeach
  in
    { addForeach     = toMsg { foreachUI | newForeach = { newForeach | newItem = newItem}} ( {call | foreachName = Just (if String.isEmpty newForeach.foreachName then "item" else newForeach.foreachName), foreach = Just [newItem]})
    , addNewItem     = toMsg { foreachUI | newForeach = { newForeach | newItem = newItem}} ( {call | foreach = Just newItems})
    , saveNewForeach = toMsg { foreachUI | editName = False} ( {call | foreachName = Just (newForeach.foreachName) })
    , saveEditKeys   = toMsg { foreachUI | editKeys = False, newForeach = {newForeach | newItem = newItem}} ( {call | foreach = newForeachItems })
    , removeForeach  = toMsg { foreachUI | newForeach = (defaultNewForeach Nothing Nothing)} ( {call | foreachName = Nothing, foreach = Nothing })
    }

displayTabForeach : UiInfo -> Element Msg
displayTabForeach uiInfo =
  let

    {callId, foreachName, foreach, updateForeach, foreachUI, updateUIMsg, updateMsg} = case uiInfo of
      CallUiInfo methodCallUiInfo call ->
        { callId = call.id
        , foreachName = call.foreachName
        , foreach = call.foreach
        , updateForeach = \maybeForeach -> (Call  (Just call.id) {call | foreach = maybeForeach})
        , foreachUI = methodCallUiInfo.foreachUI
        , updateUIMsg = getUIMessages methodCallUiInfo.foreachUI (\fUI -> UIMethodAction call.id {methodCallUiInfo | foreachUI = fUI}) call -- (\c -> (Call (Just call.id) {call | foreachName = c.foreachName, foreach = c.foreach}))
        , updateMsg = getUpdateMessages methodCallUiInfo.foreachUI call (\fUI c -> UpdateCallAndUi (CallUiInfo {methodCallUiInfo | foreachUI = fUI} {call | foreachName = c.foreachName, foreach = c.foreach} ))
        }

      BlockUiInfo methodBlockUiInfo call ->
        { callId = call.id
        , foreachName = call.foreachName
        , foreach = call.foreach
        , updateForeach = \maybeForeach -> (Block (Just call.id) {call | foreach = maybeForeach})
        , foreachUI = methodBlockUiInfo.foreachUI
        , updateUIMsg = getUIMessages methodBlockUiInfo.foreachUI (\fUI -> UIBlockAction call.id {methodBlockUiInfo | foreachUI = fUI}) call -- (\c -> (Block (Just call.id) {call | foreachName = c.foreachName, foreach = c.foreach}))
        , updateMsg = getUpdateMessages methodBlockUiInfo.foreachUI call (\fUI c -> UpdateCallAndUi (BlockUiInfo {methodBlockUiInfo | foreachUI = fUI} {call | foreachName = c.foreachName, foreach = c.foreach} ))
        }

    { removeKey, updateNewForeach, updateNewForeachKey, addNewKey, resetNewForeach, updateNewItem, editForeachName, editForeachKeys } = updateUIMsg
    { addForeach, addNewItem, saveNewForeach, saveEditKeys, removeForeach } = updateMsg

    newForeach = foreachUI.newForeach

    newKeys : Bool -> List (Element Msg)
    newKeys edit = newForeach.foreachKeys
      |> List.reverse
      |> List.map (\k ->
        element "span"
          |> addClass "d-inline-flex align-items-center me-2 mb-2"
          |> addClassConditional "ps-2" edit
          |> addClassConditional "p-2" (not edit)
          |> appendText k
          |> appendChildConditional
            ( element "i"
              |> addClass "fa fa-times p-2 cursorPointer"
              |> addActionStopAndPrevent ("click", removeKey k)
            ) edit
      )

    tabContent =
      let
        infoMsg =
          let
            iteratorName = case foreachName of
              Just name -> if String.isEmpty name then "item" else name
              Nothing -> "<iterator name>"
          in
            element "div"
              |> addClass "callout-fade callout-info"
              |> appendChildList
                [ element "div" |> addClass "marker" |> appendChild (element "span" |> addClass "fa fa-info-circle")
                , element "div"
                  |> appendChildList
                    [ element "span" |> appendText "Use the iterator in the Name, Parameters and Conditions tabs with "
                    , element "b"
                      |> appendChild (element "code"
                        |> addClass "cursorPointer py-2 me-1"
                        |> addAction ("click", Copy ("${" ++ iteratorName ++ ".x}"))
                        |> appendChild (element "span" |> appendText "${")
                        |> appendChildConditional (element "span" |> appendText iteratorName) (Maybe.Extra.isJust foreachName)
                        |> appendChildConditional (element "i" |> appendText iteratorName) (Maybe.Extra.isNothing foreachName)
                        |> appendChild (element "span" |> appendText ".x}")
                        |> appendChild (element "i" |> addClass "ion ion-clipboard ms-1")
                      )
                    , element "span" |> appendText " where "
                    , element "b" |> appendChild ( element "code" |> appendText "x" )
                    , element "span" |> appendText " is a key name."
                    ]
                ]
      in
        case foreachName of
          -- Creation
          Nothing ->
            element "div"
              |> addClass "d-flex row gx-3"
              |> appendChildList
                [ element "div"
                  |> addClass "col-12 col-md-6"
                  |> appendChildList
                    [ element "div"
                      |> addClass "form-group mb-3"
                      |> appendChildList
                        [ element "label"
                          |> addClass "form-label"
                          |> addAttribute (for "foreachName")
                          |> appendText "Iterator name"
                        , element "input"
                          |> addAttributeList
                            [ type_ "text"
                            , class "form-control"
                            , id "foreachName"
                            , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                            , stopPropagationOn "click" (Json.Decode.succeed (DisableDragDrop, True))
                            , onFocus DisableDragDrop
                            , placeholder "item"
                            , value newForeach.foreachName
                            ]
                          |> addInputHandler  (\s -> updateNewForeach s)
                        ]
                    , element "div"
                      |> addClass "form-group"
                      |> appendChildList
                        [ element "label"
                          |> addClass "form-label"
                          |> addAttribute (for "foreachKeys")
                          |> appendText "Iterator keys"
                        , element "div"
                          |> addClass "input-group"
                          |> appendChildList
                            [ element "input"
                              |> addAttributeList
                                [ type_ "text"
                                , class "form-control"
                                , id "foreachKeys"
                                , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                                , stopPropagationOn "click" (Json.Decode.succeed (DisableDragDrop, True))
                                , onFocus DisableDragDrop
                                , value newForeach.newKey
                                ]
                              |> addInputHandler (\s -> updateNewForeachKey s)
                            , element "button"
                              |> addAttributeList
                                [ class "btn btn-default"
                                , type_ "button"
                                , disabled (String.isEmpty newForeach.newKey)
                                , onCustomClick addNewKey
                                ]
                              |> appendChild (
                                element "i"
                                |> addClass "fa fa-plus-circle"
                              )
                            ]
                        ]
                    , element "div"
                      |> addClass "foreach-keys mt-2"
                      |> appendChildList (newKeys True)
                    , element "div"
                      |> addClass "mt-3"
                      |> appendChildList
                        [ element "button"
                          |> addAttributeList
                            [ class "btn btn-default me-3"
                            , type_ "button"
                            , onCustomClick resetNewForeach
                            ]
                          |> appendText "Reset"
                          |> appendChild (element "i" |> addClass "fa fa-undo ms-1")
                        , element "button"
                          |> addAttributeList
                            [ class "btn btn-primary"
                            , type_ "button"
                            , disabled (List.isEmpty newForeach.foreachKeys)
                            , onCustomClick addForeach
                            ]
                          |> appendText "Add foreach"
                          |> appendChild (element "i" |> addClass "fa fa-check ms-1")
                        ]
                    ]
                , element "div"
                  |> addClass "col-12 col-md-6"
                  |> appendChild infoMsg
                ]

          -- Edit
          Just name ->
            let
              keys = Dict.keys newForeach.newItem

              header = keys
                |> List.map (\k -> element "th" |> appendText k)

              foreachItems = case foreach of
                Just items ->
                  items
                    |> List.indexedMap (\index f ->
                      let
                        values = keys
                          |> List.map (\k ->
                            let
                              val = case Dict.get k f of
                                Just v -> v
                                Nothing -> ""

                              updateForeachVal : String -> List (Dict String String) -> Int -> Maybe (List (Dict String String))
                              updateForeachVal newVal list ind =
                                Just (List.Extra.updateAt ind (\i -> Dict.update k (always (Just newVal)) i) list)
                            in
                              element "td"
                              |> appendChild ( element "input"
                                |> addAttributeList
                                  [ type_ "text"
                                  , value val
                                  , class "form-control input-sm"
                                  ]
                                |> addInputHandler  (\s -> MethodCallModified (updateForeach (updateForeachVal s items index)) Nothing)
                              )
                          )
                        actionBtns =
                          [ element "td"
                            |> addClass "text-center"
                            |> appendChild (
                              element "button"
                              |> addAttributeList
                                [ type_ "button"
                                , class "btn btn-danger"
                                , disabled (List.length items <= 1)
                                , onCustomClick (MethodCallModified (updateForeach (Just (List.Extra.remove f items))) Nothing)
                                ]
                              |> appendChild (element "i" |> addClass "fa fa-times")
                            )
                          ]
                      in
                        element "tr"
                        |> addClass "item-foreach"
                        |> appendChildList (List.append values actionBtns)
                    )
                Nothing -> []

              newItemRow =
                let
                  newValues = keys
                    |> List.map (\k ->
                      let
                        val = case Dict.get k newForeach.newItem of
                          Just v -> v
                          Nothing -> ""
                      in
                        element "td"
                        |> appendChild ( element "input"
                          |> addAttributeList
                            [ type_ "text"
                            , class "form-control input-sm"
                            , value val
                            , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                            , onFocus DisableDragDrop
                            ]
                          |> addInputHandler  (\s -> updateNewItem k s)
                        )
                    )
                  actionBtns =
                    [ element "td"
                      |> addClass "text-center"
                      |> appendChild (element "button"
                        |> addAttributeList
                          [ type_ "button"
                          , class "btn btn-success"
                          , onCustomClick addNewItem
                          ]
                        |> appendChild (element "i" |> addClass "fa fa-plus-circle")
                      )
                    ]
                in
                  [ element "tr" |> addClass "item-foreach new" |> appendChildList (List.append newValues actionBtns) ]

              foreachNameLabel =
                if String.isEmpty name then
                  element "i" |> appendText "item"
                else
                  element "span" |> appendText name
            in
              element "div"
              |> addClass "d-flex row gx-3"
              |> appendChildList
                [ element "div"
                  |> addClass "col-12 col-md-6"
                  |> appendChildList
                    [ element "div"
                      |> addClass "row mb-3 form-group"
                      |> appendChildList
                        [ element "label"
                          |> addAttribute (for "foreachName")
                          |> addClass "col-auto col-form-label"
                          |> appendText "Iterator name "
                        , element "div"
                          |> addClass "col d-flex align-items-center"
                          |> appendChildListConditional
                            [ element "div"
                              |> addClass "input-group"
                              |> appendChildList
                                [ element "input"
                                  |> addAttributeList
                                    [ type_ "text"
                                    , class "form-control"
                                    , id "foreachName"
                                    , placeholder "item"
                                    , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                                    , onFocus DisableDragDrop
                                    , value newForeach.foreachName
                                    ]
                                  |> addInputHandler  (\s -> updateNewForeach s)
                                , element "button"
                                  |> addClass "btn btn-default"
                                  |> addAttributeList
                                    [ type_ "button"
                                    , onCustomClick saveNewForeach
                                    ]
                                  |> appendChild (element "i" |> addClass "fa fa-check")
                                ]
                            ] foreachUI.editName
                          |> appendChildListConditional
                            [ foreachNameLabel
                            , element "i" |> addClass "fa fa-edit ms-2" |> addActionStopAndPrevent ("click", editForeachName)
                            ] (not foreachUI.editName)

                        ]
                    , element "div"
                      |> addClass "row mb-3 form-group"
                      |> appendChild ( element "label"
                        |> addAttribute (for "foreachKeys")
                        |> addClass "col-auto col-form-label"
                        |> appendText "Iterator keys "
                      )
                      |> appendChildConditional ( element "div"
                        |> addClass "col d-flex"
                        |> appendChild ( element "div"
                          |> addClass "d-flex row w-100"
                          |> appendChildList
                            [ element "div"
                              |> addClass "input-group group-key"
                              |> appendChildList
                                [ element "input"
                                  |> addAttributeList
                                    [ type_ "text"
                                    , class "form-control"
                                    , id "foreachKeys"
                                    , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                                    , onFocus DisableDragDrop
                                    , value newForeach.newKey
                                    ]
                                  |> addInputHandler (\s -> updateNewForeachKey s)
                                , element "button"
                                  |> addClass "btn btn-default"
                                  |> addAttributeList
                                    [ type_ "button"
                                    , disabled (String.isEmpty newForeach.newKey)
                                    ]
                                  |> addActionStopAndPrevent ("click", addNewKey)
                                  |> appendChild (element "i" |> addClass "fa fa-plus-circle")
                                , element "button"
                                  |> addClass "btn btn-default ms-2"
                                  |> addAttributeList
                                    [ type_ "button"
                                    , disabled (List.isEmpty newForeach.foreachKeys)
                                    ]
                                  |> addActionStopAndPrevent ("click", saveEditKeys)
                                  |> appendChild (element "i" |> addClass "fa fa-check")
                                ]
                          , element "div" |> addClass "foreach-keys mt-2 mb-3" |> appendChildList (newKeys True)
                          ]
                        )
                      ) foreachUI.editKeys
                      |> appendChildConditional ( element "div"
                        |> addClass "col d-flex align-items-center foreach-keys"
                        |> appendChildList ( List.append
                          (newKeys False)
                          [ (element "i" |> addClass "fa fa-edit ms-2" |> addActionStopAndPrevent ("click" , editForeachKeys)) ]
                        )
                      ) (not foreachUI.editKeys)

                    , element "div"
                      |> addClass "table-foreach-container"
                      |> appendChild (element "table"
                        |> addClass "table table-bordered table-foreach mb-0"
                        |> appendChildList
                          [ element "thead" |> appendChild (element "tr" |> appendChildList (List.append header [element "th" |> appendText "Action"] ))
                          , element "tbody" |> appendChildList (List.append foreachItems newItemRow)
                          ]
                      )

                    , element "button"
                      |> addClass "btn btn-danger mt-2"
                      |> addActionStopAndPrevent ("click", removeForeach)
                      |> appendText "Remove iterator"
                      |> appendChild (element "i" |> addClass "fa fa-times ms-1")
                    ]
                , element "div"
                  |> addClass "col-12 col-md-6"
                  |> appendChild infoMsg
                ]
  in
    element "div"
      |> addClass "form-group"
      |> appendChild tabContent