#!/usr/bin/env bash
# Rebuild only CinemaHall. The operator must explicitly authorize database reset.
set -Eeuo pipefail
umask 022
if [[ ${1:-} != --reset-cinemahall || ! ${2:-} =~ ^[0-9a-f]{40}$ ]]; then
  echo 'Usage: sudo bash rebuild-api.sh --reset-cinemahall COMMIT_SHA [SNAPSHOT_DIRECTORY]' >&2
  exit 2
fi
[[ $EUID == 0 ]] || { echo 'Run as root.' >&2; exit 2; }
exec 9>/run/lock/cinemahall-api-rebuild.lock
flock -n 9 || { echo 'A rebuild is already running.' >&2; exit 2; }
for command in git node npm python3 mariadb mariadb-dump gzip tar curl systemd-run; do
  command -v "$command" >/dev/null
done
node -e 'if(Number(process.versions.node.split(".")[0])<22)process.exit(1)'
id cinemahall >/dev/null
[[ -f /etc/cinemahall/database.env ]]
repo=/opt/cinemahall-source
commit=$2
reuse=${3:-}
if [[ -n $reuse ]]; then
  [[ $reuse == /var/lib/cinemahall/rebuild-* && -s $reuse/index.json && -d $reuse/shards ]] || { echo 'Invalid saved snapshot directory.' >&2; exit 2; }
