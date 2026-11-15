#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
sdk_revision=$(cat "$repo_root/sdk-revision.txt")
sdk_checkout=${GIVOPS_SDK_SOURCE:-}
maven_repo=${GIVOPS_MAVEN_REPO:-$repo_root/.m2-build}
if [[ -z "$sdk_checkout" ]]; then
  if [[ ! "$sdk_revision" =~ ^[0-9a-f]{40}$ ]]; then
    echo 'Set the reviewed public SDK commit in sdk-revision.txt or supply GIVOPS_SDK_SOURCE for coordinated development.' >&2
    exit 1
  fi
  sdk_checkout=$(mktemp -d)
  trap 'rm -rf "$sdk_checkout"' EXIT
  GIT_TERMINAL_PROMPT=0 git clone --quiet https://github.com/givops/adapter-sdk.git "$sdk_checkout"
  git -C "$sdk_checkout" checkout --quiet --detach "$sdk_revision"
  [[ $(git -C "$sdk_checkout" rev-parse HEAD) == "$sdk_revision" ]]
fi
mvn -B -Dmaven.repo.local="$maven_repo" -f "$sdk_checkout/pom.xml" clean install
mvn -B -Dmaven.repo.local="$maven_repo" -f "$repo_root/pom.xml" clean verify
