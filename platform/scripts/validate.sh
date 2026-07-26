#!/usr/bin/env bash
set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1

platform_dir="$(cd "${BASH_SOURCE[0]%/*}/.." && pwd)"
repository_dir="$(cd "${platform_dir}/.." && pwd)"
release_argument=()
release_mode=false
if [[ "${1:-}" == "--release" ]]; then
  release_argument=("--release")
  release_mode=true
elif [[ $# -gt 0 ]]; then
  echo "usage: platform/scripts/validate.sh [--release]" >&2
  exit 2
fi

if [[ "${release_mode}" == true ]]; then
  missing_release_tools=()

  for required_tool in python3 helm kubectl kubeconform kyverno; do
    if ! command -v "${required_tool}" >/dev/null 2>&1; then
      missing_release_tools+=("${required_tool}")
    fi
  done

  if ! command -v terraform >/dev/null 2>&1 &&
    ! command -v tofu >/dev/null 2>&1; then
    missing_release_tools+=("terraform or tofu")
  fi

  if [[ ${#missing_release_tools[@]} -gt 0 ]]; then
    echo "release validation requires the complete validation toolchain:" >&2
    printf -- "- %s\n" "${missing_release_tools[@]}" >&2
    exit 1
  fi
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
