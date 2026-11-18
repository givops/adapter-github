#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
sdk_revision=$(cat "$repo_root/sdk-revision.txt")
sdk_checkout=${GIVOPS_SDK_SOURCE:-}
maven_repo=${GIVOPS_MAVEN_REPO:-$repo_root/.m2-build}
[[ "$maven_repo" = /* ]] || maven_repo="$PWD/$maven_repo"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
if [[ -z "$sdk_checkout" ]]; then
  if [[ ! "$sdk_revision" =~ ^[0-9a-f]{40}$ ]]; then
    echo 'Set the reviewed public SDK commit in sdk-revision.txt or supply GIVOPS_SDK_SOURCE for coordinated development.' >&2
    exit 1
  fi
  sdk_checkout="$work/sdk"
  GIT_TERMINAL_PROMPT=0 git clone --quiet https://github.com/givops/adapter-sdk.git "$sdk_checkout"
  git -C "$sdk_checkout" checkout --quiet --detach "$sdk_revision"
  [[ $(git -C "$sdk_checkout" rev-parse HEAD) == "$sdk_revision" ]]
else
  mkdir "$work/sdk"
  tar -C "$sdk_checkout" --exclude=.git --exclude=target --exclude=.m2 --exclude=.m2-local --exclude=.m2-build -cf - . | tar -C "$work/sdk" -xf -
  sdk_checkout="$work/sdk"
fi
mvn -B -Dmaven.repo.local="$maven_repo" -f "$sdk_checkout/pom.xml" clean install
mvn -B -Dmaven.repo.local="$maven_repo" -f "$repo_root/pom.xml" clean verify
