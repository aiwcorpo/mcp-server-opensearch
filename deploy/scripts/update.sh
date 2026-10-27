#!/usr/bin/env bash
# Replaces the jar and restarts the service, keeping the previous jar for rollback. Run as root.
set -euo pipefail
APP_DIR=/opt/mcp-opensearch
JAR_SRC="${1:?usage: update.sh <path-to-new.jar>}"
cp -a "$APP_DIR/mcp-opensearch.jar" "$APP_DIR/mcp-opensearch.jar.bak.$(date +%Y%m%d%H%M%S)"
install -o root -g mcp -m 0640 "$JAR_SRC" "$APP_DIR/mcp-opensearch.jar"
systemctl restart mcp-opensearch
systemctl --no-pager status mcp-opensearch | head -n 15
