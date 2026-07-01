# 602 — Authentication & authorization

Two clearly separated concerns, with one firm split:

- **Authentication** (who are you?) is structured **entirely around Spring Security**.
- **Authorization** (what may you do?) is **entirely Rudder's own** — Spring Security's
  authorization features are *not* used.

Keep that boundary when you touch either side.

## Authentication — go through Spring Security

We do **not** write HTTP authentication logic, session-consistency checks, or protocol
handling (LDAP, OIDC, OAuth2) ourselves — we delegate to **Spring Security** and only
plug into its configuration points (it's popular, audited, and maintained). When you
need to change authentication, extend the Spring configuration/filters, don't reinvent
them.

- Providers are tried by `RudderProviderManager` in the order of the
  `rudder.auth.provider` property (the in-memory `rootAdmin` fallback is always tried
  first). External backends (LDAP/OIDC/OAuth2) are registered by the **auth-backends**
  plugin.
- The authenticated user is mapped to a Rudder `RudderUserDetail` (status, roles, API
  authz, tenant/`NodeSecurityContext`). In Lift, the request-scoped `CurrentUser` holds
  it — get it safely via `SecureDispatchSnippet` (see [`600`](600-security-in-depth.md),
  ADR 28452), never from a global.
- After authentication, `UserSessionInvalidationFilter` checks an in-memory cache of
  each user's status on every request: a disabled/removed user, or a
  permission change, invalidates the session. Preserve that behaviour when touching user
  state.

### Cryptography: BouncyCastle, and the right hash for the job

- Use **BouncyCastle for all cryptographic needs** — do **not** use Spring Security's
  crypto implementations. Passwords go through Rudder's custom `PasswordEncoder`.
- **Passwords** are hashed with **bcrypt** (default, cost `rudder.bcrypt.cost` = 12,
  via BouncyCastle `OpenBSDBCrypt`). md5/sha1/sha256/sha512 are deprecated (unsalted),
  kept only for migration — don't use them for new password storage.
- **API tokens** are 32 random alphanumeric chars from `SecureRandom` (~190 bits),
  stored as a **SHA-512** hash prefixed `v2:`. A fast unsalted hash is correct *here*
  precisely because the secret is high-entropy random — unlike a password. Don't confuse
  the two cases: bcrypt for human-chosen secrets, fast hash for high-entropy random ones.
- Token value is shown to the user **once**, at creation, then only its hash is stored.

### Public vs internal API authentication

- **Public API** under `/api` — stateless, authenticated by token in the `X-API-Token`
  header (`RestAuthenticationFilter`). Only `/api/status` (healthcheck) is unauthenticated.
- **Internal API** under `/secure/api` — authenticated by the *user session* (cookies)
  plus the CSRF header `X-Requested-With: XMLHttpRequest` (see [`601`](601-web-and-output-security.md)).

## Authorization — Rudder's RBAC + ACL

The atom is a **unitary right**: an object kind (`authzKind`) plus an `action`
(`read` / `edit` / `write`). It is modelled as `AuthorizationType`
(`rudder-core/.../Authorizations.scala`), an **enumeratum** enum
(see [`401`](401-json-zio-json.md#enums)):

```scala
sealed trait Administration extends EnumEntry with AuthorizationType { def authzKind = "administration" }
object Administration extends Enum[Administration] {
  case object Read  extends Administration with ActionType.Read  with AuthorizationType
  case object Edit  extends Administration with ActionType.Edit  with AuthorizationType
  case object Write extends Administration with ActionType.Write with AuthorizationType
  val values: IndexedSeq[Administration] = findValues
}
// id = s"${authzKind}_${action}", e.g. "administration_write"
```

The **same** `AuthorizationType` drives both:

- **Users → RBAC.** Roles are named sets of rights (built-in roles, or custom roles in
  the `rudder-users.xml` file). Rights/roles are declared in the users file.
- **API accounts → ACL.** Per-endpoint access lists. User tokens get the user's rights;
  standard API accounts get configurable ACLs (fine-grained ACLs need the
  **api-authorizations** plugin; otherwise just RO/RW).

A plugin may define its own authorization kinds (e.g. `cve_read`,
`system_update_write`) and gate its endpoints on them.

### Enforcing it

- **API/endpoint level:** every `EndpointSchema` declares the authz it requires; the
  framework checks it after Spring authentication — you don't re-check by hand (see
  [`103`](103-rest-api-and-endpoints.md)).
- **Object/feature level in Scala:** check with `CurrentUser.checkRights(...)` (the path
  used in Lift snippets).
- **In HTML templates:** `lift:authz` / `lift:Authz.whenhasrights`; pages may expose a
  marker like `var hasWriteRights` to the frontend.
- **Fail closed** and surface denials as `SecurityError` (see [`600`](600-security-in-depth.md),
  [`301`](301-error-model.md)).

### Tenants (RBAC zones)

Tenant scoping (a tenant = a zone of nodes, via the multi-tenants plugin) is layered on
top — model the scope on the object and enforce it in the repository, carrying the
caller's `QueryContext`/`NodeSecurityContext`. See the tenants section in
[`600`](600-security-in-depth.md).

## Don't leak secrets in logs

A real incident came from logging a `RudderUserDetail` whose case-class `toString`
printed the hashed password. **Never** log objects that may contain passwords, password
hashes, or API tokens. Use dedicated types / redacted renderings for anything carrying a
secret, and keep secrets out of error messages (see [`303`](303-logging.md),
[`301`](301-error-model.md)).
