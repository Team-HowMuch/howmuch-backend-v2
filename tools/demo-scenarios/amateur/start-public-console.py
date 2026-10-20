#!/usr/bin/env python3
"""Supervise the dev console and an accountless HTTPS preview tunnel."""
import os
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


def main():
    os.umask(0o077)
    origin.parent.mkdir(parents=True, exist_ok=True)
    origin.unlink(missing_ok=True)
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    console = subprocess.Popen([sys.executable, str(ROOT / "console-server.py")])
    children.append(console)
    tunnel = subprocess.Popen(["cloudflared", "tunnel", "--no-autoupdate", "--protocol", "http2",
                               "--url", "http://127.0.0.1:18120"],
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    children.append(tunnel)

    def watch():
        for line in tunnel.stdout:
            match = re.search(r"https://[a-z0-9]+(?:-[a-z0-9]+)*\.trycloudflare\.com\b", line)
            if match:
                temporary = origin.with_suffix(".tmp")
                with temporary.open("w") as file:
                    file.write(match[0] + "\n"); file.flush(); os.fsync(file.fileno())
                temporary.replace(origin)
                print("Operator URL: " + match[0] + "/admin/amateur", flush=True)

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
