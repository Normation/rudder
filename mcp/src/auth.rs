// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! Authentication: tool calls carry a Rudder API token, checked with Rudder.
//!
//! Only tool calls need one: a client can connect and list the tools without a token, or while
//! Rudder is unreachable (e.g. the webapp restarting).

use std::{fmt, net::SocketAddr, time::Instant};

use axum::{
    extract::{ConnectInfo, Request, State},
    http::{HeaderMap, Method, StatusCode, header::AUTHORIZATION, request::Parts},
    middleware::Next,
    response::Response,
};
use secrecy::SecretString;
use serde::Deserialize;
use tracing::{Instrument, Span, field};

use crate::api::{CallerApi, Envelope, RudderApi};

/// Returns the account of the calling token. A valid token gets 403 when its ACL does not allow
/// reading its own account: such tokens are refused, every tool call must be attributable.
const TOKEN_CHECK_PATH: &str = "apiaccounts/token";
/// MCP method and target name headers (SEP-2243), checked against the body by rmcp
const MCP_METHOD_HEADER: &str = "Mcp-Method";
const MCP_NAME_HEADER: &str = "Mcp-Name";
/// Id of the account of Rudder's system token (`systemAccountId` in the webapp)
const SYSTEM_ACCOUNT_ID: &str = "rudder-system-api-account";

/// The caller's Rudder API token, taken from `Authorization: Bearer <token>`.
///
/// Forwarded as-is to Rudder, which enforces its own ACLs.
#[derive(Debug, Clone)]
pub struct ApiToken(pub SecretString);

impl ApiToken {
    fn from_headers(headers: &HeaderMap) -> Option<Self> {
        // `to_str` only accepts visible ASCII, so the token is valid as a header value again
        let (scheme, token) = headers.get(AUTHORIZATION)?.to_str().ok()?.split_once(' ')?;
        // The scheme is case-insensitive (RFC 9110)
        let token = token.trim();
        (scheme.eq_ignore_ascii_case("Bearer") && !token.is_empty()).then(|| Self(token.into()))
    }
}

/// Rights of an API account on the Rudder API (`authorizationType`). An unknown value fails the
/// token check: the caller's rights would be unknown.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AuthorizationType {
    /// No access
    None,
    /// `GET` on all APIs
    Ro,
    /// All verbs on all APIs
    Rw,
    /// Per-path rights (api-authorization plugin)
    Acl,
    /// No `authorizationType`, as Rudder sends for user tokens (api-authorization plugin): the
    /// rights are those of the owning account
    #[default]
    #[serde(skip_deserializing)]
    Owner,
    /// Rudder's system token, told by its account id: Rudder sends no `authorizationType` for it
    #[serde(skip_deserializing)]
    System,
}

impl AuthorizationType {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::None => "none",
            Self::Ro => "ro",
            Self::Rw => "rw",
            Self::Acl => "acl",
            Self::Owner => "owner",
            Self::System => "system",
        }
    }

    pub fn describe(self) -> &'static str {
        match self {
            Self::None => "none: no access to the APIs",
            Self::Ro => "read-only: GET on all APIs",
            Self::Rw => "read-write: all verbs on all APIs",
            Self::Acl => "per-path access control list (api-authorization plugin)",
            Self::Owner => "those of the user owning the token (api-authorization plugin)",
            Self::System => "Rudder's internal system token: all verbs on all APIs",
        }
    }
}

/// The Rudder API account behind the caller's token
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ApiAccount {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub authorization_type: AuthorizationType,
}

/// Who is calling: what an accepted tool call hands to the tool, in its request extensions
#[derive(Debug, Clone)]
pub struct Caller {
    pub account: ApiAccount,
    /// The Rudder API with the caller's token
    pub api: CallerApi,
}

/// `GET /apiaccounts/token` response data, only the fields used here
#[derive(Deserialize)]
struct TokenAccounts {
    accounts: Vec<ApiAccount>,
}

/// Why a tool call is refused
#[derive(Debug)]
enum TokenRejection {
    /// No `Authorization: Bearer` header, or a malformed one
    Missing,
    /// Rudder does not know the token
    Rejected,
    /// Valid token, but not allowed to read its own account: the caller cannot be identified
    Unidentified,
    /// Rudder could not be asked, or gave an unexpected answer
    Unverifiable,
}

