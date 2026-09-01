#!/usr/bin/env bash
#
# Transcodes one lesson master into an HLS ladder.
#
# Inputs (env):
#   MASTER_URI    gs:// URI of the uploaded master              (required)
#   OUTPUT_PREFIX gs:// prefix to write the ladder under        (required)
#   GROUP_ID      identifies what is being transcoded            (required)
#   CALLBACK_URL  server endpoint told READY/FAILED             (optional)
#   CALLBACK_TOKEN bearer token for the callback                (optional)
#   RUNGS         override ladder, e.g. "1080:4500k 720:2500k"  (optional)
#
# The job is idempotent: output is written to a temp prefix and only moved into place once
# every rung has succeeded, so a retried or duplicated message can never leave a half-written
# manifest that a player would try to stream.

set -euo pipefail

: "${MASTER_URI:?MASTER_URI is required}"
: "${OUTPUT_PREFIX:?OUTPUT_PREFIX is required}"
: "${GROUP_ID:?GROUP_ID is required}"
WORK_DIR="${WORK_DIR:-/tmp/work}"
SEGMENT_SECONDS="${SEGMENT_SECONDS:-6}"

# height:video-bitrate:audio-bitrate — height is applied to the SHORT side, see scale_filter().
DEFAULT_RUNGS="1080:4500k:128k 720:2500k:128k 360:800k:64k"
read -r -a RUNGS_ARR <<< "${RUNGS:-$DEFAULT_RUNGS}"

log() { printf '%s [transcode:%s] %s\n' "$(date -u +%H:%M:%S)" "$GROUP_ID" "$*" >&2; }

callback() {
    local status="$1" detail="${2:-}"
    [[ -z "${CALLBACK_URL:-}" ]] && return 0
    # X-Transcode-Token, not Authorization: Bearer — a Bearer header would be parsed as a JWT by
    # the receiving application's resource server and rejected before the endpoint is reached.
    curl -fsS -X POST "$CALLBACK_URL" \
        -H "Content-Type: application/json" \
        ${CALLBACK_TOKEN:+-H "X-Transcode-Token: $CALLBACK_TOKEN"} \
        -d "{\"groupId\":\"$GROUP_ID\",\"status\":\"$status\",\"detail\":\"${detail//\"/}\"}" \
        >/dev/null 2>&1 || log "callback $status failed (job result unaffected)"
}

fail() { log "FAILED: $*"; callback FAILED "$*"; exit 1; }
trap 'fail "unexpected error on line $LINENO"' ERR

# Scale so the SHORT side hits the target. A ladder written as `-2:720` assumes landscape and
# turns a 2160x3840 portrait upload into a 405x720 sliver; deriving from the short side keeps
# portrait, landscape and square sources all at the intended visual quality.
scale_filter() {
    local target="$1"
    echo "scale=w='if(gt(iw,ih),-2,${target})':h='if(gt(iw,ih),${target},-2)':force_original_aspect_ratio=decrease:force_divisible_by=2"
}

rm -rf "$WORK_DIR" && mkdir -p "$WORK_DIR/out"
cd "$WORK_DIR"

log "downloading master $MASTER_URI"
gcloud storage cp "$MASTER_URI" master.src --quiet || fail "could not download master"

# Probe once. A source shorter than a rung's target is never upscaled — encoding a 480p webcam
# recording up to 1080p costs bitrate and CPU to produce something that looks no better.
SHORT_SIDE=$(ffprobe -v error -select_streams v:0 \
    -show_entries stream=width,height -of csv=p=0:s=x master.src \
    | awk -Fx '{print ($1 < $2) ? $1 : $2}')
DURATION=$(ffprobe -v error -show_entries format=duration -of csv=p=0 master.src | cut -d. -f1)
HAS_AUDIO=$(ffprobe -v error -select_streams a -show_entries stream=index -of csv=p=0 master.src | head -1)
log "source short side ${SHORT_SIDE}px, duration ${DURATION}s, audio=${HAS_AUDIO:-none}"

# ── build one ffmpeg invocation covering every rung ──────────────────────────
# Single decode, N encodes. Decoding a 4K master three times would roughly triple the job's
# CPU-seconds, which is the entire cost of this pipeline.
declare -a FILTER MAPS ENC
declare -a VAR_MAP
idx=0
for rung in "${RUNGS_ARR[@]}"; do
    IFS=: read -r height vbr abr <<< "$rung"
    if (( SHORT_SIDE < height )); then
        log "skipping ${height}p rung: source short side is only ${SHORT_SIDE}px"
        continue
    fi
    FILTER+=( "[vin]$(scale_filter "$height")[v${idx}]" )
    MAPS+=( -map "[v${idx}]" )
    ENC+=( -c:v:${idx} libx264 -preset veryfast -profile:v main -crf 21
           -maxrate:v:${idx} "$vbr" -bufsize:v:${idx} "$((${vbr%k} * 2))k"
           -g 48 -keyint_min 48 -sc_threshold 0 )
    if [[ -n "$HAS_AUDIO" ]]; then
        MAPS+=( -map "a:0" )
        ENC+=( -c:a:${idx} aac -b:a:${idx} "$abr" -ac 2 )
        VAR_MAP+=( "v:${idx},a:${idx},name:${height}p" )
    else
        VAR_MAP+=( "v:${idx},name:${height}p" )
    fi
    idx=$((idx+1))
done

(( idx == 0 )) && fail "no rung matched a ${SHORT_SIDE}px source"

# split feeds one decoded stream into every scaler
SPLIT="[0:v]split=${idx}$(for i in $(seq 0 $((idx-1))); do printf '[vin%s]' "$i"; done)"
FILTER_COMPLEX="$SPLIT"
for i in $(seq 0 $((idx-1))); do
    FILTER_COMPLEX+=";${FILTER[$i]//\[vin\]/[vin$i]}"
done

log "encoding $idx rung(s)"
ffmpeg -nostdin -hide_banner -loglevel warning -y -i master.src \
    -filter_complex "$FILTER_COMPLEX" \
    "${MAPS[@]}" "${ENC[@]}" \
    -f hls \
    -hls_time "$SEGMENT_SECONDS" \
    -hls_playlist_type vod \
    -hls_segment_type fmp4 \
    -hls_flags independent_segments \
    -master_pl_name master.m3u8 \
    -var_stream_map "$(IFS=' '; echo "${VAR_MAP[*]}")" \
    -hls_segment_filename "out/%v/seg_%05d.m4s" \
    "out/%v/index.m3u8" \
    || fail "ffmpeg encode failed"

# ── publish atomically ──────────────────────────────────────────────────────
# Segments are immutable and content-addressed by path, so they can cache forever. The master
# playlist is the only mutable entry point; a short TTL there is what lets us add a rung later
# without stale players pinning the old ladder.
STAGING="${OUTPUT_PREFIX%/}/.staging-${GROUP_ID}-$(date +%s)"
log "uploading ladder to $STAGING"
gcloud storage cp -r out/* "$STAGING/" --quiet \
    --cache-control="public, max-age=31536000, immutable" || fail "ladder upload failed"
gcloud storage cp out/master.m3u8 "$STAGING/master.m3u8" --quiet \
    --cache-control="public, max-age=300" || fail "master playlist upload failed"

log "promoting staging to final prefix"
gcloud storage mv "$STAGING/*" "${OUTPUT_PREFIX%/}/" --quiet || fail "promote failed"
gcloud storage rm -r "$STAGING" --quiet 2>/dev/null || true

log "done: ${idx} rung(s) at ${OUTPUT_PREFIX%/}/master.m3u8"
trap - ERR
callback READY "${idx} rungs"
