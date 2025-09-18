module Plugins.Action exposing
    ( Action(..)
    , ActionDisallowedResult(..)
    , ActionModel(..)
    , PluginActionResult
    , PluginsAction(..)
    , PluginsActionExplanation(..)
    , actionText
    , allSortedActionDisallowedResult
    , explainDisallowedResult
    , findPluginsAction
    , initPluginsAction
    , installUpgradeCount
    , pluginAction
    , successPluginsFromExplanation
    )

import Dict exposing (Dict)
import List.Extra
import Maybe.Extra
import Ordering exposing (Ordering)
import Plugins.PluginData exposing (..)
import Set exposing (Set)
import String.Extra


{-| Summary of what an action was allowed to do, with optional disallowed with warning.
It can be a useful description at any level.
-}
type Action
    = ActionEnable
    | ActionDisable
    | ActionUninstall
    | ActionInstall
    | ActionUpgrade


{-| Result computation on install status, activation status, and license status of a plugin.
The license status cannot be changed, so it is just a limiting factor when associated with the others.
The name is long and specific enough but also descriptive (it is a sentence), so that we can generate
the message using the type.
-}
type ActionDisallowedResult
    = PluginAlreadyUninstalled -- --    (InstallStatus ⊗ InstallStatus)
    | PluginAlreadyEnabled -- --            (ActivationStatus ⊗ ActivationStatus)
    | PluginAlreadyDisabled
    | UninstalledPluginCannotBeEnabled
    | UninstalledPluginCannotBeDisabled -- --       (InstallStatus ⊗ (ActivationStatus \ InstallStatus))
    | IntegrationPluginCannotBeDisabled -- --       (PluginType ⊗ ActivationStatus)
    | InvalidLicensePreventPluginInstallation -- -- (LicenseStatus ⊗ InstallStatus)
    | InvalidLicensePreventPluginActivation -- --   (LicenseStatus ⊗ ActivationStatus)



-- We could add logic for when a plugin will restart, by defining results :
-- e.g. uninstalling a disabled plugin
-- We should just add such a result as a parameter to the associated result type below


type ActionModel
    = AllowedAction Action
    | DisallowedAction ActionDisallowedResult


{-| Action result for several plugins, merged and with exposed methods
for creating and updating actions when plugins are updated.
The model stores details that are handled internally as ActionModel.
Instead we expose aggregated values :

  - successCount is the filtered "AllowedAction" from details
  - isActionDisabled = successCount == 0

-}
type PluginsAction
    = PluginsAction PluginActionResult


type alias PluginActionResult =
    { successCount : Int
    , isActionDisabled : Bool
    , explanation : PluginsActionExplanation
    }


{-| Internally, it keeps all computed result for plugins, given an action
-}
type PluginsActionDetails
    = PluginsActionDetails (Dict PluginId ActionModel)


{-| We want to explain several cases, and we use several types,
we also don't bother modeling non-empty collections as the type already explains it all
(so, we are not modeling all edge cases of emptiness but only dispatching to the right case).

The case of "upgrade" is specific because it is merged with the "install", so we need to consider all case where
there could be upgrades, additionally to installs.

So, either :

  - we have no plugins/no triggered actions initally, so no exlanation
  - all plugins action are allowed, given an action context (outside of this data, but it can be enabled, etc., except installed)
  - all plugins actions are disallowed, plugins most likely have common reason to not be actionable, so we group them by error message
  - some plugins actions are disallowed, a merged case for the two case above
  - there are plugins install, and they are all allowed
  - there are plugins install, but there is also some warning
  - there are plugins upgrade, and they are all allowed
  - there are plugins upgrade, but there is also some warning
  - there are plugins install and upgrade, and they are all allowed
  - there are plugins install and upgrade, but there is also some warning

-}
type PluginsActionExplanation
    = PluginsActionNoExplanation
    | PluginsActionExplainSuccess (Set PluginId)
    | PluginsActionExplainErrors (List ( ActionDisallowedResult, Set PluginId ))
    | PluginsActionExplainSuccessWarning { success : Set PluginId, warning : List ( ActionDisallowedResult, Set PluginId ) }
    | PluginsActionExplainInstall (Set PluginId)
    | PluginsActionExplainInstallWarning { install : Set PluginId, warning : List ( ActionDisallowedResult, Set PluginId ) }
    | PluginsActionExplainUpgrade (Set PluginId)
    | PluginsActionExplainUpgradeWarning { upgrade : Set PluginId, warning : List ( ActionDisallowedResult, Set PluginId ) }
    | PluginsActionExplainInstallUpgrade { install : Set PluginId, upgrade : Set PluginId }
    | PluginsActionExplainInstallUpgradeWarning { install : Set PluginId, upgrade : Set PluginId, warning : List ( ActionDisallowedResult, Set PluginId ) }


