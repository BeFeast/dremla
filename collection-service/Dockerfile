# Debian 13: debian:trixie-slim (multi-platform index).
FROM debian:trixie-slim@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f

ARG DREMLA_REVISION
WORKDIR /app
RUN test "${#DREMLA_REVISION}" -eq 40 \
    && printf '%s' "$DREMLA_REVISION" | grep -Eq '^[0-9a-fA-F]{40}$' \
    && printf '%s\n' "$DREMLA_REVISION" > /app/REVISION
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /data/state /data/published \
    && chown 1000:1000 /data/state /data/published

LABEL org.opencontainers.image.revision=$DREMLA_REVISION \
      org.opencontainers.image.source="https://git.oklabs.uk/BeFeast/dremla" \
      org.opencontainers.image.title="dremla-collection"

# Build context: collection-service/. Static assets are inside dremla_collection/.
COPY run.py /app/collection-service/run.py
COPY dremla_collection/ /app/collection-service/dremla_collection/

ENV DREMLA_COLLECTION_DATA=/data/state \
    DREMLA_COLLECTION_DEST=/data/published \
    DREMLA_COLLECTION_ACQUISITION=fixture \
    DREMLA_COLLECTION_PROCESSOR=ffmpeg \
    DREMLA_COLLECTION_PORT=8765 \
    TMPDIR=/tmp \
    PYTHONDONTWRITEBYTECODE=1
USER 1000:1000
STOPSIGNAL SIGTERM
CMD ["python3", "-I", "/app/collection-service/run.py"]
