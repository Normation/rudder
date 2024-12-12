module About.View exposing (..)

import Html exposing (Html, div, text, h1, h4, span, p, label, i, a, button, table, thead, tbody, td, th, tr)
import Html.Attributes exposing (class, title, type_)
import Html.Events exposing (onClick)
import Json.Encode exposing (Value, list, object)

import About.DataTypes exposing (..)
import About.JsonEncoder exposing (..)


view : Model -> Html Msg
view model =
    case model.info of
        Nothing -> text (if model.ui.loading then "Loading" else "Error while fetching data")
        Just info ->
            div[ class "rudder-template"]
                [ div[ class "one-col w-100"]
                    [ div[ class "main-header"]
                        [ div[ class "header-title"]
                            [ h1[]
                                [ span[] [text "About"]
                                ]
                            , div[class "header-buttons"]
                                [ button [class "btn btn-primary", type_ "button", onClick (CopyJson (encodeAboutInfo info))]
                                    [ text "Copy all to clipboard"
                                    , i [class "ms-2 ion ion-clipboard"][]
                                    ]
                                ]
                            ]
                        , div [class "header-description"]
                            [ p[][text "This page contains useful information about the Rudder server and instance."]
                            ]
                        ]
                    , div[ class "one-col-main overflow-auto h-100"]
                        [ div [class "d-flex flex-column info-container p-3 pb-5 w-100 h-100 overflow-auto"]
                            [ div [class "col-12 col-lg-8"]
                                [ div[class "mb-3"]
                                    [ h4[]
                                        [ text "Rudder info"
                                        , btnCopyJson "rudder" (encodeRudderInfo info.rudderInfo)
                                        ]
                                    , div[]
                                        [ rowTxtInfo "Version" info.rudderInfo.version
                                        , rowTxtInfo "Build" info.rudderInfo.build
                                        , rowTxtInfo "Instance ID" info.rudderInfo.instanceId
                                        , relaysList info.rudderInfo.relays model.ui
                                        ]
                                    ]
                                , div[class "mb-3"]
                                    [ h4[]
                                        [ text "System"
                                        , btnCopyJson "system" (encodeSystemInfo info.system)
                                        ]
                                    , div[]
                                        [ rowTxtInfo "Operating system name" info.system.os.name
                                        , rowTxtInfo "Operating system version" info.system.os.version
                                        , rowTxtInfo "JVM version" info.system.jvm.version
                                        , rowTxtInfo "Launch options" info.system.jvm.cmd
                                        ]
                                    ]
                                , div[class "mb-3"]
                                    [ h4[]
                                        [ text "Managed nodes"
                                        , btnCopyJson "nodes" (encodeNodesInfo info.nodes)
                                        ]
                                    , div[]
                                        [ rowNbInfo "Nodes in audit mode" info.nodes.audit
                                        , rowNbInfo "Nodes in enforce mode" info.nodes.enforce
                                        , rowNbInfo "Enabled nodes" info.nodes.enabled
                                        , rowNbInfo "Enabled nodes" info.nodes.disabled
                                        , rowNbInfo "Total" info.nodes.total
                                        ]
                                    ]
                                , div[class "mb-3"]
                                    [ h4[]
                                        [ text "Plugins"
                                        , btnCopyJson "plugins" (list encodePluginInfo info.plugins)
                                        ]
                                    , div[]
                                        [ pluginsList info.plugins model.ui
                                        ]
                                    ]
                                ]
                            ]
                        ]
                    ]
                ]

rowTxtInfo : String -> String -> Html Msg
rowTxtInfo title val =
    div[class "mb-1"]
        [ div[class "info"]
            [ label[][text title]
            , span[][text val]
            , btnCopy val
            ]
        ]

rowNbInfo : String -> Int -> Html Msg
rowNbInfo title val =
    let
        str = String.fromInt val
    in
    div[class "mb-1"]
        [ div[class "info"]
            [ label[][text title]
            , span[class "ms-0 badge fs-6"][text str]
            , btnCopy str
            ]
        ]

