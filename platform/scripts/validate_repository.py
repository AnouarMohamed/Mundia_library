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
    container: dict[str, Any],
    source: str,
    release: bool,
    require_probes: bool = True,
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

    if require_probes:
        for probe in ("startupProbe", "readinessProbe", "livenessProbe"):
            if probe not in container:
                errors.append(f"{prefix} is missing {probe}")
    return errors


def _container_environment(container: dict[str, Any]) -> dict[str, Any]:
    return {
        str(item.get("name")): item.get("value")
        for item in container.get("env", [])
        if isinstance(item, dict) and item.get("name")
    }


def _secret_refs(container: dict[str, Any]) -> list[str]:
    return [
        str(item.get("secretRef", {}).get("name"))
        for item in container.get("envFrom", [])
        if isinstance(item, dict) and item.get("secretRef", {}).get("name")
    ]


def _external_secret_keys(resource: dict[str, Any]) -> set[str]:
    return {
        str(item.get("secretKey"))
        for item in resource.get("spec", {}).get("data", [])
        if isinstance(item, dict) and item.get("secretKey")
    }


def _external_remote_keys(resource: dict[str, Any]) -> set[str]:
    return {
        str(item.get("remoteRef", {}).get("key"))
        for item in resource.get("spec", {}).get("data", [])
        if isinstance(item, dict) and item.get("remoteRef", {}).get("key")
    }


def render_environment(environment: str) -> tuple[list[dict[str, Any]], list[str]]:
    if shutil.which("kubectl") is None:
        return [], ["kubectl is required to render Kustomize + Helm environment overlays"]
    documents: list[dict[str, Any]] = []
    errors: list[str] = []
    for layer, path in (
        ("platform", f"platform/gitops/platform/environments/{environment}"),
        ("migration", f"platform/gitops/migrations/environments/{environment}"),
        ("workload", f"platform/gitops/environments/{environment}"),
    ):
        output, failure = run_checked(
            ["kubectl", "kustomize", "--enable-helm", path]
        )
        if failure:
            errors.append(failure)
            continue
        rendered, parse_errors = load_documents(
            output,
            f"rendered {environment} {layer} layer",
        )
        documents.extend(rendered)
        errors.extend(parse_errors)
    return documents, errors


