"""Upload a file to the RWX cloud host over SFTP (password from RWX_SSH_PASSWORD)."""

import os
import sys
import time

import paramiko

HOST = os.environ.get("RWX_SSH_HOST", "124.221.143.82")
USER = os.environ.get("RWX_SSH_USER", "ubuntu")
PASSWORD = os.environ.get("RWX_SSH_PASSWORD")

if not PASSWORD:
    sys.exit("RWX_SSH_PASSWORD is not set")

local, remote = sys.argv[1], sys.argv[2]

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect(HOST, port=22, username=USER, password=PASSWORD, timeout=20, allow_agent=False, look_for_keys=False)
started = time.monotonic()
last = [-1]


def progress(done, total):
    percent = int(done * 100 / total)
    if percent >= last[0] + 10 or done == total:
        last[0] = percent
        rate = done / max(time.monotonic() - started, 0.001) / 1024 / 1024
        print(f"{os.path.basename(local)}: {percent}% ({done // 1048576} MiB, {rate:.1f} MiB/s)", flush=True)


try:
    sftp = client.open_sftp()
    sftp.put(local, remote, callback=progress)
    sftp.close()
    print("uploaded", remote, flush=True)
finally:
    client.close()
