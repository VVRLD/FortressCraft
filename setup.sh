#!/usr/bin/env bash
# One-time setup on Linux. See INSTALL-LINUX.md.
exec bash "$(dirname -- "${BASH_SOURCE[0]}")/tools/setup_linux.sh" "$@"
