#!/usr/bin/env bash
# One-time setup for spartan/tools/voice/render.py.
#
#  * Kokoro-82M v1.0, int8 ONNX (Apache-2.0), taken from the npm package expo-kokoro@1.1.9: only
#    build/kokoro-quantized.onnx, build/voices/*.bin and build/tokenizer.json are extracted, the
#    tarball's npm integrity and the model's sha256 are checked. Nothing from the package is run.
#  * a Python venv with the pinned requirements.txt.
#  * with --asr: Whisper-small (int8 ONNX, transformers.js layout, npm sts-whisper-small@1.0.0) and
#    @huggingface/transformers@4.3.1 (npm install --ignore-scripts) for qa.py's intelligibility check,
#    plus DNSMOS P.835 (sig_bak_ovr.onnx, extracted from npm webmos@1.0.2) for its quality score.
#
# Everything goes to $THERMOPYLAE_VOICE_CACHE (default ~/.cache/thermopylae-voice), never into the repo.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CACHE="${THERMOPYLAE_VOICE_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/thermopylae-voice}"
WITH_ASR=0
for a in "$@"; do
  case "$a" in
    --asr) WITH_ASR=1 ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    *) echo "unknown option $a" >&2; exit 2 ;;
  esac
done

KOKORO_PKG="expo-kokoro@1.1.9"
KOKORO_TGZ="expo-kokoro-1.1.9.tgz"
KOKORO_INTEGRITY="sha512-otFjoa5x5dBDcq8KqAacytbCSzX9o+Agqglh3N96Gak6LwrPtXD8DsxB8/XfNbWuwfNmzCq13oozzLK3OeXQng=="
MODEL_SHA256="fbae9257e1e05ffc727e951ef9b9c98418e6d79f1c9b6b13bd59f5c9028a1478"
WHISPER_PKG="sts-whisper-small@1.0.0"
WHISPER_TGZ="sts-whisper-small-1.0.0.tgz"
WHISPER_INTEGRITY="sha512-NoW0Id+PSL22BoV+34HXs0uT0et1Y63Cks/QCosYe0khaGMr6y/fBEup2k1+5fut3jxrZg1k1SrZ0gOpAU/LsQ=="
TRANSFORMERS="@huggingface/transformers@4.3.1"
DNSMOS_PKG="webmos@1.0.2"
DNSMOS_TGZ="webmos-1.0.2.tgz"
DNSMOS_INTEGRITY="sha512-xoZ64Gc8gXX/VgQ8L7mMb6DapIZQJZtbVByFFRty++Ur3i0XbT0877XEk8mOVU/xnZgwX3DUR7J1n7Udk4MMPw=="
DNSMOS_SHA256="269fbebdb513aa23cddfbb593542ecc540284a91849ac50516870e1ac78f6edd"

need() { command -v "$1" >/dev/null 2>&1 || { echo "setup.sh: '$1' is required" >&2; exit 1; }; }
need npm; need tar; need sha256sum; need openssl; need ffmpeg
case "$(ffmpeg -hide_banner -encoders 2>/dev/null)" in *libopus*) ;; *) echo "setup.sh: ffmpeg lacks libopus" >&2; exit 1 ;; esac

integrity() { echo "sha512-$(openssl dgst -sha512 -binary "$1" | base64 | tr -d '\n')"; }

# npm pack downloads the tarball only (no install, no package scripts).
fetch_pkg() { # <spec> <tgz name> <integrity> <dir>
  local spec="$1" tgz="$2" want="$3" dir="$4"
  mkdir -p "$dir"
  if [ ! -f "$dir/$tgz" ] || [ "$(integrity "$dir/$tgz")" != "$want" ]; then
    rm -f "$dir/$tgz"
    (cd "$dir" && npm pack --silent --ignore-scripts "$spec" >/dev/null)
  fi
  local got; got="$(integrity "$dir/$tgz")"
  [ "$got" = "$want" ] || { echo "setup.sh: $tgz integrity $got != $want" >&2; exit 1; }
}

