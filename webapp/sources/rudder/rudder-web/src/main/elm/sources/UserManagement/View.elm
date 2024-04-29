module UserManagement.View exposing (..)

import UserManagement.ApiCalls exposing (deleteUser)
import UserManagement.DataTypes exposing (Model, Msg(..), PanelMode(..), ProviderInfo, RoleListOverride(..), Roles, StateInput(..), User, UserForm, UserStatus(..), Users, filterUserProviderByRoleListOverride, filterUserProviderEnablingRoles, providerCanEditRoles, takeFirstExtProvider, takeFirstOverrideProviderInfo, userFormToNewUser)
import Dict exposing (keys)
import Html exposing (..)
import Html.Attributes exposing (attribute, class, disabled, for, href, id, placeholder, required, style, tabindex, type_, value)
import Html.Events exposing (onClick, onInput)
import List
import List.Extra
import Set
import String exposing (isEmpty)


view : Model -> Html Msg
view model =
    let
        content =
            if isEmpty model.digest || List.isEmpty (keys model.users) then
                text "Waiting for data from server..."

            else
                displayUsersConf model model.users

        deleteModal =
            if model.ui.openDeleteModal then
                showDeleteModal model.userForm.login

            else
                div [] []
    in
    div []
        [ deleteModal
        , content
        ]


showDeleteModal : String -> Html Msg
showDeleteModal username =
    div [ tabindex -1, class "modal fade show", style "z-index" "1050", style "display" "block" ]
        [ div [ class "modal-backdrop fade show", onClick CloseDeleteModal ] []
        , div [ class "modal-dialog" ]
            [ div [ class "modal-content" ]
                [ div [ class "modal-header" ]
                    [ h5 [ class "modal-title" ] [ text "Delete a user" ]
                    , button [ type_ "button", class "btn-close", attribute "data-bs-dismiss" "modal", attribute "aria-label" "Close", onClick CloseDeleteModal ] []
                    ]
                , div [ class "modal-body" ]
                    [ div [ class "row" ]
                        [ h4 [ class "col-lg-12 col-sm-12 col-xs-12 text-center" ]
                            [ text "Are you sure you want to delete user "
                            , b [] [ text username ]
                            , text " from the users file ?"
                            , br [] []
                            , text "They will be purged from the database after a certain amount of time as defined by the Rudder configuration."
                            ]
                        ]
                    ]
                , div [ class "modal-footer" ]
                    [ button [ class "btn btn-default", onClick CloseDeleteModal ] [ text "Cancel" ]
                    , button [ class "btn btn-danger", onClick (CallApi (deleteUser username)) ] [ text "Delete" ]
                    ]
                ]
            ]
        ]


hashPasswordMenu : Bool -> Html Msg
hashPasswordMenu isHashedPasswd =
    let
        hashPasswdIsActivate =
            if isHashedPasswd == True then
                "active "

            else
                ""

        clearPasswdIsActivate =
            if isHashedPasswd == False then
                "active "

            else
                ""
    in
    div [ class "btn-group", attribute "role" "group" ]
        [ a [ class ("btn btn-default " ++ hashPasswdIsActivate), onClick (PreHashedPasswd True) ] [ text "Enter pre-hashed value" ]
        , a [ class ("btn btn-default " ++ clearPasswdIsActivate), onClick (PreHashedPasswd False) ] [ text "Password to hash" ]
        ]


