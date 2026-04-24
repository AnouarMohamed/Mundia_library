#!/usr/bin/env bash
set -euo pipefail

# Apply branch protection for main using GitHub API.
# Requires: gh CLI authenticated with admin repo permissions.

if ! command -v gh >/dev/null 2>&1; then
  echo "gh CLI is required. Install from https://cli.github.com/"
  exit 1
fi

REPO_FULL_NAME="${1:-}"
if [[ -z "$REPO_FULL_NAME" ]]; then
  origin_url="$(git remote get-url origin)"
  if [[ "$origin_url" =~ github.com[:/]([^/]+/[^/.]+)(\.git)?$ ]]; then
    REPO_FULL_NAME="${BASH_REMATCH[1]}"
  else
    echo "Unable to infer repo from origin. Pass owner/repo as first argument."
    exit 1
  fi
fi

OWNER="${REPO_FULL_NAME%%/*}"
REPO="${REPO_FULL_NAME##*/}"

cat <<EOF
Applying protection to ${OWNER}/${REPO} branch: main
Required checks:
- CI / Quality (Lint, Typecheck, Build)
- CI / Docker Image Build
- API Performance Benchmarks / Benchmark key API routes
EOF

gh api \
  --method PUT \
  -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  "/repos/${OWNER}/${REPO}/branches/main/protection" \
  --input - <<'JSON'
{
  "required_status_checks": {
    "strict": true,
    "contexts": [
      "CI / Quality (Lint, Typecheck, Build)",
      "CI / Docker Image Build",
      "API Performance Benchmarks / Benchmark key API routes"
    ]
  },
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "dismiss_stale_reviews": true,
    "require_code_owner_reviews": true,
    "required_approving_review_count": 1
  },
  "restrictions": null,
  "required_linear_history": true,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "block_creations": false,
  "required_conversation_resolution": true,
  "lock_branch": false,
  "allow_fork_syncing": true
}
JSON

echo "Branch protection applied successfully."
