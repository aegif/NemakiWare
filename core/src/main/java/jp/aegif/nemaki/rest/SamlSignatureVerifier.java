package jp.aegif.nemaki.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * SAML 2.0 Response signature verifier using Java 21 standard APIs.
 * No external SAML library dependency (no OpenSAML).
 *
 * Verifies:
 * 1. XML digital signature (javax.xml.crypto.dsig)
 * 2. Conditions (NotBefore/NotOnOrAfter with clock skew tolerance)
 * 3. AudienceRestriction (SP Entity ID)
 */
public class SamlSignatureVerifier {

	private static final Logger logger = LoggerFactory.getLogger(SamlSignatureVerifier.class);

	/** Clock skew tolerance: 5 minutes */
	private static final long CLOCK_SKEW_SECONDS = 300;

	private static final String SAML_ASSERTION_NS = "urn:oasis:names:tc:SAML:2.0:assertion";
	private static final String SAML_PROTOCOL_NS = "urn:oasis:names:tc:SAML:2.0:protocol";
	private static final String XMLDSIG_NS = "http://www.w3.org/2000/09/xmldsig#";

	/**
	 * Result of SAML signature verification.
	 *
	 * <p>On success the result also carries a {@link PendingCommits} bundle
	 * that the caller MUST {@link PendingCommits#apply()} only after every
	 * downstream operation (user extraction, getOrCreateUser, auth-method
	 * allowlist, tokenService.setToken) has succeeded. Committing earlier
	 * means a transient DAO/user/token-service failure burns the SAML
	 * Response and forces the user back through the IdP.
	 */
	public static class VerificationResult {
		private final boolean valid;
		private final String error;
		private final Element signedElement;
		private final PendingCommits pendingCommits;

		private VerificationResult(boolean valid, String error, Element signedElement, PendingCommits pendingCommits) {
			this.valid = valid;
			this.error = error;
			this.signedElement = signedElement;
			this.pendingCommits = pendingCommits;
		}

		public static VerificationResult success(Element signedElement, PendingCommits pendingCommits) {
			return new VerificationResult(true, null, signedElement, pendingCommits);
		}

		/** Internal success (no signed element / no pending commits — used by sub-verification steps). */
		static VerificationResult success() {
			return new VerificationResult(true, null, null, null);
		}

		public static VerificationResult failure(String error) {
			return new VerificationResult(false, error, null, null);
		}

		public boolean isValid() { return valid; }
		public String getError() { return error; }
		/** The signed Response or Assertion element. Identity data must be extracted from this subtree only. */
		public Element getSignedElement() { return signedElement; }
		/** Commits to apply only after the entire login flow has succeeded. May be null on failure or for sub-results. */
		public PendingCommits getPendingCommits() { return pendingCommits; }
	}

	/**
	 * State that the verifier validated as ready-to-commit but deliberately
	 * did NOT commit, so that a later downstream failure does not poison
	 * replay state for the legitimate user.
	 *
	 * <p>{@link #apply()} performs the atomic commits in the same order the
	 * verifier originally enforced (binding cookie consume, then replay-id
	 * record). Each commit is idempotent for the same identity: a second
	 * apply() returns false but is safe to call.
	 */
	public static final class PendingCommits {
		private final String bindingTokenToConsume;     // null when no binding cookie / non-strict no-cookie
		private final String inResponseToToConsume;     // null when no InResponseTo
		private final java.util.List<String> replayIds; // never null, may be empty
		private boolean applied;

		PendingCommits(String bindingTokenToConsume, String inResponseToToConsume, java.util.List<String> replayIds) {
			this.bindingTokenToConsume = bindingTokenToConsume;
			this.inResponseToToConsume = inResponseToToConsume;
			this.replayIds = replayIds == null ? java.util.List.of() : replayIds;
		}

