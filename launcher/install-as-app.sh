#!/usr/bin/env bash
# Build the launcher WITHOUT the HOME category and install it as an ordinary app, beside the stock home.
#
# For sets whose firmware refuses every third-party launcher at install time, whatever the method:
#   INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES: disable install launcher app not signature by tcl
# (TCL ak30a5 and other Chinese-market TCL/FFalcon sets, see examples/tcl-ak30a5/debloat.txt). The same app
# without HOME passes that check. Nothing on the TV is disabled and Home stays the stock launcher: open the
# app from the TV's app list, and use its screensaver (printed at the end).
#
# Usage: TV_HOST=192.168.1.50 launcher/install-as-app.sh
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
TOOLS="$(cd ../tools && pwd)"
PY=$TOOLS/venv/bin/python
[ -x "$PY" ] || PY=$TOOLS/venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
tv() { "$PY" -I "$TOOLS/tv.py" "$@"; }

# PREBUILT_APK=<file> installs that APK (install.sh passes dist/tvhome-app.apk) instead of building one.
if [ -z "${PREBUILT_APK:-}" ]; then
# Same JDK 17 and SDK lookup as build-and-install.sh.
if [ -z "${JAVA_HOME:-}" ]; then
  JH="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  if [ -n "$JH" ]; then
    case "$("$JH/bin/java" -version 2>&1 || true)" in *'version "17.'*) ;; *) JH="" ;; esac
  fi
  if [ -z "$JH" ] && [ -d /opt/homebrew/opt/openjdk@17 ]; then JH=/opt/homebrew/opt/openjdk@17; fi
  if [ -z "$JH" ]; then JH="$(java -XshowSettings:properties -version 2>&1 | awk '/java.home/ {print $3}')"; fi
  JAVA_HOME="$JH"
fi
export JAVA_HOME
export ANDROID_HOME="${ANDROID_HOME:-$(for d in ~/Library/Android/sdk ~/Android/Sdk; do [ -d "$d" ] && echo "$d" && break; done)}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease -PlauncherNoHome -q --console=plain
fi

APK="${PREBUILT_APK:-app/build/outputs/apk/release/app-release.apk}"
SIZE=$(wc -c < "$APK" | tr -d ' ')
# Some of these sets do not let the shell write /data/local/tmp; /sdcard always works.
REMOTE=/sdcard/tvhome-app.apk
"$PY" -I "$TOOLS/b64push.py" "$APK" "$REMOTE"

# Plain pm install first; firmware that patches it to refuse shell installs ("install apk has be disabled from
# pm by system default") still accepts a PackageInstaller session created, written and committed from the shell.
OUT=$(tv "pm install -r $REMOTE 2>&1" | tr -d '\r')
echo "$OUT"
case "$OUT" in
  *Success*) ;;
  *)
    echo "pm install refused; trying a PackageInstaller session."
    CREATE=$(tv "pm install-create -r -S $SIZE 2>&1" | tr -d '\r')
    echo "$CREATE"
    SID=$(printf '%s' "$CREATE" | grep -o '\[[0-9]*\]' | tr -d '[]' | head -n 1)
    [ -n "$SID" ] || { echo "ERROR: could not create an install session; nothing was changed on the TV." >&2; tv "rm -f $REMOTE"; exit 1; }
    tv "pm install-write -S $SIZE $SID base.apk $REMOTE 2>&1" | tr -d '\r'
    OUT=$(tv "pm install-commit $SID 2>&1" | tr -d '\r')
    echo "$OUT"
    case "$OUT" in
      *Success*) ;;
      *) tv "pm install-abandon $SID" >/dev/null 2>&1 || true
         tv "rm -f $REMOTE"
         case "$OUT" in
           *INSUFFICIENT_STORAGE*) echo "ERROR: not enough free space on /data; try: tools/tv.py 'pm trim-caches 2000M'" >&2 ;;
           *) echo "ERROR: the install failed; nothing else was changed on the TV." >&2 ;;
         esac
         exit 1 ;;
    esac ;;
esac
tv "rm -f $REMOTE" >/dev/null

echo
echo "Installed as an app. The stock launcher stays Home; open CleanYourTV from the TV's app list."
echo "Make it the screensaver (the TV shows it when idle):"
echo "  TV_HOST=$TV_HOST tools/venv/bin/python -I tools/tv.py 'settings put secure screensaver_components com.gathin.tvhome/.ClockDream; settings put secure screensaver_enabled 1'"
echo "Push a theme or desktops as usual: tools/theme.sh themes/<name>, tools/theme.sh --add ..."
