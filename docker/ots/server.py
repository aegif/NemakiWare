#!/usr/bin/env python3
"""OpenTimestamps sidecar — rung 2 of the trust ladder.

Why a sidecar rather than a Java client
---------------------------------------
The only Java implementation on Maven Central (com.eternitywall:java-opentimestamps 1.20)
has been unmaintained since 2021 and pulls bitcoinj 0.14.7, which drags H2 1.3.167 (a known
RCE), an ancient MySQL driver and guava 18 into the WAR. bitcoinj cannot simply be excluded:
the library's own entry class imports it. Writing the protocol in Java instead sounds cheap —
it is two HTTP calls — but the `.ots` serialization has no specification document; the Python
implementation IS the specification. Reimplementing it would put our reading of the format in
place of the format, and rung 2 exists precisely so that a third party can verify WITHOUT
trusting us. So the official Python client does the work.

What this service will and will not do
--------------------------------------
It accepts a hex digest and nothing else. No content, no filenames, no identifiers. The
client blinds the digest before it reaches a calendar (SHA256(digest || 16 random bytes)), so
the calendars learn neither the document nor the digest we are anchoring.

`pending` is a normal outcome, not a failure: an OpenTimestamps commitment is not verifiable
until Bitcoin confirms it, which takes hours. Callers upgrade later; they do not re-stamp.
"""
import base64
import binascii
import json
import os
import subprocess
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("OTS_PORT", "8082"))
# Timeouts: stamping talks to remote calendars; upgrading may talk to several.
STAMP_TIMEOUT_S = int(os.environ.get("OTS_STAMP_TIMEOUT_S", "60"))
UPGRADE_TIMEOUT_S = int(os.environ.get("OTS_UPGRADE_TIMEOUT_S", "60"))
VERIFY_TIMEOUT_S = int(os.environ.get("OTS_VERIFY_TIMEOUT_S", "60"))
# Calendars, comma-separated. Empty = the client's own defaults.
CALENDARS = [c.strip() for c in os.environ.get("OTS_CALENDARS", "").split(",") if c.strip()]
MAX_BODY = 64 * 1024


def _run(args, timeout):
    proc = subprocess.run(args, capture_output=True, timeout=timeout)
    return proc.returncode, proc.stdout, proc.stderr.decode("utf-8", "replace")


def _digest_bytes(hex_digest):
    """Reject anything that is not a 64-char SHA-256 hex digest.

    This is the privacy boundary: if it is not a digest, it does not go out."""
    if not isinstance(hex_digest, str) or len(hex_digest) != 64:
        raise ValueError("expected a 64-character SHA-256 hex digest")
    try:
        return binascii.unhexlify(hex_digest)
    except binascii.Error as exc:
        raise ValueError("digest is not hexadecimal") from exc


def stamp(hex_digest):
    """Create a timestamp for a digest. Returns a (possibly pending) .ots proof."""
    raw = _digest_bytes(hex_digest)
    with tempfile.TemporaryDirectory() as tmp:
        target = os.path.join(tmp, "digest.bin")
        with open(target, "wb") as fh:
            fh.write(raw)
        args = ["ots", "stamp"]
        for cal in CALENDARS:
            args += ["-c", cal]
        # -m 1: one calendar suffices for a valid proof; more calendars are redundancy,
        # and demanding all of them turns one slow calendar into a total failure.
        args += ["-m", "1", target]
        code, _, err = _run(args, STAMP_TIMEOUT_S)
        ots_path = target + ".ots"
        if code != 0 or not os.path.exists(ots_path):
            return {"status": "FAILED", "error": f"ots stamp exited {code}: {err.strip()}"}
        with open(ots_path, "rb") as fh:
            proof = fh.read()
    # Freshly stamped proofs are always pending: the commitment exists, Bitcoin has not
    # confirmed it yet. Reporting CONFIRMED here would assert a proof nobody can check.
    return {
        "status": "PENDING",
        "proofBase64": base64.b64encode(proof).decode("ascii"),
        "calendars": CALENDARS,
        "stderr": err.strip(),
    }


def upgrade(proof_b64):
    """Try to complete a pending proof. Unchanged output means still pending."""
    proof = base64.b64decode(proof_b64, validate=True)
    with tempfile.TemporaryDirectory() as tmp:
        ots_path = os.path.join(tmp, "digest.bin.ots")
        with open(ots_path, "wb") as fh:
            fh.write(proof)
        code, _, err = _run(["ots", "upgrade", ots_path], UPGRADE_TIMEOUT_S)
        with open(ots_path, "rb") as fh:
            upgraded = fh.read()
    changed = upgraded != proof
    return {
        # `ots upgrade` exits non-zero when nothing could be upgraded yet, which is the
        # normal state for hours after stamping — not an error to report as one.
        "status": "CONFIRMED" if changed else "PENDING",
        "proofBase64": base64.b64encode(upgraded).decode("ascii"),
        "changed": changed,
        "exitCode": code,
        "stderr": err.strip(),
    }


