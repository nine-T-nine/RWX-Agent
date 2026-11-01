#!/usr/bin/env bash
set -euo pipefail
test "$(id -u)" = 0 || { echo 'Run via sudo'; exit 1; }
test "$(uname -m)" = x86_64 || { echo 'Uploaded runtime is x86_64 only'; exit 1; }
package="$(realpath "$1")"
runtime="$(realpath "$2")"
package_sha="$3"
runtime_sha="$4"
printf '%s  %s\n%s  %s\n' "$package_sha" "$package" "$runtime_sha" "$runtime" | sha256sum -c -
if test -e /opt/rwx/current && ! test -L /opt/rwx/current; then
  echo '/opt/rwx/current exists and is not a release symlink; refusing to replace it'; exit 1
fi
if test -L /var/lib/rwx; then echo 'Unexpected symlink at /var/lib/rwx'; exit 1; fi
if systemctl is-active --quiet rwx.service; then
  systemctl cat rwx.service | grep -q '/opt/rwx/' || { echo 'Unrecognized existing rwx.service'; exit 1; }
  systemctl stop rwx.service
fi
if ss -H -lnptu 'sport = :5123' | grep -q .; then echo 'Port 5123 is already in use'; exit 1; fi
getent group rwx >/dev/null || groupadd --system rwx
id rwx >/dev/null 2>&1 || useradd --system --gid rwx --home-dir /var/lib/rwx --shell /usr/sbin/nologin rwx
install -d -m 0755 /opt/rwx/releases /etc/rwx
install -d -o rwx -g rwx -m 0750 /var/lib/rwx
runtime_dir="/opt/rwx/java25-${runtime_sha:0:12}"
if ! test -x "$runtime_dir/bin/java"; then
  install -d "$runtime_dir"
  tar -xzf "$runtime" --strip-components=1 -C "$runtime_dir"
fi
if test -e /opt/rwx/java25 && ! test -L /opt/rwx/java25; then
  echo '/opt/rwx/java25 already exists as a directory; refusing to replace it'; exit 1
fi
ln -sfn "$runtime_dir" /opt/rwx/java25
release="/opt/rwx/releases/$(date -u +%Y%m%d-%H%M%S)-${package_sha:0:12}"
install -d "$release"
tar -xzf "$package" -C "$release"
ln -sfn "$release" /opt/rwx/current
if test -f /etc/systemd/system/rwx.service; then
  cp -a /etc/systemd/system/rwx.service "$release/rwx.service.previous"
fi
install -m 0644 "$release/agent-operation/server/rwx.service" /etc/systemd/system/rwx.service
if ! test -e /etc/rwx/rwx.env; then
  printf '%s\n' 'RWX_JAVA_OPTS="-Xms64m -Xmx512m -XX:ActiveProcessorCount=2"' >/etc/rwx/rwx.env
  chmod 0600 /etc/rwx/rwx.env
fi
if command -v ufw >/dev/null && ufw status | grep -q '^Status: active'; then
  ufw allow 5123/tcp comment 'RWX game TCP'
  ufw allow 5123/udp comment 'RWX reliable UDP'
fi
systemd-analyze verify /etc/systemd/system/rwx.service
systemctl daemon-reload
systemctl enable --now rwx.service
systemctl status rwx.service --no-pager
runuser -u rwx -- python3 /opt/rwx/current/agent-operation/tools/rwx-cli.py --port-file /var/lib/rwx/agent-operation/tmp/port rwx-connection-status
ss -lnptu 'sport = :5123'
