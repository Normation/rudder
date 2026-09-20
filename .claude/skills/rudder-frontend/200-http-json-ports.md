# 200 — HTTP, JSON, ports & error handling

> **This file is the frontend's edge.** Everything here is
> [principle 2 — parse at the edge; the core is pure structure](../rudder-principles/SKILL.md#2-parse-at-the-edge-the-core-is-pure-structure):
> an HTTP response and a port `Value` are untrusted input, decoded **once** into `Model`
> types, so that `update` and `view` only ever see valid data. A `Maybe` threaded deep into
> the view because "the decoder wasn't sure" is the symptom of a decoder that gave up too
> early — fix the decoder, not the view
> ([principle 3](../rudder-principles/SKILL.md#3-fix-the-root-cause-not-the-visible-symptom)).

## HTTP requests

Requests live in `ApiCalls.elm` (or locally for a standalone app) and use **`Http.Detailed`**
so the error carries the response body. The Rudder API wraps payloads in a `data` field, so
decode at `["data"]`; send the `X-Requested-With` header.

```elm
getReportData : Model -> Cmd Msg
getReportData model =
    request
        { method = "GET"
        , headers = [ Http.header "X-Requested-With" "XMLHttpRequest" ]
        , url = model.contextPath ++ "/secure/api/securityBenchmarks/" ++ model.benchmarkId ++ "/reportData"
        , body = emptyBody
        , expect = Detailed.expectJson GotReport (at [ "data" ] decodeSecurityReportData)
        , timeout = Nothing
        , tracker = Nothing
        }
```

## Decoding — match the zio-json contract

Decoders must mirror what the Scala side emits (see
[`rudder-scala/401`](../rudder-scala/401-json-zio-json.md) /
[`404`](../rudder-scala/404-serialization-contracts.md)):

- **`Option` is an absent field, not `null`** — decode with `optional "field" (maybe d) Nothing`.
- **`Float` keeps its shape** (`45.0`, `81.666664`); decode with `float`, don't assume ints.
- Enums come as their serialized token (e.g. score `"A".."F"`, `"X"`); map with a small
  `toScoreValue`-style function.
- **Decode records with `Json.Decode.Pipeline`** (`succeed` + `required` / `optional`), not
  `map2`/`mapN` — it's the house style and reads well as fields are added:

  ```elm
  decodeReadUrl : Decoder ReadUrl
  decodeReadUrl =
      Json.Decode.succeed ReadUrl
          |> Json.Decode.Pipeline.required "id" Json.Decode.string
          |> Json.Decode.Pipeline.optional "node" (Json.Decode.maybe Json.Decode.string) Nothing
  ```

  `optional` covers a missing field; wrap in `maybe` to also treat an explicit `null` as `Nothing`.

When you change a response shape, update the decoder **and** the API YAML fixture on the Scala
side in the same change (that fixture is the contract the reviewer reads).

## Ports (Elm ↔ JS)

Ports are typed and named; payloads are JSON-encodable records.

```elm
port errorNotification : String -> Cmd msg            -- outgoing: subscribed in the template
port printReport : { fileName : String, rows : List { hostname : String } } -> Cmd msg
port initReportDoughnut : Value -> Cmd msg
port readUrl : (Value -> msg) -> Sub msg              -- incoming: `.send`-ed from JS
```

Decode incoming port `Value`s inside `update`/subscriptions with a dedicated decoder and treat a
decode failure as a no-op. Keep the JS side of a port to DOM/library glue only
([`300`](300-javascript-in-templates.md)).

## Error handling — never surface a raw API body

A failed request must not dump the raw JSON body at the user. Route every API error through the
shared **`ApiError`** module:

```elm
-- ApiError.elm (shared)
errorMessage : Detailed.Error String -> String        -- friendly text per error kind;
                                                       -- BadStatus -> decodeErrorDetails body
decodeErrorDetails : String -> ( String, String )      -- parse "errorDetails", split on "<-":
                                                       -- (title, "‣"-prefixed nested causes)
```

Each app keeps a thin wrapper that adds context and wires its own `Model`/notification port:

```elm
processApiError : String -> Detailed.Error String -> Model -> ( Model, Cmd Msg )
processApiError context err model =
    let message = errorMessage err in
    ( { model | error = Just message }, errorNotification (context ++ ", details: \n" ++ message) )
```

Rules of thumb:
- **`BadStatus` → decode `errorDetails`**, never show the raw body.
- Map `BadUrl` / `Timeout` / `NetworkError` / `BadBody` to human sentences.
- Surface the message both inline (an alert in the view) and as a notification, so a failed load
  doesn't spin forever on "Loading…".
- If a second app needs the same handling, use the shared `ApiError` — don't re-derive it.
