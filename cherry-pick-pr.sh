#!/bin/bash
set -euo pipefail

if [ -z "${1:-}" ]; then
  echo "Usage: $0 <PR_NUMBER>"
  exit 1
fi

PR_NUMBER="$1"

# Ensure clean working tree
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "Error: Working tree is not clean. Please commit or stash changes first."
  exit 1
fi

# Get PR title
PR_TITLE=$(gh pr view "$PR_NUMBER" --json title --jq '.title')
if [ -z "$PR_TITLE" ]; then
  echo "Error: Could not fetch PR #$PR_NUMBER"
  exit 1
fi
echo "PR #$PR_NUMBER: $PR_TITLE"

# Download and apply patch
PATCH_FILE=$(mktemp /tmp/pr-"$PR_NUMBER"-XXXXXX.patch)
trap 'rm -f "$PATCH_FILE"' EXIT

echo "Downloading patch..."
gh pr diff "$PR_NUMBER" > "$PATCH_FILE"

echo "Applying patch..."
if ! git apply --check "$PATCH_FILE" 2>/dev/null; then
  echo "Error: Patch does not apply cleanly. There may be conflicts."
  exit 1
fi
git apply "$PATCH_FILE"

# Stage and commit
git add -A
git commit -m "$PR_TITLE (cherry-pick from PR #$PR_NUMBER)"

echo "Successfully applied and committed PR #$PR_NUMBER"