//! Authentication: every request carries a Rudder API token, checked with Rudder.

use axum::{
    extract::{Request, State},
    http::{HeaderMap, Method, StatusCode, header::AUTHORIZATION},
    middleware::Next,
    response::{IntoResponse, Response},
};
use secrecy::SecretString;
use serde::Deserialize;
use tracing::Instrument;

use crate::RudderApi;

/// Returns the account of the calling token. A valid token gets 403 when its ACL does not allow
/// reading its own account: such tokens are refused, every request must be attributable
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
        let token = headers
            .get(AUTHORIZATION)?
            .to_str()
            .ok()?
            .strip_prefix("Bearer ")?
            .trim();
        (!token.is_empty()).then(|| Self(token.into()))
    }
}

/// Rights of an API account on the Rudder API
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AuthorizationType {
    /// `GET` on all APIs
    Ro,
    /// All verbs on all APIs
    Rw,
    /// Per-path rights (api-authorization plugin)
    Acl,
}

impl AuthorizationType {
    fn as_str(self) -> &'static str {
        match self {
            Self::Ro => "ro",
            Self::Rw => "rw",
            Self::Acl => "acl",
        }
    }

    pub fn describe(self) -> &'static str {
        match self {
            Self::Ro => "read-only: GET on all APIs",
            Self::Rw => "read-write: all verbs on all APIs",
            Self::Acl => "per-path access control list (api-authorization plugin)",
        }
    }
}

/// The Rudder API account behind the caller's token. Every accepted request has one.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ApiAccount {
    pub id: String,
    pub name: String,
    pub authorization_type: Option<AuthorizationType>,
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

/// Checks the token with Rudder
async fn check_token(api: &RudderApi, token: &ApiToken) -> Result<ApiAccount, TokenRejection> {
    let response = api
        .send(token, Method::GET, TOKEN_CHECK_PATH, &[])
        .await
        .map_err(|e| {
            tracing::warn!("token check: {e}");
            TokenRejection::Unverifiable
        })?;
    match response.status() {
        status if status.is_success() => {
            // A 2xx without an account means the API changed: nobody to attribute the request to
            let body = response.text().await.map_err(|e| {
                tracing::warn!("token check: could not read the account response: {e}");
                TokenRejection::Unverifiable
            })?;
            serde_json::from_str::<TokenAccountResponse>(&body)
                .map_err(|e| e.to_string())
                .and_then(|parsed| {
                    parsed
                        .data
                        .accounts
                        .into_iter()
                        .next()
                        .ok_or_else(|| "no account".to_owned())
                })
                .map_err(|e| {
                    tracing::warn!("token check: unexpected account response: {e}");
                    TokenRejection::Unverifiable
                })
        }
        StatusCode::FORBIDDEN => Err(TokenRejection::Unidentified),
        StatusCode::UNAUTHORIZED => Err(TokenRejection::Rejected),
        // Fail closed: a token that cannot be checked is not accepted
        status => {
            tracing::warn!("token check: Rudder API returned {status}");
            Err(TokenRejection::Unverifiable)
        }
    }
}

/// Rejects requests without a token Rudder accepts and attributes to an account, before they reach
/// MCP (local tools included), hands the token and the account to the tools, and logs each request
/// with its account.
///
/// Costs one Rudder API call per MCP request.
pub async fn require_token(
    State(api): State<RudderApi>,
    mut request: Request,
    next: Next,
) -> Response {
    let Some(token) = ApiToken::from_headers(request.headers()) else {
        return TokenRejection::Missing.into_response();
    };
    let account = match check_token(&api, &token).await {
        Ok(account) => account,
        Err(rejection) => {
            tracing::info!(?rejection, "MCP request refused");
            return rejection.into_response();
        }
    };

    // Scoped: a `&Request` held across the `.await` below would make this future `!Send`
    let (method, name) = {
        let header = |name| {
            request
                .headers()
                .get(name)
                .and_then(|v| v.to_str().ok())
                .unwrap_or("-")
                .to_owned()
        };
        (header(MCP_METHOD_HEADER), header(MCP_NAME_HEADER))
    };
    // Every log line of the request, tools included, carries the account id. rmcp passes the
    // current span on to the tasks it spawns for the request.
    let span = tracing::info_span!("request", account = account.id);
    let account_name = account.name.clone();
    let rights = account
        .authorization_type
        .map_or("-", AuthorizationType::as_str);
    request.extensions_mut().insert(token);
    request.extensions_mut().insert(account);
    async move {
        let response = next.run(request).await;
        tracing::info!(
            account_name,
            rights,
            mcp_method = method,
            mcp_name = name,
            http_status = response.status().as_u16(),
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
    }

    #[test]
    fn no_token_without_valid_bearer_header() {
        assert_eq!(token(None), None);
        assert_eq!(token(Some("Basic dXNlcjpwYXNz")), None);
        assert_eq!(token(Some("Bearer ")), None);
        assert_eq!(token(Some("Bearer    ")), None);
        assert_eq!(token(Some("bearer abc123")), None);
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
        assert_eq!(account.authorization_type, Some(AuthorizationType::Ro));
    }
}
