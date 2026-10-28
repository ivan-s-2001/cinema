#!/usr/bin/env bash
# Update code only. Existing catalogue, accounts and history stay in MariaDB.
set -Eeuo pipefail
umask 022
[[ $EUID == 0 && ${1:-} =~ ^[0-9a-f]{40}$ ]] || { echo 'Usage: sudo bash update-api.sh COMMIT_SHA' >&2; exit 2; }
exec 9>/run/lock/cinemahall-api-rebuild.lock
flock -n 9 || { echo 'Another API deployment is running.' >&2; exit 2; }
repo=/opt/cinemahall-source
stamp=$(date -u +%Y%m%dT%H%M%SZ)
release=/opt/cinemahall-api/releases/$1-$stamp
backup=/root/cinemahall-update-$stamp
unit=/etc/systemd/system/cinemahall-api.service
smoke=cinemahall-update-smoke-$stamp.service
changed=0
node_bin=$(command -v node)
install -d -m 700 "$backup"
install -d -m 755 "$release"
[[ -f $unit && -f /etc/cinemahall/rebuild-runtime.env ]]
cp -L --preserve=mode,timestamps "$unit" "$backup/previous.service"
cleanup(){
  code=$?;trap - EXIT
  if (( code != 0 )); then journalctl -u "$smoke" -n 40 --no-pager >&2 || true; fi
  systemctl stop "$smoke" >/dev/null 2>&1 || true
  if (( code != 0 && changed == 1 )); then
    echo 'Update failed; restoring the previous service definition.' >&2
    cp "$backup/previous.service" "$unit"
    systemctl daemon-reload
    systemctl restart cinemahall-api.service
  fi
  if (( code != 0 )); then echo "Update stopped. Diagnostics: $backup" >&2; fi
  exit "$code"
}
trap cleanup EXIT
git -c safe.directory="$repo" -C "$repo" archive "$1" backend | tar -x -C "$release"
cd "$release/backend"
npm ci --omit=dev --ignore-scripts --no-audit --no-fund
node --check server.mjs
"$node_bin" - "$backup/previous.service" "$backup/new.service" "$release" "$node_bin" <<'NODE'
const fs=require('fs');const [previous,next,release,node]=process.argv.slice(2);let unit=fs.readFileSync(previous,'utf8');
if((unit.match(/^WorkingDirectory=/gm)||[]).length!==1||(unit.match(/^ExecStart=/gm)||[]).length!==1)throw Error('Unexpected existing service definition');
unit=unit.replace(/^WorkingDirectory=.*$/m,'WorkingDirectory='+release+'/backend').replace(/^ExecStart=.*$/m,'ExecStart='+node+' '+release+'/backend/server.mjs');fs.writeFileSync(next,unit);
NODE
python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",18091)); s.close()'
systemd-run --quiet --collect --unit="$smoke" --uid=cinemahall --working-directory="$release/backend" \
  --property=EnvironmentFile=/etc/cinemahall/database.env --property=RuntimeMaxSec=240 --property=TimeoutStopSec=10 \
  /usr/bin/env DB_NAME=cinemahall CINEMA_API_PORT=18091 CINEMA_DISABLE_SCHEDULERS=1 CINEMA_SOURCE_DISCOVERY=1 "$node_bin" server.mjs
probe(){
  local base=$1 label=$2 ready=0
  for attempt in {1..20}; do
    if curl --fail --silent --max-time 5 "$base/health" > "$backup/$label-health.json"; then ready=1;break;fi
    sleep 1
  done
  (( ready == 1 ))
  curl --fail --silent --show-error --max-time 60 "$base/api/v2/home" > "$backup/$label-home.json"
  local image
  image=$("$node_bin" -e 'const fs=require("fs");const h=JSON.parse(fs.readFileSync(process.argv[1]));const image=h.rows.flatMap(r=>r.items).map(i=>i.poster).find(v=>v&&v.includes("/api/v2/images/tmdb/"));if(!image)throw Error("No server-hosted posters returned");console.log(new URL(image).pathname)' "$backup/$label-home.json")
  curl --fail --silent --show-error --max-time 30 "$base$image" > "$backup/$label-poster.jpg"
  curl --fail --silent --show-error --max-time 30 "$base/api/v2/titles/ch:snapshot:tv:tmdb:1668/source-candidates?episode=S1E1" > "$backup/$label-sources.json"
  "$node_bin" -e 'const fs=require("fs");const s=JSON.parse(fs.readFileSync(process.argv[1]));if(s.status!=="ready"||!s.candidates.length)throw Error("Source discovery check returned no candidates");console.log("Server poster and "+s.candidates.length+" source candidates verified")' "$backup/$label-sources.json"
}
echo 'Checking API, actual poster bytes and source discovery before switching.'
probe http://127.0.0.1:18091 staged
systemctl stop "$smoke"
changed=1
install -o root -g root -m 644 "$backup/new.service" "$unit"
systemctl daemon-reload
systemctl restart cinemahall-api.service
probe http://127.0.0.1:8091 production
changed=0
echo "API_UPDATE_OK release=$release backup=$backup"
