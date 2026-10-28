#!/usr/bin/env bash
# Update code only. Existing catalogue, accounts and history stay in MariaDB.
set -Eeuo pipefail
umask 022
[[ $EUID == 0 && ${1:-} =~ ^[0-9a-f]{40}$ ]] || { echo 'Usage: sudo bash update-api.sh COMMIT_SHA [PUBLIC_IMAGE_ROUTE_HINTS]' >&2; exit 2; }
hints=${2:-}
exec 9>/run/lock/cinemahall-api-rebuild.lock
flock -n 9 || { echo 'Another API deployment is running.' >&2; exit 2; }
repo=/opt/cinemahall-source
stamp=$(date -u +%Y%m%dT%H%M%SZ)
started=$(date +%s)
release=/opt/cinemahall-api/releases/$1-$stamp
backup=/root/cinemahall-update-$stamp
unit=/etc/systemd/system/cinemahall-api.service
smoke=cinemahall-update-smoke-$stamp.service
changed=0
node_bin=$(command -v node)
install -d -m 700 "$backup"
install -d -m 755 "$release"
install -d -o cinemahall -g cinemahall -m 700 /var/lib/cinemahall/image-cache
[[ -f $unit && -f /etc/cinemahall/rebuild-runtime.env ]]
cp -L --preserve=mode,timestamps "$unit" "$backup/previous.service"
cleanup(){
  code=$?;trap - EXIT
  if (( code != 0 )); then
    journalctl -u "$smoke" --since "@$started" -n 40 --no-pager >&2 || true
    if (( changed == 1 )); then journalctl -u cinemahall-api.service --since "@$started" -n 80 --no-pager >&2 || true; fi
  fi
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
echo 'Preparing verified watchable shelves, actor credits and episode inventories.'
systemd-run --quiet --wait --pipe --collect --unit="cinemahall-discovery-$stamp" --uid=cinemahall --working-directory="$release/backend" \
  --property=EnvironmentFile=/etc/cinemahall/database.env --property=RuntimeMaxSec=1800 \
  "$node_bin" prepare-discovery.mjs --full
"$node_bin" --input-type=module -e 'import {publicImageIPv4} from "./image-transport.mjs";const hints=process.argv[1];if(hints&&(!hints.split(",").every(publicImageIPv4)||hints.split(",").length>3))throw Error("Invalid public image route hints")' "$hints"
"$node_bin" - "$backup/previous.service" "$backup/new.service" "$release" "$node_bin" <<'NODE'
const fs=require('fs');const [previous,next,release,node]=process.argv.slice(2);let unit=fs.readFileSync(previous,'utf8');
if((unit.match(/^WorkingDirectory=/gm)||[]).length!==1||(unit.match(/^ExecStart=/gm)||[]).length!==1)throw Error('Unexpected existing service definition');
unit=unit.replace(/^WorkingDirectory=.*$/m,'WorkingDirectory='+release+'/backend').replace(/^ExecStart=.*$/m,'ExecStart='+node+' '+release+'/backend/server.mjs');fs.writeFileSync(next,unit);
NODE
python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",18091)); s.close()'
systemd-run --quiet --collect --unit="$smoke" --uid=cinemahall --working-directory="$release/backend" \
  --property=EnvironmentFile=/etc/cinemahall/database.env --property=RuntimeMaxSec=240 --property=TimeoutStopSec=10 \
  /usr/bin/env DB_NAME=cinemahall CINEMA_API_PORT=18091 CINEMA_DISABLE_SCHEDULERS=1 CINEMA_SOURCE_DISCOVERY=1 "CINEMA_IMAGE_ROUTE_HINTS=$hints" "$node_bin" server.mjs
probe(){
  local base=$1 label=$2 ready=0
  for attempt in {1..20}; do
    if curl --fail --silent --max-time 5 "$base/health" > "$backup/$label-health.json"; then ready=1;break;fi
    sleep 1
  done
  (( ready == 1 ))
  echo "$label: checking home"
  curl --fail-with-body --silent --show-error --max-time 60 "$base/api/v2/home" > "$backup/$label-home.json"
  local image
  image=$("$node_bin" -e 'const fs=require("fs");const h=JSON.parse(fs.readFileSync(process.argv[1]));const image=h.rows.flatMap(r=>r.items).map(i=>i.poster).find(v=>v&&v.includes("/api/v2/images/tmdb/"));if(!image)throw Error("No server-hosted posters returned");console.log(new URL(image).pathname)' "$backup/$label-home.json")
  echo "$label: checking catalogue filters"
  curl --fail-with-body --silent --show-error --max-time 30 "$base/api/v2/facets" > "$backup/$label-facets.json"
  "$node_bin" -e 'const fs=require("fs");const f=JSON.parse(fs.readFileSync(process.argv[1]));if(!Array.isArray(f.genres)||!f.genres.length||!Array.isArray(f.countries)||!Array.isArray(f.years)||!f.years.length)throw Error("Catalogue filters unavailable");console.log("Filters: "+f.genres.length+" genres, "+f.countries.length+" countries, "+f.years.length+" years")' "$backup/$label-facets.json"
  echo "$label: checking real poster bytes"
  curl --fail-with-body --silent --show-error --max-time 30 "$base$image" > "$backup/$label-poster.jpg"
  echo "$label: checking episode selection and actor filmography"
  curl --fail-with-body --silent --show-error --max-time 90 "$base/api/v2/titles/ch:snapshot:tv:tmdb:1668/tv-meta" > "$backup/$label-tv-meta.json"
  local artwork
  artwork=$("$node_bin" -e 'const fs=require("fs");const m=JSON.parse(fs.readFileSync(process.argv[1])).meta;const v=m.videos?.find(v=>v.season>0&&v.episode>0&&v.thumbnail);if(!v)throw Error("Episode artwork missing");const u=new URL(v.thumbnail);if(u.origin!=="https://cinemahome.duckdns.org"||!u.pathname.startsWith("/api/v2/images/tmdb/"))throw Error("Unexpected episode artwork origin");console.log(u.pathname)' "$backup/$label-tv-meta.json")
  curl --fail-with-body --silent --show-error --max-time 30 "$base$artwork" > "$backup/$label-episode.jpg"
  local person
  person=$("$node_bin" -e 'const fs=require("fs");const m=JSON.parse(fs.readFileSync(process.argv[1])).meta;if(!m.videos?.some(v=>v.season>0&&v.episode>0))throw Error("No selectable series episodes");const p=m.app_extras?.cast?.find(p=>/^person:[a-f0-9]{32}$/.test(p.personId));if(!p)throw Error("No navigable actors returned");console.log(p.personId)' "$backup/$label-tv-meta.json")
  curl --fail-with-body --silent --show-error --max-time 30 "$base/api/v2/people/$person/filmography?limit=2" > "$backup/$label-filmography.json"
  "$node_bin" -e 'const fs=require("fs");const f=JSON.parse(fs.readFileSync(process.argv[1]));if(!f.person?.id||!f.items?.length)throw Error("Actor filmography is empty");console.log("Server episodes and actor filmography verified")' "$backup/$label-filmography.json"
  echo "$label: checking source discovery"
  curl --fail-with-body --silent --show-error --max-time 30 "$base/api/v2/titles/ch:snapshot:tv:tmdb:1668/source-candidates?episode=S1E1" > "$backup/$label-sources.json"
  "$node_bin" -e 'const fs=require("fs");const s=JSON.parse(fs.readFileSync(process.argv[1]));if(s.status!=="ready"||!s.candidates.length)throw Error("Source discovery check returned no candidates");console.log("Server poster and "+s.candidates.length+" source candidates verified")' "$backup/$label-sources.json"
}
echo 'Checking API, actual poster bytes and source discovery before switching.'
probe http://127.0.0.1:18091 staged
if [[ -s /var/lib/cinemahall/image-cache/routes.json ]]; then
  cp -p /var/lib/cinemahall/image-cache/routes.json "$backup/verified-image-routes.json"
fi
echo 'Verified poster bytes are persisted for the production process.'
systemctl stop "$smoke"
changed=1
install -o root -g root -m 644 "$backup/new.service" "$unit"
systemctl daemon-reload
systemctl restart cinemahall-api.service
probe http://127.0.0.1:8091 production
changed=0
echo "API_UPDATE_OK release=$release backup=$backup"
