#!/usr/bin/env bash
set -euo pipefail

platform_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repository_dir="$(cd "${platform_dir}/.." && pwd)"
release_argument=()
if [[ "${1:-}" == "--release" ]]; then
  release_argument=("--release")
elif [[ $# -gt 0 ]]; then
  echo "usage: platform/scripts/validate.sh [--release]" >&2
  exit 2
fi

cd "${repository_dir}"

python3 platform/scripts/validate_repository.py "${release_argument[@]}"
python3 -m unittest discover -s platform/tests -p "test_*.py"

for environment in dev staging prod; do
  helm lint platform/helm/mundia-service \
    -f "platform/gitops/environments/${environment}/values-circulation.yaml"
  helm template circulation platform/helm/mundia-service \
    --namespace "mundia-${environment}" \
    -f "platform/gitops/environments/${environment}/values-circulation.yaml" \
    >/dev/null
done

if command -v terraform >/dev/null 2>&1; then
  terraform fmt -check -recursive platform/terraform
elif command -v tofu >/dev/null 2>&1; then
  tofu fmt -check -recursive platform/terraform
elif command -v docker >/dev/null 2>&1 &&
  docker image inspect hashicorp/terraform:1.9.8 >/dev/null 2>&1; then
  docker run --rm \
    -v "${platform_dir}/terraform:/workspace:ro" \
    -w /workspace \
    hashicorp/terraform:1.9.8 \
    fmt -check -recursive
else
  echo "notice: terraform/tofu formatter is unavailable; HCL formatting check skipped" >&2
fi

if command -v kubeconform >/dev/null 2>&1; then
  for environment in dev staging prod; do
    kubectl kustomize --enable-helm \
      "platform/gitops/environments/${environment}" |
      kubeconform -strict -summary -ignore-missing-schemas
  done
else
  echo "notice: kubeconform is unavailable; Kubernetes schema check skipped" >&2
fi

if command -v kyverno >/dev/null 2>&1; then
  (
    cd platform/policies/kyverno
    kyverno test .
  )
else
  echo "notice: kyverno CLI is unavailable; admission policy test skipped" >&2
fi

echo "platform validation suite passed"
