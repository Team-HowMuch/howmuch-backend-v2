"""Run with python3 -m unittest discover -s scripts/tests (requires PyYAML)."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = yaml.safe_load((ROOT / ".github/workflows/backend-ci-cd.yml").read_text())
BUILD = WORKFLOW["jobs"]["build"]["steps"][-1]["run"]
DEPLOY = WORKFLOW["jobs"]["deploy"]["steps"][0]["with"]["script"]
REPOSITORY = "ghcr.io/team-howmuch/howmuch-backend-v2"
A = REPOSITORY + "@sha256:" + "a" * 64
B = REPOSITORY + "@sha256:" + "b" * 64

MOCK = r"""#!/bin/sh
set -eu
printf '%s\n' "$*" >> "$LOG"
case "$*" in
  login*) cat >/dev/null ;;
  "compose -f compose.yml -f "*)
    override=$5
    grep -Fx "    image: $APP_IMAGE" "$override" >/dev/null
    shift 5
    case "$*" in
      "pull app") test "${FAIL_PULL:-0}" = 0 ;;
      "up -d --no-build --pull never app") ;;
      "ps -q app") printf 'container\n' ;;
      *) exit 90 ;;
    esac ;;
  "image inspect "*) printf '%s\n' "$APP_IMAGE" ;;
  "inspect container "*) printf '%s\n' "${ACTUAL_IMAGE:-$APP_IMAGE}" ;;
  "image prune -f") ;;
  *) exit 91 ;;
esac
"""


class DeployDigestTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)
        docker = self.directory / "docker"
        docker.write_text(MOCK)
        docker.chmod(0o755)
        self.env = {
            **os.environ, "PATH": str(self.directory) + ":" + os.environ["PATH"],
            "APP_DIR": str(self.directory), "LOG": str(self.directory / "docker.log"),
            "GHCR_USERNAME": "test", "GHCR_PAT": "synthetic",
            "DEPLOY_SHA": "commit-a", "DEPLOY_RUN_ID": "123", "DEPLOY_RUN_ATTEMPT": "1",
            "REGISTRY": "ghcr.io", "IMAGE_NAME": "Team-HowMuch/howmuch-backend-v2",
            "GITHUB_OUTPUT": str(self.directory / "output"),
            "GITHUB_STEP_SUMMARY": str(self.directory / "summary"),
            "GITHUB_SHA": "commit-a", "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1",
        }

    def run_script(self, script, **env):
        return subprocess.run(["bash", "-c", script], env={**self.env, **env},
                              text=True, capture_output=True)

    def test_build_output_and_audit(self):
        result = self.run_script(BUILD, DIGEST="sha256:" + "a" * 64)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.directory / "output").read_text(), "image=" + A + "\n")
        summary = (self.directory / "summary").read_text()
        for value in ("commit-a", "123", "attempt 1", A):
            self.assertIn(value, summary)

    def test_invalid_build_digest(self):
        for digest in ("", "latest", "sha256:123", "sha256:" + "a" * 64 + "\ninjected"):
            self.assertNotEqual(self.run_script(BUILD, DIGEST=digest).returncode, 0)

    def test_overlapping_publication_and_reruns(self):
        # Branch tag now identifies B; deployment A must still use its build output.
        for image, attempt in ((B, "1"), (A, "1"), (A, "2"), (B, "3")):
            result = self.run_script(DEPLOY, APP_IMAGE=image,
                                     DEPLOY_RUN_ATTEMPT=attempt, BRANCH_TAG_IMAGE=B)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("Verified", result.stdout)
            self.assertIn("attempt=" + attempt + " image=" + image, result.stdout)
            self.assertEqual(list(self.directory.glob(".deploy-image.*")), [])
        log = (self.directory / "docker.log").read_text()
        self.assertNotIn(":develop", log)
        self.assertIn("image inspect " + A, log)
        self.assertIn("image inspect " + B, log)

    def test_invalid_reference_stops_before_docker(self):
        for image in ("", REPOSITORY + ":develop", A + "\ninjected"):
            self.assertNotEqual(self.run_script(DEPLOY, APP_IMAGE=image).returncode, 0)
        self.assertFalse((self.directory / "docker.log").exists())

    def test_pull_failure_and_image_mismatch(self):
        for extra in ({"FAIL_PULL": "1"}, {"ACTUAL_IMAGE": B}):
            result = self.run_script(DEPLOY, APP_IMAGE=A, **extra)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn("Verified", result.stdout)
            self.assertEqual(list(self.directory.glob(".deploy-image.*")), [])

    def test_workflow_wires_build_output(self):
        jobs = WORKFLOW["jobs"]
        self.assertEqual(jobs["build"]["outputs"]["image"], "${{ steps.image.outputs.image }}")
        publish = next(s for s in jobs["build"]["steps"] if s.get("id") == "publish")
        self.assertEqual(publish["uses"], "docker/build-push-action@v6")
        self.assertEqual(jobs["deploy"]["needs"], "build")
        self.assertEqual(jobs["deploy"]["steps"][0]["env"]["APP_IMAGE"],
                         "${{ needs.build.outputs.image }}")
        self.assertIn("APP_IMAGE", jobs["deploy"]["steps"][0]["with"]["envs"])


if __name__ == "__main__":
    unittest.main()
