module UserManagement.View exposing (..)

import Dict.Extra
import UserManagement.ApiCalls exposing (deleteUser)
import UserManagement.DataTypes exposing (..)
import Dict exposing (Dict, keys)
import Html exposing (..)
import Html.Attributes exposing (attribute, checked, class, colspan, disabled, for, href, id, placeholder, required, style, tabindex, target, title, type_, value)
import Html.Events exposing (onClick, onInput, onCheck)
import List
import List.Extra
import Maybe.Extra exposing (isJust)
import Set
import String exposing (isEmpty)
import NaturalOrdering as N
import Json.Encode exposing (encode)

view : Model -> Html Msg
view model =
    let
        content =
            if isEmpty model.digest || List.isEmpty (keys model.users) then
                text "Waiting for data from server..."

            else
                displayUsersConf model

        deleteModal =
            if model.ui.openDeleteModal then
                showDeleteModal model.userForm.login

            else
                div [] []
    in
    div [class "rudder-template"]
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
        [ a [ class ("btn btn-default " ++ clearPasswdIsActivate), onClick (PreHashedPasswd False) ] [ text "Password to hash" ]
        , a [ class ("btn btn-default " ++ hashPasswdIsActivate), onClick (PreHashedPasswd True) ] [ text "Enter pre-hashed value" ]
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
                [ div [ id "input-role" ] (List.map (\x -> span [ class "role" ] [ text x ]) (Set.toList inferredRoles))
                ]
            ]


displayAuthorizations : User -> Html Msg
displayAuthorizations user =
    let
        userAuthz =
            List.map (\x -> span [ class "role" ] [ text x ]) user.authz
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
                            [ input [ id k, class "form-control user-info-value", value (encode 0 v), disabled True ] []
                            , button [ class "btn btn-default", onClick (RemoveUserOtherInfoField k) ] [ i [ class "fa fa-trash" ] [] ]
                            ]
                        ]
                )
                (Dict.toList userForm.userInfoForm.otherInfo)
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
                (Dict.toList userForm.userInfoForm.info)
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
                [ displayDeleteButton model user
                , displayToggleStatusButton model user
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
                    (case user.previousLogin of
                        Nothing ->
                            [ em [] [ text "Never logged in" ] ]

                        Just l ->
                            [ em [] [ text ("Previous login: " ++ String.replace "T" " " l) ] ]
                    )
                ]
             , displayPasswordBlock model (Just user)
             ]
                ++ displayedProviders
                ++ (displayUserInfo model.userForm True :: rolesAuthzInformationSection)
            )
        ]

displayDeleteButton : Model -> User -> Html Msg
displayDeleteButton model user = -- Do not display button for current user
    if user.login == model.userId then
        text ""
    else
        button [ class "btn btn-sm btn-danger btn-delete", onClick (OpenDeleteModal user.login) ] [ text "Delete" ]
displayToggleStatusButton : Model -> User -> Html Msg
displayToggleStatusButton model user = -- Do not display button when active : user cannot disable itself
    if user.login == model.userId && user.status == Active then
        text ""
    else
        button
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

