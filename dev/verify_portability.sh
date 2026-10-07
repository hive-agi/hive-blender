#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo"
seconds=${PORTABILITY_TIMEOUT:-120}
report=${PORTABILITY_REPORT_DIR:-$(mktemp -d -t blender-portability.XXXXXX)}
mkdir -p "$report"
printf 'host\tstatus\tseconds\n' > "$report/results.tsv"
if (( $# == 0 )); then set -- jvm cljw cljrs cljs; fi
for host in "$@"; do
  start=$SECONDS
  status=passed
  case "$host" in
    jvm) timeout "${seconds}s" clojure -M:dev -m portability > "$report/jvm.log" 2>&1 || status=failed ;;
    cljw) timeout "${seconds}s" cljw -cp src:dev dev/portability.cljc > "$report/cljw.log" 2>&1 || status=failed ;;
    cljrs) binary=${CLJRS:-/home/klein/PP/clojurust/target/debug/cljrs}
           timeout "${seconds}s" "$binary" run dev/portability.cljc --src-path src --src-path dev > "$report/cljrs.log" 2>&1 || status=failed ;;
    cljs) timeout "${seconds}s" clojure -M:cljs -m shadow.cljs.devtools.cli release portability > "$report/cljs-build.log" 2>&1 || status=failed
          if [[ $status == passed ]]; then timeout "${seconds}s" node target/portability.js > "$report/cljs.log" 2>&1 || status=failed; fi ;;
    *) echo "Unknown host $host" >&2; exit 2 ;;
  esac
  printf '%s\t%s\t%s\n' "$host" "$status" "$((SECONDS-start))" >> "$report/results.tsv"
  echo "$host: $status ($((SECONDS-start))s)"
  if [[ $status != passed ]]; then tail -20 "$report/$host.log" 2>/dev/null || true; exit 1; fi
done
echo "Report: $report"