displayPasswordBlock : Model -> Maybe User -> Html Msg
displayPasswordBlock model user =
    let
        mainProvider =
            case user of
                Just u ->
                    case takeFirstExtProvider u.providers of
                        Just p ->
                            p

                        Nothing ->
                            "unknown_provider"

                Nothing ->
                    "unknown_provider"

        classDeactivatePasswdInput =
            if isPasswordMandatory then
                ""

            else
                " deactivate-auth-backend  ask-passwd-deactivate "

        isPasswordMandatory =
            case user of
                Nothing ->
                    True

                Just u ->
                    -- at least one provider is external
                    case takeFirstExtProvider u.providers of
                        Nothing ->
                            True

                        Just _ ->
                            False

        isHidden =
            if model.userForm.isHashedPasswd then
                "text"

            else
                "password"

        phMsg =
            if model.userForm.isHashedPasswd then
                "New hashed password"

            else
                "This password will be hashed and then stored"

        passwdRequiredValidation =
            if isPasswordMandatory then
                case model.ui.panelMode of
                    AddMode ->
                        if model.userForm.isValidInput == InvalidUsername then
                            "invalid-form-field"

                        else
                            ""

                    _ ->
                        ""

            else
                ""

        passwordInput =
            [ h3 [] [ text "Password" ]
            , hashPasswordMenu model.userForm.isHashedPasswd
            , input [ class ("form-control anim-show  " ++ classDeactivatePasswdInput ++ " " ++ passwdRequiredValidation), type_ isHidden, placeholder phMsg, onInput Password, attribute "autocomplete" "new-password", value model.userForm.password ] []
            , hashType
            ]

        hashType =
            if model.userForm.isHashedPasswd then
                div [ class "hash-block" ] [ i [ class "fa fa-key hash-icon" ] [], div [ class "hash-type" ] [ text model.digest ] ]

            else
                div [] []
    in
    if not isPasswordMandatory then
        div [ class "msg-providers" ]
            [ i [ class "fa fa-exclamation-circle info-passwd-icon" ] []
            , text "Since the authentication method used an external provider, no password will be asked for this user."
            , br [] []
            , text "If you want to add a password anyway as fallback, "
            , a [ class "click-here", onClick AddPasswdAnyway ] [ text "Click here" ]
            , if model.userForm.userForcePasswdInput then
                div [ class "force-password" ]
                    (div [ class "info-force-passwd" ]
                        [ text "The password value won't be used with the current default authentication provider "
                        , b [ style "color" "#2557D6" ] [ text mainProvider ]
                        , text ", you should let it blank."
                        ]
                        :: passwordInput
                    )

              else
                div [] []
            ]

    else
        div [] passwordInput


displayRightPanelAddUser : Model -> Html Msg
displayRightPanelAddUser model =
    let
        emptyUsername =
            if model.userForm.isValidInput == InvalidUsername then
                "invalid-form-field"

            else
                ""
    in
    div [ class "panel-wrap" ]
        [ div [ class "panel" ]
            [ button [ class "btn btn-sm btn-outline-secondary", onClick DeactivatePanel ] [ text "Close" ]
            , div [ class "card-header" ] [ h2 [ class "title-username" ] [ text "Create User" ] ]
            , div []
                [ input [ class ("form-control username-input " ++ emptyUsername), type_ "text", placeholder "Username", onInput Login, value model.userForm.login, required True ] []
                , displayPasswordBlock model Nothing
                , displayUserInfo model.userForm False
                , div [ class "btn-container" ]
                    [ button
                        [ class "btn btn-sm btn-success btn-save"
                        , type_ "button"
                        , onClick (SubmitNewUser (userFormToNewUser model.userForm))
                        ]
                        [ i [ class "fa fa-download" ] [] ]
                    ]
                ]
            ]
        ]


displayRoleListOverrideWarning : RoleListOverride -> Html Msg
displayRoleListOverrideWarning rlo =
    case rlo of
        None ->
            div [ class "msg-providers" ]
                [ text " The current main provider for this user does not enable roles. The file roles can be changed but will only be used as fallback for when the provider is no longer enabled or configured for roles."
                ]

        Extend ->
            div [ class "msg-providers" ]
                [ i [ class "fa fa-exclamation-triangle warning-icon" ] []
                , span [] [ text " Be careful! Displayed user roles originate from the provider which extends roles defined in the Rudder static configuration file" ]
                ]

        Override ->
            div [ class "msg-providers" ]
                [ text " Displayed user roles originate from the provider, which is configured to override roles in the Rudder static configuration file. This configuration option can be changed so that the provider extends those roles instead of overriding them."
                , br [] []
                , text "Changing the roles will create fallback roles in file when the provider is no longer enabled or configured for roles."
                ]


getDiffRoles : Roles -> List String -> List String
getDiffRoles total sample =
    let
        t =
            keys total
    in
    List.filter (\r -> List.all (\r2 -> r /= r2) sample) t


displayDropdownRoleList : Model -> User -> Bool -> Html Msg
displayDropdownRoleList model user readonly =
    let
        availableRoles =
            getDiffRoles model.roles (user.roles ++ model.userForm.rolesToAddOnSave)

        tokens =
            List.map (\r -> a [ href "#", onClick (AddRole r) ] [ text r ]) availableRoles

        addBtn =
            if List.isEmpty availableRoles then
                button [ id "addBtn-disabled", class "addBtn", disabled True ] [ i [ class "fa fa-plus" ] [] ]

            else
                button [ class "addBtn" ] [ i [ class "fa fa-plus" ] [] ]
    in
    if not readonly then
        div [ class "dropdown" ]
            [ addBtn
            , div [ class "dropdown-content" ] tokens
            ]

    else
        text ""


