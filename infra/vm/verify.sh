#!/bin/bash
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
names=()
ips=()
while IFS=$'\t' read -r name _ _ ip _; do
  [ -z "$name" ] && continue
  names+=("$name")
  ips+=("$ip")
done < "$HERE/vms.tsv"

for name in "${names[@]}"; do
  echo "===== $name"
  ssh -n -o ConnectTimeout=10 "$name" 'cloud-init status --wait >/dev/null; cloud-init status; id; hostname; nproc; free -m; df -h / /var/lib/docker; ip -4 addr; docker version --format {{.Server.Version}}; docker compose version; docker info --format "{{.DockerRootDir}} {{.Driver}}"; docker run --rm hello-world'
  for ip in "${ips[@]}"; do
    ssh -n "$name" "ping -c1 -W2 $ip >/dev/null && echo 'ping $ip ok' || echo 'ping $ip FAIL'"
  done
  ping -c1 -t2 "$(ssh -G "$name" | awk '$1=="hostname"{print $2}')" >/dev/null && echo "host -> $name ok" || echo "host -> $name FAIL"
done
