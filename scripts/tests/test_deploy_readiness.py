"""Run the production deploy script with Docker responses controlled at its boundary."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class DeployReadinessTest(unittest.TestCase):
    def run_scenario(self, scenario):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            docker = root / 'docker'
            docker.write_text('''#!/usr/bin/env python3
import os, sys
from pathlib import Path
args = sys.argv[1:]
s = os.environ['SCENARIO']
if args[:2] == ['compose', '-f']: args = ['compose'] + args[5:]
with open('calls', 'a') as f: f.write(' '.join(args) + '\\n')
if args[0] == 'login': sys.stdin.read()
if args[:2] == ['compose', 'pull'] and s == 'pull': sys.exit(1)
if args[:2] == ['compose', 'ps']:
    print('' if s == 'missing' else 'app-id')
if args[:2] == ['image', 'inspect']: print('image-id')
if args[0] == 'inspect' and '{{.Image}}' in args:
    print('image-id'); sys.exit(0)
if args[0] == 'inspect':
    if s == 'inspect': sys.exit(1)
    print(s if s in ['exited', 'restarting'] else 'running')
if args[0] == 'exec':
    p = Path('attempts'); n = int(p.read_text()) + 1 if p.exists() else 1; p.write_text(str(n))
    if s == 'exec': sys.exit(127)
    print('503' if s == 'never' or (s == 'delayed' and n < 3) else '200')
''')
            docker.chmod(0o755)
            # macOS does not ship GNU timeout; emulate its process deadline for tests.
            timeout = root / 'timeout'
            timeout.write_text('''#!/usr/bin/env python3
import subprocess, sys
try: sys.exit(subprocess.run(sys.argv[2:], timeout=float(sys.argv[1])).returncode)
except subprocess.TimeoutExpired: sys.exit(124)
''')
            timeout.chmod(0o755)
            script = (ROOT / 'scripts/deploy-readiness.sh').read_text()
            # Compress only timing; execute the actual deployment control flow.
            script = script.replace('SECONDS + 180', 'SECONDS + 2').replace('sleep 2', 'sleep 0.05')
            env = dict(os.environ, PATH=f'{root}:' + os.environ['PATH'], APP_DIR=directory,
                       APP_IMAGE='ghcr.io/team-howmuch/howmuch-backend-v2@sha256:' + 'a' * 64,
                       DEPLOY_SHA='commit', DEPLOY_RUN_ID='1', DEPLOY_RUN_ATTEMPT='1', GHCR_USERNAME='test-user', GHCR_PAT='secret-must-not-appear', SCENARIO=scenario)
            result = subprocess.run(['bash', '-c', script], env=env, capture_output=True, text=True, timeout=10)
            self.assertEqual(list(root.glob('.deploy-image.*')), [])
            calls = (root / 'calls').read_text()
            output = result.stdout + result.stderr
            self.assertNotIn('secret-must-not-appear', output + calls)
            self.assertNotIn('compose logs --tail=100 app\n', calls)
            return result.returncode, output, calls

    def test_normal_and_delayed_start(self):
        for scenario in ['normal', 'delayed']:
            with self.subTest(scenario=scenario):
                code, output, calls = self.run_scenario(scenario)
                self.assertEqual(code, 0, output)
                self.assertIn('Application ready:', output)
                self.assertIn('image prune -f', calls)
                if scenario == 'delayed': self.assertEqual(calls.count('exec app-id'), 3)

    def test_failures_never_prune_or_report_success(self):
        for scenario in ['never', 'exited', 'restarting', 'missing', 'inspect', 'exec', 'pull']:
            with self.subTest(scenario=scenario):
                code, output, calls = self.run_scenario(scenario)
                self.assertNotEqual(code, 0, output)
                self.assertIn('Deployment failed:', output)
                self.assertNotIn('Application ready:', output)
                self.assertNotIn('Verified commit=', output)
                self.assertNotIn('image prune', calls)
                if scenario in ['never', 'exec']: self.assertIn('timed out', output)


if __name__ == '__main__':
    unittest.main()
