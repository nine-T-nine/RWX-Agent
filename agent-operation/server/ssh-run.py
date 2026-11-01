"""Run a shell command on the RWX cloud host over SSH.

The password is read from the RWX_SSH_PASSWORD environment variable and is never written to disk,
printed, or passed on a command line. Usage:

    $env:RWX_SSH_PASSWORD = '...'
    python agent-tmp/ssh-run.py "uname -a"
"""

import os
import sys

import paramiko

HOST = os.environ.get("RWX_SSH_HOST", "124.221.143.82")
USER = os.environ.get("RWX_SSH_USER", "ubuntu")
PASSWORD = os.environ.get("RWX_SSH_PASSWORD")

if not PASSWORD:
    sys.exit("RWX_SSH_PASSWORD is not set")

command = sys.argv[1] if len(sys.argv) > 1 else "true"

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect(HOST, port=22, username=USER, password=PASSWORD, timeout=20, allow_agent=False, look_for_keys=False)
try:
    stdin, stdout, stderr = client.exec_command(command, timeout=120)
    out = stdout.read().decode("utf-8", "replace")
    err = stderr.read().decode("utf-8", "replace")
    code = stdout.channel.recv_exit_status()
    sys.stdout.write(out)
    if err:
        sys.stderr.write(err)
    print(f"\n[exit {code}]")
finally:
    client.close()
