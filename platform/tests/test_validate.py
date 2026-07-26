from __future__ import annotations

import importlib.util
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


if __name__ == "__main__":
    unittest.main()