def validate_environment(environment: str, release: bool = False) -> list[str]:
    documents, errors = render_environment(environment)
    if errors:
        return errors
    index = _resource_index(documents)
    expected_namespace = f"mundia-{environment}"
    expected_migration_namespace = f"{expected_namespace}-migrations"

    namespace = index.get(("Namespace", expected_namespace))
    labels = (namespace or {}).get("metadata", {}).get("labels", {})
    if not namespace:
        errors.append(f"{environment}: expected Namespace {expected_namespace}")
    elif labels.get("pod-security.kubernetes.io/enforce") != "restricted":
        errors.append(f"{environment}: namespace must enforce restricted Pod Security")
    migration_namespace = index.get(("Namespace", expected_migration_namespace))
    migration_labels = (migration_namespace or {}).get("metadata", {}).get("labels", {})
    if not migration_namespace:
        errors.append(
            f"{environment}: expected migration Namespace {expected_migration_namespace}"
        )
    elif (
        migration_labels.get("pod-security.kubernetes.io/enforce") != "restricted"
        or migration_labels.get("mundiapolis.io/purpose") != "migrations"
    ):
        errors.append(
            f"{environment}: migration namespace must be restricted and purpose-labelled"
        )

    cluster_stores = [
        item for item in documents if item.get("kind") == "ClusterSecretStore"
    ]
    if cluster_stores:
        errors.append(f"{environment}: ClusterSecretStore is forbidden")
    stores = [item for item in documents if item.get("kind") == "SecretStore"]
    store_namespaces = {
        str(item.get("metadata", {}).get("namespace")) for item in stores
    }
    if store_namespaces != {expected_namespace, expected_migration_namespace}:
        errors.append(
            f"{environment}: runtime and migration namespaces each require one local SecretStore"
        )

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

    circulation_network_policy = index.get(
        ("NetworkPolicy", "circulation-mundia-service"), {}
    )
    otel_peers = [
        peer
        for rule in circulation_network_policy.get("spec", {}).get("egress", [])
        for peer in rule.get("to", [])
        if peer.get("podSelector", {}).get("matchLabels", {}).get(
            "app.kubernetes.io/name"
        )
        == "otel-gateway-collector"
    ]
    if len(otel_peers) != 1 or otel_peers[0].get("namespaceSelector", {}).get(
        "matchLabels", {}
    ).get("kubernetes.io/metadata.name") != "monitoring":
        errors.append(
            f"{environment}: OTLP egress must select the monitoring namespace and gateway pod"
        )

    circulation_name = "circulation-mundia-service"
    migration_name = f"{circulation_name}-migration"
    circulation = index.get(("Deployment", circulation_name), {})
    circulation_pod = circulation.get("spec", {}).get("template", {}).get("spec", {})
    runtime_containers = circulation_pod.get("containers", [])
    runtime_container = runtime_containers[0] if runtime_containers else {}
    runtime_environment = _container_environment(runtime_container)
    runtime_secret_refs = _secret_refs(runtime_container)

    if runtime_environment.get("SPRING_FLYWAY_ENABLED") != "false":
        errors.append(
            f"{environment}/Deployment/{circulation_name}: "
            "runtime Flyway execution must be disabled"
        )
    if "APP_MIGRATION_ONLY" in runtime_environment:
        errors.append(
            f"{environment}/Deployment/{circulation_name}: "
            "migration-only mode may not be configured on runtime pods"
        )

    runtime_external_secret = index.get(("ExternalSecret", circulation_name), {})
    migration_external_secret = index.get(("ExternalSecret", migration_name), {})
    for purpose, external_secret in (
        ("runtime", runtime_external_secret),
        ("migration", migration_external_secret),
    ):
        store_ref = external_secret.get("spec", {}).get("secretStoreRef", {})
        if store_ref != {"name": "aws-secrets-manager", "kind": "SecretStore"}:
            errors.append(
                f"{environment}: {purpose} ExternalSecret must use the "
                "platform-owned namespace SecretStore"
            )
    runtime_secret_target = (
        runtime_external_secret.get("spec", {}).get("target", {}).get("name")
    )
    migration_secret_target = (
        migration_external_secret.get("spec", {}).get("target", {}).get("name")
    )
    if not runtime_external_secret:
        errors.append(f"{environment}: runtime ExternalSecret is missing")
    if not migration_external_secret:
        errors.append(f"{environment}: migration ExternalSecret is missing")
    if runtime_external_secret.get("metadata", {}).get("namespace") != expected_namespace:
        errors.append(f"{environment}: runtime ExternalSecret escaped its runtime namespace")
    if migration_external_secret.get("metadata", {}).get("namespace") != (
        expected_migration_namespace
    ):
        errors.append(
            f"{environment}: migration ExternalSecret must be in the isolated migration namespace"
        )
    if not runtime_secret_target or not migration_secret_target:
        errors.append(f"{environment}: ExternalSecret targets must be explicit")
    elif runtime_secret_target == migration_secret_target:
        errors.append(f"{environment}: runtime and migration secret targets must differ")

    runtime_secret_keys = _external_secret_keys(runtime_external_secret)
    migration_secret_keys = _external_secret_keys(migration_external_secret)
    required_migration_keys = {
        "DATABASE_MIGRATION_URL",
        "DATABASE_MIGRATION_USERNAME",
        "DATABASE_MIGRATION_PASSWORD",
    }
    if any(item.startswith("DATABASE_MIGRATION_") for item in runtime_secret_keys):
        errors.append(
            f"{environment}: runtime ExternalSecret contains migration credentials"
        )
    if migration_secret_keys != required_migration_keys:
        errors.append(
            f"{environment}: migration ExternalSecret must contain exactly the "
            "three dedicated migration variables"
        )
    if _external_remote_keys(runtime_external_secret) & _external_remote_keys(
        migration_external_secret
    ):
        errors.append(
            f"{environment}: runtime and migration credentials share a remote secret"
        )
    environment_segment = "prod" if environment == "prod" else environment
    allowed_runtime_remote_keys = {
        f"mundia/{environment_segment}/circulation/postgres",
        f"mundia/{environment_segment}/circulation/oidc",
        f"mundia/{environment_segment}/circulation/kafka",
        f"mundia/{environment_segment}/circulation/redis",
    }
    runtime_remote_keys = _external_remote_keys(runtime_external_secret)
    if not runtime_remote_keys or not runtime_remote_keys <= allowed_runtime_remote_keys:
        errors.append(f"{environment}: runtime ExternalSecret references an unapproved key")
    if _external_remote_keys(migration_external_secret) != {
        f"mundia/{environment_segment}/circulation/postgres-migration"
    }:
        errors.append(f"{environment}: migration ExternalSecret key is not exact")
    if runtime_secret_refs != [runtime_secret_target]:
        errors.append(
            f"{environment}/Deployment/{circulation_name}: runtime pod must mount "
            "only the runtime secret target"
        )
    if migration_secret_target in runtime_secret_refs:
        errors.append(
            f"{environment}/Deployment/{circulation_name}: migration secret is exposed "
            "to runtime pods"
        )

    migration_job = index.get(("Job", migration_name), {})
    if not migration_job:
        errors.append(f"{environment}: pre-sync migration Job is missing")
    else:
        job_source = f"{environment}/Job/{migration_name}"
        if migration_job.get("metadata", {}).get("namespace") != (
            expected_migration_namespace
        ):
            errors.append(f"{job_source}: migration Job is not namespace-isolated")
        annotations = migration_job.get("metadata", {}).get("annotations", {})
        if annotations.get("argocd.argoproj.io/hook") != "PreSync":
            errors.append(f"{job_source}: Argo CD PreSync hook is required")
        if annotations.get("argocd.argoproj.io/hook-delete-policy") != (
            "BeforeHookCreation,HookSucceeded"
        ):
            errors.append(f"{job_source}: Argo CD hook cleanup policy is incomplete")
        if annotations.get("helm.sh/hook") != "pre-install,pre-upgrade":
            errors.append(f"{job_source}: Helm pre-install/pre-upgrade hook is required")
        if annotations.get("helm.sh/hook-delete-policy") != (
            "before-hook-creation,hook-succeeded"
        ):
            errors.append(f"{job_source}: Helm hook cleanup policy is incomplete")

        job_spec = migration_job.get("spec", {})
        if job_spec.get("activeDeadlineSeconds", 0) <= 0:
            errors.append(f"{job_source}: activeDeadlineSeconds is required")
        if job_spec.get("backoffLimit", 99) > 1:
            errors.append(f"{job_source}: backoffLimit must be at most one")
        if job_spec.get("ttlSecondsAfterFinished", 0) < 300:
            errors.append(f"{job_source}: TTL cleanup must be at least five minutes")
        if job_spec.get("completions") != 1 or job_spec.get("parallelism") != 1:
            errors.append(f"{job_source}: migration execution must be single-flight")

        job_pod = job_spec.get("template", {}).get("spec", {})
        job_context = job_pod.get("securityContext", {})
        if job_pod.get("restartPolicy") != "Never":
            errors.append(f"{job_source}: restartPolicy must be Never")
        if job_pod.get("automountServiceAccountToken") is not False:
            errors.append(f"{job_source}: service-account token automount must be false")
        if job_context.get("runAsNonRoot") is not True:
            errors.append(f"{job_source}: pod must run as non-root")
        if job_context.get("seccompProfile", {}).get("type") != "RuntimeDefault":
            errors.append(f"{job_source}: pod must use RuntimeDefault seccomp")

        job_containers = job_pod.get("containers", [])
        if len(job_containers) != 1:
            errors.append(f"{job_source}: expected exactly one migration container")
        else:
            migration_container = job_containers[0]
            errors.extend(
                _validate_container(
                    migration_container,
                    job_source,
                    release,
                    require_probes=False,
                )
            )
            if migration_container.get("image") != runtime_container.get("image"):
                errors.append(
                    f"{job_source}: migration and runtime must use the same image digest"
                )
            if migration_container.get("ports"):
                errors.append(f"{job_source}: migration container may not expose ports")
            for probe in ("startupProbe", "readinessProbe", "livenessProbe"):
                if probe in migration_container:
                    errors.append(f"{job_source}: migration container may not define {probe}")
            if _container_environment(migration_container) != {
                "APP_MIGRATION_ONLY": "true"
            }:
                errors.append(
                    f"{job_source}: only APP_MIGRATION_ONLY=true may be literal environment"
                )
            if _secret_refs(migration_container) != [migration_secret_target]:
                errors.append(
                    f"{job_source}: migration container must mount only its dedicated secret"
                )

        runtime_service_account = circulation_pod.get("serviceAccountName")
        migration_service_account = job_pod.get("serviceAccountName")
        if (
            not migration_service_account
            or migration_service_account == runtime_service_account
        ):
            errors.append(f"{job_source}: migration ServiceAccount must be distinct")
        migration_sa = index.get(
            ("ServiceAccount", str(migration_service_account)),
            {},
        )
        if not migration_sa:
            errors.append(f"{job_source}: migration ServiceAccount is missing")
        elif migration_sa.get("automountServiceAccountToken") is not False:
            errors.append(f"{job_source}: migration ServiceAccount token must be disabled")

        migration_policy = index.get(("NetworkPolicy", migration_name), {})
        if not migration_policy:
            errors.append(f"{job_source}: dedicated migration NetworkPolicy is missing")
        else:
            policy_spec = migration_policy.get("spec", {})
            if policy_spec.get("ingress") != []:
                errors.append(f"{job_source}: migration NetworkPolicy must deny ingress")
            policy_ports = {
                (str(port.get("protocol", "TCP")), port.get("port"))
                for rule in policy_spec.get("egress", [])
                for port in rule.get("ports", [])
                if isinstance(port, dict)
            }
            expected_ports = {("UDP", 53), ("TCP", 53), ("TCP", 5432)}
            if policy_ports != expected_ports:
                errors.append(
                    f"{job_source}: egress must be limited to DNS and PostgreSQL"
                )

    cleanup_name = f"{migration_name}-cleanup"
    cleanup_job = index.get(("Job", cleanup_name), {})
    if not cleanup_job:
        errors.append(f"{environment}: migration credential cleanup Job is missing")
    else:
        cleanup_source = f"{environment}/Job/{cleanup_name}"
        if cleanup_job.get("metadata", {}).get("namespace") != (
            expected_migration_namespace
        ):
            errors.append(f"{cleanup_source}: cleanup Job is not namespace-isolated")
        cleanup_annotations = cleanup_job.get("metadata", {}).get("annotations", {})
        if cleanup_annotations.get("argocd.argoproj.io/hook") != "PreSync":
            errors.append(f"{cleanup_source}: Argo CD PreSync hook is required")
        if cleanup_annotations.get("argocd.argoproj.io/sync-wave") != "0":
            errors.append(f"{cleanup_source}: cleanup must run after the migration wave")
        if cleanup_annotations.get("helm.sh/hook") != "pre-install,pre-upgrade":
            errors.append(f"{cleanup_source}: Helm pre-install/pre-upgrade hook is required")

        cleanup_spec = cleanup_job.get("spec", {})
        if cleanup_spec.get("activeDeadlineSeconds", 0) <= 0:
            errors.append(f"{cleanup_source}: activeDeadlineSeconds is required")
        if cleanup_spec.get("backoffLimit", 99) > 1:
            errors.append(f"{cleanup_source}: backoffLimit must be at most one")
        if cleanup_spec.get("ttlSecondsAfterFinished", 0) < 300:
            errors.append(f"{cleanup_source}: TTL cleanup must be at least five minutes")
        if cleanup_spec.get("completions") != 1 or cleanup_spec.get("parallelism") != 1:
            errors.append(f"{cleanup_source}: cleanup execution must be single-flight")

        cleanup_pod = cleanup_spec.get("template", {}).get("spec", {})
        cleanup_context = cleanup_pod.get("securityContext", {})
        if cleanup_pod.get("restartPolicy") != "Never":
            errors.append(f"{cleanup_source}: restartPolicy must be Never")
        if cleanup_pod.get("automountServiceAccountToken") is not False:
            errors.append(f"{cleanup_source}: automatic token mounting must be disabled")
        if cleanup_context.get("runAsNonRoot") is not True:
            errors.append(f"{cleanup_source}: pod must run as non-root")
        if cleanup_context.get("seccompProfile", {}).get("type") != "RuntimeDefault":
            errors.append(f"{cleanup_source}: pod must use RuntimeDefault seccomp")

        cleanup_containers = cleanup_pod.get("containers", [])
        if len(cleanup_containers) != 1:
            errors.append(f"{cleanup_source}: expected exactly one cleanup container")
        else:
            cleanup_container = cleanup_containers[0]
            errors.extend(
                _validate_container(
                    cleanup_container,
                    cleanup_source,
                    release,
                    require_probes=False,
                )
            )
            if cleanup_container.get("image") != runtime_container.get("image"):
                errors.append(
                    f"{cleanup_source}: cleanup and runtime must use the same image digest"
                )
            if cleanup_container.get("ports") or _secret_refs(cleanup_container):
                errors.append(
                    f"{cleanup_source}: cleanup may expose neither ports nor secret envFrom"
                )
            cleanup_environment = _container_environment(cleanup_container)
            expected_cleanup_environment = {
                "APP_MIGRATION_SECRET_CLEANUP_ONLY": "true",
                "POD_NAMESPACE": None,
                "MIGRATION_EXTERNAL_SECRET_NAME": migration_name,
                "MIGRATION_SECRET_NAME": migration_secret_target,
            }
            if cleanup_environment != expected_cleanup_environment:
                errors.append(
                    f"{cleanup_source}: cleanup resource identity contract is incomplete"
                )

        cleanup_service_account = cleanup_pod.get("serviceAccountName")
        cleanup_sa = index.get(("ServiceAccount", str(cleanup_service_account)), {})
        if (
            not cleanup_service_account
            or cleanup_service_account
            in {
                circulation_pod.get("serviceAccountName"),
                migration_job.get("spec", {})
                .get("template", {})
                .get("spec", {})
                .get("serviceAccountName"),
            }
        ):
            errors.append(f"{cleanup_source}: cleanup ServiceAccount must be distinct")
        if not cleanup_sa:
            errors.append(f"{cleanup_source}: cleanup ServiceAccount is missing")
        elif cleanup_sa.get("automountServiceAccountToken") is not False:
            errors.append(f"{cleanup_source}: cleanup ServiceAccount automount must be false")

        projected_tokens = [
            source.get("serviceAccountToken", {})
            for volume in cleanup_pod.get("volumes", [])
            for source in volume.get("projected", {}).get("sources", [])
            if isinstance(source, dict) and source.get("serviceAccountToken")
        ]
        if len(projected_tokens) != 1:
            errors.append(
                f"{cleanup_source}: expected one explicit short-lived projected token"
            )
        elif projected_tokens[0].get("expirationSeconds", 9999) > 900:
            errors.append(f"{cleanup_source}: projected token lifetime exceeds 15 minutes")

        cleanup_role = index.get(("Role", cleanup_name), {})
        expected_role_rules = {
            ("external-secrets.io", "externalsecrets", migration_name, "delete"),
            ("", "secrets", str(migration_secret_target), "delete"),
        }
        actual_role_rules = {
            (
                str(api_group),
                str(resource),
                str(resource_name),
                str(verb),
            )
            for rule in cleanup_role.get("rules", [])
            for api_group in rule.get("apiGroups", [])
            for resource in rule.get("resources", [])
            for resource_name in rule.get("resourceNames", [])
            for verb in rule.get("verbs", [])
        }
        if actual_role_rules != expected_role_rules:
            errors.append(
                f"{cleanup_source}: cleanup RBAC must grant only named-resource deletion"
            )
        cleanup_binding = index.get(("RoleBinding", cleanup_name), {})
        binding_subjects = cleanup_binding.get("subjects", [])
        if (
            cleanup_binding.get("roleRef", {}).get("name") != cleanup_name
            or len(binding_subjects) != 1
            or binding_subjects[0].get("kind") != "ServiceAccount"
            or binding_subjects[0].get("name") != cleanup_name
        ):
            errors.append(
                f"{cleanup_source}: cleanup Role must bind only its dedicated ServiceAccount"
            )

        cleanup_policy = index.get(("NetworkPolicy", cleanup_name), {})
        if not cleanup_policy:
            errors.append(f"{cleanup_source}: cleanup NetworkPolicy is missing")
        else:
            cleanup_policy_spec = cleanup_policy.get("spec", {})
            if cleanup_policy_spec.get("ingress") != []:
                errors.append(f"{cleanup_source}: cleanup NetworkPolicy must deny ingress")
            cleanup_ports = {
                (str(port.get("protocol", "TCP")), port.get("port"))
                for rule in cleanup_policy_spec.get("egress", [])
                for port in rule.get("ports", [])
                if isinstance(port, dict)
            }
            if cleanup_ports != {("UDP", 53), ("TCP", 53), ("TCP", 443)}:
                errors.append(
                    f"{cleanup_source}: egress must be limited to DNS and Kubernetes API"
                )

    if environment == "prod":
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
            "postgres_migration_secret_arn",
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