displayCoverageRoles : User -> Html Msg
displayCoverageRoles user =
    let
        inferredRoles =
            Set.diff (Set.fromList user.rolesCoverage) (Set.fromList user.roles)
    in
    if Set.isEmpty inferredRoles then
        text ""

    else
        div []
            [ h4 [ class "role-title" ] [ text "Inferred roles" ]
            , div [ class "callout-fade callout-info" ]
                [ div [ class "marker" ] [ span [ class "glyphicon glyphicon-info-sign" ] [] ]
                , text "Roles that are also included with the authorizations of this user. You can add them explicitly to user roles, and user authorizations will remain the same."
                ]
            , div [ class "role-management-wrapper" ]
                [ div [ id "input-role" ] (List.map (\x -> span [ class "auth" ] [ text x ]) (Set.toList inferredRoles))
                ]
            ]


displayAuthorizations : User -> Html Msg
displayAuthorizations user =
    let
        userAuthz =
            List.map (\x -> span [ class "auth" ] [ text x ]) user.authz
    in
    if List.isEmpty user.authz then
        text ""

    else
        div [ class "row-foldable row-folded" ]
            [ h4 [ class "role-title" ] [ text "Authorizations" ]
            , text "Current rights of the user :"
            , div [ class "role-management-wrapper" ]
                [ div [ id "input-role" ] userAuthz
                ]
            ]



-- name, email, other info as list of key-value pairs


displayUserInfo : UserForm -> Bool -> Html Msg
displayUserInfo userForm allowSaveInfo =
    div [ class "user-info" ]
        ([ h3 [] [ text "User information" ]
         , div [ class "user-info-row" ]
            [ label [ for "user-name" ] [ text "Name" ]
            , input [ id "user-name", class "form-control user-info-value", placeholder "User name", value userForm.userInfoForm.name, onInput UserInfoName ] []
            ]
         , div [ class "user-info-row" ]
            [ label [ for "user-email" ] [ text "Email" ]
            , input [ id "user-email", class "form-control user-info-value", placeholder "User email", value userForm.userInfoForm.email, onInput UserInfoEmail ] []
            ]
         ]
            ++ List.map
                (\( k, v ) ->
                    div [ class "user-info-row user-info-other" ]
                        [ label [ for k ] [ text k ]
                        , div [ class "user-info-row-edit" ]
                            [ input [ id k, class "form-control user-info-value", placeholder ("Enter value for field '" ++ k ++ "'"), onInput (ModifyUserInfoField k), value v ] []
                            , button [ class "btn btn-default", onClick (RemoveUserInfoField k) ] [ i [ class "fa fa-trash" ] [] ]
                            ]
                        ]
                )
                (Dict.toList userForm.userInfoForm.otherInfo)
            ++ List.indexedMap
                (\idx ( k, v ) ->
                    div [ class "user-info-row user-info-other" ]
                        [ text "New field"
                        , div [ class "user-info-row-edit" ]
                            [ input [ class "form-control user-info-label", placeholder "New field", onInput (\s -> NewUserInfoFieldKey s idx), value k ] []
                            , input [ class "form-control user-info-value", placeholder "New value", onInput (\s -> NewUserInfoFieldValue s idx), value v ] []
                            , button [ class "btn btn-default", onClick (RemoveNewUserInfoField idx) ] [ i [ class "fa fa-trash" ] [] ]
                            ]
                        ]
                )
                userForm.newUserInfoFields
            ++ (div [ class "user-info-row" ]
                    [ div [ class "user-info-add" ]
                        [ button [ class "addBtn", onClick AddUserInfoField ] [ i [ class "fa fa-plus" ] [] ]
                        ]
                    ]
                    :: (if allowSaveInfo then
                            [ div [ class "btn-container" ]
                                [ button
                                    [ class "btn btn-sm btn-success btn-save"
                                    , type_ "button"
                                    , onClick SubmitUserInfo
                                    ]
                                    [ i [ class "fa fa-download" ] [] ]
                                ]
                            ]

                        else
                            []
                       )
               )
        )


