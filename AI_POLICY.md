# Responsible use of AI agents and LLMs when contributing to Rudder

This policy governs the use of AI coding agents and large language models (LLMs) when
producing any contribution to the Rudder code base. It applies to all the Rudder source
repositories

It is a policy for **humans**. The companion agent-facing guidance (coding conventions an
agent should follow) lives in `AGENTS.md` and in `.claude/skills/`.

## Summary

You may use AI agents as *assistants*. You may not let them author contributions you do
not understand and stand behind. **A human is always responsible** — for every line
contributed and for every review and the ongoing maintenance that follows. That
responsibility is never delegable to a tool. You must **disclose** any such use *traceably*
— the exact tool, model, and version, recorded in the git history so the contribution can
be found and audited later — keep the agent **confined** (no access to secrets, no direct
access to GitHub, nothing done in your name), and you remain fully accountable for what
you propose.

## Rules

1. **No "vibe coding" on the Rudder code base (default rule).**
   "Vibe coding" — generating code with an agent/LLM and proposing it without fully
   reading, understanding, and validating it yourself — is *forbidden* by default for
   contributions to Rudder. You must comprehend every line you submit as if you had
   written it by hand, and be able to explain *what* it changes and *why*, in your own
   words, in review — without going back to an agent to answer for you.

2. **Agents are welcome as assistants.**
   Using an AI agent or LLM to help you write, refactor, explore, explain, or test code
   is fine. The tool assists; it does not author. The bar is the same as for any code:
   you understand it, it meets our conventions, and it is correct.

3. **Privacy, secrets, and confinement — keep the agent on a short leash.**
   Most AI agents send their context to a third-party service, and many can run commands,
   read your whole filesystem, and reach the network. Treat *what an agent can see and do*
   as a first-class security and privacy concern, not an afterthought. Agent usage **must
   be confined** so that it cannot, by construction:
   - **Access secrets or sensitive data.** No credentials, API tokens, SSH/GPG private
     keys, `.env` files, production or customer configuration, inventories, support data,
     or any personal data in the agent's reach or context. Run agents in a sandbox scoped
     to the source you're working on, with secrets excluded — never point one at a real
     Rudder server, a production database, or a directory containing credentials.
   - **Have direct access to GitHub (or any forge / CI / infrastructure).** Agents must
     not push, open or merge pull requests, comment, approve, manage issues, cut releases,
     or drive CI/CD on their own. They produce changes *locally*; a human reviews the diff
     and performs every forge and infrastructure action themselves.
   - **Act "in the name of" a human.** An agent must never operate under your identity,
     tokens, or credentials, or otherwise speak or act as you — no posting, reviewing,
     approving, or communicating that appears to come from you. Every outward,
     attributable, or irreversible action (commit authorship, push, PR, review, merge,
     release, chat) is taken by a person who owns it.

   Independently of the IP question below, do **not** paste non-public Rudder source,
   proprietary customer configurations, support tickets, or personal data into a
   third-party hosted AI tool unless an agreement explicitly forbids training on, and
   retaining, that content. When unsure whether something is safe to share with a tool,
   assume it is not.

4. **Disclose and trace every agent/LLM use — durably and precisely.**
   *Any* — really any — use of an agent or LLM to produce content that goes into a Rudder
   PR (code, docs, config, tests, scripts) must be disclosed. The point is *traceability*:
   we must be able, at any later date, to find every contribution produced with a given
   tool, model, or version (see "Why traceability matters" below). So the disclosure must
   be **specific** and **durable**, recording for each AI-assisted change:
   - the **agent / tool** name *and its version* (e.g. `Claude Code 2.0.1`, `Cursor 0.42`,
     `GitHub Copilot`);
   - the **model** identifier *including its version* (e.g. `claude-opus-4-8`, `gpt-5.1`)
     — "some AI" or a bare vendor name is *not* a disclosure; if you don't know the exact
     model/version, find out before you commit;
   - briefly, *what* it assisted with, when not obvious from the diff.

   Record this in **commit trailers** on the relevant commits. The git history is the
   durable, queryable record — a PR description is not (it is not preserved in history and
   is easily lost), so a PR note is welcome only *in addition* to the trailers. Example:

   ```
   Assisted-by: Claude Code 2.0.1 (model: claude-opus-4-8)
   Assisted-by: GitHub Copilot (model: gpt-5.1)
   ```

   Keep trailers per-commit so the attribution stays attached to the exact changes the
   tool touched (an agent used on only part of a PR must not be implied across the rest).
   Authorship and DCO-style trailers (`Signed-off-by:`, `Co-authored-by:`) refer to
   **humans only**; an AI tool is never listed as an author or co-author.

5. **Write in your own words.**
   PR descriptions, issue reports, and replies to review feedback must reflect *your*
   understanding, not a raw LLM dump. A machine-generated PR body disconnected from the
   actual diff will be treated as if no description had been provided. (LLM-assisted
   translation for non-native English speakers is welcome — just say so.)