btnCopy : String -> Html Msg
btnCopy value =
    a [ class "btn-goto always clipboard", title "Copy to clipboard" , onClick (Copy value) ]
        [ i [class "ion ion-clipboard"][]
        ]

btnCopyJson : String -> Value -> Html Msg
btnCopyJson key val =
    let
        obj = object
            [ ( key, val ) ]
    in
        a [ class "btn-goto always clipboard", title "Copy to clipboard" , onClick (CopyJson obj) ]
            [ i [class "ion ion-clipboard"][]
            ]

relaysList : List Relay -> UI -> Html Msg
relaysList relays ui =
    let
        showList = ui.showRelays
        relayRow : Relay -> Html Msg
        relayRow relay =
            tr[]
            [ td[][text relay.hostname]
            , td[][text relay.uuid]
            , td[][text (String.fromInt relay.managedNodes)]
            ]

        nbRelays = String.fromInt (List.length relays)
    in
        div[class "mb-1"]
            [ div[class "info"]
                ( if List.isEmpty relays then
                [ label[]
                    [ text "Relays"
                    ]
                , i [class "text-secondary"][text "There are no managed relays"]
                ]
                else
                    [ label[]
                        [ text "Relays"
                        ]
                    , span[class "ms-0 me-2 badge fs-6"][text nbRelays]
                    , button[class "btn btn-sm btn-default", onClick (UpdateUI {ui | showRelays = not showList})]
                        [ text ((if showList then "Hide" else "Show") ++ " list")
                        ]
                    , btnCopyJson "relays" (list encodeRelay relays)
                    ]
                )
            , ( if List.isEmpty relays then
                    text ""
                else
                    div[class (if showList then "d-flex " else "d-none")]
                        [ table[class "dataTable mt-1"]
                            [ thead[]
                                [ tr[class "head"]
                                    [ th[][text "Hostname"]
                                    , th[][text "ID"]
                                    , th[][text "Managed nodes"]
                                    ]
                                ]
                            , tbody[]
                                (relays |> List.sortBy .hostname |> List.map relayRow)
                            ]
                        ]
                )
            ]

pluginsList : List PluginInfo -> UI -> Html Msg
pluginsList plugins ui =
    let
        showList = ui.showPlugins
        pluginRow : PluginInfo -> Html Msg
        pluginRow plugin =
            tr[]
            [ td[][text plugin.id]
            , td[][text plugin.name]
            , td[][text plugin.version]
            , td[][text plugin.abiVersion]
            , td[][text plugin.license.licensee]
            , td[][text ("from " ++ plugin.license.startDate ++ " to " ++ plugin.license.endDate)]
            , td[][text (String.fromInt plugin.license.allowedNodesNumber)]
            , td[][text plugin.license.supportedVersions]
            ]

        nbPlugins = String.fromInt (List.length plugins)
    in
        div[class "mb-1"]
            [ div[class "info"]
                ( if List.isEmpty plugins then
                [ label[]
                    [ text "Relays"
                    ]
                , i [class "text-secondary"][text "There are no managed relays"]
                ]
                else
                    [ label[]
                        [ text "Relays"
                        ]
                    , span[class "ms-0 me-2 badge fs-6"][text nbPlugins]
                    , button[class "btn btn-sm btn-default", onClick (UpdateUI {ui | showPlugins = not showList})]
                        [ text ((if showList then "Hide" else "Show") ++ " list")
                        ]
                    , btnCopyJson "relays" (list encodePluginInfo plugins)
                    ]
                )
            , ( if List.isEmpty plugins then
                    text ""
                else
                    div[class (if showList then "d-flex " else "d-none")]
                        [ table[class "dataTable mt-1"]
                            [ thead[]
                                [ tr[class "head"]
                                    [ th[][text "ID"]
                                    , th[][text "Name"]
                                    , th[][text "Version"]
                                    , th[][text "ABI version"]
                                    , th[][text "Licensee"]
                                    , th[][text "Validity period"]
                                    , th[][text "Node limit"]
                                    , th[][text "Supported versions"]
                                    ]
                                ]
                            , tbody[]
                                (plugins |> List.sortBy .id |> List.map pluginRow)
                            ]
                        ]
                )
            ]

