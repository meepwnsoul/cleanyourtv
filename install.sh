#!/usr/bin/env bash
# One command: put this fork's launcher and its desktops on an Android TV.
#
#   TV_HOST=192.168.1.50 ./install.sh
#
# What it does, in order:
#   1. Checks it can reach the TV over ADB (Developer options > USB / network debugging on, and accept the
#      "Allow debugging?" prompt on the TV the first time).
#   2. Optionally applies a debloat list, if you name one: DEBLOAT=examples/<your tv>/debloat.txt.
#   3. Installs the ready-made launcher from dist/ (no Java or Android SDK needed) and makes it Home, with the
#      same safety checks as launcher/build-and-install.sh. On TVs that refuse any other launcher (some
#      China-market TCL sets) it installs the same app beside the stock home instead.
#   4. Stores the desktops on the TV (turntable, bubbles, and the classic themes) and shows one of them.
#      Switch later with the remote: Launcher settings > Desktop, or hold OK on Settings > Switch desktop.
#
# Settings, all optional:
#   DESKTOP=turntable          the desktop shown first (turntable, bubbles, default, aurora, glass, scenes, ...)
#   DESKTOPS="turntable ..."   which desktops to store (folders in themes/)
#   MODE=app                   always install beside the stock home instead of replacing it
#   DEBLOAT=<debloat.txt>      also disable the packages in this list (see examples/)
#
# Undo: tools/restore.sh examples/<your tv>/debloat.txt   (or, for MODE=app: tools/tv.py 'pm uninstall com.gathin.tvhome')
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 ./install.sh}"
export TV_HOST
DESKTOP="${DESKTOP:-turntable}"
DESKTOPS="${DESKTOPS:-turntable bubbles default aurora glass scenes}"
MODE="${MODE:-home}"

say() { printf '\n== %s\n' "$*"; }

# Python tools: a venv in tools/, made once.
PY=tools/venv/bin/python
[ -x "$PY" ] || PY=tools/venv/Scripts/python.exe
if [ ! -x "$PY" ]; then
  say "Setting up the Python tools (once)"
  python3 -m venv tools/venv
  PY=tools/venv/bin/python
  [ -x "$PY" ] || PY=tools/venv/Scripts/python.exe
  "$PY" -m pip install -q -r tools/requirements.txt
fi
tv() { "$PY" -I tools/tv.py "$@"; }

say "Connecting to the TV at $TV_HOST"
SDK=$(tv 'getprop ro.build.version.sdk' 2>/dev/null | tr -dc '0-9' || true)
if [ -z "$SDK" ]; then
  echo "Could not reach the TV. Check that it is on, on the same network, that USB or network debugging is on" >&2
  echo "in Developer options, and accept the \"Allow debugging?\" prompt on the TV, then run this again." >&2
  exit 1
fi
MODEL=$(tv 'getprop ro.product.model' 2>/dev/null | tr -d '\r' || true)
MEM_KB=$(tv 'grep MemTotal /proc/meminfo' 2>/dev/null | tr -dc '0-9' || true)
MEM_KB=${MEM_KB:-0}
echo "TV: ${MODEL:-unknown model}, Android SDK $SDK, $((MEM_KB / 1024)) MB memory"
if [ "$SDK" -lt 26 ]; then
  echo "This TV runs Android older than 8.0 (SDK $SDK); the launcher needs SDK 26 or newer. Only debloating works here." >&2
  exit 1
fi

if [ -n "${DEBLOAT:-}" ]; then
  say "Disabling the packages in $DEBLOAT"
  tools/disable.sh "$DEBLOAT"
fi

if [ "$MODE" != app ]; then
  say "Installing the launcher and making it Home"
  LOG=$(mktemp)
  set +e
  PREBUILT_APK="$(pwd)/dist/tvhome.apk" launcher/build-and-install.sh 2>&1 | tee "$LOG"
  STATUS=${PIPESTATUS[0]}
  set -e
  if [ "$STATUS" -ne 0 ]; then
    if grep -qE 'not signature by tcl|INCONSISTENT_CERTIFICATES|has be disabled from pm' "$LOG"; then
      echo
      echo "This TV refuses third-party launchers. Installing it beside the stock home instead."
      MODE=app
    else
      rm -f "$LOG"
      echo >&2
      echo "The launcher was not installed (see above); the TV's own home was left as it was." >&2
      echo "If this TV does not allow another launcher, run again with MODE=app." >&2
      exit 1
    fi
  fi
  rm -f "$LOG"
fi
if [ "$MODE" = app ]; then
  say "Installing the launcher as an app (the stock home stays)"
  PREBUILT_APK="$(pwd)/dist/tvhome-app.apk" launcher/install-as-app.sh
fi

# The video desktop is for TVs with memory to spare.
LIST=""
for d in $DESKTOPS; do
  if [ "$d" = bubbles ] && [ "$MEM_KB" -gt 0 ] && [ "$MEM_KB" -lt 1800000 ]; then
    echo "Skipping the bubbles video desktop: it needs about 2 GB of memory, this TV has $((MEM_KB / 1024)) MB."
    continue
  fi
  [ -f "themes/$d/config.json" ] || { echo "No theme folder themes/$d, skipped." >&2; continue; }
  LIST="$LIST themes/$d"
done
if [ "$DESKTOP" = bubbles ] && [ "$MEM_KB" -gt 0 ] && [ "$MEM_KB" -lt 1800000 ]; then DESKTOP=turntable; fi

if [ -n "$LIST" ]; then
  say "Storing the desktops on the TV (the video takes a minute)"
  # shellcheck disable=SC2086
  tools/theme.sh --add $LIST
fi
say "Showing the $DESKTOP desktop"
tools/theme.sh "themes/$DESKTOP"

cat <<EOF

Done.
- Switch desktops with the remote: Launcher settings > Desktop, or hold OK on the Settings button > Switch desktop.
- On the turntable: Left/Right pick an app, Down goes to the knobs (Inputs, Wi-Fi, Settings), OK opens.
EOF
if [ "$MODE" = app ]; then
  echo "- This TV keeps its own home: open CleanYourTV from its app list. To show it when the TV is idle:"
  echo "  TV_HOST=$TV_HOST $PY -I tools/tv.py 'settings put secure screensaver_components com.gathin.tvhome/.ClockDream; settings put secure screensaver_enabled 1'"
  echo "- Remove it: TV_HOST=$TV_HOST $PY -I tools/tv.py 'pm uninstall com.gathin.tvhome'"
else
  echo "- To undo, see \"Restoring your TV\" in README.md (tools/restore.sh puts the stock launcher back)."
fi
if [ -z "${DEBLOAT:-}" ]; then
  echo "- Ads and telemetry are not removed yet. If examples/ has your model, run again with"
  echo "  DEBLOAT=examples/<your tv>/debloat.txt, or let an AI assistant find them for you (使用指南.md)."
fi
