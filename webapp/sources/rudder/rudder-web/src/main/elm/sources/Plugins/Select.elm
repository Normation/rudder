module Plugins.Select exposing
    ( Select(..)
    , Selected
    , Selection(..)
    , ViewModel
    , getSelection
    , isAllSelected
    , noSelected
    , select
    , selectedCount
    , selectedSet
    , setNotSelectablePlugins
    )

import Dict exposing (Dict)
import Dict.Extra
import Plugins.Action exposing (Action(..), PluginsAction, findPluginsAction)
import Plugins.PluginData exposing (..)
import Set exposing (Set)


{-| The generic record type with interesting fields for selection and plugin actions
-}
type alias ViewModel a =
    { a
        | plugins : Dict PluginId Plugin
        , selected : Selected
        , installAction : PluginsAction
        , enableAction : PluginsAction
        , disableAction : PluginsAction
        , uninstallAction : PluginsAction
    }


type Select
    = SelectOne PluginId
    | UnselectOne PluginId
    | SelectAll (Set PluginId)
    | UnselectAll


{-| Opaque type with invariants defined in its manipulation
(plugins have a unique selection which is only selectable)
-}
type Selected
    = Selected (Dict PluginId Selection)


{-| Local type for implementation for selected element
We store only a Selection and non-selectable elements,
non-selected ones are not in the dict
-}
type Selection
    = Selection Bool
    | NotSelectable


{-| Main exposed setter, which enforces upon selection that actions are all updated
-}
select : Select -> Dict PluginId Plugin -> ViewModel a -> ViewModel a
select sel allPlugins model =
    model
        |> updateSelected (toSelected sel)
        |> updateInstallAction allPlugins
        |> updateEnableAction allPlugins
        |> updateDisableAction allPlugins
        |> updateUninstallAction allPlugins


{-| For initial selection : set plugins selection according to their selectability.
We enforce that some plugins cannot be selected, depending on their attributes
-}
setNotSelectablePlugins : Dict PluginId Plugin -> ViewModel a -> ViewModel a
setNotSelectablePlugins plugins model =
    model
        |> updateSelected (\_ -> findNotSelectablePlugins plugins)
        |> updateInstallAction plugins
        |> updateEnableAction plugins
        |> updateDisableAction plugins
        |> updateUninstallAction plugins


{-| For initialization, when there isn't anything yet to even setup initial selection with
-}
noSelected : Selected
noSelected =
    Selected Dict.empty


toSelected : Select -> Selected -> Selected
toSelected s =
    case s of
        SelectOne id ->
            transformSelected <| Dict.insert id (Selection True)

        UnselectOne id ->
            transformSelected <| Dict.remove id

        SelectAll plugins ->
            transformSelected <| \_ -> plugins |> Set.toList |> List.map (\p -> ( p, Selection True )) |> Dict.fromList

        UnselectAll ->
            transformSelected <| \_ -> Dict.empty


updateSelected : (Selected -> Selected) -> ViewModel a -> ViewModel a
updateSelected f ({ selected } as model) =
    { model
        | selected = f selected
    }


onlySelectedPlugins : Selected -> Dict PluginId a -> Dict PluginId a
onlySelectedPlugins sel plugins =
    plugins |> Dict.Extra.keepOnly (selectedSet sel)


updateInstallAction : Dict PluginId Plugin -> ViewModel a -> ViewModel a
updateInstallAction plugins ({ selected } as model) =
    { model
        | installAction = findPluginsAction ActionInstall (onlySelectedPlugins selected plugins)
    }


updateEnableAction : Dict PluginId Plugin -> ViewModel a -> ViewModel a
updateEnableAction plugins ({ selected } as model) =
    { model
        | enableAction = findPluginsAction ActionEnable (onlySelectedPlugins selected plugins)
    }


updateDisableAction : Dict PluginId Plugin -> ViewModel a -> ViewModel a
updateDisableAction plugins ({ selected } as model) =
    { model
        | disableAction = findPluginsAction ActionDisable (onlySelectedPlugins selected plugins)
    }


updateUninstallAction : Dict PluginId Plugin -> ViewModel a -> ViewModel a
updateUninstallAction plugins ({ selected } as model) =
    { model
        | uninstallAction = findPluginsAction ActionUninstall (onlySelectedPlugins selected plugins)
    }


nonSelectable : Selected -> Dict PluginId Selection
nonSelectable (Selected sel) =
    sel |> Dict.filter (\_ -> \v -> v == NotSelectable)


{-| Local helper function, guarantees that we cannot select non-selectable
-}
transformSelected : (Dict PluginId Selection -> Dict PluginId Selection) -> Selected -> Selected
transformSelected f ((Selected d) as sel) =
    Selected (Dict.union (nonSelectable sel) (f d))


findNotSelectablePlugins : Dict PluginId Plugin -> Selected
findNotSelectablePlugins plugins =
    let
        notSelectable { installStatus, licenseStatus } =
            case ( installStatus, licenseStatus ) of
                ( Uninstalled, InvalidLicense _ ) ->
                    Just NotSelectable

                _ ->
                    Nothing
    in
    plugins
        |> Dict.Extra.filterMap (\_ -> notSelectable)
        |> Selected


isAllSelected : Selected -> Set PluginId -> Bool
isAllSelected (Selected s) plugins =
    Set.diff plugins (s |> Dict.keys |> Set.fromList)
        |> Set.isEmpty


getSelection : PluginId -> Selected -> Selection
getSelection id (Selected sel) =
    Dict.get id sel |> Maybe.withDefault (Selection False)


selectedCount : Selected -> Int
selectedCount (Selected sel) =
    Dict.foldl
        (\_ ->
            \s ->
                \acc ->
                    case s of
                        Selection True ->
                            acc + 1

                        _ ->
                            acc
        )
        0
        sel


selectedSet : Selected -> Set PluginId
selectedSet (Selected s) =
    Dict.foldl
        (\key selection keySet ->
            case selection of
                Selection True ->
                    Set.insert key keySet

                _ ->
                    keySet
        )
        Set.empty
        s
