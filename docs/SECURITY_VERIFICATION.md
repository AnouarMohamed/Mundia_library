# Mundiapolis Library security verification standard

| Field                | Value                                                                                                                                                                       |
| -------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Status               | Mandatory release and operating standard; evidence is not yet complete                                                                                                      |
| Standard version     | 1.0, 2026-07-26                                                                                                                                                             |
| ASVS baseline        | OWASP ASVS 5.0.0 Level 2 for every production component                                                                                                                     |
| Enhanced assurance   | Applicable ASVS Level 3 requirements for privileged identity/access, identity evidence, personal data, fines, audit, cryptographic key management, deployment, and recovery |
| Threat model         | [THREAT_MODEL.md](./THREAT_MODEL.md)                                                                                                                                        |
| Architecture program | [PRODUCTION_OVERHAUL.md](./PRODUCTION_OVERHAUL.md)                                                                                                                          |

## Security claim

This document defines what must be tested, what evidence must be retained, and
what blocks release. It does not certify the product and it does not promise
“zero vulnerabilities.”

No finite checklist, automated scan, code review, ASVS assessment, or
penetration test can prove that every vulnerability is absent. OWASP does not
certify applications or vendors against ASVS. A defensible release claim is
therefore narrower:

> For the exact release digest and production configuration in scope, every
> applicable ASVS 5.0.0 requirement has a recorded disposition, required tests
> passed, no known critical or high vulnerability remains open, independent
> testing is complete, and operations can detect, contain, recover, and learn
> from failures.

Any public or contractual security statement must name the assessed build,
scope, ASVS version and level, exclusions, assessment date, assessors, and
evidence period. “ASVS compliant,” “secure,” “fully tested,” and similar
unqualified claims are prohibited.

## Scope and assurance target

The verification scope includes:

- The Next.js browser application/BFF, route handlers, server actions, and
  middleware.
- Every Kotlin/Spring service and its API, database, migrations, event
  producers/consumers, and container.
- Managed OIDC tenant/client policy and BFF integration.
- Membership, identity-document processing, catalog/reviews, circulation/fines,
  notifications, discovery/recommendations, exports, admin/support, and audit.
- Edge/CDN/WAF, DNS, TLS, ingress/proxy configuration, Redis, PostgreSQL,
  broker, object storage, search, email/workflow providers, and backups.
- Source control, CI runners, dependencies, artifact registry, build
  provenance, signing, Terraform/Helm/GitOps, Kubernetes, cloud IAM, secrets,
  telemetry, incident response, and disaster recovery.
- Legacy-to-service backfill, shadowing, cutover, rollback, and retirement.

ASVS Level 2 is the minimum production baseline. Apply the relevant Level 3
requirements to:

- Authentication, account recovery, session handling, privileged roles, and
  service/workload identity.
- University identity evidence and its storage, review, retention, deletion,
  backup, and audit.
- Fine ledger changes, sensitive exports, high-impact bulk operations, and
  break-glass access.
- Audit integrity, secrets, cryptographic key management, build/deployment
  identity, Kubernetes/cloud control planes, and recovery.

An assessor cannot reduce the target because implementation is inconvenient.
An item may be not applicable only when the functionality or data flow truly
does not exist, with a written rationale and architecture evidence.

## Normative sources and identifier policy

