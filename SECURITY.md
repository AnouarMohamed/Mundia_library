# Security Policy

Mundiapolis Library handles account data, university identity information, borrow history, admin actions, and operational exports. Treat security reports and production secrets seriously.

## Supported Versions

Security fixes target the active `main` branch first. If a release branch is still deployed in production, backport only the minimal fix needed to protect users.

## Reporting A Vulnerability

Do not open public issues for suspected vulnerabilities.

Report security issues privately through GitHub Security Advisories:

https://github.com/AnouarMohamed/Mundia_library/security/advisories/new

Include:

- Affected route, workflow, dependency, or deployment path.
- Reproduction steps or proof of concept.
- Expected impact.
- Whether data exposure, privilege escalation, account takeover, or service disruption is possible.
- Suggested mitigation, if known.

The maintainer should acknowledge valid reports within 3 business days, share remediation status as it becomes available, and coordinate public disclosure after a fix is released or within 90 days when a longer timeline is required.

## Sensitive Data

Treat these as sensitive:

- Password hashes.
- Session secrets.
- Database URLs.
- Upstash tokens.
- ImageKit private keys.
- Email provider tokens.
- University card images or references.
- Borrow history.
- Admin exports.
- Audit log details.

Do not put sensitive values in Markdown, screenshots, issue text, PR descriptions, logs, or release archives.

## Production Security Controls

The app uses:

- NextAuth credentials auth.
- JWT sessions with user role claims.
- Admin route middleware for `/admin/*`.
- Server-side admin route guard for admin APIs.
- User-owned data checks for borrow records.
- Upstash-backed rate limiting for public API routes.
- Soft-delete style catalog visibility through `books.isActive`.
- CI security checks for static analysis, secrets, dependencies, npm audit, containers, and supply-chain posture.

## Automated Protection

This repository uses:

- CodeQL for JavaScript/TypeScript static analysis.
- Secret scanning for committed credentials.
- Dependency Review for risky dependency changes.
- Dependabot for npm, GitHub Actions, and Docker update PRs.
- npm audit for production dependency advisories.
- Trivy for Docker image vulnerability scanning.
- OpenSSF Scorecard for supply-chain posture checks.

High-severity dependency advisories should be reviewed quickly and either patched, mitigated, or documented with a clear follow-up plan.

## Vulnerability Triage

| Severity | Examples | Target response |
| --- | --- | --- |
| Critical | Remote code execution, exposed production secrets, account takeover, admin privilege bypass, private data leak | Immediate triage and mitigation. |
| High | Cross-user data access, stored XSS, auth bypass for protected workflows, destructive admin action without guard | Same business day. |
| Medium | Rate-limit bypass, reflected XSS with limited scope, sensitive error disclosure | Within 3 business days. |
| Low | Missing security header, low-risk dependency advisory, hardening suggestion | Normal backlog. |

## Secret Rotation

Rotate a secret immediately if it was:

- Committed to Git.
- Printed in CI logs.
- Included in a release archive.
- Shared in chat or issue text.
- Exposed through a client bundle.

After rotation:

1. Revoke the old value at the provider.
2. Update Vercel or deployment environment.
3. Update GitHub Actions secrets if needed.
4. Redeploy.
5. Confirm the old value no longer works.
6. Document the incident privately.

## Security Review Checklist For PRs

- Does the change add a new route handler or server action?
- Is authentication required where needed?
- Is authorization enforced server-side?
- Can a non-admin access admin data?
- Can a user request another user's borrow records?
- Are inputs validated and pagination limits clamped?
- Are secrets kept server-only?
- Does the response expose passwords, tokens, internal errors, or private user data?
- Are admin exports handled as sensitive files?
- Does the change require a new environment variable?
- Does the change require docs or runbook updates?

## Known Hardening Priorities

- Replace salted SHA-256 password hashing with Argon2id or bcrypt.
- Add explicit audit coverage for all privileged admin mutations.
- Add end-to-end tests for auth and authorization boundaries.
- Add a health endpoint that does not expose sensitive internals.
- Consolidate email and workflow ownership to reduce duplicated secret handling.
