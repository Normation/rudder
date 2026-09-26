module Tenants.SecurityTag exposing (SecurityTag(..), badgeSecurityTags, decodeSecurityTag)

{-| The security tag (tenants) attached to a configuration object, shared across the Elm apps so that the
model, its JSON decoder and its badge are defined only once.

  - `Nothing` (no tag) means the object has no tenant and is only visible to an administrator.
  - `Just OpenRo` means everybody sees and uses it, and only an administrator changes it: that is what the
    root categories, the groups Rudder provides and the shipped techniques are.
  - `Just OpenRw` means everybody sees it and anybody who may write at all may change it.
  - `Just (ByTenants tenants)` means it is visible to the listed tenants.

Rudder 9.2 first had a single `open` tag, which meant read-write; it is still decoded.

-}

import Html exposing (Html, b, i, span, text)
import Html.Attributes exposing (attribute, class, title)
import Json.Decode exposing (Decoder, andThen, fail, field, list, map, oneOf, string, succeed)


type SecurityTag
    = OpenRo
    | OpenRw
    | ByTenants (List String)


decodeSecurityTag : Decoder SecurityTag
decodeSecurityTag =
    oneOf
        [ string
            |> andThen
                (\s ->
                    case s of
                        "open-ro" ->
                            succeed OpenRo

                        "open-rw" ->
                            succeed OpenRw

                        -- what 9.2 wrote before the two open tags were told apart: it was read-write
                        "open" ->
                            succeed OpenRo

                        _ ->
                            fail ("Unknown security tag value: " ++ s)
                )
        , map ByTenants (field "tenants" (list string))
        ]


{-| A small badge showing the number of tenants an object belongs to, with the tenant list as tooltip.
`Nothing` and the open tags render nothing (the object is not tenant-scoped for display purposes).
It is polymorphic in `msg` since it emits no message, so it can be used from any app.
-}
badgeSecurityTags : Maybe SecurityTag -> Html msg
badgeSecurityTags mTag =
    case mTag of
        Nothing ->
            text ""

        Just OpenRo ->
            text ""

        Just OpenRw ->
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
