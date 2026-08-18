/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.rest.purview.anchor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.bouncycastle.asn1.cmp.PKIStatus;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;
import org.bouncycastle.tsp.TimeStampTokenInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RFC 3161 time stamping — rung 3 of the trust ladder.
 *
 * <h3>Why this class is longer than "post the digest, keep the token"</h3>
 *
 * <p>BouncyCastle's TSP API has a handful of behaviours that produce code which compiles, runs,
 * and quietly fails to prove anything. Each is handled here explicitly, with the reason, because
 * every one of them was found by reading the library rather than by a test failing:
 *
 * <ol>
 *   <li><b>{@code TimeStampResponse.validate(request)} lets rejections through.</b> When the TSA
 *       refuses, the response carries no token, and validate() returns normally instead of
 *       throwing. Code that trusts validate() alone treats a refusal as a success. We check the
 *       status and the presence of a token ourselves, first.</li>
 *   <li><b>{@code certReq} defaults to false.</b> RFC 3161 §2.4.1 says that when it is absent or
 *       false the TSA MUST NOT include its certificate. Forget {@code setCertReq(true)} and the
 *       token arrives with nothing to build a chain from — which nobody notices until the day
 *       someone tries to verify it, possibly years later, and it only shows up against some
 *       TSAs. We require it and record whether the certificate actually came.</li>
 *   <li><b>A nonce must be unpredictable.</b> The widespread idiom of using
 *       {@code System.currentTimeMillis()} makes it guessable; RFC 3161 asks for a large random
 *       number. We use {@link SecureRandom} over 64 bits and let BouncyCastle enforce that the
 *       same value comes back.</li>
 *   <li><b>A non-DER response is an {@code IOException}, not a {@code TSPException}.</b> A proxy
 *       error page parses as garbage rather than as a refusal, so we check the content type and
 *       say what actually happened.</li>
 *   <li><b>{@code reqPolicy} demands an exact match.</b> Asking for a policy OID we guessed
 *       wrong fails every request, so the policy is only sent when an operator configured one.</li>
 *   <li><b>validate() is not signature verification.</b> It checks the nonce, the status, the
 *       message imprint and the presence of a SigningCertificate attribute — not the CMS
 *       signature, and certainly not certificate path building or revocation. We record what we
 *       captured and what we did not, rather than implying a verification we did not perform.</li>
 * </ol>
 *
 * <h3>What is and is not established here</h3>
 *
 * <p><b>Done:</b> the CMS signature over the TSTInfo is verified against the certificate the
 * token carries. Without this, anyone able to answer the configured URL could return a token
 * with the right nonce and imprint and have it recorded as confirmed evidence — {@code
 * validate()} would accept it.
 *
 * <p><b>Not done:</b> PKIX path validation and revocation checking. When a trust anchor is
 * configured we check that the signer's certificate is signed by it and currently valid, which
 * is an issuer check, NOT path validation — no intermediates, no basicConstraints, no policy,
 * no CRL/OCSP. The receipt says so in {@code trustAnchorCheck} rather than letting a reader
 * assume more. {@code revocationDataCapturedAt=never} is recorded because revocation data cannot
 * be reconstructed after the fact and its absence has to be visible rather than assumed.
 *
 * <h3>What CONFIRMED means here</h3>
 *
 * <p>Narrower than the word suggests, so it is spelled out: the response answered our nonce and
 * imprint, and its CMS signature verifies against the certificate the token itself carried. If
 * a trust anchor is configured, the signer also chains to it — and if it does not, the result is
 * FAILED rather than a CONFIRMED receipt with a quiet attribute saying otherwise. What CONFIRMED
 * does NOT mean: that the signer was authenticated against any external directory, that its
 * certificate path was validated per PKIX, or that revocation was checked.
 *
 * <p><b>Not derivable at all:</b> whether the TSA is organizationally independent of this
 * deployment. An operator can run their own TSA and configure its certificate as the anchor;
 * every cryptographic check then passes. Independence is therefore taken from an explicit
 * operator declaration ({@code accreditation}) combined with the chain check, and the receipt
 * marks it as declared.
 */
public class Rfc3161AnchorTarget implements AnchorTarget {

    private static final Logger logger = LoggerFactory.getLogger(Rfc3161AnchorTarget.class);

