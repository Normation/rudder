module Tenants.SecurityTag exposing (SecurityTag(..), badgeSecurityTags, decodeSecurityTag)

{-| The security tag (tenants) attached to a configuration object, shared across the Elm apps so that the
model, its JSON decoder and its badge are defined only once.

  - `Nothing` (no tag) means the object has no tenant and is only visible to an administrator.
  - `Just Open` means it is visible to everyone.
  - `Just (ByTenants tenants)` means it is visible to the listed tenants.

-}

import Html exposing (Html, b, i, span, text)
import Html.Attributes exposing (attribute, class, title)
import Json.Decode exposing (Decoder, andThen, fail, field, list, map, oneOf, string, succeed)


type SecurityTag
    = Open
    | ByTenants (List String)


decodeSecurityTag : Decoder SecurityTag
decodeSecurityTag =
    oneOf
        [ string
            |> andThen
                (\s ->
                    if s == "open" then
                        succeed Open

                    else
                        fail ("Unknown security tag value: " ++ s)
                )
        , map ByTenants (field "tenants" (list string))
        ]


{-| A small badge showing the number of tenants an object belongs to, with the tenant list as tooltip.
`Nothing` and `Open` render nothing (the object is not tenant-scoped for display purposes).
It is polymorphic in `msg` since it emits no message, so it can be used from any app.
-}
badgeSecurityTags : Maybe SecurityTag -> Html msg
badgeSecurityTags mTag =
    case mTag of
        Nothing ->
            text ""

        Just Open ->
            text ""

        Just (ByTenants tenants) ->
            let
                tenantNames =
                    String.join ", " tenants

                nbTenants =
                    List.length tenants

                label =
                    if nbTenants == 0 then
                        "no tenants"

                    else
                        tenantNames
            in
            span
                [ class "tenants-label"
                , attribute "data-bs-toggle" "tooltip"
                , attribute "data-bs-placement" "top"
                , title ("Tenants: " ++ label)
                ]
                [ i [ class "fa fa-building" ] []
                , b [] [ text (String.fromInt nbTenants) ]
                ]
