#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
export PATH="$(go env GOPATH)/bin:$PATH"
if [ -n "${JAVA_HOME:-}" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
mobile_version=v0.0.0-20261011034528-197770444867

go install "golang.org/x/mobile/cmd/gomobile@$mobile_version"
go install "golang.org/x/mobile/cmd/gobind@$mobile_version"
gomobile init
mkdir -p "$project_dir/app/libs"
build_dir="$(mktemp -d)"
trap 'rm -rf "$build_dir"' EXIT
cd "$project_dir/backend"
gomobile bind -target=android/arm64,android/arm,android/amd64 -androidapi 26 -ldflags=-checklinkname=0 \
  -javapkg com.vkturn.backend -o "$build_dir/backend.aar" .
mv "$build_dir/backend.aar" "$project_dir/app/libs/backend.aar"

