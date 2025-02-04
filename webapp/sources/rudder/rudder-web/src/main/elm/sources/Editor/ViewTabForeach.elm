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

displayTabForeach : ForeachUI -> MethodElem -> (String -> Msg) -> (String -> Msg) -> (String -> Msg) -> Msg -> Msg -> Msg -> (String -> String -> Msg) -> Msg -> Msg -> Msg -> Msg -> Msg -> Msg -> Element Msg
displayTabForeach foreachUI elem actionRemoveKey actionUpdateNewForeach actionUpdateNewForeachKey actionAddNewKey actionResetNewForEach actionAddForeach actionUpdateNewItem actionAddNewItem actionSaveNewForeach actionEditForeachName actionSaveEditKeys actionEditForeachKeys actionRemoveForeach =
  let
    (callId, foreachName, foreach) = case elem of
      Call  _ c ->
        ( c.id
        , c.foreachName
        , c.foreach
        )
      Block _ b ->
        ( b.id
        , b.foreachName
        , b.foreach
        )
    updateForeach maybeForeach = case elem of
      Call  _ c -> (Call  (Just callId) {c | foreach = maybeForeach})
      Block _ b -> (Block (Just callId) {b | foreach = maybeForeach})

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
              |> addActionStopAndPrevent ("click", actionRemoveKey k)
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
                        |> addAction ("click", Copy ("${" ++ iteratorName ++ "}.x"))
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
                            |> addInputHandler  (\s -> actionUpdateNewForeach s)
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
                                |> addInputHandler (\s -> actionUpdateNewForeachKey s)
                              , element "button"
                                |> addAttributeList
                                  [ class "btn btn-default"
                                  , type_ "button"
                                  , disabled (String.isEmpty newForeach.newKey)
                                  , onCustomClick actionAddNewKey
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
                              , onCustomClick actionResetNewForEach
                              ]
                            |> appendText "Reset"
                            |> appendChild (element "i" |> addClass "fa fa-undo ms-1")
                          , element "button"
                            |> addAttributeList
                              [ class "btn btn-primary"
                              , type_ "button"
                              , disabled (List.isEmpty newForeach.foreachKeys)
                              , onCustomClick actionAddForeach
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
                    |> List.map (\f ->
                      let
                        values = keys
                          |> List.map (\k ->
                            let
                              val = case Dict.get k f of
                                Just v -> v
                                Nothing -> ""

                              updateForeachVal : String -> List (Dict String String) -> Dict String String -> Maybe (List (Dict String String))
                              updateForeachVal newVal list currentForeach =
                                Just (List.Extra.updateIf (\i -> i == currentForeach) (\i -> Dict.update k (always (Just newVal)) i) list)
                            in
                              element "td"
                              |> appendChild ( element "input"
                                |> addAttributeList
                                  [ type_ "text"
                                  , value val
                                  , class "form-control input-sm"
                                  ]
                                |> addInputHandler  (\s -> MethodCallModified (updateForeach (updateForeachVal s items f)))
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
                                , onCustomClick (MethodCallModified (updateForeach (Just (List.Extra.remove f items))))
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
                          |> addInputHandler  (\s -> actionUpdateNewItem k s)
                        )
                    )
                  actionBtns =
                    let
                      newItems = case foreach of
                        Just f -> List.append f [newForeach.newItem]
                        Nothing -> [newForeach.newItem]
                      newItem = newForeach.newItem
                        |> Dict.map (\k v -> "")
                    in
                      [ element "td"
                        |> addClass "text-center"
                        |> appendChild (element "button"
                          |> addAttributeList
                            [ type_ "button"
                            , class "btn btn-success"
                            , onCustomClick actionAddNewItem
                            ]
                          |> appendChild (element "i" |> addClass "fa fa-plus-circle")
                        )
                      ]
                in
                  [ element "tr" |> addClass "item-foreach new" |> appendChildList (List.append newValues actionBtns) ]

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

              foreachNameLabel =
                if String.isEmpty name then
                  element "i" |> appendText "item"
                else
                  element "span" |> appendText name

              updatedNewItem =
                newForeach.foreachKeys
                  |> List.map (\k -> (k, ""))
                  |> Dict.fromList
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
                                  |> addInputHandler  (\s -> actionUpdateNewForeach s)
                                , element "button"
                                  |> addClass "btn btn-default"
                                  |> addAttributeList
                                    [ type_ "button"
                                    , onCustomClick actionSaveNewForeach
                                    ]
                                  |> appendChild (element "i" |> addClass "fa fa-check")
                                ]
                            ] foreachUI.editName
                          |> appendChildListConditional
                            [ foreachNameLabel
                            , element "i" |> addClass "fa fa-edit ms-2" |> addActionStopAndPrevent ("click", actionEditForeachName)
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
                                  |> addInputHandler (\s -> actionUpdateNewForeachKey s)
                                , element "button"
                                  |> addClass "btn btn-default"
                                  |> addAttributeList
                                    [ type_ "button"
                                    , disabled (String.isEmpty newForeach.newKey)
                                    ]
                                  |> addActionStopAndPrevent ("click", actionAddNewKey)
                                  |> appendChild (element "i" |> addClass "fa fa-plus-circle")
                                , element "button"
                                  |> addClass "btn btn-default ms-2"
                                  |> addAttributeList
                                    [ type_ "button"
                                    , disabled (List.isEmpty newForeach.foreachKeys)
                                    ]
                                  |> addActionStopAndPrevent ("click", actionSaveEditKeys)
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
                          [ (element "i" |> addClass "fa fa-edit ms-2" |> addActionStopAndPrevent ("click" , actionEditForeachKeys)) ]
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
                      |> addActionStopAndPrevent ("click", actionRemoveForeach)
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