initPluginsAction : PluginsAction
initPluginsAction =
    PluginsAction
        { successCount = 0
        , isActionDisabled = True
        , explanation = PluginsActionNoExplanation
        }


pluginAction : ( PluginId, ActionModel ) -> PluginsAction
pluginAction ( id, action ) =
    let
        ( count, isActionDisabled ) =
            case action of
                AllowedAction _ ->
                    ( 1, False )

                DisallowedAction _ ->
                    ( 0, True )

        details =
            PluginsActionDetails (Dict.fromList [ ( id, action ) ])
    in
    PluginsAction
        { successCount = count
        , isActionDisabled = isActionDisabled
        , explanation = explainPluginsAction details
        }


successPluginsFromExplanation : PluginsActionExplanation -> Set PluginId
successPluginsFromExplanation x =
    case x of
        PluginsActionNoExplanation ->
            Set.empty

        PluginsActionExplainSuccess success ->
            success

        PluginsActionExplainErrors _ ->
            Set.empty

        PluginsActionExplainSuccessWarning { success } ->
            success

        PluginsActionExplainInstall success ->
            success

        PluginsActionExplainInstallWarning { install } ->
            install

        PluginsActionExplainUpgrade success ->
            success

        PluginsActionExplainUpgradeWarning { upgrade } ->
            upgrade

        PluginsActionExplainInstallUpgrade { install, upgrade } ->
            Set.union install upgrade

        PluginsActionExplainInstallUpgradeWarning { install, upgrade } ->
            Set.union install upgrade


{-| Is this input action an install ? An upgrade ? Both ?
And how many of each ?
-}
installUpgradeCount : PluginsActionExplanation -> { install : Maybe Int, upgrade : Maybe Int }
installUpgradeCount x =
    let
        ( i, u ) =
            case x of
                PluginsActionExplainInstall success ->
                    ( Set.size success, 0 )

                PluginsActionExplainInstallWarning { install } ->
                    ( Set.size install, 0 )

                PluginsActionExplainUpgrade success ->
                    ( 0, Set.size success )

                PluginsActionExplainUpgradeWarning { upgrade } ->
                    ( 0, Set.size upgrade )

                PluginsActionExplainInstallUpgrade { install, upgrade } ->
                    ( Set.size install, Set.size upgrade )

                PluginsActionExplainInstallUpgradeWarning { install, upgrade } ->
                    ( Set.size install, Set.size upgrade )

                _ ->
                    ( 0, 0 )
    in
    { install = Just i |> Maybe.Extra.filter (\v -> v > 0)
    , upgrade = Just u |> Maybe.Extra.filter (\v -> v > 0)
    }