    /** RFC 3161 §3.4: the media type a TSA must answer with over HTTP. */
    static final String RESPONSE_CONTENT_TYPE = "application/timestamp-reply";
    private static final String REQUEST_CONTENT_TYPE = "application/timestamp-query";

    /** SHA-256, matching the digests the rest of the evidence chain uses. */
    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final org.bouncycastle.asn1.ASN1ObjectIdentifier SHA256_OID =
            new org.bouncycastle.asn1.ASN1ObjectIdentifier("2.16.840.1.101.3.4.2.1");

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    /** A TSA reply is a few KB. Anything far larger is a proxy page, not a token. */
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private final String tsaUrl;
    private final String reqPolicyOid;
    private final String accreditation;
    private final java.security.cert.X509Certificate trustAnchor;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param tsaUrl        endpoint, or null/blank to leave rung 3 unconfigured
     * @param reqPolicyOid  policy OID to demand, or null to accept the TSA's default. Only set
     *                      this when the operator knows their provider's OID: a wrong value
     *                      fails every request (pitfall 5).
     * @param accreditation free-form marker for the evidence report ("JP_MIC_ACCREDITED",
     *                      "EU_QUALIFIED", "NONE"). Recorded, never inferred — whether a TSA is
     *                      accredited is a fact about a contract, not something to detect.
     */
    public Rfc3161AnchorTarget(String tsaUrl, String reqPolicyOid, String accreditation) {
        this(tsaUrl, reqPolicyOid, accreditation, null);
    }

    /**
     * @param trustAnchor the certificate the TSA's signer must chain to, or null when the
     *        operator has configured none. Without an anchor the signature can still be checked
     *        against the certificate the token carries — but that certificate is supplied by
     *        whoever answered, so it establishes internal consistency, NOT that an independent
     *        party issued the token. Receipts say so instead of assuming.
     */
    public Rfc3161AnchorTarget(String tsaUrl, String reqPolicyOid, String accreditation,
                               java.security.cert.X509Certificate trustAnchor) {
        this.trustAnchor = trustAnchor;
        this.tsaUrl = tsaUrl == null || tsaUrl.isBlank() ? null : tsaUrl.trim();
        this.reqPolicyOid = reqPolicyOid == null || reqPolicyOid.isBlank() ? null : reqPolicyOid.trim();
        this.accreditation = accreditation == null || accreditation.isBlank() ? "NONE" : accreditation.trim();
    }

    @Override
    public AnchorKind kind() {
        return AnchorKind.RFC3161_TSA;
    }

    @Override
    public boolean isConfigured() {
        return tsaUrl != null;
    }

