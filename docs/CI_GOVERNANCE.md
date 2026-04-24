# CI Governance

## Branch Protection Standard

The `main` branch should enforce:
- Pull request required before merge.
- At least 1 approving review.
- Dismiss stale approvals on new commits.
- Require code owner review.
- Require conversation resolution before merge.
- Require linear history.
- Restrict force pushes and deletions.
- Require these checks to pass:
  - `CI / Quality (Lint, Typecheck, Build)`
  - `CI / Docker Image Build`
  - `API Performance Benchmarks / Benchmark key API routes`

## Apply Protection

Use the helper script:

```bash
chmod +x scripts/apply-branch-protection.sh
./scripts/apply-branch-protection.sh
```

Or pass explicit repo name:

```bash
./scripts/apply-branch-protection.sh owner/repo
```

## Notes

- Running this script requires repository admin permission.
- If check names change in workflows, update the status check list in the script.