fi
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup=/root/cinemahall-rebuild-$stamp
release=/opt/cinemahall-api/releases/$commit-$stamp
data=/var/lib/cinemahall/rebuild-$stamp
stage=cinemahall_build_${stamp//[^0-9]/}
unit=/etc/systemd/system/cinemahall-api.service
dropins=$unit.d
cutover=0
unit_changed=0
had_unit=0
had_dropins=0
had_runtime=0
smoke_running=0
smoke_unit=cinemahall-rebuild-smoke-$stamp.service
node_bin=$(command -v node)
python_bin=$(command -v python3)
install -d -m 700 "$backup"
install -d -m 755 "$release"
install -d -o cinemahall -g cinemahall -m 755 "$data"
echo "Backup: $backup"

# A failure after cutover restores the original database and service definition.
recover() {
  result=$?
  trap - EXIT
  if (( smoke_running == 1 )); then
    if (( result != 0 )); then journalctl -u "$smoke_unit" -n 40 --no-pager >&2 || true; fi
    systemctl stop "$smoke_unit" >/dev/null 2>&1 || true
  fi
  if (( result != 0 && cutover == 1 )); then
    echo "Rebuild failed; restoring CinemaHall from $backup" >&2
    set +e
    systemctl stop cinemahall-api.service
    mariadb -e 'DROP DATABASE IF EXISTS cinemahall; CREATE DATABASE cinemahall CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;'
    gzip -dc "$backup/database.sql.gz" | mariadb cinemahall
    restored=$?
    if (( unit_changed == 1 )); then
      if (( had_unit == 1 )); then cp -a "$backup/previous.service" "$unit"; else rm -f "$unit"; fi
      if (( had_dropins == 1 )); then mv "$backup/previous-dropins" "$dropins"; fi
      if (( had_runtime == 1 )); then
        cp -p "$backup/previous-runtime.env" /etc/cinemahall/rebuild-runtime.env
      else
        rm -f /etc/cinemahall/rebuild-runtime.env
      fi
    fi
    systemctl daemon-reload
    systemctl restart cinemahall-api.service
    restarted=$?
    if (( restored == 0 && restarted == 0 )); then
      echo 'Original database and service restored. Inspect the original error.' >&2
    else
      echo "RECOVERY NEEDS ATTENTION. Backup: $backup" >&2
    fi
  elif (( result != 0 )); then
    echo "Preparation failed; the production database was not reset. Files: $backup and $data" >&2
  fi
  exit "$result"
}
trap recover EXIT

git -c safe.directory="$repo" -C "$repo" cat-file -e "$commit^{commit}"
git -c safe.directory="$repo" -C "$repo" archive "$commit" backend scripts/build_catalog.py | tar -x -C "$release"
cd "$release/backend"
npm ci --omit=dev --ignore-scripts --no-audit --no-fund
node --check server.mjs
node --check import-snapshot.mjs
node --check catalog-v2.mjs
systemctl cat cinemahall-api.service > "$backup/previous-unit.txt"
if [[ -f $unit ]]; then cp -L --preserve=mode,timestamps "$unit" "$backup/previous.service"; had_unit=1; fi
if [[ -f /etc/cinemahall/rebuild-runtime.env ]]; then
  cp -p /etc/cinemahall/rebuild-runtime.env "$backup/previous-runtime.env"
  had_runtime=1
fi
for path in $(systemctl show cinemahall-api.service -p DropInPaths --value); do
  [[ $path == /etc/systemd/system/cinemahall-api.service.d/* ]] || { echo "Unsupported external service override: $path" >&2; exit 1; }
done
if [[ -d $dropins ]]; then cp -a "$dropins" "$backup/saved-dropins"; fi

job() {
  local label=$1
  shift
  systemd-run --quiet --wait --pipe --collect --unit="cinemahall-rebuild-$label-$stamp" \
    --uid=cinemahall --working-directory="$release/backend" \
    --property=EnvironmentFile=/etc/cinemahall/database.env "$@"
}
# Resolve the configured SQL account without printing credentials. env overrides
# DB_NAME after systemd has loaded the existing protected EnvironmentFile.
account=$(job account "$node_bin" --input-type=module -e '
import mariadb from "mariadb";
if(!process.env.KINOAPI_KEY)throw Error("KINOAPI_KEY is missing in /etc/cinemahall/database.env");
const db=await mariadb.createConnection({host:"127.0.0.1",port:3306,user:process.env.DB_USER||"cinemahall",password:process.env.DB_PASSWORD,database:"cinemahall"});
try{const rows=await db.query("SELECT CURRENT_USER() AS account");console.log(rows[0].account);}finally{await db.end();}')
[[ $account =~ ^[A-Za-z0-9_-]+@(localhost|127\.0\.0\.1|%)$ ]] || { echo 'Unsupported database account format.' >&2; exit 1; }
db_user=${account%@*}
db_host=${account#*@}

# Verify that the backup can actually be restored before deleting anything.
(umask 077; mariadb-dump --single-transaction --quick --skip-lock-tables cinemahall | gzip > "$backup/database.sql.gz")
gzip -t "$backup/database.sql.gz"
mariadb -e "CREATE DATABASE \`$stage\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
gzip -dc "$backup/database.sql.gz" | mariadb "$stage"
echo 'Backup restore verified in a temporary database.'
mariadb -e "DROP DATABASE \`$stage\`; CREATE DATABASE \`$stage\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; GRANT ALL PRIVILEGES ON \`$stage\`.* TO '$db_user'@'$db_host';"

echo 'Preparing fresh metadata and episodes. This may take several minutes.'
if [[ -n $reuse ]]; then
  echo "Reusing previously prepared snapshot: $reuse"
  cp -a "$reuse/index.json" "$reuse/shards" "$data/"
else
  job snapshot "$python_bin" -u "$release/scripts/build_catalog.py" --pages 15 --enrich 0 --details 100 --season-limit 20 --availability 0 --output "$data/index.json"
fi
mariadb "$stage" < schema.sql
# Fresh schema.sql already contains the index from migration 001.
for migration in migrations/00[2345]_*.sql; do mariadb "$stage" < "$migration"; done
job import /usr/bin/env "DB_NAME=$stage" DB_HOST=127.0.0.1 DB_PORT=3306 "$node_bin" import-snapshot.mjs "$data"
job check /usr/bin/env "DB_NAME=$stage" DB_HOST=127.0.0.1 DB_PORT=3306 "$node_bin" backfill-catalog-identity.mjs
job counts /usr/bin/env "DB_NAME=$stage" "$node_bin" --input-type=module -e '
import mariadb from "mariadb";
const db=await mariadb.createConnection({host:"127.0.0.1",user:process.env.DB_USER||"cinemahall",password:process.env.DB_PASSWORD,database:process.env.DB_NAME,bigIntAsNumber:true});
try{const [row]=await db.query("SELECT (SELECT COUNT(*) FROM titles WHERE kind=\u0027movie\u0027) AS movies,(SELECT COUNT(*) FROM titles WHERE kind=\u0027series\u0027) AS series,(SELECT COUNT(*) FROM episodes) AS episodes");console.log(JSON.stringify(row));if(!row.movies||!row.series||!row.episodes)throw Error("Incomplete catalogue: production reset canceled");}finally{await db.end();}'
(umask 077; mariadb-dump --single-transaction --quick --skip-lock-tables "$stage" | gzip > "$backup/new-database.sql.gz")
gzip -t "$backup/new-database.sql.gz"

probe() {
  local base=$1 label=$2 ready=0
  for attempt in {1..30}; do
    if curl --fail --silent --show-error --max-time 5 "$base/health" > "$backup/$label-health.json" 2> "$backup/$label-health-error.log"; then ready=1; break; fi
    sleep 1
  done
  if (( ready == 0 )); then cat "$backup/$label-health-error.log" >&2; return 1; fi
  curl --fail --silent --show-error --max-time 15 "$base/api/v2/config" > "$backup/$label-config.json"
  curl --fail --silent --show-error --max-time 60 --output "$backup/$label-home.json" --write-out "$label /home: %{time_total}s\n" "$base/api/v2/home"
  "$node_bin" -e 'const fs=require("fs");const c=JSON.parse(fs.readFileSync(process.argv[1]));const h=JSON.parse(fs.readFileSync(process.argv[2]));if(c.apiVersion!==2||h.rows.length!==8||!h.rows.some(r=>r.items.length))process.exit(1)' "$backup/$label-config.json" "$backup/$label-home.json"
}

# Exercise the actual server and its production-sized pool against the staged
# database before deleting production data. Background writers stay disabled.
echo 'Testing the new API on 127.0.0.1:18091 before production cutover.'
"$python_bin" -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",18091)); s.close()'
smoke_running=1
systemd-run --quiet --collect --unit="$smoke_unit" --uid=cinemahall \
  --working-directory="$release/backend" --property=EnvironmentFile=/etc/cinemahall/database.env \
  --property=RuntimeMaxSec=240 --property=TimeoutStopSec=10 \
  /usr/bin/env "DB_NAME=$stage" DB_HOST=127.0.0.1 DB_PORT=3306 CINEMA_API_PORT=18091 CINEMA_DISABLE_SCHEDULERS=1 "$node_bin" server.mjs
probe http://127.0.0.1:18091 staged
systemctl stop "$smoke_unit"
smoke_running=0
echo 'Staged API health, config and all eight home rows passed.'

echo 'Replacing CinemaHall database and service.'
cutover=1
systemctl stop cinemahall-api.service
mariadb -e 'DROP DATABASE cinemahall; CREATE DATABASE cinemahall CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;'
gzip -dc "$backup/new-database.sql.gz" | mariadb cinemahall
if [[ -d $dropins ]]; then mv "$dropins" "$backup/previous-dropins"; had_dropins=1; fi
unit_changed=1
install -d -m 755 /etc/cinemahall
cat > "$data/runtime.env" <<'RUNTIME'
DB_NAME=cinemahall
DB_HOST=127.0.0.1
DB_PORT=3306
CINEMA_API_PORT=8091
CINEMA_SOURCE_DISCOVERY=1
CINEMA_REFRESH_ENABLED=1
RUNTIME
install -o root -g root -m 600 "$data/runtime.env" /etc/cinemahall/rebuild-runtime.env
cat > "$data/api.service" <<UNIT
[Unit]
Description=CinemaHall catalogue API
After=network.target mariadb.service
[Service]
Type=simple
User=cinemahall
Group=cinemahall
WorkingDirectory=$release/backend
EnvironmentFile=/etc/cinemahall/database.env
EnvironmentFile=/etc/cinemahall/rebuild-runtime.env
ExecStart=$node_bin $release/backend/server.mjs
Restart=on-failure
RestartSec=3
TimeoutStopSec=20
NoNewPrivileges=true
PrivateTmp=true
UMask=0027
[Install]
WantedBy=multi-user.target
UNIT
rm -f "$unit"
install -o root -g root -m 644 "$data/api.service" "$unit"
systemctl daemon-reload
systemctl restart cinemahall-api.service
probe http://127.0.0.1:8091 production
systemctl enable --force cinemahall-api.service
cutover=0
# Recovery is no longer necessary. Leave both SQL dumps and old code intact.
mariadb -e "DROP DATABASE \`$stage\`; REVOKE ALL PRIVILEGES ON \`$stage\`.* FROM '$db_user'@'$db_host';" || echo 'Temporary database cleanup needs attention.' >&2
echo
echo "REBUILD_OK release=$release backup=$backup"
echo 'CinemaHall API uses the new code and database on 127.0.0.1:8091.'
