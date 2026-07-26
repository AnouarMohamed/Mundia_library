# Institutional OIDC identity

The Next.js BFF is an OpenID Connect confidential client. It uses the
authorization-code flow with PKCE, state, and nonce checks. Auth.js validates
the provider response and the BFF creates its existing encrypted, HttpOnly
session. Provider access, refresh, and ID tokens are not copied into that
session and are not stored in PostgreSQL.

## Trust and binding rules

A successful provider callback is admitted only when all of these conditions
hold:

1. The ID token is accepted by Auth.js for the configured client and exact
   HTTPS issuer.
2. PKCE, state, and nonce checks pass.
3. `iss` equals `OIDC_ISSUER` byte-for-byte and `sub` is a bounded,
   control-character-free string.
4. `email_verified` is the boolean `true` and the exact email domain is in
   `OIDC_ALLOWED_EMAIL_DOMAINS`.
5. `(iss, sub)` already exists in `federated_identities`.
6. The mapped local user is still `APPROVED`, and the verified email equals the
   local account email after case normalization.
7. The encrypted session's opaque binding ID still maps to that exact local
   user. Every protected guard reloads the binding, so deleting it rejects the
   session on its next protected request.

Email is a post-binding defense only. It is never queried to discover or
automatically link an account. Role, status, university ID, display name, and
the stable application user ID always come from the local account. Application
authorization guards continue to reload current local role and status.
Only one local user identity per issuer is allowed; issuer migrations can
temporarily provision one identity for each distinct issuer.

BFF sessions have an eight-hour absolute maximum. Local password and
institutional sessions carry an explicit authentication-method marker; sessions
issued before this control are rejected. Rotate the Auth.js secret during the
production cutover to invalidate every pre-migration cookie deterministically.

## Provider registration

Register a confidential web client with exactly:

- Redirect URI:
  `https://<library-host>/api/auth/callback/institutional-oidc`
- Authorization flow: authorization code
- Scopes: `openid profile email`
- Client authentication: the method required by the selected provider
- Logout URI: select and verify during the real provider integration

Set `OIDC_ISSUER` to the issuer published by discovery without trimming,
lowercasing, adding, or removing a trailing slash. Set a separate client and
secret per environment. Store the secret in the platform secret manager.

## Provision and revoke a mapping

There is deliberately no public provisioning endpoint. An operator needs
database connectivity, the deployed OIDC configuration, an existing approved
administrator ID, the target local user ID, the target's expected email, the
provider's exact subject, and a business reason.

After applying migration `0011_add_federated_identities`:

Place the exact subject in a regular file owned by the operator with mode
`0600`. A secret-manager file mount is preferred. The CLI refuses symlinks,
non-owner files, and files readable by group or other users. This keeps the
stable subject out of argv, process listings, npm command output, and normal
shell history.

```bash
npm run auth:identity -- provision \
  --subject-file '/run/secrets/exact-idp-subject' \
  --user-id '<local-user-uuid>' \
  --expected-email 'person@allowed.example.edu' \
  --actor-user-id '<approved-admin-uuid>' \
  --reason 'Approved institutional identity enrollment' \
  --confirm-protected-tier
```

Revoke the same binding with:

```bash
npm run auth:identity -- revoke \
  --subject-file '/run/secrets/exact-idp-subject' \
  --user-id '<local-user-uuid>' \
  --expected-email 'person@allowed.example.edu' \
  --actor-user-id '<approved-admin-uuid>' \
  --reason 'Institutional access revoked by identity operations' \
  --confirm-protected-tier
```

The confirmation flag is mandatory in staging and production. Provision and
revoke occur in the same transaction as an append-only audit event. Audit
details contain a SHA-256 digest of the subject, the business reason, and an
explicit outcome, not the raw subject. Repeating an already-completed operation
is idempotent and still creates an audit event.

`--confirm-protected-tier` is only an accident-prevention acknowledgement. It
is not authentication, MFA, dual control, or authorization. The database
connection identity, protected operations runner, IdP privileged MFA, reviewed
change approval, and independent audit controls remain mandatory release gates.

Use a protected operations runner or short-lived administrative workstation
with a least-privilege database role. Do not paste subjects into tickets,
shared shell history, logs, or CI output.

## Release verification

Before enabling the provider in production:

- Verify discovery, JWKS rotation, client authentication, exact redirect URI,
  issuer and audience behavior against the real tenant.
- Exercise valid patron/admin login plus wrong issuer, wrong audience, invalid
  signature, expired/not-yet-valid token, missing/wrong state and nonce, code
  replay, unverified email, disallowed domain, unknown tuple, revoked tuple,
  suspended user, and local-email mismatch.
- Verify privileged MFA and recovery policy at the identity provider.
- Inspect browser storage, logs, traces, HTML, and PostgreSQL to prove provider
  tokens are absent.
- Test provider logout/session-revocation behavior and document the bounded
  invalidation time.

These provider-dependent checks cannot be completed with repository-only unit
tests; they are release blockers until a real non-production tenant and client
are supplied.
