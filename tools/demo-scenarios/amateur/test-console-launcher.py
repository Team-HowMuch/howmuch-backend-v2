"""Exercise the actual launcher without starting servers or tunnels."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location('launcher', Path(__file__).with_name('start-public-console.py'))
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)


class LauncherTests(unittest.TestCase):
    def run_launcher(self, mode):
        with tempfile.TemporaryDirectory() as folder:
            origin = Path(folder) / 'public-origin.txt'
            console = MagicMock()
            console.poll.return_value = 0
            tunnel = MagicMock()
            tunnel.poll.return_value = 0
            tunnel.stdout = iter(['https://example-preview.trycloudflare.com\n'])
            publications = []
            publish = launcher.publish_origin
            def record(value):
                publish(value)
                publications.append(origin.read_text().strip())
            class ImmediateThread:
                def __init__(self, target, daemon): self.target = target
                def start(self): self.target()
            with patch.object(launcher, 'origin', origin), patch.object(launcher, 'children', []), \
                    patch.object(launcher, 'publish_origin', side_effect=record), \
                    patch.object(launcher.signal, 'signal'), \
                    patch.object(launcher.threading, 'Thread', ImmediateThread), \
                    patch.object(launcher.stopping, 'wait', return_value=False), \
                    patch.object(launcher.stopping, 'is_set', return_value=False), \
                    patch.object(launcher.stopping, 'set'), \
                    patch.dict(launcher.os.environ, {'HOWMUCH_DEMO_ACCESS_MODE': mode}, clear=True), \
                    patch.object(launcher.subprocess, 'Popen', side_effect=[console, tunnel]) as popen:
                self.assertEqual(launcher.main(), 1)  # Unexpected child exit.
                calls = [call.args[0] for call in popen.call_args_list]
            self.assertFalse(origin.exists())
            return calls, publications

    def test_fixed_deployment_publishes_origin_and_starts_proxy_without_tunnel(self):
        calls, values = self.run_launcher('fixed')
        self.assertEqual(values, ['https://dev.howmuch.wars.p-e.kr'])
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][-4:], ['--proxy-bind', '192.168.1.135', '--proxy-allow-address', '192.168.1.20'])

    def test_explicit_tunnel_mode_retains_preview_behavior(self):
        calls, values = self.run_launcher('tunnel')
        self.assertEqual(len(calls), 2)
        self.assertNotIn('--proxy-bind', calls[0])
        self.assertEqual(calls[1][0], 'cloudflared')
        self.assertEqual(values, ['https://example-preview.trycloudflare.com'])

    def test_invalid_mode_or_public_proxy_is_rejected(self):
        for env in [{'HOWMUCH_DEMO_ACCESS_MODE': 'other'}, {'HOWMUCH_DEMO_PROXY_BIND': '8.8.8.8'}]:
            with self.assertRaises(ValueError):
                launcher.configuration(env)


if __name__ == '__main__':
    unittest.main()