mkdir -p "$CACHE"
MODEL="$CACHE/kokoro"
if [ -f "$MODEL/kokoro-quantized.onnx" ] && [ -f "$MODEL/tokenizer.json" ] && [ -d "$MODEL/voices" ] \
   && echo "$MODEL_SHA256  $MODEL/kokoro-quantized.onnx" | sha256sum -c --status; then
  echo "Kokoro model already in $MODEL"
else
  fetch_pkg "$KOKORO_PKG" "$KOKORO_TGZ" "$KOKORO_INTEGRITY" "$CACHE/npm"
  tmp="$(mktemp -d "$CACHE/extract.XXXXXX")"
  tar -xzf "$CACHE/npm/$KOKORO_TGZ" -C "$tmp" \
      package/build/kokoro-quantized.onnx package/build/tokenizer.json package/build/voices
  echo "$MODEL_SHA256  $tmp/package/build/kokoro-quantized.onnx" | sha256sum -c --status \
    || { echo "setup.sh: kokoro-quantized.onnx sha256 mismatch" >&2; rm -rf "$tmp"; exit 1; }
  rm -rf "$MODEL"; mkdir -p "$MODEL/voices"
  mv "$tmp/package/build/kokoro-quantized.onnx" "$tmp/package/build/tokenizer.json" "$MODEL/"
  mv "$tmp/package/build/voices/"*.bin "$MODEL/voices/"     # the voice tensors only
  rm -rf "$tmp"
  echo "Kokoro model installed in $MODEL"
fi

VENV="$CACHE/venv"
PY="${PYTHON:-python3}"
[ -x "$VENV/bin/python" ] || "$PY" -m venv "$VENV"
"$VENV/bin/python" -m pip install --disable-pip-version-check -q -r "$HERE/requirements.txt"
echo "Python venv ready: $VENV"

if [ "$WITH_ASR" = 1 ]; then
  need node
  ASR="$CACHE/asr"
  fetch_pkg "$WHISPER_PKG" "$WHISPER_TGZ" "$WHISPER_INTEGRITY" "$CACHE/npm"
  if [ ! -f "$ASR/models/Xenova/whisper-small/onnx/encoder_model_quantized.onnx" ]; then
    tmp="$(mktemp -d "$CACHE/extract.XXXXXX")"
    tar -xzf "$CACHE/npm/$WHISPER_TGZ" -C "$tmp" package/models
    rm -rf "$ASR/models"; mkdir -p "$ASR"; mv "$tmp/package/models" "$ASR/models"; rm -rf "$tmp"
  fi
  mkdir -p "$ASR/node"
  [ -f "$ASR/node/package.json" ] || echo '{"private":true,"type":"module"}' > "$ASR/node/package.json"
  (cd "$ASR/node" && npm install --ignore-scripts --no-audit --no-fund --silent "$TRANSFORMERS")
  fetch_pkg "$DNSMOS_PKG" "$DNSMOS_TGZ" "$DNSMOS_INTEGRITY" "$CACHE/npm"
  if ! echo "$DNSMOS_SHA256  $ASR/dnsmos/sig_bak_ovr.onnx" | sha256sum -c --status 2>/dev/null; then
    tmp="$(mktemp -d "$CACHE/extract.XXXXXX")"
    tar -xzf "$CACHE/npm/$DNSMOS_TGZ" -C "$tmp" package/models/sig_bak_ovr.onnx
    echo "$DNSMOS_SHA256  $tmp/package/models/sig_bak_ovr.onnx" | sha256sum -c --status \
      || { echo "setup.sh: sig_bak_ovr.onnx sha256 mismatch" >&2; rm -rf "$tmp"; exit 1; }
    mkdir -p "$ASR/dnsmos"; mv "$tmp/package/models/sig_bak_ovr.onnx" "$ASR/dnsmos/"; rm -rf "$tmp"
  fi
  echo "ASR and DNSMOS ready in $ASR"
fi

echo
echo "Render:  $VENV/bin/python -I $HERE/render.py            (add --only <key>, --out <dir>, --qa)"
echo "QA:      $VENV/bin/python -I $HERE/qa.py --help"