displayRightPanel : Model -> User -> Html Msg
displayRightPanel model user =
    let
        -- Additional RO information
        rolesAuthzInformationSection =
            [ hr [] []
            , displayCoverageRoles user
            , displayAuthorizations user
            ]

        rolesSection : ProviderInfo -> Maybe RoleListOverride -> Bool -> Bool -> Bool -> List (Html Msg)
        rolesSection provider override readonlyAddRoles readonlyRemoveRoles hideAddedRoles =
            [ h4 [ class "role-title" ] [ text ("Roles (from " ++ provider.provider ++ ")") ]
            , Maybe.withDefault (text "") (Maybe.map displayRoleListOverrideWarning override)
            , div [ class "role-management-wrapper" ]
                [ div [ id "input-role" ] (displayAddRole model user provider readonlyAddRoles hideAddedRoles)
                , displayDropdownRoleList model user readonlyRemoveRoles
                ]
            ]

        displayedProviders =
            let
                rlo =
                    if takeFirstOverrideProviderInfo model user == Nothing then
                        Extend

                    else
                        Override

                externalProvidersWithEnabledRoles =
                    filterUserProviderEnablingRoles model user

                externalProvidersWithoutRoles =
                    filterUserProviderByRoleListOverride None model user

                fileProviderRlo =
                    externalProvidersWithoutRoles |> List.head |> Maybe.map (\_ -> None)
            in
            case Dict.get "file" user.providersInfo of
                Nothing ->
                    -- file is not an explicit provider so roles can be added in the provider
                    List.Extra.intercalate [ hr [] [] ]
                        (List.map (\p -> rolesSection p (Just rlo) True False False) externalProvidersWithEnabledRoles
                            -- if there is a provider with None roles extension, we also need a section to put roles in file
                            |> List.append (List.map (\p -> rolesSection { p | provider = "file" } (Just None) False False False) externalProvidersWithoutRoles |> List.take 1)
                        )
                        ++ formSubmitSection "file"
                        ++ [ hr [] [] ]

                Just providerInfo ->
                    -- separate sections : extending provider are readonly, file provider are editable
                    List.Extra.intercalate [ hr [] [] ] (List.map (\p -> rolesSection p (Just rlo) True True True) externalProvidersWithEnabledRoles)
                        ++ -- and one section for the actually editable file provider
                           rolesSection providerInfo fileProviderRlo False False False
                        ++ formSubmitSection providerInfo.provider
                        ++ [ hr [] [] ]

        formSubmitSection provider =
            [ div [ class "btn-container" ]
                [ button [ class "btn btn-sm btn-danger btn-delete", onClick (OpenDeleteModal user.login) ] [ text "Delete" ]
                , button
                    [ class
                        ("btn btn-sm btn-status-toggle "
                            ++ (if user.status == Active then
                                    "btn-default"

                                else
                                    "btn-primary"
                               )
                        )
                    , onClick
                        (if user.status == Active then
                            DisableUser user.login

                         else
                            ActivateUser user.login
                        )
                    ]
                    [ if user.status == Active then
                        text "Disable"

                      else
                        text "Activate"
                    ]
                , button
                    [ class "btn btn-sm btn-success btn-save"
                    , type_ "button"
                    , if providerCanEditRoles model provider then
                        disabled False

                      else
                        disabled True
                    , onClick
                        (SubmitUpdateUser
                            { login = user.login
                            , password = model.userForm.password
                            , permissions = user.authz ++ model.userForm.rolesToAddOnSave ++ (List.filterMap (\p -> Dict.get p user.providersInfo |> Maybe.map .roles) [ provider ] |> List.concat)
                            , isPreHashed = model.userForm.isHashedPasswd
                            }
                        )
                    ]
                    [ i [ class "fa fa-download" ] [] ]
                ]
            ]
    in
    div [ class "panel-wrap" ]
        [ div [ class "panel" ]
            ([ div [ class "panel-header" ]
                [ h2 [ class "title-username" ] (text user.login :: List.map (\x -> span [ class "providers" ] [ text x ]) user.providers)
                , button [ class "btn btn-sm btn-outline-secondary", onClick DeactivatePanel ] [ text "Close" ]
                , div [ class "user-last-login" ]
                    [ case user.lastLogin of
                        Nothing ->
                            em [] [ text "Never logged in" ]

                        Just l ->
                            em [] [ text ("Last login: " ++ String.replace "T" " " l) ]
                    ]
                ]
             , displayPasswordBlock model (Just user)
             ]
                ++ displayedProviders
                ++ (displayUserInfo model.userForm True :: rolesAuthzInformationSection)
            )
        ]