{-| Aggregation state computation, keeping the details state and returning it.
We do the aggregation on the new details, we do not bother deducing fields value from previous one.
-}
updatePluginAction : ( PluginId, ActionModel ) -> ( PluginsActionDetails, PluginsAction ) -> ( PluginsActionDetails, PluginsAction )
updatePluginAction ( id, action ) ( PluginsActionDetails details, value ) =
    let
        ((PluginsActionDetails d) as updatedDetails) =
            PluginsActionDetails (Dict.insert id action details)

        newCount =
            Dict.foldl
                (\_ v n ->
                    case v of
                        AllowedAction _ ->
                            n + 1

                        DisallowedAction _ ->
                            n
                )
                0
                d

        newDisabled =
            newCount <= 0
    in
    ( updatedDetails
    , PluginsAction
        { successCount = newCount
        , isActionDisabled = newDisabled
        , explanation = explainPluginsAction updatedDetails
        }
    )


initPluginDetails : PluginsActionDetails
initPluginDetails =
    PluginsActionDetails Dict.empty


{-| Sorted lists of disallowed reasons, from least to most important
-}
allSortedActionDisallowedResult : List ActionDisallowedResult
allSortedActionDisallowedResult =
    [ PluginAlreadyUninstalled
    , PluginAlreadyEnabled
    , PluginAlreadyDisabled
    , UninstalledPluginCannotBeEnabled
    , UninstalledPluginCannotBeDisabled
    , IntegrationPluginCannotBeDisabled
    , InvalidLicensePreventPluginInstallation
    , InvalidLicensePreventPluginActivation
    ]


actionDisallowedResultOrdering : Ordering ActionDisallowedResult
actionDisallowedResultOrdering =
    Ordering.reverse <|
        Ordering.explicit allSortedActionDisallowedResult


explainPluginsAction : PluginsActionDetails -> PluginsActionExplanation
explainPluginsAction (PluginsActionDetails details) =
    let
        ordering =
            Ordering.byFieldWith actionDisallowedResultOrdering Tuple.first

        ( ( success, install, upgrade ), warning ) =
            details
                |> Dict.toList
                -- first partition by success to isolate warnings
                |> List.partition
                    (\( _, v ) ->
                        case v of
                            AllowedAction _ ->
                                True

                            DisallowedAction _ ->
                                False
                    )
                |> Tuple.mapBoth
                    (\allowed ->
                        allowed
                            |> List.foldl
                                (\( p, a ) ->
                                    \( s, i, u ) ->
                                        case a of
                                            AllowedAction ActionInstall ->
                                                ( s, p :: i, u )

                                            AllowedAction ActionUpgrade ->
                                                ( s, i, p :: u )

                                            _ ->
                                                ( p :: s, i, u )
                                )
                                ( [], [], [] )
                    )
                    (\disallowed ->
                        disallowed
                            |> List.filterMap
                                (\( p, a ) ->
                                    case a of
                                        DisallowedAction reason ->
                                            Just ( reason, p )

                                        AllowedAction _ ->
                                            Nothing
                                )
                            |> List.sortWith ordering
                            |> List.Extra.groupWhile (\a -> \b -> ordering a b == EQ)
                            |> List.map (\( ( reason, plugin ), plugins ) -> ( reason, (plugin :: List.map Tuple.second plugins) |> Set.fromList ))
                    )

        nonEmptySet l =
            if List.isEmpty l then
                Nothing

            else
                Just (Set.fromList l)

        nonEmptyList l =
            if List.isEmpty l then
                Nothing

            else
                Just l
    in
    case ( nonEmptySet success, ( nonEmptySet install, nonEmptySet upgrade ), nonEmptyList warning ) of
        ( Nothing, ( Nothing, Nothing ), Nothing ) ->
            PluginsActionNoExplanation

        ( Just s, ( Nothing, Nothing ), Nothing ) ->
            PluginsActionExplainSuccess s

        ( Just s, ( Nothing, Nothing ), Just _ ) ->
            PluginsActionExplainSuccessWarning { success = s, warning = warning }

        ( _, ( Just s, Nothing ), Nothing ) ->
            PluginsActionExplainInstall s

        ( _, ( Just s, Nothing ), Just _ ) ->
            PluginsActionExplainInstallWarning { install = s, warning = warning }

        ( _, ( Nothing, Just upgr ), Nothing ) ->
            PluginsActionExplainUpgrade upgr

        ( _, ( Nothing, Just upgr ), Just _ ) ->
            PluginsActionExplainUpgradeWarning { upgrade = upgr, warning = warning }

        ( _, ( Just inst, Just upgr ), Nothing ) ->
            PluginsActionExplainInstallUpgrade { install = inst, upgrade = upgr }

        ( _, ( Just inst, Just upgr ), Just _ ) ->
            PluginsActionExplainInstallUpgradeWarning { install = inst, upgrade = upgr, warning = warning }

        ( Nothing, ( Nothing, Nothing ), Just errors ) ->
            PluginsActionExplainErrors errors