		/**
		 * Commit binding consumption and replay records.
		 * @return null on full success, or an error string describing the
		 *         first race condition encountered (caller must reject).
		 */
		public synchronized String apply() {
			if (applied) {
				return null;
			}
			applied = true;
			if (bindingTokenToConsume != null && inResponseToToConsume != null) {
				if (!SamlAuthnRequestRegistry.getInstance().consume(bindingTokenToConsume, inResponseToToConsume)) {
					logger.warn("Concurrent SAML binding consume for InResponseTo='{}'", inResponseToToConsume);
					return "SAML binding cookie was consumed by a concurrent request";
				}
			}
			SamlReplayCache cache = SamlReplayCache.getInstance();
			for (String key : replayIds) {
				if (!cache.recordIfNew(key)) {
					logger.warn("Concurrent SAML replay commit for {}", key);
					return "SAML element already consumed (race): " + key;
				}
			}
			return null;
		}
	}

	/**
	 * Parse a PEM-encoded X.509 certificate string into an X509Certificate.
	 *
	 * @param pemCertificate PEM string (with or without header/footer lines)
	 * @return X509Certificate
	 * @throws Exception if the certificate cannot be parsed
	 */
	public static X509Certificate parseCertificate(String pemCertificate) throws Exception {
		if (pemCertificate == null || pemCertificate.trim().isEmpty()) {
			throw new IllegalArgumentException("Certificate PEM is null or empty");
		}

		String pem = pemCertificate.trim();
		// Strip PEM header/footer if present
		pem = pem.replace("-----BEGIN CERTIFICATE-----", "")
				.replace("-----END CERTIFICATE-----", "")
				.replaceAll("\\s+", "");

		byte[] certBytes = Base64.getDecoder().decode(pem);
		CertificateFactory cf = CertificateFactory.getInstance("X.509");
		return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certBytes));
	}

	/**
	 * Verify a SAML Response document.
	 *
	 * <p>Equivalent to the 5-arg overload with {@code requireInResponseTo=false}
	 * and {@code bindingToken=null} — non-strict InResponseTo handling for
	 * backward compatibility.
	 */
	public static VerificationResult verify(Document document, X509Certificate idpCertificate, String spEntityId) {
		return verify(document, idpCertificate, spEntityId, false, null);
	}

	/**
	 * Verify a SAML Response document with optional strict InResponseTo
	 * enforcement.
	 *
	 * <p><b>Phase ordering.</b> Validation runs entirely as read-only
	 * checks first (signature, conditions, audience, replay lookup,
	 * InResponseTo lookup). Stateful commits — {@link SamlReplayCache#recordIfNew}
	 * and {@link SamlAuthnRequestRegistry#consume} — happen only at the end,
	 * after every other check has succeeded. This prevents an attacker who
	 * submits a captured-but-mismatched Response from poisoning replay state
	 * for a legitimate user.
	 *
	 * @param document             parsed XML Document of the SAML Response
	 * @param idpCertificate       IdP's X.509 signing certificate
	 * @param spEntityId           expected SP Entity ID (for AudienceRestriction check)
	 * @param requireInResponseTo  when true, the Response MUST carry
	 *                             {@code InResponseTo} that matches the
	 *                             AuthnRequest bound to {@code bindingToken}.
	 * @param bindingToken         opaque token from the {@code NEMAKI_SAML_BIND}
	 *                             cookie set by {@link SamlInitiateServlet}.
	 *                             May be null in non-strict mode.
	 */
	public static VerificationResult verify(Document document, X509Certificate idpCertificate, String spEntityId,
			boolean requireInResponseTo, String bindingToken) {
		try {
			PublicKey publicKey = idpCertificate.getPublicKey();

			// 1. Find Signature element
			NodeList signatureNodes = document.getElementsByTagNameNS(XMLDSIG_NS, "Signature");
			if (signatureNodes.getLength() == 0) {
				return VerificationResult.failure("No XML signature found in SAML response");
			}

			Element signatureElement = (Element) signatureNodes.item(0);

			// 1b. Guard against XML Signature Wrapping attacks:
			// Signature must be a direct child of samlp:Response or saml:Assertion
			org.w3c.dom.Node parentNode = signatureElement.getParentNode();
			if (!(parentNode instanceof Element)) {
				return VerificationResult.failure("XML Signature parent is not an element");
			}
			Element parent = (Element) parentNode;
			String parentNs = parent.getNamespaceURI();
			String parentLocal = parent.getLocalName();
			if (!(SAML_PROTOCOL_NS.equals(parentNs) && "Response".equals(parentLocal))
					&& !(SAML_ASSERTION_NS.equals(parentNs) && "Assertion".equals(parentLocal))) {
				return VerificationResult.failure("XML Signature is not a child of Response or Assertion element");
			}

			// 2. Register ID attributes on Response and Assertion elements
			// so that DOMValidateContext can resolve URI references like #_response-id
			registerIdAttributes(document, SAML_PROTOCOL_NS, "Response");
			registerIdAttributes(document, SAML_ASSERTION_NS, "Assertion");

			// 3. Verify XML signature
			DOMValidateContext valContext = new DOMValidateContext(publicKey, signatureElement);
			XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
			XMLSignature signature = factory.unmarshalXMLSignature(valContext);

			boolean coreValid = signature.validate(valContext);
			if (!coreValid) {
				// Check signature value and references separately for better error messages
				boolean signatureValid = signature.getSignatureValue().validate(valContext);
				if (!signatureValid) {
					return VerificationResult.failure("XML signature value validation failed");
				}
				for (Object ref : signature.getSignedInfo().getReferences()) {
					boolean refValid = ((Reference) ref).validate(valContext);
					if (!refValid) {
						return VerificationResult.failure("XML signature reference validation failed: " + ((Reference) ref).getURI());
					}
				}
				return VerificationResult.failure("XML signature validation failed");
			}

			// 4. Resolve the actual signed element from the Reference URI.
			// We use document.getElementById() — the same resolution mechanism that
			// DOMValidateContext used during signature validation — to identify the
			// exact DOM element whose digest was cryptographically verified.
			// This prevents attacks involving duplicate IDs or moved signatures where
			// parentNode alone would be unreliable.
			Element signedElement = null;
			for (Object ref : signature.getSignedInfo().getReferences()) {
				String refUri = ((Reference) ref).getURI();
				if (refUri != null && refUri.startsWith("#")) {
					String refId = refUri.substring(1);
					signedElement = document.getElementById(refId);
					break;
				}
			}

			if (signedElement == null) {
				return VerificationResult.failure("Could not resolve signed element from Reference URI");
			}

			// 4b. The resolved element must be a SAML Response or Assertion
			String signedNs = signedElement.getNamespaceURI();
			String signedLocal = signedElement.getLocalName();
			if (!(SAML_PROTOCOL_NS.equals(signedNs) && "Response".equals(signedLocal))
					&& !(SAML_ASSERTION_NS.equals(signedNs) && "Assertion".equals(signedLocal))) {
				return VerificationResult.failure("Signed element is not a Response or Assertion");
			}

			// 4c. Verify the Signature is enveloped in the resolved element.
			// parent (from step 1b) must be the same DOM node as the Reference target.
			// This catches duplicate-ID attacks where two elements share an ID but only
			// one was actually signed.
			if (signedElement != parent) {
				return VerificationResult.failure("XML Signature is not enveloped in the referenced signed element");
			}

			// 5. Verify Conditions (NotBefore / NotOnOrAfter / Audience)
			// Search within the signed subtree only to prevent unsigned elements from being accepted
			VerificationResult conditionsResult = verifyConditions(signedElement, spEntityId);
			if (!conditionsResult.isValid()) {
				return conditionsResult;
			}

			// 6. Anti-replay LOOKUP — read-only. Collect IDs and reject early
			//    if any are already known to the cache. We DO NOT commit yet;
			//    that happens via PendingCommits.apply() once AuthTokenResource
			//    has actually issued the user a token.
			SamlReplayCache cache = SamlReplayCache.getInstance();
			java.util.List<String> idsToCommit = collectReplayIds(document, signedElement);
			for (String key : idsToCommit) {
				if (cache.isAlreadySeen(key)) {
					logger.warn("Rejecting replayed SAML element: {}", key);
					return VerificationResult.failure("SAML element already consumed (replay): " + key);
				}
			}

			// 7. InResponseTo binding (cookie-bound, server-issued).
			Element response = SAML_PROTOCOL_NS.equals(signedElement.getNamespaceURI())
					&& "Response".equals(signedElement.getLocalName())
					? signedElement
					: findResponseAncestor(signedElement);
			String inResponseTo = response == null ? null : response.getAttribute("InResponseTo");
			boolean hasInResponseTo = inResponseTo != null && !inResponseTo.isEmpty();

			if (requireInResponseTo) {
				if (!hasInResponseTo) {
					logger.warn("Strict mode rejected SAML Response: missing InResponseTo");
					return VerificationResult.failure("SAML Response missing InResponseTo (strict mode)");
				}
				if (bindingToken == null || bindingToken.isBlank()) {
					logger.warn("Strict mode rejected SAML Response: missing NEMAKI_SAML_BIND cookie");
					return VerificationResult.failure("SAML binding cookie missing — initiate the SSO flow via /rest/all/saml/initiate");
				}
				// Strict-mode pre-flight: peek at the registry to confirm a
				// match exists, but do not consume yet. The commit phase
				// (PendingCommits.apply) does the real consume atomically and
				// will reject if a concurrent caller beat us.
				if (!SamlAuthnRequestRegistry.getInstance().peekMatches(bindingToken, inResponseTo)) {
					logger.warn("Strict mode rejected SAML Response: InResponseTo='{}' did not match the binding cookie", inResponseTo);
					return VerificationResult.failure("SAML Response InResponseTo did not match the binding cookie");
				}
				if (logger.isDebugEnabled()) {
					logger.debug("Strict mode pre-validated SAML Response InResponseTo='{}'", inResponseTo);
				}
			} else if (hasInResponseTo) {
				logger.info("SAML Response InResponseTo='{}' (strict-mode disabled; binding consume deferred to apply())", inResponseTo);
			}

			// 8. Build the deferred-commit bundle. The caller MUST invoke
			//    pendingCommits.apply() only after the user has been
			//    extracted, created/loaded, allowed by allowedAuthMethods,
			//    and a token has been minted by tokenService.setToken().
			String bindingForCommit = (hasInResponseTo && bindingToken != null && !bindingToken.isBlank())
					? bindingToken : null;
			String inResponseToForCommit = bindingForCommit == null ? null : inResponseTo;
			PendingCommits pending = new PendingCommits(bindingForCommit, inResponseToForCommit, idsToCommit);

			logger.info("SAML response signature verified successfully (commits deferred)");
			return VerificationResult.success(signedElement, pending);

		} catch (Exception e) {
			logger.error("SAML signature verification error", e);
			return VerificationResult.failure("Signature verification error: " + e.getMessage());
		}
	}

	/**
	 * Collect every Response/Assertion ID that the replay cache should
	 * eventually track. Pure helper — does not mutate any state.
	 */
	private static java.util.List<String> collectReplayIds(Document document, Element signedElement) {
		java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
		// Top-level Response ID — outermost replay surface even when only the
		// Assertion is signed.
		NodeList responses = document.getElementsByTagNameNS(SAML_PROTOCOL_NS, "Response");
		for (int i = 0; i < responses.getLength(); i++) {
			String id = ((Element) responses.item(i)).getAttribute("ID");
			if (id != null && !id.isEmpty()) keys.add("response:" + id);
		}
		// Every Assertion inside the signed element (typical IdP-signs-Assertion).
		if (SAML_ASSERTION_NS.equals(signedElement.getNamespaceURI())
				&& "Assertion".equals(signedElement.getLocalName())) {
			String id = signedElement.getAttribute("ID");
			if (id != null && !id.isEmpty()) keys.add("assertion:" + id);
		}
		NodeList assertions = signedElement.getElementsByTagNameNS(SAML_ASSERTION_NS, "Assertion");
		for (int i = 0; i < assertions.getLength(); i++) {
			String id = ((Element) assertions.item(i)).getAttribute("ID");
			if (id != null && !id.isEmpty()) keys.add("assertion:" + id);
		}
		return new java.util.ArrayList<>(keys);
	}

	/** Walk up to find the enclosing samlp:Response element, if any. */
	private static Element findResponseAncestor(Element start) {
		org.w3c.dom.Node n = start.getParentNode();
		while (n instanceof Element) {
			Element e = (Element) n;
			if (SAML_PROTOCOL_NS.equals(e.getNamespaceURI()) && "Response".equals(e.getLocalName())) {
				return e;
			}
			n = e.getParentNode();
		}
		return null;
	}

	/**
	 * Register ID attributes on elements so that DOMValidateContext
	 * can resolve URI references (e.g., Reference URI="#_assertion-id").
	 */
	private static void registerIdAttributes(Document document, String namespace, String localName) {
		NodeList nodes = document.getElementsByTagNameNS(namespace, localName);
		for (int i = 0; i < nodes.getLength(); i++) {
			Element element = (Element) nodes.item(i);
			if (element.hasAttribute("ID")) {
				element.setIdAttributeNS(null, "ID", true);
			}
		}
	}

	/**
	 * Verify SAML Conditions: time validity and AudienceRestriction.
	 */
	private static VerificationResult verifyConditions(Element signedElement, String spEntityId) {
		NodeList conditionsNodes = signedElement.getElementsByTagNameNS(SAML_ASSERTION_NS, "Conditions");
		if (conditionsNodes.getLength() == 0) {
			// No Conditions element: this is acceptable per SAML spec (optional)
			logger.debug("No Conditions element in SAML assertion — skipping time/audience check");
			return VerificationResult.success();
		}

		Element conditions = (Element) conditionsNodes.item(0);
		Instant now = Instant.now();

		// Check NotBefore
		String notBefore = conditions.getAttribute("NotBefore");
		if (notBefore != null && !notBefore.isEmpty()) {
			try {
				Instant nbInstant = Instant.parse(notBefore);
				if (now.plusSeconds(CLOCK_SKEW_SECONDS).isBefore(nbInstant)) {
					return VerificationResult.failure("SAML assertion is not yet valid (NotBefore: " + notBefore + ")");
				}
			} catch (DateTimeParseException e) {
				return VerificationResult.failure("Invalid NotBefore format: " + notBefore);
			}
		}

		// Check NotOnOrAfter
		String notOnOrAfter = conditions.getAttribute("NotOnOrAfter");
		if (notOnOrAfter != null && !notOnOrAfter.isEmpty()) {
			try {
				Instant noaInstant = Instant.parse(notOnOrAfter);
				if (now.minusSeconds(CLOCK_SKEW_SECONDS).isAfter(noaInstant)) {
					return VerificationResult.failure("SAML assertion has expired (NotOnOrAfter: " + notOnOrAfter + ")");
				}
			} catch (DateTimeParseException e) {
				return VerificationResult.failure("Invalid NotOnOrAfter format: " + notOnOrAfter);
			}
		}

		// Check AudienceRestriction
		if (spEntityId != null && !spEntityId.isEmpty()) {
			NodeList audienceRestrictions = conditions.getElementsByTagNameNS(SAML_ASSERTION_NS, "AudienceRestriction");
			if (audienceRestrictions.getLength() > 0) {
				boolean audienceMatch = false;
				for (int i = 0; i < audienceRestrictions.getLength(); i++) {
					Element ar = (Element) audienceRestrictions.item(i);
					NodeList audiences = ar.getElementsByTagNameNS(SAML_ASSERTION_NS, "Audience");
					for (int j = 0; j < audiences.getLength(); j++) {
						String audience = audiences.item(j).getTextContent().trim();
						if (spEntityId.equals(audience)) {
							audienceMatch = true;
							break;
						}
					}
					if (audienceMatch) break;
				}
				if (!audienceMatch) {
					return VerificationResult.failure("AudienceRestriction does not match SP Entity ID: " + spEntityId);
				}
			}
		}

		return VerificationResult.success();
	}
}
