# Cloud Run Job image: pulls a master upload from GCS, produces an HLS ladder, uploads it back.
#
# ffmpeg comes from the distro rather than a static build so security updates arrive with the
# base image. google-cloud-cli is needed for `gcloud storage`, which handles resumable transfer
# and parallel composite uploads for us — the ladder is a few hundred small segment files.
FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        ffmpeg curl ca-certificates gnupg python3 \
    && echo "deb [signed-by=/usr/share/keyrings/cloud.google.gpg] https://packages.cloud.google.com/apt cloud-sdk main" \
        > /etc/apt/sources.list.d/google-cloud-sdk.list \
    && curl -fsSL https://packages.cloud.google.com/apt/doc/apt-key.gpg \
        | gpg --dearmor -o /usr/share/keyrings/cloud.google.gpg \
    && apt-get update && apt-get install -y --no-install-recommends google-cloud-cli \
    && rm -rf /var/lib/apt/lists/*

COPY transcode.sh /usr/local/bin/transcode.sh
RUN chmod +x /usr/local/bin/transcode.sh

# Scratch space for the master + renditions. Cloud Run gives this to us as tmpfs backed by
# instance memory, so the job's memory limit must exceed master size + ladder size.
ENV WORK_DIR=/tmp/work

ENTRYPOINT ["/usr/local/bin/transcode.sh"]