{-| Main exposed utility method to get all plugins actions from a given action on plugins
-}
findPluginsAction : Action -> Dict PluginId Plugin -> PluginsAction
findPluginsAction action plugins =
    plugins
        |> Dict.map (\_ -> getActionResult action)
        |> Dict.toList
        |> List.foldl updatePluginAction ( initPluginDetails, initPluginsAction )
        |> Tuple.second


{-| The main business logic
-}
getActionResult : Action -> Plugin -> ActionModel
getActionResult action { installStatus, pluginType, licenseStatus } =
    case ( action, ( installStatus, pluginType, licenseStatus ) ) of
        ( ActionInstall, ( _, _, InvalidLicense _ ) ) ->
            DisallowedAction InvalidLicensePreventPluginInstallation

        ( ActionEnable, ( _, _, InvalidLicense _ ) ) ->
            DisallowedAction InvalidLicensePreventPluginActivation

        ( ActionDisable, ( Installed Enabled, Integration, _ ) ) ->
            DisallowedAction IntegrationPluginCannotBeDisabled

        ( ActionUninstall, ( Uninstalled, _, _ ) ) ->
            DisallowedAction PluginAlreadyUninstalled

        ( ActionEnable, ( Installed Enabled, _, _ ) ) ->
            DisallowedAction PluginAlreadyEnabled

        ( ActionEnable, ( Uninstalled, _, _ ) ) ->
            DisallowedAction UninstalledPluginCannotBeEnabled

        ( ActionDisable, ( Uninstalled, _, _ ) ) ->
            DisallowedAction UninstalledPluginCannotBeDisabled

        ( ActionInstall, ( Installed _, _, _ ) ) ->
            AllowedAction ActionUpgrade

        ( ActionDisable, ( Installed Enabled, _, _ ) ) ->
            AllowedAction ActionDisable

        ( ActionDisable, ( Installed Disabled, _, _ ) ) ->
            DisallowedAction PluginAlreadyDisabled

        _ ->
            AllowedAction action


actionText : Action -> String
actionText action =
    case action of
        ActionInstall ->
            "Install"

        ActionUpgrade ->
            "Upgrade"

        ActionDisable ->
            "Disable"

        ActionEnable ->
            "Enable"

        ActionUninstall ->
            "Uninstall"


explainDisallowedResult : ActionDisallowedResult -> String
explainDisallowedResult arg =
    String.Extra.humanize <|
        case arg of
            PluginAlreadyUninstalled ->
                "PluginAlreadyUninstalled"

            PluginAlreadyEnabled ->
                "PluginAlreadyEnabled"

            PluginAlreadyDisabled ->
                "PluginAlreadyDisabled"

            UninstalledPluginCannotBeEnabled ->
                "UninstalledPluginCannotBeEnabled"

            UninstalledPluginCannotBeDisabled ->
                "UninstalledPluginCannotBeDisabled"

            IntegrationPluginCannotBeDisabled ->
                "IntegrationPluginCannotBeDisabled"

            InvalidLicensePreventPluginActivation ->
                "InvalidLicensePreventPluginActivation"

            InvalidLicensePreventPluginInstallation ->
                "InvalidLicensePreventPluginInstallation"
