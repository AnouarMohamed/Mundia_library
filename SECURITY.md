# Security Policy

## Supported Versions

Security fixes are expected to target the active `main` branch first.

## Reporting a Vulnerability

Please do not open public issues for suspected vulnerabilities.

Report security issues privately through GitHub Security Advisories:

https://github.com/AnouarMohamed/Mundia_library/security/advisories/new

Include:

- Affected route, workflow, or dependency
- Reproduction steps or proof of concept
- Expected impact
- Suggested mitigation, if known

The maintainer should acknowledge valid vulnerability reports within 3 business
days, share remediation status as it becomes available, and coordinate public
disclosure after a fix is released or within 90 days when a longer timeline is
required.

## Automated Protection

This repository uses:

- CodeQL for JavaScript/TypeScript static analysis
- Gitleaks for secret detection
- Dependency Review for risky dependency changes
- Dependabot for npm, GitHub Actions, and Docker update PRs
- npm audit for critical production dependency advisories
- Trivy for Docker image vulnerability scanning
- OpenSSF Scorecard for supply-chain posture checks

High-severity dependency advisories should be reviewed quickly and either patched, mitigated, or documented with a clear follow-up plan.
