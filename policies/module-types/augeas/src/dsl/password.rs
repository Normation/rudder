// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

//! Password complexity policy
//!
//! We never display the values of the passwords.

use anyhow::Result;
use zxcvbn::feedback::Feedback;
use zxcvbn::{zxcvbn, Score};

/// Password complexity policy.
///
/// The default value should be considered safe for most use cases.
pub enum PasswordPolicy {
    /// Scores are between zero and four.
    ///
    /// * https://github.com/dropbox/zxcvbn
    /// * https://www.usenix.org/conference/usenixsecurity16/technical-sessions/presentation/wheeler
    ///
    /// Any score less than 3 should be considered too weak.
    Score(Score),
    /// Minimum length of the password and different character classes.
    ///
    /// TLUDS:
    /// total length, lowercase, uppercase, digits, special characters
    Criteria(u8, u8, u8, u8, u8),
}

impl PasswordPolicy {
    fn format_feedback(feedback: &Feedback) -> String {
        format!(
            "{} {}",
            feedback
                .warning()
                .map(|w| w.to_string())
                .unwrap_or("".to_string()),
            feedback
                .suggestions()
                .iter()
                .map(ToString::to_string)
                .collect::<Vec<String>>()
                .join(" ")
        )
    }

    pub fn check(&self, password: &str) -> Result<()> {
        match self {
            PasswordPolicy::Score(s) => {
                let entropy = zxcvbn(password, &[]);
                if entropy.score() < *s {
                    let feedback = entropy.feedback();
                    let message = format!(
                        "The score is too low: {} < {}. {}",
                        entropy.score(),
                        s,
                        feedback
                            .map(Self::format_feedback)
                            .unwrap_or("".to_string())
                    );
                    return Err(anyhow::anyhow!(message));
                }
            }
            PasswordPolicy::Criteria(t, l, u, d, s) => {
                if password.len() < *t as usize {
                    return Err(anyhow::anyhow!(
                        "The password is too short: {} < {}",
                        password.len(),
                        t
                    ));
                }
                if *l > 0u8 {
                    let l_count = password.chars().filter(|c| c.is_lowercase()).count();
                    if l_count < *l as usize {
                        return Err(anyhow::anyhow!(
                            "The password does not contain enough lowercase characters: {} < {}",
                            l_count,
                            l
                        ));
                    }
                }
                if *u > 0u8 {
                    let u_count = password.chars().filter(|c| c.is_uppercase()).count();
                    if u_count < *u as usize {
                        return Err(anyhow::anyhow!(
                            "The password does not contain enough uppercase characters: {} < {}",
                            u_count,
                            u
                        ));
                    }
                }
                if *d > 0u8 {
                    let d_count = password.chars().filter(|c| c.is_ascii_digit()).count();
                    if d_count < *d as usize {
                        return Err(anyhow::anyhow!(
                            "The password does not contain enough digits: {} < {}",
                            d_count,
                            d
                        ));
                    }
                }
                if *s > 0u8 {
                    let s_count = password.chars().filter(|c| !c.is_alphanumeric()).count();
                    if s_count < *s as usize {
                        return Err(anyhow::anyhow!(
                            "The password does not contain enough special characters: {} < {}",
                            s_count,
                            s
                        ));
                    }
                }
            }
        }
        Ok(())
    }
}

impl Default for PasswordPolicy {
    fn default() -> Self {
        PasswordPolicy::Score(Score::Three)
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
            "This is a top-10 common password. Add another word or two. Uncommon words are better."
                .to_string()
        );
    }

    #[test]
    fn test_password_policy_score() {
        let p = PasswordPolicy::Score(Score::Three);
        assert_eq!(
            p.check("password").err().unwrap().to_string(),
            "The score is too low: 0 < 3. This is a top-10 common password. Add another word or two. Uncommon words are better.".to_string()
        );
    }
}