displayUsersConf : Model -> Users -> Html Msg
displayUsersConf model u =
    let
        users =
            Dict.values u |> List.filter (\user -> user.status /= Deleted) |> List.map (\user -> displayUser user)

        newUserMenu =
            if model.ui.panelMode == AddMode then
                displayRightPanelAddUser model

            else
                text ""

        panel =
            case model.ui.panelMode of
                EditMode user ->
                    displayRightPanel model user

                _ ->
                    text ""

        hasExternal =
            takeFirstExtProvider model.providers

        lstOfExtProviders =
            case hasExternal of
                Nothing ->
                    text ""

                Just _ ->
                    div [ class "provider-list" ] (List.map (\s -> span [ class "providers" ] [ text s ]) model.providers)

        msgProvider =
            case hasExternal of
                Nothing ->
                    "Default authentication method is used"

                Just _ ->
                    "Authentication providers priority: "
    in
    div [ class "row" ]
        [ div [ class "header-plugin" ]
            [ div [ id "header-flex" ]
                [ div []
                    [ h2 [] [ text "User management" ]
                    , div [ class "description-plugin" ]
                        [ p [] [ text "This page shows you the current Rudder users and their rights." ]
                        ]
                    , button [ class "btn btn-sm btn-success new-icon btn-add", onClick ActivePanelAddUser ] [ text "Create" ]
                    , button [ class "btn btn-box-tool btn-blue btn-sm btn-reload", onClick SendReload ]
                        [ text "Reload"
                        , span [ id "reloadBtn", class "fa fa-refresh" ] []
                        ]
                    ]
                , div [ class "callout-fade callout-info" ]
                    [ div [ class "marker" ] [ span [ class "glyphicon glyphicon-info-sign" ] [] ]
                    , text msgProvider
                    , lstOfExtProviders
                    ]
                ]
            , newUserMenu
            , panel
            ]
        , div [ class "col-xs-12" ] [ div [ class "user-list" ] users ]
        ]


providerToIcon : String -> String
providerToIcon provider =
    case provider of
        "file" ->
            "fa-file"

        _ ->
            "fa-link"


displayUser : User -> Html Msg
displayUser user =
    if user.status == Deleted then
        text ""

    else
        div [ class "user-card-wrapper", onClick (ActivePanelSettings user) ]
            [ div [ class "user-card" ]
                [ div [ class "user-card-inner" ]
                    [ div [ class "user-card-providers" ] (List.map (\p -> span [ class ("fa " ++ providerToIcon p) ] []) user.providers)
                    , h3 [ id "name" ] [ text user.login ]
                    ]
                , displayRoles user
                , displayUserLastLogin user
                ]
            ]


displayAddRole : Model -> User -> ProviderInfo -> Bool -> Bool -> List (Html Msg)
displayAddRole model user providerInfo readonly hideAddedRoles =
    let
        newAddedRole =
            if hideAddedRoles then
                []

            else
                List.map
                    (\x ->
                        span [ class "auth-added" ] [ text x ]
                    )
                    model.userForm.rolesToAddOnSave

        userRoles =
            List.map
                (\x ->
                    span [ class "auth" ]
                        (text x
                            :: (if not readonly then
                                    [ div
                                        ([ id "remove-role", class "fa fa-times" ]
                                            ++ (if not readonly then
                                                    [ onClick (RemoveRole user providerInfo.provider x) ]

                                                else
                                                    []
                                               )
                                        )
                                        []
                                    ]

                                else
                                    []
                               )
                        )
                )
                providerInfo.roles
    in
    userRoles ++ newAddedRole


displayRoles : User -> Html Msg
displayRoles user =
    let
        userRoles =
            List.map
                (\x ->
                    span [ class "auth" ] [ text x ]
                )
                user.roles
    in
    if List.isEmpty userRoles then
        span [ class "list-auths-empty" ]
            [ i [ class "fa fa-lock fa-4x" ] []
            , p [] [ text "No rights found" ]
            ]

    else
        span [ class "list-auths" ] userRoles


displayUserLastLogin : User -> Html Msg
displayUserLastLogin user =
    case user.lastLogin of
        Nothing ->
            text ""

        Just l ->
            div [ class "user-card-last-login" ]
                [ text (String.replace "T" " " l)
                , i [ class "fa fa-sign-in" ] []
                ]