displayUsersConf : Model -> Html Msg
displayUsersConf model =
    let
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
            isJust (takeFirstExtProvider model.providers)

        (lstOfExtProviders, msgProvider) =
            if hasExternal then
                ( div [ class "provider-list" ] (List.map (\s -> span [ class "providers" ] [ text s ]) model.providers)
                , "Authentication providers priority: "
                )
            else
                ( text ""
                , "Local password-based authentication is used"
                )

        tableFilters = model.ui.tableFilters

        existingUsers = model.users
          |> Dict.values
          |> List.filter (\user -> user.status /= Deleted)
        filteredUsers = existingUsers
          |> List.filter (\u -> filterSearch model.ui.tableFilters.filter (searchField u))
          |> List.sortWith (getSortFunction model)

        nbUsers = List.length existingUsers
        nbFilteredUsers = List.length filteredUsers

    in
    div [ class "one-col flex-fill" ]
        [ div [ class "main-header" ]
            [ div [ class "header-title" ]
                [ h1 []
                    [ span [] [ text "User management" ]
                    ]
                ]
            , div [ class "header-description" ]
                [ p []
                    [ text "Manage the current Rudder users and their rights."
                    , span[class "ms-2 text-info"]
                        [ i[class "fa fa-info-circle me-1"][]
                        , text msgProvider
                        , lstOfExtProviders
                        ]
                    ]
                , displaySafeHashesStatus model
                ]
            ]
            , div [ class "one-col-main" ]
                [ div [ class "template-main" ]
                    [ div [ class "main-container" ]
                        [ div [ class "main-details" ]
                            [ button [ class "btn me-auto btn-success new-icon btn-add", onClick ActivePanelAddUser ] [ text "Create a user" ]
                            , div [ class "main-table" ]
                                [ div [ class "table-container" ]
                                    [ div [ class "dataTables_wrapper_top table-filter" ]
                                        [ div [ class "form-group" ]
                                            [ input
                                                [ class "form-control"
                                                , type_ "text"
                                                , value model.ui.tableFilters.filter
                                                , placeholder "Filter..."
                                                , onInput (\s -> UpdateTableFilters { tableFilters | filter = s } )
                                                ]
                                                []
                                            ]
                                        , div [ class "end" ]
                                            [ button [ class "btn btn-default", onClick SendReload ] [ i [ class "fa fa-refresh" ] [] ]
                                            ]
                                        ]
                                      , displayUsersTable model filteredUsers
                                      , div[class "dataTables_wrapper_bottom"]
                                          [ div [class "dataTables_info"]
                                              [ text ("Showing " ++ (String.fromInt nbFilteredUsers) ++ " of "++ (String.fromInt nbUsers) ++ " entries") ]
                                          ]
                                    ]
                                ]
                            ]
                        ]
                    ]
                ]
            , newUserMenu
            , panel
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
                        span [ class "role-added" ] [ text x ]
                    )
                    model.userForm.rolesToAddOnSave

        userRoles =
            List.map
                (\x ->
                    span [ class "role" ]
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


displayRights : User -> Dict String (List String) -> Html Msg
displayRights user roles =
    let
        userRoles =
            List.map
                (\x ->
                    let
                        tooltipRoles = case Dict.get x roles of
                            Just authz  ->
                                let
                                    listAuthz = authz
                                        |> List.map(\a -> "<span class='auth ms-0 me-1 mb-2'>" ++ a ++ "</span>")
                                        |> String.join " "
                                    tooltipContent = buildTooltipContent x listAuthz
                                in
                                [ attribute "data-bs-toggle" "tooltip"
                                , attribute "data-bs-placement" "top"
                                , attribute "data-bs-html" "true"
                                , title tooltipContent
                                ]
                            Nothing -> []
                    in
                        span ((class "role") :: tooltipRoles) [ text x ]
                )
                user.roles

        tooltipAuths =
            if List.isEmpty user.customRights then
                []
            else
                let
                    customRights = user.customRights
                        |> List.map (\a -> "<span class='auth ms-0 me-1 mb-2'>" ++ a ++ "</span>")
                        |> String.join " "
                    nbCustomRights = List.length user.customRights
                    txtCustomRights = (String.fromInt nbCustomRights) ++ " authorization" ++ (if nbCustomRights > 1 then "s" else "")
                    tooltipContent = buildTooltipContent txtCustomRights customRights
                    prefix = if List.isEmpty user.roles then "" else "+ "
                in
                    [ span
                        [ class "auth fw-medium"
                        , attribute "data-bs-toggle" "tooltip"
                        , attribute "data-bs-placement" "top"
                        , attribute "data-bs-html" "true"
                        , title tooltipContent
                        ]
                        [ text (prefix ++ txtCustomRights)
                        , i[class "fa fa-info-circle ms-1"][]
                        ]
                    ]

    in
    if List.isEmpty userRoles && List.isEmpty tooltipAuths then
        span [ class "empty" ] [ text "No rights found" ]
    else
        span [ class "list-auths" ] (userRoles ++ tooltipAuths)

displayProviders : Model -> User -> Html Msg
displayProviders model user =
    let
        tooltipContent =
            buildTooltipContent "Provider not configured" "This user cannot log in with this provider, because it is not configured with the \"rudder.auth.provider\" property."
        attributes p =
            if List.member p model.providers then
                [ class "badge" ]
            else
                [ class "badge badge-provider-warning"
                , attribute "data-bs-toggle" "tooltip"
                , attribute "data-bs-placement" "top"
                , attribute "data-bs-html" "true"
                , title tooltipContent
                ]
        providerEl p =
            if List.member p model.providers then
                span (attributes p)[text p]
            else
                span(attributes p)[i[class "fa fa-exclamation-triangle warning-icon"][], text p]


    in
        if List.isEmpty user.providers then
          i[][text "None"]
        else
          div[]
          (List.map providerEl user.providers)

displayTenants : User -> Html Msg
displayTenants user =
    case user.tenants of
        "all"  -> span [class "empty"][text "all"]
        "none" -> span [class "empty"][text "none"]
        o      -> text o

displayUserPreviousLogin : User -> Html Msg
displayUserPreviousLogin user =
    case user.previousLogin of
        Nothing ->
            span[class "empty"][text "Never logged in"]

        Just l ->
            text (String.replace "T" " " l)

displayUsersTable : Model -> List User -> Html Msg
displayUsersTable model users =
  let
    hasUserWithUnknownProvider =
        model.users |> Dict.Extra.any (\_ user -> List.any (\p -> List.Extra.notMember p model.providers) user.providers)
    hasExternal =
        isJust (takeFirstExtProvider model.providers) || hasUserWithUnknownProvider

    trUser : User -> Html Msg
    trUser user  =
      let
        inputId = "toggle-" ++ user.login
        active = user.status == Active
        toggleAction =
            if active then
                DisableUser user.login
            else
                ActivateUser user.login
      in
        tr[]
            [ td []
                [ text user.login
                ]
            , ( if String.isEmpty user.name then
                td [] [ span[class "empty"] [text user.login] ]
            else
                td [] [ text user.name ]
            )
            , td []
                [ displayRights user model.roles
                ]
            , ( if hasExternal then
                td [] [ displayProviders model user ]
            else
                text ""
            )
            , ( if model.tenantsEnabled then
                td []
                [ displayTenants user
                ]
            else
                text ""
            )
            , td []
                [ displayUserPreviousLogin user
                ]
            , td []
              [ button
                [ class "btn btn-default"
                , onClick (ActivePanelSettings user)
                ]
                [ span [class "fa fa-pencil"] [] ]
              , label [for inputId, class "custom-toggle ms-2"]
                [ input [type_ "checkbox", id inputId, checked active, onCheck (\c -> toggleAction)][]
                , label [for inputId, class "custom-toggle-group"]
                  [ label [for inputId, class "toggle-enabled" ][text "Enabled"]
                  , span  [class "cursor"][]
                  , label [for inputId, class "toggle-disabled"][text "Disabled"]
                  ]
                ]
              , button
                [ class "btn btn-danger delete-button ms-2"
                , onClick (OpenDeleteModal user.login)
                ]
                [ span [class "fa fa-times-circle"] [] ]
              ]
            ]
    filters = model.ui.tableFilters
  in
    table [class "dataTable"]
    [ thead []
      [ tr [class "head"]
        [ th [class (thClass model.ui.tableFilters UserLogin      ), onClick (UpdateTableFilters (sortTable filters UserLogin      ))] [ text "User"           ]
        , th [class (thClass model.ui.tableFilters Name           ), onClick (UpdateTableFilters (sortTable filters Name           ))] [ text "User name"      ]
        , th [class (thClass model.ui.tableFilters Rights         ), onClick (UpdateTableFilters (sortTable filters Rights         ))] [ text "Rights"         ]
        , ( if hasExternal then
            th [class (thClass model.ui.tableFilters Providers      ), onClick (UpdateTableFilters (sortTable filters Providers      ))] [ text "Providers"      ]
        else
            text ""
        )
        , ( if model.tenantsEnabled then
            th [class (thClass model.ui.tableFilters Tenants        ), onClick (UpdateTableFilters (sortTable filters Tenants        ))] [ text "Tenants"        ]
        else
            text ""
        )
        , th [class (thClass model.ui.tableFilters PreviousLogin  ), onClick (UpdateTableFilters (sortTable filters PreviousLogin  ))] [ text "Previous login" ]
        , th [style "width" "220px"][ text "Actions" ]
        ]
      ]
    , tbody []
      ( if Dict.isEmpty model.users then
        [ tr[]
          [ td[class "empty", colspan 7][i [class"fa fa-exclamation-triangle"][], text "There are no users defined"] ]
        ]
      else if List.isEmpty users then
        [ tr[]
          [ td[class "empty", colspan 7][i [class"fa fa-exclamation-triangle"][], text "No users match your filters"] ]
        ]
      else
        List.map (\u -> trUser u ) users
      )
    ]

displaySafeHashesStatus : Model -> Html Msg
displaySafeHashesStatus { safeHashes } =
    if safeHashes then
        text ""

    else
        div [ class "callout-fade callout-danger" ]
        [ p [] [ i [ class "me-1 fa fa-warning" ] [], text "Your configuration allows unsafe hashes, which will be removed in an upcoming version of Rudder." ]
        , p []
          [ text "You should migrate all user passwords and set "
          , code [] [ text "unsafe-hashes=\"false\""]
          , text " in the users configuration file."
          ]
        , p []
          [ text "See "
          , a [ href "/rudder-doc/reference/current/administration/users.html#_passwords", target "_blank" ] [ text "the documentation"]
          , text " for instructions."
          ]
        ]


thClass : TableFilters -> SortBy -> String
thClass tableFilters sortBy =
  if sortBy == tableFilters.sortBy then
    case  tableFilters.sortOrder of
      Asc  -> "sorting_asc"
      Desc -> "sorting_desc"
  else
    "sorting"

sortTable : TableFilters -> SortBy -> TableFilters
sortTable tableFilters sortBy =
  let
    order =
      case tableFilters.sortOrder of
        Asc -> Desc
        Desc -> Asc
  in
    if sortBy == tableFilters.sortBy then
      { tableFilters | sortOrder = order}
    else
      { tableFilters | sortBy = sortBy, sortOrder = Asc}

filterSearch : String -> List String -> Bool
filterSearch filterString searchFields =
  let
    -- Join all the fields into one string to simplify the search
    stringToCheck = searchFields
      |> String.join "|"
      |> String.toLower

    searchString  = filterString
      |> String.toLower
      |> String.trim
  in
    String.contains searchString stringToCheck

searchField user =
  [ user.login
  , user.name
  ]

getSortFunction : Model -> User -> User -> Order
getSortFunction model u1 u2 =
  let
      compareStringList : List String -> List String -> Order
      compareStringList l1 l2 =
          let
              getFirstElement : List String -> String
              getFirstElement lst =
                  case List.head lst of
                      Just el -> el
                      Nothing -> ""
              i1 = getFirstElement u1.roles
              i2 = getFirstElement u2.roles
          in
              case (String.isEmpty i1, String.isEmpty i2) of
                  (False, True)  -> LT
                  (True, False)  -> GT
                  (True, True)   -> EQ
                  _ -> checkOrder (N.compare i1 i2)

      checkOrder : Order -> Order
      checkOrder o =
          if model.ui.tableFilters.sortOrder == Asc then
              o
          else
              case o of
                  LT -> GT
                  EQ -> EQ
                  GT -> LT
  in
      case model.ui.tableFilters.sortBy of
          Name          ->
              let
                  name1 = if String.isEmpty u1.name then u1.login else u1.name
                  name2 = if String.isEmpty u2.name then u2.login else u2.name
              in
                  checkOrder (N.compare name1 name2)
          Rights        -> compareStringList u1.roles u2.roles
          Providers     -> compareStringList u1.providers u2.providers
          Tenants       -> checkOrder (N.compare u1.name u2.name)
          PreviousLogin ->
              case (u1.previousLogin, u2.previousLogin) of
                  (Just _, Nothing)  -> LT
                  (Nothing, Just _)  -> GT
                  (Nothing, Nothing) -> EQ
                  (Just l1, Just l2) -> checkOrder (N.compare l1 l2)
          _ -> checkOrder (N.compare u1.login u2.login)

buildTooltipContent : String -> String -> String
buildTooltipContent title content =
  let
    headingTag = "<h4 class='tags-tooltip-title'>"
    contentTag = "</h4><div class='tooltip-inner-content'>"
    closeTag   = "</div>"
  in
    headingTag ++ title ++ contentTag ++ content ++ closeTag