def info(proof_b64):
    """Report what the proof itself says, WITHOUT a Bitcoin node.

    `ots verify` needs a Bitcoin RPC endpoint (it calls getblockcount/getblockhash), which this
    container deliberately does not ship: running a full node beside an ECM server is not a
    reasonable deployment requirement, and checking Bitcoin is precisely the step a third-party
    auditor performs for themselves — that is what makes rung 2 independent of us.

    So completeness is determined offline from the proof's own structure: a complete proof
    carries a BitcoinBlockHeaderAttestation naming a block height, an incomplete one still
    carries PendingAttestation. Reporting the block height is a statement about the proof, not
    about the blockchain, and the caller records it as exactly that.
    """
    proof = base64.b64decode(proof_b64, validate=True)
    with tempfile.TemporaryDirectory() as tmp:
        ots_path = os.path.join(tmp, "digest.bin.ots")
        with open(ots_path, "wb") as fh:
            fh.write(proof)
        code, out, err = _run(["ots", "info", ots_path], VERIFY_TIMEOUT_S)
    text = (out.decode("utf-8", "replace") + "\n" + err).strip()
    height = None
    for line in text.splitlines():
        if "BitcoinBlockHeaderAttestation" in line:
            digits = "".join(ch for ch in line.split("BitcoinBlockHeaderAttestation")[1] if ch.isdigit())
            if digits:
                height = int(digits)
    return {
        "complete": height is not None,
        "bitcoinBlockHeight": height,
        "pending": "PendingAttestation" in text,
        "exitCode": code,
        "info": text[:4000],
    }


def verify(hex_digest, proof_b64):
    """Full verification against the Bitcoin chain.

    REQUIRES a Bitcoin node: the client resolves the attested block through RPC. Without one it
    reports failure, which is why completeness is decided by `info` above and this endpoint is
    kept only for deployments that do run a node (and for the auditor's own procedure)."""
    raw = _digest_bytes(hex_digest)
    proof = base64.b64decode(proof_b64, validate=True)
    with tempfile.TemporaryDirectory() as tmp:
        target = os.path.join(tmp, "digest.bin")
        with open(target, "wb") as fh:
            fh.write(raw)
        with open(target + ".ots", "wb") as fh:
            fh.write(proof)
        code, _, err = _run(["ots", "verify", target + ".ots"], VERIFY_TIMEOUT_S)
    text = err.strip()
    attested = None
    for line in text.splitlines():
        # The client reports success as "Success! Bitcoin block N attests existence as of <date>"
        if "attests existence as of" in line:
            attested = line.strip()
    return {
        "verified": code == 0 and attested is not None,
        "pending": "Pending confirmation" in text,
        "attestation": attested,
        "exitCode": code,
        "stderr": text,
    }


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        # Access logs would record which digests were anchored and when. That is exactly the
        # correlation the nonce is there to prevent, so the default logger stays off.
        pass

    def _send(self, code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            code, out, _ = _run(["ots", "--version"], 10)
            self._send(200 if code == 0 else 503, {
                "ok": code == 0,
                "client": out.decode("utf-8", "replace").strip(),
                "calendars": CALENDARS or ["<client defaults>"],
            })
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            # A negative length would make rfile.read(-1) read to EOF, sailing past MAX_BODY and
            # letting one slow client hold a thread while it feeds unbounded input.
            if length < 0 or length > MAX_BODY:
                self._send(413, {"error": "body too large"})
                return
            request = json.loads(self.rfile.read(length) or b"{}")

            if self.path == "/stamp":
                self._send(200, stamp(request.get("digest")))
            elif self.path == "/upgrade":
                self._send(200, upgrade(request["proofBase64"]))
            elif self.path == "/info":
                self._send(200, info(request["proofBase64"]))
            elif self.path == "/verify":
                self._send(200, verify(request.get("digest"), request["proofBase64"]))
            else:
                self._send(404, {"error": "not found"})

        except ValueError as exc:
            self._send(400, {"error": str(exc)})
        except subprocess.TimeoutExpired:
            self._send(504, {"error": "the OpenTimestamps client timed out"})
        except Exception as exc:  # noqa: BLE001 - the sidecar must answer, never hang
            self._send(500, {"error": f"{type(exc).__name__}: {exc}"})


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