/// Told to the model as the tool result, so it can tell the user
impl fmt::Display for TokenRejection {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Missing => {
                "no Rudder API token: the MCP client must send an 'Authorization: Bearer <Rudder \
                 API token>' header"
            }
            Self::Rejected => "Rudder rejected the API token",
            Self::Unidentified => {
                "the API token must be allowed to read its own account (GET apiaccounts/token)"
            }
            Self::Unverifiable => "could not check the API token with Rudder",
        })
    }
}

/// Checks the token with Rudder. Fails closed: a token that cannot be checked is refused.
#[tracing::instrument(name = "token_check", skip_all)]
async fn check_token(api: &CallerApi) -> Result<ApiAccount, TokenRejection> {
    let response = api
        .send(Method::GET, TOKEN_CHECK_PATH, &[])
        .await
        .map_err(|e| {
            // `{:#}` shows the cause chain, e.g. a refused connection
            tracing::warn!("{e:#}");
            TokenRejection::Unverifiable
        })?;
    match response.status() {
        status if status.is_success() => {
            // A 2xx without an account means the API changed: nobody to attribute the request to
            let parsed: Envelope<TokenAccounts> = response.json().await.map_err(|e| {
                tracing::warn!("unexpected account response: {e}");
                TokenRejection::Unverifiable
            })?;
            parsed.data.accounts.into_iter().next().ok_or_else(|| {
                tracing::warn!("no account in response");
                TokenRejection::Unverifiable
            })
        }
        StatusCode::UNAUTHORIZED => Err(TokenRejection::Rejected),
        StatusCode::FORBIDDEN => Err(TokenRejection::Unidentified),
        status => {
            tracing::warn!("Rudder API returned {status}");
            Err(TokenRejection::Unverifiable)
        }
    }
}

/// What a request brings to authenticate its tool calls: its token, not checked yet, and the
/// request span to record the account in
#[derive(Debug, Clone)]
pub struct Credentials {
    /// The Rudder API with the request's token, if it has one
    api: Option<CallerApi>,
    span: Span,
}

impl Credentials {
    /// Checks the token with Rudder and records the account in the request span
    async fn check(&self) -> Result<Caller, TokenRejection> {
        let api = self.api.as_ref().ok_or(TokenRejection::Missing)?;
        let mut account = check_token(api).await?;
        if account.id == SYSTEM_ACCOUNT_ID {
            account.authorization_type = AuthorizationType::System;
        }
        self.span.record("account", account.id.as_str());
        self.span.record("account_name", account.name.as_str());
        self.span
            .record("rights", account.authorization_type.as_str());
        if account.authorization_type == AuthorizationType::System {
            // After the recording: the warning names the account
            tracing::warn!(
                "tool call with Rudder's system API token, which has every right and is meant \
                 for Rudder itself: give the MCP client the token of a dedicated API account"
            );
        }
        Ok(Caller {
            account,
            api: api.clone(),
        })
    }
}

/// Authenticates a tool call: checks the request's token with Rudder and hands the [`Caller`] to
/// the tool. Fails closed: a token that cannot be checked is refused, with the reason for the
/// model.
///
/// Costs one Rudder API call per tool call.
pub async fn authenticate(request: &mut Parts) -> Result<(), String> {
    let credentials = request
        .extensions
        .get::<Credentials>()
        .ok_or("no credentials in request")?;
    match credentials.check().await {
        Ok(caller) => {
            request.extensions.insert(caller);
            Ok(())
        }
        Err(rejection) => {
            tracing::info!(?rejection, "tool call refused");
            Err(rejection.to_string())
        }
    }
}

/// Value of a request header, for the logs
fn header<'a>(headers: &'a HeaderMap, name: &str) -> Option<&'a str> {
    headers.get(name)?.to_str().ok()
}

/// Records a tool call's method and name in the request span when the client did not send them
/// in the `Mcp-Method` and `Mcp-Name` headers: rmcp has decoded them from the body by then
pub fn record_tool_call(request: &Parts, name: &str) {
    let Some(credentials) = request.extensions.get::<Credentials>() else {
        return;
    };
    if header(&request.headers, MCP_METHOD_HEADER).is_none() {
        credentials.span.record("mcp_method", "tools/call");
    }
    if header(&request.headers, MCP_NAME_HEADER).is_none() {
        credentials.span.record("mcp_name", name);
    }
}

