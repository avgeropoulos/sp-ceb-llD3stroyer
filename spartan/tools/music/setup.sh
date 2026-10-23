#!/usr/bin/env bash
# One-time setup for spartan/tools/music (render.mjs, master.py, qa.py). See README.md.
#
#  * npm pack (tarball download only: nothing is installed, no package script runs), every tarball
#    checked against its pinned npm sha512 integrity, then unpacked into $CACHE/node_modules:
#      spessasynth_core@4.3.22 (Apache-2.0) and its one dependency stb-vorbis@0.0.6 (Apache-2.0)
#      midi-writer-js@3.2.1 (MIT) with @tonaljs/midi@4.10.4, pitch-note@6.1.2, pitch@5.0.3 (MIT),
#        for the composers' score scripts (render.mjs does not need it)
#  * generaluser@1.47.1: only GeneralUser.sf2 (GeneralUser GS v1.471 by S. Christian Collins) and
#    its LICENSE.txt are extracted; the .sf2's sha256 is checked against the pinned value (the same
#    one render.mjs checks before every render).
#  * a Python venv ($CACHE/venv) with numpy and scipy, pinned (master.py and qa.py).
#
#   tools/music/setup.sh [--no-venv] [--verify]
#     --no-venv  skip the venv (bring your own python with numpy + scipy)
#     --verify   check an existing cache (integrity, sha256, versions) without downloading
#
# Everything goes to $THERMOPYLAE_MUSIC_CACHE (default ~/.cache/thermopylae-music), never into the repo.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CACHE="${THERMOPYLAE_MUSIC_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/thermopylae-music}"
WITH_VENV=1
VERIFY_ONLY=0
for a in "$@"; do
  case "$a" in
    --no-venv) WITH_VENV=0 ;;
    --verify) VERIFY_ONLY=1 ;;
    -h|--help) sed -n '2,21p' "$0"; exit 0 ;;
    *) echo "setup.sh: unknown option $a" >&2; exit 2 ;;
  esac
done

SF2_SHA256="f45b6b4a68b6bf3d792fcbb6d7de24dc701a0f89c5900a21ef3aaece993b839a"   # GeneralUser GS v1.471
# name|version|tarball|npm integrity|install into node_modules/<name>? (sf2 package: extracted apart)
PACKAGES=(
  "generaluser|1.47.1|generaluser-1.47.1.tgz|sha512-HLojVJe4MJk3Jatr0N4sHREeGyXNH75vU2RA3nNXBbqOmhpuccYByK7r8Dr7+uCpU5aCtKnT5xoK0m8P1/TWqg==|no"
  "spessasynth_core|4.3.22|spessasynth_core-4.3.22.tgz|sha512-qP6NkcZLmTLIs9fCUh2IhZRkd3Junwu8ppD4K0l/0R+Lo4P48d4Rbe8YQ8RlEYyRKFz8HcPM75sXYVQLAb7k4A==|yes"
  "stb-vorbis|0.0.6|stb-vorbis-0.0.6.tgz|sha512-CyvjJEGROIdnuZknwFvdOfMIO1ssUxO2OawsXCR3IlbJ8z2IMBjDe8FDxFJqBfLgf1ZOdqvoHAYx3Xk8q9rbbg==|yes"
  "midi-writer-js|3.2.1|midi-writer-js-3.2.1.tgz|sha512-mILW/zthDiinxd/fsVfCe0XWef0hHQzS+fukjS3W+gwBG3ljur241u7CsEC5T+9cOwIPVZCgw37pjKiKb7LJ1g==|yes"
  "@tonaljs/midi|4.10.4|tonaljs-midi-4.10.4.tgz|sha512-pEIwjTUiJyVlfVJlG0pYLTSCQ4xoWo+NYLChFMIcrq/KUjdrtGOwgbFVfD2jyff5qgDmH05rRrJBJsVE1gwIkg==|yes"
  "@tonaljs/pitch-note|6.1.2|tonaljs-pitch-note-6.1.2.tgz|sha512-xNtUXR6DvFMHyXKgewF0QFv9UIAd8Xxc07HIWOABIGFuKFmgT3znN2TbACSnHJGIKVfSoJhwPen2eok6UdOCwg==|yes"
  "@tonaljs/pitch|5.0.3|tonaljs-pitch-5.0.3.tgz|sha512-2OpxK/p0NaOymVdlxgJxruFAt2pHfFIkPxkQ3PKQqMXhaza+SXG1Kxpo0jpuAjw946LlQ3KBC+GJRSncjYtoKA==|yes"
)
PY_PINS=(numpy==2.5.4 scipy==1.18.1)   # resolved and tested on CPython 3.13, Linux x86_64

need() { command -v "$1" >/dev/null 2>&1 || { echo "setup.sh: '$1' is required" >&2; exit 1; }; }
need npm; need node; need tar; need sha256sum; need openssl; need ffmpeg
case "$(ffmpeg -hide_banner -encoders 2>/dev/null)" in *libopus*) ;; *) echo "setup.sh: ffmpeg lacks libopus" >&2; exit 1 ;; esac
case "$(ffmpeg -hide_banner -decoders 2>/dev/null)" in *opus*) ;; *) echo "setup.sh: ffmpeg cannot decode Opus" >&2; exit 1 ;; esac

