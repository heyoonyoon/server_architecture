#!/bin/bash
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CACHE="${CACHE:-$HOME/.cache/sa-vm}"
PUBKEY_FILE="${PUBKEY_FILE:-$HOME/.ssh/sa_vm.pub}"
GATEWAY="${GATEWAY:-192.168.64.1}"
PREFIX="${PREFIX:-24}"
ROOT_SIZE="${ROOT_SIZE:-16G}"
PYTHON="${PYTHON:-$(python3 -c "import sys; print(sys.executable)")}"
IMG_URL="https://cloud-images.ubuntu.com/noble/current"
IMG_NAME="noble-server-cloudimg-arm64.img"
UTMCTL=/Applications/UTM.app/Contents/MacOS/utmctl
ONLY="${1:-}"

mkdir -p "$CACHE/seeds"

if [ ! -f "$PUBKEY_FILE" ]; then
  ssh-keygen -t ed25519 -N "" -C sa-vm -f "${PUBKEY_FILE%.pub}" -q
fi
PUBKEY="$(cat "$PUBKEY_FILE")"

curl -fsSL "$IMG_URL/SHA256SUMS" -o "$CACHE/SHA256SUMS"
if ! (cd "$CACHE" && grep " \*\{0,1\}$IMG_NAME\$" SHA256SUMS | shasum -a 256 -c - >/dev/null 2>&1); then
  curl -fL "$IMG_URL/$IMG_NAME" -o "$CACHE/$IMG_NAME"
  (cd "$CACHE" && grep " \*\{0,1\}$IMG_NAME\$" SHA256SUMS | shasum -a 256 -c -)
fi

render() {
  sed -e "s|@NAME@|$1|g" -e "s|@IP@|$2|g" -e "s|@PREFIX@|$PREFIX|g" -e "s|@GATEWAY@|$GATEWAY|g" \
      -e "s|@INSTANCE@|$3|g" -e "s|@PUBKEY@|$PUBKEY|g" "$4"
}

while IFS=$'\t' read -r name cpus mem ip datamb; do
  [ -z "$name" ] && continue
  [ -n "$ONLY" ] && [ "$ONLY" != "$name" ] && continue
  if "$UTMCTL" list | awk 'NR>1{print $3}' | grep -qx "$name"; then
    echo "$name already exists, skipping"
    continue
  fi
  seed="$CACHE/seeds/$name"
  rm -rf "$seed" "$seed-cidata.iso"
  mkdir -p "$seed"
  instance="$(date +%Y%m%d%H%M%S)"
  for f in user-data meta-data network-config; do
    render "$name" "$ip" "$instance" "$HERE/cloud-init/$f.tmpl" > "$seed/$f"
  done
  hdiutil makehybrid -quiet -iso -joliet -default-volume-name cidata -o "$seed-cidata.iso" "$seed"
  root="$CACHE/$name-root.qcow2"
  cp "$CACHE/$IMG_NAME" "$root"
  DYLD_FRAMEWORK_PATH=/Applications/UTM.app/Contents/Frameworks "$PYTHON" -I "$HERE/utm-qemu-img.py" resize "$root" "$ROOT_SIZE"
  uuid="$(osascript "$HERE/create-vm.applescript" "$name" "$cpus" "$mem" "$datamb" "$root" "$seed-cidata.iso")"
  rm -f "$root" "$seed-cidata.iso"
  echo "$name created: $uuid"
  "$UTMCTL" start "$uuid"
done < "$HERE/vms.tsv"
