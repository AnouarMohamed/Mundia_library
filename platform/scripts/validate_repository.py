#!/usr/bin/env python3
"""Static platform validation with no cloud or cluster access."""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any

try:
    import yaml
except ImportError as exc:  # pragma: no cover - exercised only on an incomplete tool image
    raise SystemExit(
        "PyYAML is required for platform validation; install "
        "platform/requirements-validation.txt in an isolated tool environment."
    ) from exc


PLATFORM_ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = PLATFORM_ROOT.parent
ENVIRONMENTS = ("dev", "staging", "prod")
SHA256_IMAGE = re.compile(r"^[^\s:@]+(?:/[^\s:@]+)+@sha256:[a-f0-9]{64}$")
ZERO_DIGEST = "sha256:" + ("0" * 64)


class UniqueKeyLoader(yaml.SafeLoader):
    """Reject duplicate YAML keys instead of silently taking the last value."""


def _construct_unique_mapping(
    loader: UniqueKeyLoader, node: yaml.MappingNode, deep: bool = False
) -> dict[Any, Any]:
    mapping: dict[Any, Any] = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise yaml.constructor.ConstructorError(
                "while constructing a mapping",
                node.start_mark,
                f"duplicate key {key!r}",
                key_node.start_mark,
            )
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


UniqueKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
    _construct_unique_mapping,
)


def load_documents(text: str, source: str) -> tuple[list[dict[str, Any]], list[str]]:
    errors: list[str] = []
    try:
        documents = [
            item
            for item in yaml.load_all(text, Loader=UniqueKeyLoader)
            if isinstance(item, dict)
        ]
    except yaml.YAMLError as exc:
        return [], [f"{source}: invalid YAML: {exc}"]
    return documents, errors


def run_checked(command: list[str]) -> tuple[str, str | None]:
    result = subprocess.run(
        command,
        cwd=REPOSITORY_ROOT,
        check=False,
        text=True,
        capture_output=True,
    )
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        return "", f"{' '.join(command)} failed: {detail}"
    return result.stdout, None


def validate_raw_yaml() -> list[str]:
    errors: list[str] = []
    for path in sorted(PLATFORM_ROOT.rglob("*.yaml")):
        if "helm/mundia-service/templates" in path.as_posix():
            continue
        documents, parse_errors = load_documents(
            path.read_text(encoding="utf-8"), str(path.relative_to(REPOSITORY_ROOT))
        )
        errors.extend(parse_errors)
        for document in documents:
            if document.get("kind") == "Secret":
                errors.append(
                    f"{path.relative_to(REPOSITORY_ROOT)}: committed Kubernetes Secret is forbidden"
                )
    return errors


def _resource_index(
    documents: list[dict[str, Any]],
) -> dict[tuple[str, str], dict[str, Any]]:
    result: dict[tuple[str, str], dict[str, Any]] = {}
    for document in documents:
        kind = str(document.get("kind", ""))
        name = str(document.get("metadata", {}).get("name", ""))
        if kind and name:
            result[(kind, name)] = document
    return result


def _validate_container(
    container: dict[str, Any], source: str, release: bool
) -> list[str]:
    errors: list[str] = []
    name = container.get("name", "<unnamed>")
    prefix = f"{source}: container {name}"
    image = str(container.get("image", ""))
    if not SHA256_IMAGE.fullmatch(image):
        errors.append(f"{prefix} must use registry/repository@sha256:digest")
    if release and ZERO_DIGEST in image:
        errors.append(f"{prefix} still uses the zero placeholder digest")

    context = container.get("securityContext", {})
    if context.get("allowPrivilegeEscalation") is not False:
        errors.append(f"{prefix} must disable privilege escalation")
    if context.get("readOnlyRootFilesystem") is not True:
        errors.append(f"{prefix} must use a read-only root filesystem")
    dropped = context.get("capabilities", {}).get("drop", [])
    if "ALL" not in dropped:
        errors.append(f"{prefix} must drop ALL Linux capabilities")

    resources = container.get("resources", {})
    for boundary in ("requests", "limits"):
        for resource in ("cpu", "memory"):
            if not resources.get(boundary, {}).get(resource):
                errors.append(f"{prefix} is missing {boundary}.{resource}")

    for probe in ("startupProbe", "readinessProbe", "livenessProbe"):
        if probe not in container:
            errors.append(f"{prefix} is missing {probe}")
    return errors


