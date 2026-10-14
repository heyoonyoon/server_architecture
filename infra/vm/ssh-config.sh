#!/bin/bash
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CONFIG="${SSH_CONFIG:-$HOME/.ssh/config}"
KEY="${KEY:-~/.ssh/sa_vm}"
VMUSER="${VMUSER:-sa}"
BEGIN="# >>> sa-vm >>>"
END="# <<< sa-vm <<<"
OLD_BEGIN="# >>> sugang-vm >>>"
OLD_END="# <<< sugang-vm <<<"

touch "$CONFIG"
chmod 600 "$CONFIG"

block="$(mktemp)"
trap 'rm -f "$block" "$block.new"' EXIT
{
  echo "$BEGIN"
  while IFS=$'\t' read -r name _ _ ip _; do
    [ -z "$name" ] && continue
    printf 'Host %s\n  HostName %s\n  User %s\n  IdentityFile %s\n  IdentitiesOnly yes\n  StrictHostKeyChecking accept-new\n\n' "$name" "$ip" "$VMUSER" "$KEY"
  done < "$HERE/vms.tsv"
  echo "$END"
} > "$block"

if grep -qxF -e "$BEGIN" -e "$OLD_BEGIN" "$CONFIG"; then
  awk -v b1="$BEGIN" -v e1="$END" -v b2="$OLD_BEGIN" -v e2="$OLD_END" -v blk="$block" '
    !skip && ($0 == b1 || $0 == b2) {
      if (!done) { while ((getline l < blk) > 0) print l; done = 1 }
      skip = ($0 == b1) ? e1 : e2
      next
    }
    skip { if ($0 == skip) skip = ""; next }
    { print }
  ' "$CONFIG" > "$block.new"
  cat "$block.new" > "$CONFIG"
else
  { [ -s "$CONFIG" ] && echo; cat "$block"; } >> "$CONFIG"
fi