integrity() { echo "sha512-$(openssl dgst -sha512 -binary "$1" | base64 | tr -d '\n')"; }
fail() { echo "setup.sh: $*" >&2; exit 1; }

# npm pack downloads the tarball only (no install, no package scripts).
fetch_pkg() { # <name@version> <tgz> <integrity>
  local spec="$1" tgz="$2" want="$3" dir="$CACHE/npm"
  mkdir -p "$dir"
  if [ ! -f "$dir/$tgz" ] || [ "$(integrity "$dir/$tgz")" != "$want" ]; then
    [ "$VERIFY_ONLY" = 1 ] && fail "$dir/$tgz missing or corrupt"
    rm -f "$dir/$tgz"
    (cd "$dir" && npm pack --silent --ignore-scripts "$spec" >/dev/null)
  fi
  [ -f "$dir/$tgz" ] || fail "npm pack $spec did not produce $tgz"
  local got; got="$(integrity "$dir/$tgz")"
  [ "$got" = "$want" ] || fail "$tgz integrity $got != pinned $want"
}

mkdir -p "$CACHE"
[ -f "$CACHE/package.json" ] || echo '{ "private": true, "type": "module" }' > "$CACHE/package.json"

for row in "${PACKAGES[@]}"; do
  IFS='|' read -r name version tgz want install <<< "$row"
  fetch_pkg "$name@$version" "$tgz" "$want"
  [ "$install" = yes ] || continue
  dest="$CACHE/node_modules/$name"
  if [ -f "$dest/package.json" ] && [ "$(node -p "require('$dest/package.json').version")" = "$version" ] \
     && [ -f "$dest/.integrity" ] && [ "$(cat "$dest/.integrity")" = "$want" ]; then
    continue
  fi
  [ "$VERIFY_ONLY" = 1 ] && fail "$dest is not $name@$version from the pinned tarball"
  rm -rf "$dest"; mkdir -p "$dest"
  tar -xzf "$CACHE/npm/$tgz" -C "$dest" --strip-components=1 --no-same-owner
  echo "$want" > "$dest/.integrity"
  echo "unpacked $name@$version"
done

# The SoundFont: GeneralUser.sf2 + its licence, sha256-pinned.
SF2="$CACHE/GeneralUser.sf2"
if echo "$SF2_SHA256  $SF2" | sha256sum -c --status 2>/dev/null && [ -f "$CACHE/GeneralUser-LICENSE.txt" ]; then
  echo "GeneralUser GS v1.471 already in $SF2"
else
  [ "$VERIFY_ONLY" = 1 ] && fail "$SF2 missing or not sha256 $SF2_SHA256"
  tmp="$(mktemp -d "$CACHE/extract.XXXXXX")"
  tar -xzf "$CACHE/npm/generaluser-1.47.1.tgz" -C "$tmp" --no-same-owner package/GeneralUser.sf2 package/LICENSE.txt
  echo "$SF2_SHA256  $tmp/package/GeneralUser.sf2" | sha256sum -c --status \
    || { rm -rf "$tmp"; fail "GeneralUser.sf2 sha256 mismatch (want $SF2_SHA256)"; }
  mv "$tmp/package/GeneralUser.sf2" "$SF2"
  mv "$tmp/package/LICENSE.txt" "$CACHE/GeneralUser-LICENSE.txt"
  rm -rf "$tmp"
  echo "GeneralUser GS v1.471 installed in $SF2"
fi
echo "$SF2_SHA256  GeneralUser.sf2" > "$CACHE/GeneralUser.sf2.sha256"

# The renderer pins the same versions; make sure it agrees with this script.
grep -q "SF2_SHA256 = '$SF2_SHA256'" "$HERE/render.mjs" || fail "render.mjs pins a different GeneralUser.sf2 sha256"
grep -q "SPESSA_VERSION = '4.3.22'" "$HERE/render.mjs" || fail "render.mjs pins a different spessasynth_core version"

VENV="$CACHE/venv"
if [ "$WITH_VENV" = 1 ]; then
  if [ "$VERIFY_ONLY" = 1 ]; then
    [ -x "$VENV/bin/python" ] || fail "no venv in $VENV"
  else
    PY="${PYTHON:-python3}"
    [ -x "$VENV/bin/python" ] || "$PY" -m venv "$VENV"
    "$VENV/bin/python" -m pip install --disable-pip-version-check -q --only-binary=:all: "${PY_PINS[@]}"
  fi
  "$VENV/bin/python" -I -c 'import numpy, scipy, sys; print("python", sys.version.split()[0], "numpy", numpy.__version__, "scipy", scipy.__version__)'
  PYRUN="$VENV/bin/python -I"
else
  PYRUN="python3 -I"
fi

cat <<EOF

Music tools ready in $CACHE
  render:  node $HERE/render.mjs <cue>.mid               (-> $CACHE/build/<cue>.loop.wav)
  master:  $PYRUN $HERE/master.py $CACHE/build/<cue>.loop.wav
  QA:      $PYRUN $HERE/qa.py
EOF
