# OpenTimestamps sidecar (trust ladder, rung 2).
#
# The official Python client is the reference implementation of the .ots format — there is no
# specification document — so it does the protocol work and we only wrap it in HTTP.
#
# Pinned deliberately: opentimestamps-client is the thing whose output an auditor verifies
# years later, so "whatever pip resolves today" is not good enough. Bump it consciously and
# re-run the sidecar tests.
FROM python:3.12-slim

ARG OTS_CLIENT_VERSION=0.7.1

RUN pip install --no-cache-dir "opentimestamps-client==${OTS_CLIENT_VERSION}" \
    && ots --version

# Unprivileged: this service reaches the public internet, and it needs no filesystem beyond
# the temporary directories the request handler creates.
RUN useradd --create-home --uid 10001 ots
USER ots

COPY --chown=ots:ots server.py /app/server.py
WORKDIR /app

ENV OTS_PORT=8082
EXPOSE 8082

# No curl/wget in a slim image; python is already here and is the thing that must be alive.
HEALTHCHECK --interval=30s --timeout=10s --start-period=15s --retries=3 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8082/health', timeout=5).status==200 else 1)"

CMD ["python", "-u", "/app/server.py"]
