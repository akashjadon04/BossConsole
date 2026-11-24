#!/usr/bin/env bash
# Shared helpers for Chromium release state and publishing steps.

load_chromium_required_assets() {
  local assets_file="$1"
  local asset=""
  CHROMIUM_REQUIRED_ASSETS=()

  [[ -f "$assets_file" ]] || {
    echo "ERROR: required asset list is unavailable: $assets_file" >&2
    return 1
  }
  while IFS= read -r asset || [[ -n "$asset" ]]; do
    if [[ "$asset" =~ [^[:space:]] ]]; then
      CHROMIUM_REQUIRED_ASSETS+=("$asset")
    fi
  done < "$assets_file"
  [[ "${#CHROMIUM_REQUIRED_ASSETS[@]}" -gt 0 ]] || {
    echo "ERROR: required asset list is empty: $assets_file" >&2
    return 1
  }
}

verify_chromium_release_assets() {
  local uploaded_assets="$1"
  local release_label="$2"
  local asset

  for asset in "${CHROMIUM_REQUIRED_ASSETS[@]}"; do
    if ! grep -Fqx "$asset" <<< "$uploaded_assets"; then
      echo "::error::$release_label is missing required asset: $asset"
      return 1
    fi
  done
}