def render_environment(environment: str) -> tuple[list[dict[str, Any]], list[str]]:
    if shutil.which("kubectl") is None:
        return [], ["kubectl is required to render Kustomize + Helm environment overlays"]
    output, failure = run_checked(
        [
            "kubectl",
            "kustomize",
            "--enable-helm",
            f"platform/gitops/environments/{environment}",
        ]
    )
    if failure:
        return [], [failure]
    return load_documents(output, f"rendered {environment}")


def validate_environment(environment: str, release: bool = False) -> list[str]:
    documents, errors = render_environment(environment)
    if errors:
        return errors
    index = _resource_index(documents)
    expected_namespace = f"mundia-{environment}"

    namespace = index.get(("Namespace", expected_namespace))
    labels = (namespace or {}).get("metadata", {}).get("labels", {})
    if not namespace:
        errors.append(f"{environment}: expected Namespace {expected_namespace}")
    elif labels.get("pod-security.kubernetes.io/enforce") != "restricted":
        errors.append(f"{environment}: namespace must enforce restricted Pod Security")

    stores = [item for item in documents if item.get("kind") == "ClusterSecretStore"]
    if len(stores) != 1:
        errors.append(f"{environment}: expected exactly one ClusterSecretStore")
    elif stores[0].get("spec", {}).get("conditions", [{}])[0].get("namespaces") != [
        expected_namespace
    ]:
        errors.append(f"{environment}: ClusterSecretStore scope is not environment-local")

    deployments = [item for item in documents if item.get("kind") == "Deployment"]
    if not deployments:
        errors.append(f"{environment}: no Deployments rendered")
    for deployment in deployments:
        metadata = deployment.get("metadata", {})
        name = metadata.get("name", "<unnamed>")
        source = f"{environment}/Deployment/{name}"
        pod_spec = deployment.get("spec", {}).get("template", {}).get("spec", {})
        pod_context = pod_spec.get("securityContext", {})
        if pod_spec.get("automountServiceAccountToken") is not False:
            errors.append(f"{source}: service-account token automount must be false")
        if pod_context.get("runAsNonRoot") is not True:
            errors.append(f"{source}: pod must run as non-root")
        if pod_context.get("seccompProfile", {}).get("type") != "RuntimeDefault":
            errors.append(f"{source}: pod must use RuntimeDefault seccomp")
        for container in pod_spec.get("containers", []):
            errors.extend(_validate_container(container, source, release))

        if ("HorizontalPodAutoscaler", name) not in index:
            errors.append(f"{source}: matching HPA is missing")
        if ("PodDisruptionBudget", name) not in index:
            errors.append(f"{source}: matching PodDisruptionBudget is missing")
        if ("NetworkPolicy", name) not in index:
            errors.append(f"{source}: matching NetworkPolicy is missing")

    if environment == "prod":
        circulation = index.get(("Deployment", "circulation-mundia-service"), {})
        if circulation.get("spec", {}).get("replicas", 0) < 4:
            errors.append("prod: circulation starts with fewer than four replicas")
        hpa = index.get(("HorizontalPodAutoscaler", "circulation-mundia-service"), {})
        hpa_spec = hpa.get("spec", {})
        if hpa_spec.get("minReplicas", 0) < 4 or hpa_spec.get("maxReplicas", 0) < 10:
            errors.append("prod: circulation HPA floor/ceiling is below the production gate")

    for service in (item for item in documents if item.get("kind") == "Service"):
        service_type = service.get("spec", {}).get("type", "ClusterIP")
        if service_type in {"LoadBalancer", "NodePort"}:
            errors.append(
                f"{environment}/Service/{service.get('metadata', {}).get('name')}: "
                "direct external exposure is forbidden"
            )

    for policy in (item for item in documents if item.get("kind") == "NetworkPolicy"):
        if set(policy.get("spec", {}).get("policyTypes", [])) != {"Ingress", "Egress"}:
            errors.append(
                f"{environment}/NetworkPolicy/{policy.get('metadata', {}).get('name')}: "
                "must select both ingress and egress"
            )
        if "0.0.0.0/0" in yaml.safe_dump(policy):
            errors.append(
                f"{environment}/NetworkPolicy/{policy.get('metadata', {}).get('name')}: "
                "unrestricted egress is forbidden"
            )

    for ingress in (item for item in documents if item.get("kind") == "Ingress"):
        tls = ingress.get("spec", {}).get("tls", [])
        redirect = ingress.get("metadata", {}).get("annotations", {}).get(
            "nginx.ingress.kubernetes.io/force-ssl-redirect"
        )
        if not tls or redirect != "true":
            errors.append(
                f"{environment}/Ingress/{ingress.get('metadata', {}).get('name')}: TLS is mandatory"
            )

    for secret in (item for item in documents if item.get("kind") == "Secret"):
        errors.append(
            f"{environment}/Secret/{secret.get('metadata', {}).get('name')}: "
            "GitOps may not render literal Secrets"
        )
    return errors