6. **Keep contributions focused and clean.**
   One logical change per PR. Agents tend to "helpfully" touch unrelated files
   (reformatting, import reordering, adjacent refactors) — strip those out before
   submitting. Split large changes into reviewable, logically ordered commits rather than
   a single agent dump. Do not commit agent *session* artefacts (transient logs, scratch
   configs); deliberate, reviewed additions under `.claude/skills/` are of course fine.

7. **Test what you change.**
   AI-assisted changes must build, pass the existing test suite, and ship tests for new
   behaviour — written or reviewed with the same scrutiny as the production code. "The
   agent says it added tests" is not enough; confirm the tests actually exercise the
   intended behaviour and would fail without the change.

8. **All code is reviewed by humans, and a human maintains it.**
   Any code going into Rudder must be reviewed by human maintainers. AI assistance never
   replaces human review, on either side of the PR. A maintainer's approval is a
   *personal* attestation by a human and is not delegable to a tool. Responsibility does
   not end at merge: a human owns the ongoing maintenance of the code (fixes, up-merges,
   regressions), so don't contribute code that no human is prepared to maintain.

9. **A human owns what you propose.**
   Responsibility lies with the *human* contributor opening the PR. If you propose code,
   you — a person — own it: its correctness, quality, security, and licensing, *whatever
   means you used to produce it*. "The AI wrote it" is never an excuse, and the
   responsibility can never be shifted to or shared with a tool. Given that Rudder manages
   server configuration, patch management, and compliance, take particular care with the
   things that have an outsized blast radius here: idempotency of techniques, backward
   compatibility of the API and relay protocol, behaviour at scale on large inventories,
   and security implications.

10. **You must own the IP, and sign the CLA.**
    You must own (or have the right to contribute) the intellectual property of all code
    you propose to Rudder, and you must sign the
    [CLA/CCLA](https://www.rudder.io/en/expand/contribute/) in any case (see also
    `CONTRIBUTING.md`). Do not submit code whose provenance or license you cannot vouch
    for — this includes AI-generated code you cannot account for. In particular:
    - AI tools may reproduce *memorised snippets* from their training data, possibly under
      a license incompatible with Rudder's, or near-verbatim copies of third-party code.
      If a tool emits a block that looks copied (unusual specificity, foreign comments, a
      stray license header), do not include it — rewrite or remove it.
    - Do not paste *proprietary customer configurations, support tickets, or non-public
      Rudder source* into third-party hosted AI tools unless the tool is covered by an
      agreement that prohibits training on submitted content. This is a confidentiality
      obligation, independent of the open-source IP question.
    - Be aware of your tool vendor's terms: some assert rights over generated output that
      are incompatible with redistribution under our license. If in doubt, ask before
      submitting.

11. **Maintainers may refuse any PR.**
    Rudder maintainers are free to refuse any contribution, for any reason, including
    concerns about undisclosed or irresponsible AI use, scope creep, un-reviewable size,
    or a contributor who cannot explain their own diff.

## Why traceability matters

Disclosure is not box-ticking — it builds a *durable audit trail* we may need to act on
years later, when a problem surfaces that is tied to a specific tool, model, or version.
Recording the agent and model (with versions) per commit lets us, after the fact:

- **Trace IP / licensing issues.** If a model is later found to reproduce code under an
  incompatible license, we can locate every commit produced with that model/version and
  assess or remediate the affected code.
- **Respond to security / supply-chain concerns.** If a tool or model version is
  implicated in introducing subtle defects, vulnerabilities, or a deliberate backdoor, we
  can find and re-audit exactly the contributions it touched, instead of having to suspect
  the whole code base.
- **Investigate anything else, later.** Whatever the future concern — a model recall, a
  vendor terms change, a quality regression linked to a tool — we can only review what we
  can *find*. Untraceable AI contributions can't be reviewed in hindsight; that is why the
  trail must live, precisely, in the git history.

Treat the disclosure trail as a long-lived safety record: write it as if someone will
query it in five years to answer "which of our code came from *that* tool/model/version?"

## Why these rules

Rudder is security and compliance software that our users trust to manage their
infrastructure. Untraceable, unreviewed, or not-understood code is a risk to them and to
the project. These rules keep contributions accountable, legally clean, and worthy of
that trust — while still letting contributors benefit from good tooling.

## This policy can change without notice

The AI tooling landscape — the capabilities, the legal and licensing context, the
security and privacy implications, the tools and models themselves — is extremely
dynamic. Accordingly, **this policy may change at any time, without prior notice**, as we
react to new developments. Always refer to the current version in the repository, and
re-read it before relying on it; what was acceptable under a previous version is not
guaranteed to remain so.

See also: `CONTRIBUTING.md`.
