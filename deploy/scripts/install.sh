#!/usr/bin/env bash
# First installation on a VM, as root: install.sh <mcp-opensearch.jar>. Review before running.
set -euo pipefail

APP_USER=mcp
APP_DIR=/opt/mcp-opensearch
CFG_DIR=/etc/mcp-opensearch
JAR_SRC="${1:?usage: install.sh <path-to-mcp-opensearch.jar>}"

id -u "$APP_USER" &>/dev/null || useradd --system --no-create-home --shell /usr/sbin/nologin "$APP_USER"

install -d -o root -g "$APP_USER" -m 0750 "$APP_DIR" "$CFG_DIR" "$CFG_DIR/tls"
install -o root -g "$APP_USER" -m 0640 "$JAR_SRC" "$APP_DIR/mcp-opensearch.jar"
install -o root -g root -m 0644 deploy/systemd/mcp-opensearch.service /etc/systemd/system/mcp-opensearch.service
systemctl daemon-reload
echo "Installed. Next: $CFG_DIR/mcp-opensearch.env (from deploy/systemd/mcp-opensearch.env.template),"
echo "keystore and truststore in $CFG_DIR/tls, then: systemctl enable --now mcp-opensearch"