def validate_terraform_contract() -> list[str]:
    errors: list[str] = []
    required_fragments = {
        "terraform/modules/aws-eks/main.tf": [
            "endpoint_private_access = true",
            'resources = ["secrets"]',
            'http_tokens                 = "required"',
            "aws_eks_pod_identity_association",
            '"audit"',
        ],
        "terraform/modules/aws-network/main.tf": [
            "aws_flow_log",
            "map_public_ip_on_launch = false",
            "aws_vpc_endpoint",
        ],
        "terraform/modules/environment/main.tf": [
            "production_invariants",
            'var.environment == "dev" || !var.endpoint_public_access',
            "external_secrets_identity",
        ],
    }
    for relative, fragments in required_fragments.items():
        text = (PLATFORM_ROOT / relative).read_text(encoding="utf-8")
        for fragment in fragments:
            if fragment not in text:
                errors.append(f"{relative}: missing platform invariant {fragment!r}")

    forbidden = list(PLATFORM_ROOT.rglob("*.tfstate")) + list(
        PLATFORM_ROOT.rglob("*.tfstate.*")
    )
    for path in forbidden:
        errors.append(f"{path.relative_to(REPOSITORY_ROOT)}: Terraform state is committed")
    return errors


def validate_release_inputs() -> list[str]:
    errors: list[str] = []
    for environment in ENVIRONMENTS:
        for filename in ("backend.hcl", "terraform.tfvars"):
            path = PLATFORM_ROOT / "terraform" / "environments" / environment / filename
            if not path.exists():
                errors.append(
                    f"{path.relative_to(REPOSITORY_ROOT)} is required for release validation"
                )

    release_paths = [
        *sorted((PLATFORM_ROOT / "gitops" / "bootstrap").glob("*.yaml")),
        *sorted((PLATFORM_ROOT / "gitops" / "environments").rglob("*.yaml")),
        *sorted((PLATFORM_ROOT / "policies" / "kyverno").glob("*.yaml")),
    ]
    unresolved = re.compile(
        r"REPLACE_[A-Z0-9_]+|\.invalid(?:[/:\s]|$)|sha256:0{64}"
    )
    for path in release_paths:
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if unresolved.search(line):
                errors.append(
                    f"{path.relative_to(REPOSITORY_ROOT)}:{line_number}: "
                    "unresolved release placeholder"
                )

    applications = (
        PLATFORM_ROOT / "gitops" / "bootstrap" / "environment-applications.yaml"
    ).read_text(encoding="utf-8")
    revisions = re.findall(r"targetRevision:\s*([^\s]+)", applications)
    if not revisions or any(not re.fullmatch(r"[a-f0-9]{40}", item) for item in revisions):
        errors.append("environment Applications must pin a reviewed 40-character Git commit")
    return errors


def validate(release: bool = False) -> list[str]:
    errors: list[str] = []
    errors.extend(validate_raw_yaml())
    errors.extend(validate_terraform_contract())
    for environment in ENVIRONMENTS:
        errors.extend(validate_environment(environment, release=release))
    if release:
        errors.extend(validate_release_inputs())
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--release",
        action="store_true",
        help="also reject every unresolved deployment decision and placeholder",
    )
    arguments = parser.parse_args()
    errors = validate(release=arguments.release)
    if errors:
        print(f"platform validation failed with {len(errors)} issue(s):", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("platform static validation passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