    @Override
    public AnchorReceipt anchor(String hexDigest) {
        byte[] imprint = decodeSha256Hex(hexDigest);
        if (!isConfigured()) {
            return AnchorReceipt.notConfigured(kind(), hexDigest);
        }
        Instant attemptedAt = Instant.now();
        try {
            TimeStampRequestGenerator gen = new TimeStampRequestGenerator();
            // Pitfall 2: without this the TSA is REQUIRED to omit its certificate.
            gen.setCertReq(true);
            if (reqPolicyOid != null) {
                gen.setReqPolicy(new org.bouncycastle.asn1.ASN1ObjectIdentifier(reqPolicyOid));
            }
            // Pitfall 3: unpredictable, 64 bits, and echoed back by the TSA.
            BigInteger nonce = new BigInteger(64, random);
            TimeStampRequest request = gen.generate(SHA256_OID, imprint, nonce);

            byte[] responseBytes = post(request.getEncoded());
            TimeStampResponse response = new TimeStampResponse(responseBytes);

            // Pitfall 1: check the refusal BEFORE trusting validate(), which passes it through.
            int status = response.getStatus();
            TimeStampToken token = response.getTimeStampToken();
            if (token == null || (status != PKIStatus.GRANTED && status != PKIStatus.GRANTED_WITH_MODS)) {
                String failInfo = response.getFailInfo() == null ? "none"
                        : response.getFailInfo().toString();
                return AnchorReceipt.failed(kind(), hexDigest, attemptedAt,
                        "TSA refused: status=" + status
                                + " failInfo=" + failInfo
                                + " message=" + String.valueOf(response.getStatusString()));
            }

            // Now validate() is meaningful: nonce echo, imprint match, policy match if requested.
            response.validate(request);

            TimeStampTokenInfo info = token.getTimeStampInfo();
            byte[] encodedToken = token.getEncoded();

            // THE check validate() does not do. Without it a CMS-shaped blob carrying the right
            // nonce and imprint — which anyone who can answer this URL can produce — becomes
            // "confirmed independent evidence". Verifying against the certificate the token
            // carries proves the token is internally consistent; chaining that certificate to a
            // configured anchor is what proves somebody else issued it. They are separate facts
            // and the receipt reports them separately (external review, 3.4).
            SignatureCheck check = verifySignature(token);
            if (!check.signatureValid) {
                return AnchorReceipt.failed(kind(), hexDigest, attemptedAt,
                        "TSA token signature did not verify: " + check.detail);
            }
            if (trustAnchor != null && !check.chainsToAnchor) {
                // An operator who configured an anchor asked for exactly this check. Recording
                // the failure in an attribute and returning CONFIRMED anyway would answer a
                // different question than the one they configured (external review, 3.4).
                return AnchorReceipt.failed(kind(), hexDigest, attemptedAt,
                        "TSA signer does not chain to the configured trust anchor");
            }

            // Independence is a fact about the WORLD, not about cryptography: an operator can
            // configure their own self-signed TSA as its own trust anchor and every
            // cryptographic test passes. It is therefore declared, never inferred.
            boolean operatorDeclaredThirdParty = !"NONE".equals(accreditation);

            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("tsaUrl", tsaUrl);
            attrs.put("accreditation", accreditation);
            attrs.put("policyOid", String.valueOf(info.getPolicy()));
            attrs.put("serialNumber", String.valueOf(info.getSerialNumber()));
            attrs.put("genTime", info.getGenTime().toInstant().toString());
            attrs.put("accuracySeconds", accuracySecondsOf(info));
            attrs.put("digestAlgorithm", DIGEST_ALGORITHM);
            // Pitfall 2's observable half. Named for what it measures: a count. A non-empty
            // certificate set usually means the signer certificate alone, which is NOT a chain,
            // and calling it one would overstate what a later verifier has to work with.
            attrs.put("embeddedCertificateCount", String.valueOf(check.certificateCount));
            attrs.put("signatureVerified", "true");
            attrs.put("signerTrustAnchorConfigured", String.valueOf(trustAnchor != null));
            attrs.put("signerChainsToTrustAnchor", String.valueOf(check.chainsToAnchor));
            // Named to stop a reader mistaking a configuration value for a verified property.
            attrs.put("thirdPartyStatusIsOperatorDeclared", String.valueOf(operatorDeclaredThirdParty));
            attrs.put("trustAnchorCheck", "issuer signature + validity only; NOT PKIX path validation");
            // Not a TODO: revocation data genuinely cannot be captured retroactively, so its
            // absence is part of the evidence rather than a gap to paper over.
            attrs.put("revocationDataCapturedAt", "never");

            logger.info("RFC 3161 token obtained from {} (serial {}, genTime {})",
                    tsaUrl, info.getSerialNumber(), info.getGenTime().toInstant());

            // Independence is a fact about the WORLD, not about cryptography, and no amount of
            // certificate checking can establish it: an operator can configure their own
            // self-signed TSA as its own trust anchor and every cryptographic test passes. So it
            // is never inferred. The operator must declare the TSA an accredited third party
            // (accreditation != NONE), AND the signature must verify, AND the signer must chain
            // to a separately configured anchor. Even then the evidence report presents this as
            // DECLARED rather than proven (external review, 3.4).
            // An absent accuracy means the token states no precision, so it cannot carry the
            // bidirectional claim its kind normally does — it degrades to an upper bound.
            AnchorKind.TimeSemantics semantics = info.getGenTimeAccuracy() == null
                    ? AnchorKind.TimeSemantics.UPPER_BOUND_ONLY
                    : AnchorKind.TimeSemantics.BIDIRECTIONAL_WITHIN_ACCURACY;

            return AnchorReceipt.confirmed(kind(), hexDigest, attemptedAt,
                    info.getGenTime().toInstant(), encodedToken, sha256Hex(encodedToken), attrs,
                    semantics);

        } catch (Exception e) {
            // Anchoring must never fail the operation that triggered it.
            logger.warn("RFC 3161 anchoring failed against {}: {}", tsaUrl, e.toString());
            return AnchorReceipt.failed(kind(), hexDigest, attemptedAt,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Accuracy in seconds as RFC 3161 states it, or "unspecified" when the token omits it. */
    private static String accuracySecondsOf(TimeStampTokenInfo info) {
        if (info.getGenTimeAccuracy() == null) {
            return "unspecified";
        }
        double seconds = info.getGenTimeAccuracy().getSeconds()
                + info.getGenTimeAccuracy().getMillis() / 1_000d
                + info.getGenTimeAccuracy().getMicros() / 1_000_000d;
        return String.valueOf(seconds);
    }

    /**
     * POST the DER request and return the DER reply.
     *
     * <p>Pitfall 4: an HTML error page from a proxy would otherwise surface as an opaque parse
     * failure, so the content type is checked and the mismatch reported as itself.
     */
    private byte[] post(byte[] derRequest) throws IOException {
        URL url = URI.create(tsaUrl).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", REQUEST_CONTENT_TYPE);
            conn.setRequestProperty("Accept", RESPONSE_CONTENT_TYPE);
            conn.setFixedLengthStreamingMode(derRequest.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(derRequest);
            }

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("TSA answered HTTP " + code + " " + conn.getResponseMessage());
            }
            String contentType = conn.getContentType();
            if (contentType == null || !contentType.toLowerCase().startsWith(RESPONSE_CONTENT_TYPE)) {
                throw new IOException("TSA answered Content-Type '" + contentType
                        + "', expected " + RESPONSE_CONTENT_TYPE
                        + " (an intermediary is probably answering instead of the TSA)");
            }
            try (InputStream in = conn.getInputStream()) {
                byte[] body = in.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (body.length > MAX_RESPONSE_BYTES) {
                    throw new IOException("TSA reply exceeds " + MAX_RESPONSE_BYTES
                            + " bytes; refusing to parse it as a token");
                }
                return body;
            }
        } finally {
            conn.disconnect();
        }
    }

    /** What could be established about the token's signature, as separate facts. */
    private record SignatureCheck(boolean signatureValid, boolean chainsToAnchor,
                                  int certificateCount, String detail) {
    }

    /**
     * Verify the CMS signature against the certificate carried in the token, and — only when an
     * anchor is configured — whether that certificate chains to it.
     */
    private SignatureCheck verifySignature(TimeStampToken token) {
        try {
            java.util.Collection<org.bouncycastle.cert.X509CertificateHolder> certs =
                    token.getCertificates().getMatches(null);
            java.util.Collection<org.bouncycastle.cert.X509CertificateHolder> signers =
                    token.getCertificates().getMatches(token.getSID());
            if (signers.isEmpty()) {
                return new SignatureCheck(false, false, certs.size(),
                        "the token carries no certificate matching its signer id"
                                + " (certReq was probably not honoured)");
            }
            org.bouncycastle.cert.X509CertificateHolder signer = signers.iterator().next();
            token.validate(new org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder()
                    .setProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())
                    .build(signer));

            boolean chains = false;
            if (trustAnchor != null) {
                try {
                    java.security.cert.X509Certificate signerCert =
                            new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                                    .setProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())
                                    .getCertificate(signer);
                    signerCert.verify(trustAnchor.getPublicKey());
                    signerCert.checkValidity(java.util.Date.from(Instant.now()));
                    chains = true;
                } catch (Exception e) {
                    logger.warn("TSA signer does not chain to the configured trust anchor: {}",
                            e.toString());
                }
            }
            return new SignatureCheck(true, chains, certs.size(), "ok");

        } catch (Exception e) {
            return new SignatureCheck(false, false, 0,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Lowercase 64-char hex to bytes. A caller that gets this wrong has a bug, so this throws. */
    static byte[] decodeSha256Hex(String hexDigest) {
        if (hexDigest == null || hexDigest.length() != 64) {
            throw new IllegalArgumentException("expected a 64-character SHA-256 hex digest, got "
                    + (hexDigest == null ? "null" : hexDigest.length() + " chars"));
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) {
            // Character.digit would accept uppercase and even full-width Unicode digits, which
            // the sidecar's binascii.unhexlify rejects — the two rungs would then disagree about
            // what a valid digest is. Accept exactly lowercase ASCII hex.
            int hi = lowerHexValue(hexDigest.charAt(i * 2));
            int lo = lowerHexValue(hexDigest.charAt(i * 2 + 1));
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException(
                        "digest is not lowercase ASCII hexadecimal at position " + (i * 2));
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int lowerHexValue(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return -1;
    }

    static String sha256Hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance(DIGEST_ALGORITHM).digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
