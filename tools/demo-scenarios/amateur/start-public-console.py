#!/usr/bin/env python3
"""Supervise the fixed dev proxy console, or an explicitly selected preview tunnel."""
import os
import ipaddress
from pathlib import Path
import re
import signal
import subprocess
import sys
import threading

ROOT = Path(__file__).resolve().parent
origin = ROOT / "console-state" / "public-origin.txt"
children = []
stopping = threading.Event()


def stop(*_):
    stopping.set()
    for child in children:
        if child.poll() is None:
            child.terminate()


def configuration(env):
    mode = env.get('HOWMUCH_DEMO_ACCESS_MODE', 'fixed')
    if mode not in ('fixed', 'tunnel'):
        raise ValueError('HOWMUCH_DEMO_ACCESS_MODE must be fixed or tunnel')
    command = [sys.executable, str(ROOT / 'console-server.py')]
    if mode == 'fixed':
        bind = str(ipaddress.IPv4Address(env.get('HOWMUCH_DEMO_PROXY_BIND', '192.168.1.135')))
        allowed = str(ipaddress.IPv4Address(env.get('HOWMUCH_DEMO_PROXY_ALLOW_ADDRESS', '192.168.1.20')))
        if not ipaddress.IPv4Address(bind).is_private or not ipaddress.IPv4Address(allowed).is_private:
            raise ValueError('Fixed console proxy addresses must be private')
        command += ['--proxy-bind', bind, '--proxy-allow-address', allowed]
    return mode, command


def publish_origin(value):
    temporary = origin.with_suffix('.tmp')
    with temporary.open('w') as file:
        file.write(value + '\n'); file.flush(); os.fsync(file.fileno())
    temporary.replace(origin)
    print('Operator URL: ' + value + '/admin/amateur', flush=True)


def main():
    mode, command = configuration(os.environ)
    os.umask(0o077)
    origin.parent.mkdir(parents=True, exist_ok=True)
    origin.unlink(missing_ok=True)
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    if mode == 'fixed':
        publish_origin('https://dev.howmuch.wars.p-e.kr')
    console = subprocess.Popen(command)
    children.append(console)
    tunnel = None
    if mode == 'tunnel':
        tunnel = subprocess.Popen(["cloudflared", "tunnel", "--no-autoupdate", "--protocol", "http2",
                                   "--url", "http://127.0.0.1:18120"],
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        children.append(tunnel)

    def watch():
        for line in tunnel.stdout:
            match = re.search(r"https://[a-z0-9]+(?:-[a-z0-9]+)*\.trycloudflare\.com\b", line)
            if match:
                publish_origin(match[0])

    if tunnel is not None:
        thread = threading.Thread(target=watch, daemon=True)
        thread.start()
    try:
        while not stopping.wait(1):
            if any(child.poll() is not None for child in children):
                break
    finally:
        requested_stop = stopping.is_set()
        stop()
        for child in children:
            try:
                child.wait(timeout=8)
            except subprocess.TimeoutExpired:
                child.kill(); child.wait()
        origin.unlink(missing_ok=True)
    return 0 if requested_stop else 1


if __name__ == "__main__":
    raise SystemExit(main())