/// Hands the request's token, unchecked, to its tool calls (see [`authenticate`]) as
/// [`Credentials`], and logs each request. Other requests need no token, and cost no Rudder call.
///
/// Every log line of the request, refusals included, is in a `mcp_request` span with the client
/// address (the proxy's when behind one), the MCP method and target name, and once a tool call's
/// token is accepted, the account id, name and rights. A tool call fills in the method and name
/// when the client did not send them as headers. rmcp passes the current span on to the tasks it
/// spawns for the request.
pub async fn with_credentials(
    State(api): State<RudderApi>,
    ConnectInfo(client): ConnectInfo<SocketAddr>,
    mut request: Request,
    next: Next,
) -> Response {
    let span = tracing::info_span!(
        "mcp_request",
        %client,
        mcp_method = header(request.headers(), MCP_METHOD_HEADER),
        mcp_name = header(request.headers(), MCP_NAME_HEADER),
        account = field::Empty,
        account_name = field::Empty,
        rights = field::Empty,
    );
    let credentials = Credentials {
        api: ApiToken::from_headers(request.headers()).map(|token| api.for_caller(token)),
        span: span.clone(),
    };
    request.extensions_mut().insert(credentials);
    async {
        let start = Instant::now();
        let response = next.run(request).await;
        tracing::info!(
            http_status = response.status().as_u16(),
            duration_ms = start.elapsed().as_millis(),
            "MCP request"
        );
        response
    }
    .instrument(span)
    .await
}

#[cfg(test)]
mod tests {
    use axum::http::HeaderValue;
    use pretty_assertions::assert_eq;
    use secrecy::ExposeSecret;

    use super::*;

    fn token(authorization: Option<&str>) -> Option<String> {
        let mut headers = HeaderMap::new();
        if let Some(value) = authorization {
            headers.insert(AUTHORIZATION, HeaderValue::from_str(value).unwrap());
        }
        ApiToken::from_headers(&headers).map(|t| t.0.expose_secret().to_owned())
    }

    #[test]
    fn token_from_bearer_header() {
        assert_eq!(token(Some("Bearer abc123")), Some("abc123".to_owned()));
        assert_eq!(token(Some("Bearer  abc123 ")), Some("abc123".to_owned()));
        assert_eq!(token(Some("bearer abc123")), Some("abc123".to_owned()));
    }

    #[test]
    fn no_token_without_valid_bearer_header() {
        assert_eq!(token(None), None);
        assert_eq!(token(Some("Basic dXNlcjpwYXNz")), None);
        assert_eq!(token(Some("Bearer")), None);
        assert_eq!(token(Some("Bearer ")), None);
        assert_eq!(token(Some("Bearer    ")), None);
        assert_eq!(token(Some("abc123")), None);
    }

    #[test]
    fn token_is_redacted_in_debug_output() {
        let token = ApiToken(SecretString::from("abc123"));
        assert!(!format!("{token:?}").contains("abc123"));
    }

    #[test]
    fn parses_token_account_response() {
        // Shape of `GET /apiaccounts/token` (api-doc `token-account.yml`)
        let body = r#"{
            "result": "success", "action": "getTokenAccount", "id": "x",
            "data": {"accounts": [{
                "id": "91252ea2-feb2-412d-8599-c6945fee02c4", "name": "Audit directives",
                "description": "ro audit", "status": "enabled", "authorizationType": "ro",
                "tenants": "*", "tokenState": "generated"
            }]}
        }"#;
        let account = serde_json::from_str::<Envelope<TokenAccounts>>(body)
            .unwrap()
            .data
            .accounts
            .remove(0);
        assert_eq!(account.id, "91252ea2-feb2-412d-8599-c6945fee02c4");
        assert_eq!(account.name, "Audit directives");
        assert_eq!(account.authorization_type, AuthorizationType::Ro);
    }

    #[test]
    fn authorization_type_of_token_account() {
        let parse = |account: &str| serde_json::from_str::<ApiAccount>(account);
        let rights = |account| parse(account).unwrap().authorization_type;
        assert_eq!(
            rights(r#"{"id": "a", "name": "A", "authorizationType": "none"}"#),
            AuthorizationType::None
        );
        assert_eq!(
            rights(r#"{"id": "a", "name": "A", "authorizationType": "acl"}"#),
            AuthorizationType::Acl
        );
        // User token
        assert_eq!(
            rights(r#"{"id": "a", "name": "A"}"#),
            AuthorizationType::Owner
        );
        // Not a value Rudder sends
        assert!(parse(r#"{"id": "a", "name": "A", "authorizationType": "owner"}"#).is_err());
        assert!(parse(r#"{"id": "a", "name": "A", "authorizationType": "admin"}"#).is_err());
    }
}