def validate_gitops_boundaries() -> list[str]:
    errors: list[str] = []
    project_path = PLATFORM_ROOT / "gitops" / "bootstrap" / "argocd-projects.yaml"
    projects, parse_errors = load_documents(
        project_path.read_text(encoding="utf-8"),
        str(project_path.relative_to(REPOSITORY_ROOT)),
    )
    errors.extend(parse_errors)
    index = _resource_index(projects)
    workload = index.get(("AppProject", "mundia-workloads"), {})
    if workload.get("spec", {}).get("clusterResourceWhitelist"):
        errors.append("mundia-workloads AppProject must not manage cluster resources")
    allowed_namespace_kinds = {
        (item.get("group", ""), item.get("kind", ""))
        for item in workload.get("spec", {}).get("namespaceResourceWhitelist", [])
    }
    forbidden_workload_kinds = {
        ("", "Secret"),
        ("batch", "Job"),
        ("external-secrets.io", "SecretStore"),
        ("rbac.authorization.k8s.io", "Role"),
        ("rbac.authorization.k8s.io", "RoleBinding"),
    }
    if allowed_namespace_kinds & forbidden_workload_kinds:
        errors.append(
            "mundia-workloads AppProject may not manage Secrets, SecretStores, or RBAC"
        )

    applications_path = (
        PLATFORM_ROOT / "gitops" / "bootstrap" / "environment-applications.yaml"
    )
    applications, application_errors = load_documents(
        applications_path.read_text(encoding="utf-8"),
        str(applications_path.relative_to(REPOSITORY_ROOT)),
    )
    errors.extend(application_errors)
    application_index = _resource_index(applications)
    for environment in ENVIRONMENTS:
        platform_application = application_index.get(
            ("Application", f"mundia-{environment}-platform"), {}
        )
        migration_application = application_index.get(
            ("Application", f"mundia-{environment}-migrations"), {}
        )
        workload_application = application_index.get(
            ("Application", f"mundia-{environment}"), {}
        )
        if platform_application.get("spec", {}).get("project") != "platform-addons":
            errors.append(f"{environment}: platform layer is not platform-owned")
        if migration_application.get("spec", {}).get("project") != "platform-addons":
            errors.append(f"{environment}: migration layer is not platform-owned")
        if migration_application.get("spec", {}).get("destination", {}).get(
            "namespace"
        ) != f"mundia-{environment}-migrations":
            errors.append(f"{environment}: migration layer is not namespace-isolated")
        if workload_application.get("spec", {}).get("project") != "mundia-workloads":
            errors.append(f"{environment}: workload layer is not workload-isolated")
    return errors