The canonical baseline is the stable
[OWASP ASVS 5.0.0 release](https://github.com/OWASP/ASVS/tree/v5.0.0_release/5.0/en),
published in May 2025. Use identifiers in the form
`v5.0.0-<chapter>.<section>.<requirement>` in the detailed tracking export.
Do not map against the moving `master` branch.

OWASP's
[assessment guidance](https://github.com/OWASP/ASVS/blob/v5.0.0_release/5.0/en/0x04-Assessment_and_Certification.md)
requires a stated scope, disposition of every requirement, rationale for
exceptions and not-applicable items, and repeatable test methods. Level 2 and
Level 3 assessment requires documentation, source, configuration, and people
access. Automation alone is insufficient, particularly for business logic and
authorization.

This document is the repo-specific verification overlay. It does not reproduce
the canonical ASVS requirements. The release evidence package must include a
machine-readable ASVS 5.0.0 CSV or JSON export in which every applicable exact
requirement ID is dispositioned.

## Evidence rules

### Allowed control states

| State                     | Meaning                                                                                  | Release treatment                             |
| ------------------------- | ---------------------------------------------------------------------------------------- | --------------------------------------------- |
| Not implemented           | The control is absent                                                                    | Block if applicable                           |
| Implemented, not verified | Code/config appears to exist but required evidence is missing or stale                   | Block                                         |
| Verified                  | Repeatable test passed for the exact build/configuration and evidence is retained        | Pass until evidence expires or scope changes  |
| Failed                    | Verification found a defect                                                              | Block according to severity; create a finding |
| Accepted exception        | A medium or low risk has approved, expiring acceptance and a tested compensating control | Conditional pass                              |
| Not applicable            | Functionality/data flow does not exist and rationale is approved                         | Pass only for the stated scope                |

“CI green,” a code comment, a design document, or a tool showing no findings is
not by itself evidence that a security outcome holds.

### Evidence record

Each ASVS requirement and repo-specific test records:

| Field              | Required content                                                                                |
| ------------------ | ----------------------------------------------------------------------------------------------- |
| Control/test ID    | Exact ASVS 5.0.0 ID and/or verification ID from this document                                   |
| Scope              | Service, route/action, API operation, event, job, image digest, environment, and data class     |
| Build identity     | Git commit and immutable OCI/artifact digest                                                    |
| Control owner      | Named team and accountable person                                                               |
| Applicability      | Applicable level, or approved not-applicable rationale                                          |
| Method             | Source review, config review, automated test, manual test, dynamic test, interview, or combined |
| Procedure          | Repeatable commands/steps, identities, inputs, preconditions, and expected result               |
| Environment        | Production-equivalent configuration and relevant provider/cluster version                       |
| Result             | Pass/fail with raw output or report link                                                        |
| Evidence integrity | Artifact URI, content hash, creation time, tool/version, and retention class                    |
| Finding            | Linked finding, severity, exploitability, affected builds, and remediation                      |
| Exception          | Approvers, compensating control, due/expiry date, and retest                                    |
| Freshness          | Evidence date and invalidation trigger                                                          |

Evidence must be readable by authorized auditors after a staff or vendor change.
Secrets, raw identity documents, bearer URLs, production tokens, and unnecessary
personal data must not be embedded in evidence.

### Evidence freshness

| Evidence                                                                      | Maximum age, unless invalidated earlier                 |
| ----------------------------------------------------------------------------- | ------------------------------------------------------- |
| Source/build tests, SAST, SCA, SBOM, provenance, image and IaC scan           | Exact commit/image only                                 |
| Production configuration, TLS, headers, CORS, ingress, IAM and policy capture | 90 days                                                 |
| Privileged and service-account access review                                  | 90 days                                                 |
| Restore, key rotation, secret rotation, outbox replay, DLQ replay             | 180 days                                                |
| Independent penetration test                                                  | 12 months and after material auth/trust-boundary change |
| Threat model and privacy data-flow review                                     | 90 days and after material change                       |
| Incident, ransomware, region-loss, and identity-provider outage exercise      | 12 months                                               |

Evidence expires immediately when its relevant code, dependency, configuration,
provider, data flow, permission, or trust boundary changes.

## ASVS 5.0.0 evidence matrix

The following matrix is a routing summary. The detailed release workbook must
contain every exact applicable ASVS 5.0.0 requirement; a chapter-level pass is
not sufficient.

| ASVS 5.0 area                           | Product applicability and required evidence                                                                                                                                                                                           | Minimum release disposition                                                                                 |
| --------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| V1 Encoding and Sanitization            | Reviews, names, catalog metadata, admin notes, email templates, logs, and exports: contextual output tests; rich-text allowlist if enabled; CSV formula neutralization; log-forging tests; maintained sanitizer inventory             | All Level 2 requirements verified; Level 3 where restricted data reaches complex interpreters               |
| V2 Validation and Business Logic        | Route/action/OpenAPI schemas; bounded input; unexpected-field rejection; circulation/fine state-machine and invariant tests; concurrency, replay, idempotency, quota, and workflow-abuse tests                                        | Level 2 plus applicable Level 3 circulation, fine, export, identity-review, and migration controls verified |
| V3 Web Frontend Security                | Exact-origin mutation coverage; cookie flags; CSP without unsafe production fallbacks; HSTS; framing, MIME, referrer and permissions policy; DOM XSS/open redirect/browser storage/cache tests                                        | Level 2 verified on normal and error responses in production-equivalent ingress                             |
| V4 API and Web Service                  | Complete API/action inventory; OpenAPI validation; deny-by-default auth; object/function/property-level authorization; content types; pagination; rate limits; CORS; webhook signatures/replay; service timeouts and error handling   | Level 2 for all APIs; Level 3 for privileged, restricted-data, and service-to-service operations            |
| V5 File Handling                        | Identity card, cover, video, import, export: pre-parse size caps; magic/decode validation; canonical random keys; quarantine/malware scan; private storage; active-content denial; safe download headers; cleanup and retention tests | Level 3 for identity evidence; Level 2 for all other file flows                                             |
| V6 Authentication                       | OIDC tenant/client config; exact redirect and claim checks; privileged phishing-resistant MFA; recovery; enumeration resistance; rate limits; legacy password/hash retirement; no fixtures/default accounts                           | Level 3 for privileged access and recovery; Level 2 for all users                                           |
| V7 Session Management                   | HttpOnly/Secure/SameSite cookie evidence; rotation; idle/absolute expiry; logout and server-side revocation; post-demotion/suspension invalidation; concurrent session policy; no tokens in browser storage/logs                      | Level 3 for privileged sessions; Level 2 for patrons                                                        |
| V8 Authorization                        | Role/capability matrix; two-user horizontal tests; every role versus every operation; service-side enforcement; ownership; field-level restrictions; last-admin/self/dual-control; document/export access; cache authorization        | Level 3 for staff/admin/restricted data; Level 2 throughout                                                 |
| V9 Self-contained Tokens                | JWT issuer, audience, authorized party, algorithm, signature, time, key rotation, scope/role, token type, replay and cross-service confusion tests; no sensitive claims                                                               | Level 3 for workload/delegated tokens; Level 2 for any remaining application token                          |
| V10 OAuth and OIDC                      | Authorization-code+PKCE, state, nonce, exact redirect, client type, consent/tenant constraints, refresh rotation, logout, key rollover, mix-up and code-replay tests                                                                  | Level 2 baseline; applicable Level 3 for privileged and high-value clients                                  |
| V11 Cryptography                        | Approved algorithm/library inventory; KMS policy; key generation, separation, rotation, revocation and destruction; envelope encryption; entropy; no custom crypto; encrypted backup and identity-document keys                       | Level 3 for restricted data and control-plane keys; Level 2 otherwise                                       |
| V12 Secure Communication                | External/internal TLS, certificate/hostname validation, HSTS, mTLS/workload identity as designed, database/broker/Redis/storage TLS, no downgrade, private endpoints, secure provider callbacks                                       | Level 2 verified; Level 3 where restricted data or privileged control crosses a boundary                    |
| V13 Configuration                       | Production fail-fast; no default credentials/debug; canonical host/trusted proxy; minimal Actuator/metrics; secrets; least-privilege DB roles; container hardening; Kubernetes admission/network/IAM; safe errors                     | Level 2 for all components; Level 3 for production control planes                                           |
| V14 Data Protection                     | Data inventory/classification; minimization; response projection; no-store; private identity evidence; retention/deletion/subject rights; log/analytics redaction; encrypted exports/backups; non-production de-identification        | Level 3 for identity evidence, personal history, fines, audit, exports and backups                          |
| V15 Secure Coding and Architecture      | Threat model; service/data ownership; dependency injection and framework safety; SSRF/unsafe deserialization review; outbox/inbox; single-writer migration; supply-chain controls; secure failure modes                               | Level 2 for all code; applicable Level 3 for trust boundaries and critical architecture                     |
| V16 Security Logging and Error Handling | Structured security events; transactional privileged audit; remote append-only retention; actor/subject/reason/correlation; redaction; generic errors; alert/runbook tests; clock synchronization and gap detection                   | Level 3 for privileged, identity, fine, export, migration and control-plane events                          |
| V17 WebRTC                              | The product currently has no WebRTC flow                                                                                                                                                                                              | Record exact requirements as not applicable with route/dependency evidence; reassess before adding WebRTC   |

## Mandatory verification suites

Each test below requires positive and negative cases, raw evidence, exact build,
and a linked ASVS mapping. “Unit tested” does not replace a production-shaped
integration or dynamic test where a boundary is involved.

### Identity, authentication, and session

| ID         | Required procedure and expected result                                                                                                                                                                         |
| ---------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SV-AUTH-01 | Enumerate all authentication and recovery paths. Invalid email, unknown account, bad password, suspended/rejected account, and throttled attempts return non-enumerating behavior and comparable work.         |
| SV-AUTH-02 | Verify OIDC authorization-code flow with PKCE, `state`, `nonce`, exact redirect URI and tenant/client constraints. Reject code replay, missing/wrong state or nonce, open redirects, and mix-up attempts.      |
| SV-AUTH-03 | Reject tokens with wrong issuer, audience, authorized party, token type or algorithm; invalid signature; expired/not-yet-valid time; missing scope; unknown key; and a key from another tenant.                |
| SV-AUTH-04 | Demonstrate privileged phishing-resistant MFA, step-up for sensitive actions, recovery without help-desk bypass, and alerting on factor/recovery changes.                                                      |
| SV-AUTH-05 | Inspect browser storage, HTML, client bundles, URLs, referrers, logs and telemetry. No access/refresh token, session secret, password, or identity evidence is exposed.                                        |
| SV-AUTH-06 | Demonstrate rotation on sign-in/elevation, idle and absolute expiry, logout revocation, and bounded invalidation after account suspension, role removal, compromise, or key rotation.                          |
| SV-AUTH-07 | Until legacy credentials are retired, verify modern password hashing, lazy legacy rehash, a dummy-hash path, generic errors, account+network throttling, and production denial of public signup/test fixtures. |

### Authorization and object ownership

| ID       | Required procedure and expected result                                                                                                                                                            |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SV-AZ-01 | Generate a route/action/OpenAPI/event-operation inventory and map each item to anonymous, patron, staff capability, service, and support access. Unmapped operations fail the gate.               |
| SV-AZ-02 | With two patrons, replace every user, loan, request, renewal, notification, review and document identifier. Cross-user reads and mutations are denied without disclosing object existence.        |
| SV-AZ-03 | Exercise every staff capability against every admin endpoint and field. The BFF and owning service both deny missing/incorrect capability.                                                        |
| SV-AZ-04 | Attempt mass assignment of role, status, owner, copy, fine, audit, timestamps and internal fields. Unexpected or server-owned properties are rejected or ignored deterministically.               |
| SV-AZ-05 | Verify self-promotion, self-approval, self-demotion, last-admin removal, unapproved role grant, stale-session use, and approval without required second actor all fail.                           |
| SV-AZ-06 | Verify identity-document reads, exports, fine adjustments, bulk actions, and break-glass require step-up, purpose/reason, correct scope, audit, expiration, and where required a second approver. |
| SV-AZ-07 | Verify authorization is not bypassed through caches, search projections, direct service access, event replay, GraphQL/alternate content types if introduced, or the legacy route during cutover.  |

### Browser, API, and integration boundaries

| ID        | Required procedure and expected result                                                                                                                                                                                                                   |
| --------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SV-WEB-01 | For every cookie-authenticated unsafe operation, test missing/null/malformed Origin, cross-origin, same-site sibling origin, forged Host/forwarding headers, and browser simple content types. All unauthorized cross-origin cases fail before mutation. |
| SV-WEB-02 | Verify CORS from an unlisted origin, `null` origin, wildcard/subdomain tricks, unexpected methods/headers, preflight cache, and credentialed requests. Only the exact documented matrix is allowed.                                                      |
| SV-WEB-03 | Test stored/reflected/DOM payloads in every rendered user/admin field, error, email preview, log viewer and export. No payload reaches an executable context.                                                                                            |
| SV-WEB-04 | Capture production and error responses for CSP, HSTS, `nosniff`, framing, referrer, permissions, cache and cookie policy. CSP is enforced with nonces/hashes and no unapproved unsafe directive.                                                         |
| SV-API-01 | Fuzz content type, malformed JSON, duplicate headers/parameters, deep/large objects, Unicode normalization, ranges, pagination, sorting and unexpected properties. Parsing is bounded and errors are generic.                                            |
| SV-API-02 | Verify per-route, account, session and trusted-network rate/concurrency quotas; forged forwarding headers do not create a new identity; Redis failure enters the documented safe mode.                                                                   |
| SV-API-03 | Verify webhooks against altered raw body, wrong key, missing/invalid signature, expired timestamp, replay, duplicate and out-of-order delivery. No effect occurs before authentication.                                                                  |
| SV-API-04 | Test all outbound URL/redirect behavior for private, loopback, link-local, metadata, alternate-IP, DNS-rebinding and redirect-chain targets. Network policy provides an independent denial.                                                              |

### Files and identity evidence

| ID         | Required procedure and expected result                                                                                                                                                                                                                          |
| ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SV-FILE-01 | Send zero-byte, oversized, truncated, malformed, polyglot, active SVG/HTML, double-extension, Unicode-name, decompression-bomb, extreme-dimension, and known safe malware-test files. Reject before unsafe processing and remain within memory/CPU/time limits. |
| SV-FILE-02 | Confirm image decoding/re-encoding removes metadata and active content. Confirm video/import formats have equivalent maintained parsing and malware controls or are disabled.                                                                                   |
| SV-FILE-03 | Demonstrate one-time, intent-bound, caller-bound upload authorization, opaque server-controlled keys, quarantine, scan state, atomic promotion, and cleanup after failure/timeout.                                                                              |
| SV-FILE-04 | Attempt anonymous, other-patron, unrelated-staff, CDN, guessed-key, stale-signed-URL and direct-bucket reads of identity evidence. All fail; an assigned reviewer succeeds briefly after step-up and creates an audit event.                                    |
| SV-FILE-05 | Confirm no identity evidence or useful bearer URL appears in database response projections, JWT/session, client data, cache, search, analytics, logs, traces, email, issue/test fixture, or release artifact.                                                   |
| SV-FILE-06 | Execute retention/deletion for an approved, rejected and abandoned application, including quarantine, versions, derivatives, temp files, exports and documented backup expiry. Preserve only the minimum decision record.                                       |

### Circulation, fines, events, and migration

| ID        | Required procedure and expected result                                                                                                                                                                             |
| --------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| SV-BIZ-01 | Run at least 100 concurrent approvals for one request/copy. Exactly one succeeds; no copy has more than one active loan and availability remains correct.                                                          |
| SV-BIZ-02 | Race request, approve, reject, cancel, return, renew, mark-lost, inventory edit and fine update in valid combinations. Database and domain invariants always hold.                                                 |
| SV-BIZ-03 | Replay every mutation with the same idempotency key and payload, then with changed payload. The first effect occurs once; changed reuse is rejected; retention and tenant/caller binding are verified.             |
| SV-BIZ-04 | Property/state-machine tests generate valid and invalid transition sequences. Invalid source states, impossible timestamps, duplicate open requests, and negative/unsupported monetary results are rejected.       |
| SV-BIZ-05 | Crash after business commit but before publish. The outbox later publishes exactly the committed event; no committed state is permanently missing its event or mandatory audit record.                             |
| SV-BIZ-06 | Deliver duplicate, delayed, out-of-order, incompatible and poison events. Consumers create no duplicate external effect, preserve aggregate version rules, isolate poison records, alert, and support safe replay. |
| SV-BIZ-07 | Rebuild disposable discovery/search/notification projections from approved source events. Counts and authorized visibility reconcile without using the projection as authority.                                    |
| SV-BIZ-08 | Rehearse backfill, shadow comparison, single-writer cutover, rollback and migration-identity revocation on production-shaped data. Reconciliation is exact and two writers are never active.                       |
| SV-BIZ-09 | Verify fine accrual, rounding, currency, policy version/effective date, waiver, adjustment and reversal. History is append-only and every change has actor, reason and audit evidence.                             |

### Data protection, secrets, and cryptography

| ID           | Required procedure and expected result                                                                                                                                                                         |
| ------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SV-DATA-01   | Trace every confidential/restricted field from collection through API, database, event, cache, search, object, log, analytics, backup, export, deletion and provider. Undocumented copies fail the gate.       |
| SV-DATA-02   | Verify response projections for anonymous, patron, each staff capability, service and support identity. Password/hash, token, private document, internal note and unnecessary legal identity are absent.       |
| SV-DATA-03   | Inspect database, broker, Redis, search, object storage, backups and telemetry for TLS, private access, encryption, service-specific IAM and key separation.                                                   |
| SV-CRYPTO-01 | Review algorithm/library/key inventory, KMS policies, generation, rotation, revocation, destruction and failure behavior. No application-defined encryption/token algorithm is accepted.                       |
| SV-SECRET-01 | Scan Git history, source, images, SBOM/provenance, CI logs/artifacts, Helm/Terraform state, crash dumps and client bundles. Any real secret is revoked and incident-handled, not merely removed.               |
| SV-SECRET-02 | Rotate OIDC client, JWT/service, database, broker, Redis, object-storage, email, signing and backup credentials without data loss or uncontrolled downtime; prove the old value fails.                         |
| SV-PRIV-01   | Privacy owner approves purpose, lawful basis, notice, minimization, role access, retention, deletion, data-subject procedure, provider/transfer inventory and breach process for identity and behavioral data. |

### Logging, audit, and incident response

| ID        | Required procedure and expected result                                                                                                                                                                                                  |
| --------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SV-LOG-01 | Trigger authentication, authorization, admin, document, export, fine, migration, secret, deploy and break-glass events. Required structured fields appear; tokens, passwords and document content do not.                               |
| SV-LOG-02 | Force audit-sink/outbox failure during a mandatory privileged mutation. The mutation fails or a proven same-transaction outbox preserves the audit event for delivery.                                                                  |
| SV-LOG-03 | Attempt to alter/delete/archive-expire audit data using application admin, database runtime, database admin and ordinary platform identities. Separation and retention lock prevent silent tampering.                                   |
| SV-LOG-04 | Inject newlines, control characters, oversized fields and sensitive values into logged inputs. Structured ingestion remains parseable and redaction prevents secret/PII leakage.                                                        |
| SV-DET-01 | Exercise credential abuse, cross-user denial, unusual document/export volume, role grant, audit gap, queue poison/lag, unsigned deployment, secret access and invariant violation alerts. Every page has a named responder and runbook. |
| SV-IR-01  | Run account takeover, identity-document disclosure, malicious release, ransomware and provider-outage table-top exercises. Demonstrate containment, evidence preservation, notification decision, recovery and corrective action.       |

### Supply chain, container, Kubernetes, and cloud

| ID          | Required procedure and expected result                                                                                                                                                                                              |
| ----------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SV-SC-01    | Verify npm and Gradle locks/checksums, Gradle wrapper, pinned GitHub Action commits, pinned base-image digests, dependency licenses and absence of unreviewed install/download scripts.                                             |
| SV-SC-02    | Run SAST for JavaScript/TypeScript and Kotlin/Java, secret scan, SCA/malicious-package analysis, IaC/Kubernetes/Dockerfile policy scan, and container scan on every shipped component. Reachable critical/high findings block.      |
| SV-SC-03    | Build once from the reviewed commit on an isolated runner; emit SBOM and provenance; sign the immutable digest; verify repository, commit, builder and materials. Promotion reuses that digest.                                     |
| SV-SC-04    | Attempt to deploy an unsigned image, mutable tag, disallowed registry, untrusted builder, wrong repository/commit and image exceeding vulnerability policy. Admission denies each attempt.                                          |
| SV-CLOUD-01 | Review human, workload, CI, migration, database, broker, storage, KMS and backup IAM. No shared identity, wildcard administrative grant, static CI cloud key, or unused standing privilege remains.                                 |
| SV-K8S-01   | Policy-test every workload for non-root UID, read-only root filesystem, no privilege escalation, dropped capabilities, seccomp, no host access/privileged mode, resource/ephemeral limits, approved registry and immutable digest.  |
| SV-K8S-02   | From each namespace/workload, probe every allowed and representative forbidden east-west and egress destination, cloud metadata, control plane and public internet. Default deny and explicit allowlists match the data-flow model. |
| SV-K8S-03   | Verify namespace/service-account isolation, RBAC, Pod Security admission, external secrets/workload identity, KMS encryption, private control plane/endpoints, audit logging, node patching and runtime detection.                  |
| SV-K8S-04   | Validate HPA/PDB/topology/graceful shutdown against connection pools, broker lag and downstream quotas. Scaling and eviction do not create an outage or integrity failure.                                                          |
| SV-CLOUD-02 | Verify WAF/ingress TLS, canonical host and proxy header behavior, body/time/concurrency limits, DDoS controls, origin restriction, DNS change control, certificate renewal and direct-origin denial.                                |

### Resilience and recovery

| ID        | Required procedure and expected result                                                                                                                                                           |
| --------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| SV-RES-01 | Inject PostgreSQL, Redis, broker, OIDC, email, object-storage, search, DNS and telemetry failures. The system follows documented bounded degradation, never fails open, and avoids retry storms. |
| SV-RES-02 | Run sustained 2× forecast peak, burst 5×, cold-cache and soak tests with production-shaped data. Security checks remain enabled and pools/queues/provider limits remain within budget.           |
| SV-RES-03 | Restore databases, object versions, broker/replay position, configuration and audit evidence into an isolated environment. Meet RPO/RTO and reconcile authoritative state exactly.               |
| SV-RES-04 | Demonstrate zone loss and the approved regional/provider recovery strategy, including DNS, secrets, keys, signed artifacts, identity, and communications.                                        |
| SV-RES-05 | Verify backup immutability, separate IAM/key, retention, deletion expiry, restore approval and ransomware containment. A compromised runtime identity cannot delete all recovery copies.         |

## Current repository signals and unproven gaps

This is an orientation aid, not a pass/fail assessment. It intentionally uses
“signal” rather than “control,” because deployment evidence is not present.

| Area                  | Repository signal observed                                                                                                                                                                       | Evidence still required before a production claim                                                                                                      |
| --------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Web authorization     | Central database-backed user/admin/ownership guards and route tests exist                                                                                                                        | Complete route/action inventory, BOLA matrix, capability model, service-side enforcement, deployed behavior                                            |
| CSRF/browser boundary | Exact-origin helper and tests exist for selected mutations; headers are configured                                                                                                               | Coverage of every mutation, trusted-proxy/host evidence, enforced nonce/hash CSP, browser/edge dynamic tests                                           |
| Credential abuse      | Bounded credential input, dummy hash and a credential limiter exist                                                                                                                              | Deployed multi-dimensional limiter, recovery controls, OIDC cutover, fixture/legacy retirement                                                         |
| Uploads               | Server-mediated type/size checks and image re-encoding exist; public identity upload is disabled in production logic                                                                             | Private identity object lifecycle, scanner/quarantine isolation, authorization, storage policy, deletion and log evidence                              |
| Circulation integrity | Legacy mutations are conditional; the target command slice has database constraints, caller-bound loan/inventory/fine idempotency, deterministic copy locking, immutable fine ledger, an atomic outbox, Membership eligibility projection with an immutable inbox, published OpenAPI/Protobuf contracts, and PostgreSQL 18 concurrency tests | Reservation and dynamic-policy completion, reconciliation, broker replay drill, production-shaped load, failure injection, and cutover evidence |
| Service tokens        | The circulation resource server validates issuer/audience/scopes; self-service commands bind a UUID membership claim and staff delegation has a separate scope                                   | Real-IdP claim/rollover tests, delegated-scope governance, workload identity, actor audit export, and deployed network/IAM evidence                    |
| Container             | Web and circulation base images are digest-pinned and both runtimes use unprivileged UIDs                                                                                                        | Read-only/capability/seccomp policy, SBOM/signature/provenance, registry/admission enforcement, and deployed scan evidence                             |
| CI security           | Pinned Actions, JS/TS and Kotlin/Java CodeQL, dependency review, npm audit, secret scanning, migration/concurrency gates, and high/critical scans for both images are configured                 | IaC/Kubernetes policy scans, signed promotion pipeline, retained run evidence, and protected branch/environment policy                                 |
| Audit                 | Application audit table/helper exists                                                                                                                                                            | Mandatory same-transaction coverage, append-only external archive, access separation, integrity/gap alert tests                                        |
| Platform              | Target managed Kubernetes/cloud controls are documented                                                                                                                                          | Reviewed Terraform/Helm/GitOps, live policy/config/IAM/network/restore evidence                                                                        |

Documentation-only changes are currently ignored by the main CI workflow. A
security control change in architecture, runbooks, or policy therefore requires
an explicit documentation review gate and link checking even if code CI does
not start.

## Independent penetration test

### When required

An independent, source-assisted penetration test is required:

- Before general availability.
- At least annually.
- After a material change to identity, session, authorization, privileged
  workflow, identity-document flow, service boundary, ingress, event trust,
  cloud account/cluster, or data migration.
- After a serious incident when exploit-path validation is appropriate.

The tester must be organizationally independent of the implementation and must
receive enough time, documentation, source, configuration, API/event contracts,
role accounts, and production-equivalent access to test business logic. A
black-box automated scan is not a substitute.

### Minimum scope

- Anonymous, patron, each staff capability, security/admin, support,
  service/workload, suspended/demoted, and break-glass identities.
- OIDC, MFA, recovery, session rotation/revocation and token validation.
- Every BFF route/action, service API, object/field authorization, CORS/CSRF,
  files, exports, webhooks, events and error behavior.
- Circulation/fine state machine, concurrency, idempotency, replay, outbox,
  migration and rollback.
- Identity-document collection, storage, reviewer access, logs, retention and
  deletion.
- Browser XSS/CSP, SSRF/egress, request parsing/smuggling at edge-to-app
  boundaries, cache behavior and abuse/DoS controls.
- Kubernetes/cloud/IAM/secrets/object-storage/broker/registry/CI configuration
  review and safe validation of lateral movement.
- Dependency and supply-chain attack paths.

Production testing requires an approved rules-of-engagement document, safe test
accounts/data, source IPs, rate boundaries, emergency contacts, stop conditions,
evidence handling, cleanup, and incident differentiation. Destructive tests run
in an equivalent isolated environment unless explicitly approved.

Every finding includes reproduction, affected digest/configuration, impact,
severity rationale, evidence, owner and retest. General availability requires
independent retest of every critical/high finding and security review of the
fix's regression risk.

## Release gates

| Stage                | Mandatory evidence                                                                                                                                                                                                           | Blocking conditions                                                                                                                                             |
| -------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Design               | Updated threat/data-flow model, classification, privacy review, ASVS mapping, abuse cases, owner and rollback design                                                                                                         | New trust boundary, restricted data or privileged action lacks approved design                                                                                  |
| Pull request         | Security-sensitive CODEOWNERS review; unit/integration/property tests; lint/type/build; JS/TS and Kotlin SAST; SCA; secret/license/IaC/container policy checks as applicable                                                 | Failing check; unreviewed auth/crypto/migration/policy change; new high/critical finding; missing negative tests                                                |
| Release build        | Reproducible build from protected commit; all service images; SBOM; provenance; signature; scan reports; migration dry run; evidence manifest                                                                                | Mutable/untrusted artifact; missing/invalid signature/provenance/SBOM; reachable critical/high vulnerability                                                    |
| Pre-production       | Production-equivalent config; full authorization, file, business, API, event, privacy and failure suites; DAST; load/soak; network/admission tests; upgrade/rollback rehearsal                                               | Any failed mandatory test; config drift; security dependency fails open; invariant/reconciliation mismatch                                                      |
| General availability | Complete exact-ID ASVS evidence; current independent pentest/retest; access/vendor/privacy reviews; restore/rotation/incident evidence; owner/on-call/runbooks; product, security, privacy, platform and operations approval | Any known critical/high risk; public identity evidence; missing privileged MFA; unproven restore; missing immutable audit; missing single-writer/rollback proof |
| Production deploy    | Promote same signed digest; protected environment approval; reviewed diff/config/migration; backup/restore point; deploy/rollback authority; monitoring ready                                                                | Artifact changed after assessment; unsigned admission bypass; unreviewed migration; missing backup or rollback                                                  |
| Post-deploy          | Digest/config attestation; safe smoke tests; auth/authz/header/health checks; audit and alert receipt; SLO/error/security monitoring; reconciliation                                                                         | Wrong artifact/config, unexpected exposure, audit gap, security regression or invariant mismatch triggers halt/rollback/containment                             |

No deadline, launch commitment, severity re-label, or “accepted business risk”
may waive a known critical or high vulnerability for general availability.

## Vulnerability handling and exceptions

| Severity | Examples                                                                                                                                               | Production response target                                                                                       | Release treatment                            |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------- | -------------------------------------------- |
| Critical | RCE, exposed production key, broad identity-document leak, auth/admin bypass, supply-chain takeover, unrecoverable corruption                          | Page immediately; contain/revoke/disable affected path; begin incident process; fix or isolate as emergency work | Always block                                 |
| High     | Cross-user restricted-data access, stored XSS with account impact, privileged action bypass, writable audit trail, exploitable critical invariant race | Same-day triage and compensating containment; remediation target no more than 7 calendar days                    | Always block GA and affected deployments     |
| Medium   | Bounded information disclosure, meaningful abuse-control gap, limited CSRF/XSS, hardening gap with prerequisites                                       | Triage within 3 business days; remediation target 30 days                                                        | May be excepted only under the process below |
| Low      | Defense-in-depth issue with low practical impact                                                                                                       | Remediation target 90 days                                                                                       | May be excepted only under the process below |

Severity considers exploitability, privilege, user interaction, affected data,
blast radius, detectability, persistence, safety, and business impact—not CVSS
alone. Dependency findings are triaged for reachability but an uncertain
reachable path is not assumed safe.

A medium/low exception requires:

1. Finding and affected digests/configuration.
2. Technical exploit analysis and maximum credible impact.
3. Tested compensating control and monitoring.
4. Named remediation owner and dated plan.
5. Approval by security plus the accountable product/data owner.
6. Maximum expiry of 30 days for medium and 90 days for low.
7. Automatic reopening and release blocking at expiry or scope change.

The implementer cannot be the sole approver. Repeated renewal requires executive
risk ownership and a root-cause plan; it is not a substitute for remediation.

## Continuous verification schedule

| Frequency    | Required activity                                                                                                                               |
| ------------ | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| Every change | Tests for affected controls; SAST/SCA/secrets/IaC/container; SBOM/provenance/signature; migration/contract compatibility; evidence manifest     |
| Every deploy | Digest/config/admission verification, smoke auth/authz/headers, audit receipt, reconciliation and alert/watch window                            |
| Daily        | Dependency/provider/secret/image advisories, anomalous auth/privileged access, audit gaps, queue/DLQ, invariant and backup-job alerts           |
| Weekly       | Triage findings; patch supported runtimes/base images; inspect privileged exports/document reads; verify security job coverage and failed scans |
| Monthly      | External attack-surface and certificate/DNS review; restore-point sampling; secret age; cloud/Kubernetes posture and exposed-service inventory  |
| Quarterly    | Threat model, ASVS delta, privileged/service IAM, vendor, data-flow/retention, firewall/network and exception reviews                           |
| Semiannual   | Full restore/reconciliation; key and critical-secret rotation; outbox/DLQ replay; zone/provider dependency failure exercise                     |
| Annual       | Independent penetration test; incident/ransomware/region-loss tabletop; privacy impact and business continuity review                           |
| Event-driven | Immediate reassessment after incident, material architecture/identity change, critical advisory, provider compromise, or domain cutover         |

## Evidence package and retention

Each release candidate produces one immutable manifest that links:

- Git commit, source review approvals, build ID, artifact and image digests.
- Exact dependency locks, SBOMs, SCA/license results, SAST/SARIF, secret scan,
  IaC/Kubernetes/Dockerfile checks, container findings and triage.
- Build provenance and signature verification result.
- Test reports for unit, integration, property, concurrency, E2E, API contract,
  DAST, authorization, files, events, failure injection, load and restore.
- Database migration SQL/checksum, clean install, production-shaped upgrade,
  drift, backup and rollback evidence.
- OIDC/client/MFA/session policy export, IAM/access review, TLS/headers/CORS,
  ingress, network policy, admission and workload configuration.
- Threat model version, exact ASVS 5.0.0 workbook, privacy review, residual-risk
  and exception register.
- Independent penetration-test report and remediation retest.
- Deployment approval, deployed digest/config attestation, smoke results,
  audit receipt, reconciliation, SLO/security watch and rollback decision.

Security evidence is retained according to the approved audit/legal schedule,
with access limited to security, audit, privacy and named system owners.
Evidence containing personal or sensitive architecture information is
classified and redacted before broader distribution. Integrity hashes and
retention must not prevent lawful deletion of raw personal test data; use
synthetic data wherever possible.

## Definition of security-ready

A release is security-ready only when all of the following are true:

1. Scope, build digest, production configuration and assessors are identified.
2. Every applicable exact ASVS 5.0.0 requirement has current evidence; every
   not-applicable item has an approved rationale.
3. Every mandatory suite in this document passes in a production-equivalent
   environment.
4. No known critical or high vulnerability is open.
5. Medium/low exceptions are valid, tested, owned, monitored and unexpired.
6. The independent source-assisted penetration test and required retests are
   current.
7. Identity evidence is private, minimized, access-audited and governed through
   a tested retention/deletion lifecycle.
8. Privileged MFA, granular authorization, token/session revocation, immutable
   audit, signed artifacts, admission enforcement, network isolation, backup
   restore and incident containment are demonstrated—not merely configured.
9. Migration uses one writer, exact reconciliation and a rehearsed rollback.
10. Product, security, privacy/data, platform and operations owners sign the
    evidence manifest.

Passing these gates supports a bounded, evidence-based release decision. It
does not end security work and must never be represented as proof that unknown
vulnerabilities do not exist.
