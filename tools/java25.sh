#!/usr/bin/env bash
# Sourced by the Linux scripts: makes Gradle/Minecraft run on Java 25.
# The Fabric build's Gradle can't run on newer Java (Java 27 fails with "Unsupported class file
# major version 71"), so the system's default `java` isn't good enough unless it is 25.
#
# Looks, in order, at: $FORTCRAFT_JAVA_HOME, $JAVA_HOME, the default `java`, the usual JDK
# folders (/usr/lib/jvm, /opt, ~/.jdks, SDKMAN), and FortCraft's own copy in
# ~/.cache/fortcraft/jdk-25. If none is Java 25, it downloads Eclipse Temurin 25 (the free
# OpenJDK build from adoptium.net) into that folder once. Nothing system-wide is changed.

fortcraft_java_major() {
	# Prints the major version of the java binary in $1 (a JDK folder), or nothing.
	[[ -x "$1/bin/java" ]] || return 0
	"$1/bin/java" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/; t; s/^[a-z]+ ([0-9]+).*/\1/'
}

fortcraft_find_java25() {
	local candidates=()
	[[ -n "${FORTCRAFT_JAVA_HOME:-}" ]] && candidates+=("$FORTCRAFT_JAVA_HOME")
	[[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME")
	if command -v java >/dev/null 2>&1; then
		candidates+=("$(dirname -- "$(dirname -- "$(readlink -f -- "$(command -v java)")")")")
	fi
	local d
	for d in /usr/lib/jvm/* /usr/lib64/jvm/* /opt/* /opt/java/* "$HOME"/.jdks/* "$HOME"/.sdkman/candidates/java/* "$HOME/.cache/fortcraft/jdk-25"; do
		[[ -d "$d" ]] && candidates+=("$d")
	done
	for d in ${candidates[@]+"${candidates[@]}"}; do
		if [[ "$(fortcraft_java_major "$d")" == "25" ]]; then
			echo "$d"
			return 0
		fi
	done
	return 1
}

fortcraft_download_java25() {
	local dest="$HOME/.cache/fortcraft/jdk-25" arch
	case "$(uname -m)" in
		x86_64|amd64) arch=x64 ;;
		aarch64|arm64) arch=aarch64 ;;
		*) echo "Unknown CPU type $(uname -m); please install Java 25 yourself." >&2; return 1 ;;
	esac
	local url="https://api.adoptium.net/v3/binary/latest/25/ga/linux/$arch/jdk/hotspot/normal/eclipse"
	echo "No Java 25 found. Downloading Eclipse Temurin 25 (about 200 MB) into $dest ..." >&2
	local tmp
	tmp=$(mktemp -d) || return 1
	if command -v curl >/dev/null 2>&1; then
		curl -fL --progress-bar -o "$tmp/jdk.tar.gz" "$url" || { rm -rf "$tmp"; return 1; }
	elif command -v wget >/dev/null 2>&1; then
		wget -q --show-progress -O "$tmp/jdk.tar.gz" "$url" || { rm -rf "$tmp"; return 1; }
	else
		echo "Neither curl nor wget is installed; please install Java 25 yourself." >&2
		rm -rf "$tmp"
		return 1
	fi
	mkdir -p "$tmp/x" && tar -xzf "$tmp/jdk.tar.gz" -C "$tmp/x" || { rm -rf "$tmp"; return 1; }
	local top
	top=$(find "$tmp/x" -mindepth 1 -maxdepth 1 -type d | head -1)
	rm -rf "$dest" && mkdir -p "$(dirname -- "$dest")" && mv "$top" "$dest"
	rm -rf "$tmp"
	[[ "$(fortcraft_java_major "$dest")" == "25" ]]
}

if FORTCRAFT_JDK=$(fortcraft_find_java25) || { fortcraft_download_java25 && FORTCRAFT_JDK="$HOME/.cache/fortcraft/jdk-25"; }; then
	export JAVA_HOME="$FORTCRAFT_JDK"
	export PATH="$JAVA_HOME/bin:$PATH"
	echo "Using Java 25 from $JAVA_HOME" >&2
else
	echo "Could not find or download Java 25. Install it (e.g. Temurin 25), then run again," >&2
	echo "or point FORTCRAFT_JAVA_HOME at its folder: FORTCRAFT_JAVA_HOME=/path/to/jdk-25 tools/play.sh" >&2
	return 1 2>/dev/null || exit 1
fi
