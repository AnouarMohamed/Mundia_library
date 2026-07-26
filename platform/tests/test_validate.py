from __future__ import annotations

import importlib.util
import subprocess
import unittest
from pathlib import Path


PLATFORM_ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "platform_validation",
    PLATFORM_ROOT / "scripts" / "validate_repository.py",
)
assert SPEC and SPEC.loader
VALIDATION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VALIDATION)


class PlatformValidationTest(unittest.TestCase):
    def test_reviewable_foundation_passes_static_validation(self) -> None:
        self.assertEqual([], VALIDATION.validate(release=False))

    def test_each_environment_renders_secure_workloads(self) -> None:
        for environment in VALIDATION.ENVIRONMENTS:
            with self.subTest(environment=environment):
                self.assertEqual(
                    [],
                    VALIDATION.validate_environment(environment, release=False),
                )

    def test_release_gate_stays_closed_while_decisions_are_unresolved(self) -> None:
        blockers = VALIDATION.validate_release_inputs()
        self.assertTrue(blockers)
        self.assertTrue(any("unresolved release placeholder" in item for item in blockers))
        self.assertTrue(any("terraform.tfvars" in item for item in blockers))

    def test_no_literal_kubernetes_secret_is_committed(self) -> None:
        errors = VALIDATION.validate_raw_yaml()
        self.assertFalse(
            [item for item in errors if "Kubernetes Secret is forbidden" in item],
            errors,
        )

    def test_application_artifact_defaults_runtime_flyway_off(self) -> None:
        self.assertEqual([], VALIDATION.validate_service_migration_contract())

    def test_runtime_pods_never_receive_migration_credentials(self) -> None:
        for environment in VALIDATION.ENVIRONMENTS:
            with self.subTest(environment=environment):
                documents, errors = VALIDATION.render_environment(environment)
                self.assertEqual([], errors)
                index = VALIDATION._resource_index(documents)
                deployment = index[("Deployment", "circulation-mundia-service")]
                application = deployment["spec"]["template"]["spec"]["containers"][0]
                runtime_refs = VALIDATION._secret_refs(application)
                migration_secret = index[
                    ("ExternalSecret", "circulation-mundia-service-migration")
                ]["spec"]["target"]["name"]

                self.assertEqual(["circulation-runtime"], runtime_refs)
                self.assertNotIn(migration_secret, runtime_refs)
                self.assertEqual(
                    "false",
                    VALIDATION._container_environment(application)[
                        "SPRING_FLYWAY_ENABLED"
                    ],
                )
                self.assertNotIn(
                    "APP_MIGRATION_ONLY",
                    VALIDATION._container_environment(application),
                )

    def test_migration_job_is_digest_identical_and_identity_isolated(self) -> None:
        for environment in VALIDATION.ENVIRONMENTS:
            with self.subTest(environment=environment):
                documents, errors = VALIDATION.render_environment(environment)
                self.assertEqual([], errors)
                index = VALIDATION._resource_index(documents)
                runtime_pod = index[
                    ("Deployment", "circulation-mundia-service")
                ]["spec"]["template"]["spec"]
                migration_job = index[
                    ("Job", "circulation-mundia-service-migration")
                ]
                migration_pod = migration_job["spec"]["template"]["spec"]
                migration_container = migration_pod["containers"][0]

                self.assertNotEqual(
                    runtime_pod["serviceAccountName"],
                    migration_pod["serviceAccountName"],
                )
                self.assertFalse(migration_pod["automountServiceAccountToken"])
                self.assertEqual(
                    runtime_pod["containers"][0]["image"],
                    migration_container["image"],
                )
                self.assertEqual(
                    {"APP_MIGRATION_ONLY": "true"},
                    VALIDATION._container_environment(migration_container),
                )
                self.assertEqual(
                    ["circulation-migration"],
                    VALIDATION._secret_refs(migration_container),
                )

    def test_successful_migration_has_a_least_privilege_secret_cleanup_hook(self) -> None:
        for environment in VALIDATION.ENVIRONMENTS:
            with self.subTest(environment=environment):
                documents, errors = VALIDATION.render_environment(environment)
                self.assertEqual([], errors)
                index = VALIDATION._resource_index(documents)
                cleanup_name = "circulation-mundia-service-migration-cleanup"
                cleanup_job = index[("Job", cleanup_name)]
                cleanup_pod = cleanup_job["spec"]["template"]["spec"]
                cleanup_container = cleanup_pod["containers"][0]
                cleanup_role = index[("Role", cleanup_name)]

                self.assertEqual(
                    "PreSync",
                    cleanup_job["metadata"]["annotations"][
                        "argocd.argoproj.io/hook"
                    ],
                )
                self.assertEqual(
                    "0",
                    cleanup_job["metadata"]["annotations"][
                        "argocd.argoproj.io/sync-wave"
                    ],
                )
                self.assertEqual([], VALIDATION._secret_refs(cleanup_container))
                self.assertEqual(
                    "circulation-mundia-service-migration",
                    VALIDATION._container_environment(cleanup_container)[
                        "MIGRATION_EXTERNAL_SECRET_NAME"
                    ],
                )
                self.assertEqual(
                    "circulation-migration",
                    VALIDATION._container_environment(cleanup_container)[
                        "MIGRATION_SECRET_NAME"
                    ],
                )
                self.assertEqual(
                    [["delete"], ["delete"]],
                    [rule["verbs"] for rule in cleanup_role["rules"]],
                )
                self.assertEqual(
                    [
                        ["circulation-mundia-service-migration"],
                        ["circulation-migration"],
                    ],
                    [rule["resourceNames"] for rule in cleanup_role["rules"]],
                )

    def test_release_shell_gate_requires_complete_toolchain(self) -> None:
        result = subprocess.run(
            [
                "/bin/bash",
                str(PLATFORM_ROOT / "scripts" / "validate.sh"),
                "--release",
            ],
            cwd=PLATFORM_ROOT.parent,
            env={"PATH": "/path-that-intentionally-does-not-exist"},
            check=False,
            capture_output=True,
            text=True,
        )

        self.assertEqual(1, result.returncode)
        self.assertIn("complete validation toolchain", result.stderr)
        for required_tool in (
            "python3",
            "helm",
            "kubectl",
            "kubeconform",
            "kyverno",
            "terraform or tofu",
        ):
            self.assertIn(required_tool, result.stderr)


if __name__ == "__main__":
    unittest.main()
