// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

//! Password complexity policy
//!
//! We never display the values of the passwords.

use anyhow::{Result, anyhow};
use secrecy::{ExposeSecret, SecretString};
use zxcvbn::{Score, feedback::Feedback, zxcvbn};

/// Password complexity policy.
///
/// The default value should be considered safe for most use cases.
pub enum PasswordPolicy {
    /// Scores are between zero and four.
    ///
    /// * <https://github.com/dropbox/zxcvbn>
    /// * <https://www.usenix.org/conference/usenixsecurity16/technical-sessions/presentation/wheeler>
    ///
    /// Any score less than 3 should be considered too weak.
    MinScore(Score),
    /// Minimum length of the password and different character classes.
    ///
    /// TLUDS:
    /// total length, lowercase, uppercase, digits, special characters
    CharsCriteria(u8, u8, u8, u8, u8),
}

impl PasswordPolicy {
    fn format_feedback(feedback: &Feedback) -> String {
        feedback
            .warning()
            .map(|w| w.to_string())
            .unwrap_or("".to_string())
            .to_string()
    }

    pub fn check(&self, password: SecretString) -> Result<String> {
        match self {
            PasswordPolicy::MinScore(min_score) => {
                let entropy = zxcvbn(password.expose_secret(), &[]);
                let score = entropy.score();
                let guesses_log10 = entropy.guesses_log10();
                let guesses_log10_int = guesses_log10.round() as u8;
                if score < *min_score {
                    Err(anyhow!(
                        "The password is too weak: score {score} < {min_score} (requires ~10^{guesses_log10_int} guesses). {}",
                        entropy
                            .feedback()
                            .map(Self::format_feedback)
                            .unwrap_or("".to_string())
                    ))
                } else {
                    Ok(format!(
                        "The password is strong enough: score {score} >= {min_score} (requires ~10^{guesses_log10_int} guesses)"
                    ))
                }
            }
            PasswordPolicy::CharsCriteria(t, l, u, d, s) => {
                let mut errors = vec![];

                let password_len = password.expose_secret().len();
                if password_len < *t as usize {
                    errors.push(format!("it is too short: {password_len} < {t}"));
                }
                if *l > 0u8 {
                    let l_count = password
                        .expose_secret()
                        .chars()
                        .filter(|c| c.is_lowercase())
                        .count();
                    if l_count < *l as usize {
                        errors.push(format!(
                            "it does not contain enough lowercase characters: {l_count} < {l}"
                        ));
                    }
                }
                if *u > 0u8 {
                    let u_count = password
                        .expose_secret()
                        .chars()
                        .filter(|c| c.is_uppercase())
                        .count();
                    if u_count < *u as usize {
                        errors.push(format!(
                            "it does not contain enough uppercase characters: {u_count} < {u}"
                        ));
                    }
                }
                if *d > 0u8 {
                    let d_count = password
                        .expose_secret()
                        .chars()
                        .filter(|c| c.is_ascii_digit())
                        .count();
                    if d_count < *d as usize {
                        errors.push(format!(
                            "it does not contain enough digits: {d_count} < {d}"
                        ));
                    }
                }
                if *s > 0u8 {
                    let s_count = password
                        .expose_secret()
                        .chars()
                        .filter(|c| !c.is_alphanumeric())
                        .count();
                    if s_count < *s as usize {
                        errors.push(format!(
                            "it does not contain enough special characters: {s_count} < {s}"
                        ));
                    }
                }

                if errors.is_empty() {
                    Ok("The password is strong enough".to_string())
                } else {
                    Err(anyhow!("The password is too weak: {}", errors.join(", ")))
                }
            }
        }
    }
}

impl Default for PasswordPolicy {
    fn default() -> Self {
        PasswordPolicy::MinScore(Score::Three)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_password_policy_feedback_format() {
        let e = zxcvbn("password", &[]);
        let f = e.feedback().unwrap();

        assert_eq!(
            PasswordPolicy::format_feedback(f),
            "This is a top-10 common password.".to_string()
        );
    }

    #[test]
    fn test_password_policy_score() {
        let p = PasswordPolicy::MinScore(Score::Three);
        assert_eq!(
            p.check("password".into())
                .err()
                .unwrap()
                .to_string(),
            "The password is too weak: score 0 < 3 (requires ~10^0 guesses). This is a top-10 common password.".to_string()
        );
    }
}