def validate_service_migration_contract() -> list[str]:
    errors: list[str] = []
    application_config = (
        REPOSITORY_ROOT
        / "services"
        / "circulation-service"
        / "src"
        / "main"
        / "resources"
        / "application.yml"
    ).read_text(encoding="utf-8")
    if "enabled: ${SPRING_FLYWAY_ENABLED:false}" not in application_config:
        errors.append(
            "circulation-service: packaged runtime must default Flyway to disabled"
        )

    migration_runner = (
        REPOSITORY_ROOT
        / "services"
        / "circulation-service"
        / "src"
        / "main"
        / "kotlin"
        / "com"
        / "mundiapolis"
        / "library"
        / "circulation"
        / "MigrationOnlyRunner.kt"
    ).read_text(encoding="utf-8")
    for variable in (
        "DATABASE_MIGRATION_URL",
        "DATABASE_MIGRATION_USERNAME",
        "DATABASE_MIGRATION_PASSWORD",
    ):
        if variable not in migration_runner:
            errors.append(
                f"circulation-service: migration-only contract is missing {variable}"
            )
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
        *sorted((PLATFORM_ROOT / "gitops" / "migrations").rglob("*.yaml")),
        *sorted((PLATFORM_ROOT / "gitops" / "platform").rglob("*.yaml")),
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
    errors.extend(validate_gitops_boundaries())
    errors.extend(validate_service_migration_contract())
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
