// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! Authentication: every request carries a Rudder API token, checked with Rudder.

use std::{net::SocketAddr, time::Instant};

use axum::{
    extract::{ConnectInfo, Request, State},
    http::{HeaderMap, Method, StatusCode, header::AUTHORIZATION},
    middleware::Next,
    response::{IntoResponse, Response},
};
use secrecy::SecretString;
use serde::Deserialize;
use tracing::{Instrument, Span, field};

use crate::api::{CallerApi, RudderApi};

/// Returns the account of the calling token. A valid token gets 403 when its ACL does not allow
/// reading its own account: such tokens are refused, every request must be attributable.
const TOKEN_CHECK_PATH: &str = "apiaccounts/token";
/// MCP method and target name headers (SEP-2243), checked against the body by rmcp
const MCP_METHOD_HEADER: &str = "Mcp-Method";
const MCP_NAME_HEADER: &str = "Mcp-Name";

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
    /// No `authorizationType`, as Rudder sends for user tokens (api-authorization plugin) and the
    /// system token: the rights are those of the owning account
    #[default]
    #[serde(skip_deserializing)]
    Owner,
}

impl AuthorizationType {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::None => "none",
            Self::Ro => "ro",
            Self::Rw => "rw",
            Self::Acl => "acl",
            Self::Owner => "owner",
        }
    }

    pub fn describe(self) -> &'static str {
        match self {
            Self::None => "none: no access to the APIs",
            Self::Ro => "read-only: GET on all APIs",
            Self::Rw => "read-write: all verbs on all APIs",
            Self::Acl => "per-path access control list (api-authorization plugin)",
            Self::Owner => "those of the user owning the token (api-authorization plugin)",
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

/// Who is calling: what an accepted request hands to the tools, in its extensions
#[derive(Debug, Clone)]
pub struct Caller {
    pub account: ApiAccount,
    /// The Rudder API with the caller's token
    pub api: CallerApi,
}

/// `GET /apiaccounts/token` response, only the fields used here
#[derive(Deserialize)]
struct TokenAccountResponse {
    data: TokenAccountData,
}

#[derive(Deserialize)]
struct TokenAccountData {
    accounts: Vec<ApiAccount>,
}

/// Why a request is refused
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

impl IntoResponse for TokenRejection {
    fn into_response(self) -> Response {
        match self {
            Self::Missing => (
                StatusCode::UNAUTHORIZED,
                "expected an 'Authorization: Bearer <Rudder API token>' header",
            ),
            Self::Rejected => (StatusCode::UNAUTHORIZED, "Rudder rejected the API token"),
            Self::Unidentified => (
                StatusCode::FORBIDDEN,
                "the API token must be allowed to read its own account (GET apiaccounts/token)",
            ),
            Self::Unverifiable => (
                StatusCode::BAD_GATEWAY,
                "could not check the API token with Rudder",
            ),
        }
        .into_response()
    }
}

/// Checks the token with Rudder. Fails closed: a token that cannot be checked is refused.
#[tracing::instrument(name = "token_check", skip_all)]
async fn check_token(api: &CallerApi) -> Result<ApiAccount, TokenRejection> {
    let response = api.send(Method::GET, TOKEN_CHECK_PATH).await.map_err(|e| {
        // `{:#}` shows the cause chain, e.g. a refused connection
        tracing::warn!("{e:#}");
        TokenRejection::Unverifiable
    })?;
    match response.status() {
        status if status.is_success() => {
            // A 2xx without an account means the API changed: nobody to attribute the request to
            let parsed: TokenAccountResponse = response.json().await.map_err(|e| {
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

/// Value of a request header, for the logs
fn header<'a>(request: &'a Request, name: &str) -> &'a str {
    request
        .headers()
        .get(name)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("-")
}

/// Rejects requests without a token Rudder accepts and attributes to an account, before they reach
/// MCP, hands the [`Caller`] to the tools, and logs each request
/// with its account.
///
/// Every log line of the request, refusals included, is in a `mcp_request` span with the client
/// address (the proxy's when behind one), the MCP method and target name, and once the token is
/// accepted, the account id and rights. rmcp passes the current span on to the tasks it spawns for
/// the request.
///
/// Costs one Rudder API call per MCP request.
pub async fn require_token(
    State(api): State<RudderApi>,
    ConnectInfo(client): ConnectInfo<SocketAddr>,
    request: Request,
    next: Next,
) -> Response {
    let span = tracing::info_span!(
        "mcp_request",
        %client,
        mcp_method = header(&request, MCP_METHOD_HEADER),
        mcp_name = header(&request, MCP_NAME_HEADER),
        account = field::Empty,
        rights = field::Empty,
    );
    authenticate(&api, request, next).instrument(span).await
}

async fn authenticate(api: &RudderApi, mut request: Request, next: Next) -> Response {
    let start = Instant::now();
    let Some(token) = ApiToken::from_headers(request.headers()) else {
        tracing::info!(rejection = ?TokenRejection::Missing, "MCP request refused");
        return TokenRejection::Missing.into_response();
    };
    let api = api.for_caller(token);
    let account = match check_token(&api).await {
        Ok(account) => account,
        Err(rejection) => {
            tracing::info!(?rejection, "MCP request refused");
            return rejection.into_response();
        }
    };

    let span = Span::current();
    span.record("account", account.id.as_str());
    span.record("rights", account.authorization_type.as_str());
    let account_name = account.name.clone();
    request.extensions_mut().insert(Caller { account, api });
    let response = next.run(request).await;
    tracing::info!(
        account_name,
        http_status = response.status().as_u16(),
        duration_ms = start.elapsed().as_millis(),
        "MCP request"
    );
    response
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
        let account = serde_json::from_str::<TokenAccountResponse>(body)
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
