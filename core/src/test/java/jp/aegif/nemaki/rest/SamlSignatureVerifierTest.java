package jp.aegif.nemaki.rest;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SamlSignatureVerifier.
 * Uses keytool-generated self-signed certificate + RSA key pair for testing.
 */
class SamlSignatureVerifierTest {

	private static KeyPair keyPair;
	private static X509Certificate selfSignedCert;
	private static String pemCert;

	@BeforeAll
	static void setUp() throws Exception {
		// Generate self-signed certificate via keytool (standard JDK tool, no internal APIs)
		Path tempKs = Files.createTempFile("saml-test-", ".p12");
		Files.delete(tempKs); // keytool needs non-existing file
		try {
			Process p = new ProcessBuilder(
					"keytool", "-genkeypair",
					"-alias", "test",
					"-keyalg", "RSA", "-keysize", "2048",
					"-sigalg", "SHA256withRSA",
					"-validity", "365",
					"-dname", "CN=TestIdP, O=NemakiWare Test",
					"-keystore", tempKs.toString(),
					"-storepass", "changeit",
					"-storetype", "PKCS12"
			).redirectErrorStream(true).start();
			assertEquals(0, p.waitFor(), "keytool failed: " + new String(p.getInputStream().readAllBytes()));

			KeyStore ks = KeyStore.getInstance("PKCS12");
			try (InputStream is = Files.newInputStream(tempKs)) {
				ks.load(is, "changeit".toCharArray());
			}

			selfSignedCert = (X509Certificate) ks.getCertificate("test");
			PrivateKey privKey = (PrivateKey) ks.getKey("test", "changeit".toCharArray());
			keyPair = new KeyPair(selfSignedCert.getPublicKey(), privKey);
		} finally {
			Files.deleteIfExists(tempKs);
		}

		// Export to PEM
		byte[] certBytes = selfSignedCert.getEncoded();
		String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(certBytes);
		pemCert = "-----BEGIN CERTIFICATE-----\n" + base64 + "\n-----END CERTIFICATE-----";
	}

	@Test
	void parseCertificate_validPem() throws Exception {
		X509Certificate cert = SamlSignatureVerifier.parseCertificate(pemCert);
		assertNotNull(cert);
		assertEquals(selfSignedCert.getSubjectX500Principal(), cert.getSubjectX500Principal());
	}

	@Test
	void parseCertificate_withoutHeaders() throws Exception {
		// Strip PEM headers
		String base64Only = pemCert
				.replace("-----BEGIN CERTIFICATE-----", "")
				.replace("-----END CERTIFICATE-----", "")
				.trim();
		X509Certificate cert = SamlSignatureVerifier.parseCertificate(base64Only);
		assertNotNull(cert);
	}

	@Test
	void parseCertificate_invalidPem() {
		assertThrows(Exception.class, () ->
				SamlSignatureVerifier.parseCertificate("not-a-certificate"));
	}

	@Test
	void parseCertificate_nullInput() {
		assertThrows(IllegalArgumentException.class, () ->
				SamlSignatureVerifier.parseCertificate(null));
	}

	@Test
	void parseCertificate_emptyInput() {
		assertThrows(IllegalArgumentException.class, () ->
				SamlSignatureVerifier.parseCertificate(""));
	}

	@Test
	void verify_validSignature() throws Exception {
		Document doc = createSignedSAMLResponse(keyPair, "nemakiware-sp", 5);
		SamlSignatureVerifier.VerificationResult result =
				SamlSignatureVerifier.verify(doc, selfSignedCert, "nemakiware-sp");
		assertTrue(result.isValid(), "Expected valid: " + result.getError());
	}

	@Test
	void verify_tamperedResponse() throws Exception {
		Document doc = createSignedSAMLResponse(keyPair, "nemakiware-sp", 5);
		// Tamper with the NameID after signing
		var nameIdNodes = doc.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "NameID");
		if (nameIdNodes.getLength() > 0) {
			nameIdNodes.item(0).setTextContent("attacker@evil.com");
		}
		SamlSignatureVerifier.VerificationResult result =
				SamlSignatureVerifier.verify(doc, selfSignedCert, "nemakiware-sp");
		assertFalse(result.isValid());
		assertNotNull(result.getError());
	}

	@Test
	void verify_noSignature() throws Exception {
		Document doc = createUnsignedSAMLResponse("nemakiware-sp", 5);
		SamlSignatureVerifier.VerificationResult result =
				SamlSignatureVerifier.verify(doc, selfSignedCert, "nemakiware-sp");
		assertFalse(result.isValid());
		assertTrue(result.getError().contains("No XML signature"));
	}

	@Test
	void verify_wrongCertificate() throws Exception {
		// Sign with a different key pair
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(2048);
		KeyPair otherKeyPair = kpg.generateKeyPair();

		Document doc = createSignedSAMLResponse(otherKeyPair, "nemakiware-sp", 5);
		// Verify with original certificate (different public key)
		SamlSignatureVerifier.VerificationResult result =
				SamlSignatureVerifier.verify(doc, selfSignedCert, "nemakiware-sp");
		assertFalse(result.isValid());
	}

	@Test
	void verify_expiredConditions() throws Exception {
		// Create a response that expired 10 minutes ago
		Document doc = createSignedSAMLResponse(keyPair, "nemakiware-sp", -10);
		SamlSignatureVerifier.VerificationResult result =
				SamlSignatureVerifier.verify(doc, selfSignedCert, "nemakiware-sp");
		assertFalse(result.isValid());
		assertTrue(result.getError().contains("expired"));
	}

	@Test
	void verify_withinClockSkew() throws Exception {
		// Create a response that "expired" 3 minutes ago (within 5-minute skew)
		Document doc = createSignedSAMLResponse(keyPair, "nemakiware-sp", -3);
		SamlSignatureVerifier.VerificationResult result =
				SamlSignatureVerifier.verify(doc, selfSignedCert, "nemakiware-sp");
		assertTrue(result.isValid(), "Should pass within clock skew: " + result.getError());
	}

	@Test
	void verify_audienceMismatch() throws Exception {
		Document doc = createSignedSAMLResponse(keyPair, "other-sp", 5);
		SamlSignatureVerifier.VerificationResult result =
				SamlSignatureVerifier.verify(doc, selfSignedCert, "nemakiware-sp");
		assertFalse(result.isValid());
		assertTrue(result.getError().contains("AudienceRestriction"));
	}

	@Test
	void verify_assertionSigned_valid() throws Exception {
		Document doc = createAssertionSignedSAMLResponse(keyPair, "nemakiware-sp", 5,
				"testuser@example.com");
		SamlSignatureVerifier.VerificationResult result =
				SamlSignatureVerifier.verify(doc, selfSignedCert, "nemakiware-sp");
		assertTrue(result.isValid(), "Expected valid: " + result.getError());
		// signedElement should be the Assertion, not the Response
		assertNotNull(result.getSignedElement());
		assertEquals("Assertion", result.getSignedElement().getLocalName());
	}

	@Test
	void verify_responseSigned_signedElementIsResponse() throws Exception {
		Document doc = createSignedSAMLResponse(keyPair, "nemakiware-sp", 5);
		SamlSignatureVerifier.VerificationResult result =
				SamlSignatureVerifier.verify(doc, selfSignedCert, "nemakiware-sp");
		assertTrue(result.isValid(), "Expected valid: " + result.getError());
		assertNotNull(result.getSignedElement());
		assertEquals("Response", result.getSignedElement().getLocalName());
	}

	/**
	 * Wrapping attack: attacker injects unsigned assertion (with attacker NameID)
	 * before the legitimately signed assertion. Signature verification should
	 * still pass, but signedElement must point to the signed assertion only.
	 * Identity extraction from signedElement must yield the legitimate user,
	 * NOT the attacker.
	 */
	@Test
	void verify_wrappingAttack_signedElementExcludesUnsignedAssertion() throws Exception {
		Document doc = createAssertionSignedSAMLResponse(keyPair, "nemakiware-sp", 5,
				"legitimate@example.com");

		// Inject an unsigned attacker assertion BEFORE the signed one
		Element responseElement = doc.getDocumentElement();
		Element attackerAssertion = doc.createElementNS(
				"urn:oasis:names:tc:SAML:2.0:assertion", "saml:Assertion");
		attackerAssertion.setAttribute("Version", "2.0");
		attackerAssertion.setAttribute("ID", "_attacker-assertion");
		Element attackerSubject = doc.createElementNS(
				"urn:oasis:names:tc:SAML:2.0:assertion", "saml:Subject");
		Element attackerNameId = doc.createElementNS(
				"urn:oasis:names:tc:SAML:2.0:assertion", "saml:NameID");
		attackerNameId.setTextContent("attacker@evil.com");
		attackerSubject.appendChild(attackerNameId);
		attackerAssertion.appendChild(attackerSubject);
		// Insert attacker assertion before the signed assertion
		var assertions = responseElement.getElementsByTagNameNS(
				"urn:oasis:names:tc:SAML:2.0:assertion", "Assertion");
		responseElement.insertBefore(attackerAssertion, assertions.item(0));

		// Verification should still succeed (signature is on the legitimate assertion)
		SamlSignatureVerifier.VerificationResult result =
				SamlSignatureVerifier.verify(doc, selfSignedCert, "nemakiware-sp");
		assertTrue(result.isValid(), "Expected valid: " + result.getError());

		// signedElement must be the signed assertion, not the attacker one
		Element signedElement = result.getSignedElement();
		assertNotNull(signedElement);
		assertEquals("Assertion", signedElement.getLocalName());

		// Extract NameID from signedElement only — must be the legitimate user
		var nameIdNodes = signedElement.getElementsByTagNameNS(
				"urn:oasis:names:tc:SAML:2.0:assertion", "NameID");
		assertTrue(nameIdNodes.getLength() > 0);
		assertEquals("legitimate@example.com", nameIdNodes.item(0).getTextContent().trim());

		// Whole-document search would yield attacker — this proves the fix works
		var allNameIds = doc.getElementsByTagNameNS(
				"urn:oasis:names:tc:SAML:2.0:assertion", "NameID");
		assertEquals("attacker@evil.com", allNameIds.item(0).getTextContent().trim(),
				"First NameID in document order should be the attacker's (proving the attack vector exists)");
	}

	/**
	 * Mismatched-reference wrapping attack: attacker creates a new Assertion,
	 * copies the legitimate signature (which references #_assertion-id) inside it,
	 * while the legitimate assertion with ID="_assertion-id" still exists elsewhere.
	 * The signature validates (it references the original element), but signedElement
	 * would point at the attacker's assertion if we relied on parentNode alone.
	 * The Reference-to-parent binding check must reject this.
	 */
	@Test
	void verify_mismatchedReferenceWrapping_rejected() throws Exception {
		Document doc = createAssertionSignedSAMLResponse(keyPair, "nemakiware-sp", 5,
				"legitimate@example.com");

		Element responseElement = doc.getDocumentElement();

		// Find the signed assertion and its Signature child
		var assertions = responseElement.getElementsByTagNameNS(
				"urn:oasis:names:tc:SAML:2.0:assertion", "Assertion");
		Element signedAssertion = (Element) assertions.item(0);
		var signatureNodes = signedAssertion.getElementsByTagNameNS(
				"http://www.w3.org/2000/09/xmldsig#", "Signature");
		Element signatureElement = (Element) signatureNodes.item(0);

		// Create attacker assertion with a DIFFERENT ID
		Element attackerAssertion = doc.createElementNS(
				"urn:oasis:names:tc:SAML:2.0:assertion", "saml:Assertion");
		attackerAssertion.setAttribute("Version", "2.0");
		attackerAssertion.setAttribute("ID", "_attacker-id");
		Element attackerSubject = doc.createElementNS(
				"urn:oasis:names:tc:SAML:2.0:assertion", "saml:Subject");
		Element attackerNameId = doc.createElementNS(
				"urn:oasis:names:tc:SAML:2.0:assertion", "saml:NameID");
		attackerNameId.setTextContent("attacker@evil.com");
		attackerSubject.appendChild(attackerNameId);
		attackerAssertion.appendChild(attackerSubject);

		// Move the signature from the legitimate assertion into the attacker assertion.
		// The signature still references #_assertion-id (the legitimate one).
		signedAssertion.removeChild(signatureElement);
		attackerAssertion.appendChild(signatureElement);

		// Insert attacker assertion before the legitimate one
		responseElement.insertBefore(attackerAssertion, signedAssertion);

		// Verification must FAIL: signature's Reference (#_assertion-id) does not
		// match the enclosing parent ID (_attacker-id)
		SamlSignatureVerifier.VerificationResult result =
				SamlSignatureVerifier.verify(doc, selfSignedCert, "nemakiware-sp");
		assertFalse(result.isValid(),
				"Should reject: signature Reference targets different element than its parent");
		assertTrue(result.getError().contains("not enveloped"),
				"Error should mention envelopment mismatch: " + result.getError());
	}

	/**
	 * Duplicate-ID wrapping attack: attacker creates a second Assertion with the
	 * same ID as the legitimate one, moves the Signature into it. The signature
	 * Reference resolves to the legitimate assertion (first in document order)
	 * via document.getElementById(), but the Signature's parent is the attacker's
	 * assertion. Without Reference-resolved signedElement binding, the verifier
	 * would return the attacker's assertion as signedElement.
	 * The fix uses document.getElementById() and verifies object identity with
	 * the Signature's parent.
	 */
	@Test
	void verify_duplicateIdWrapping_rejected() throws Exception {
		Document doc = createAssertionSignedSAMLResponse(keyPair, "nemakiware-sp", 5,
				"legitimate@example.com");

		Element responseElement = doc.getDocumentElement();

		// Find the signed assertion and its Signature child
		var assertions = responseElement.getElementsByTagNameNS(
				"urn:oasis:names:tc:SAML:2.0:assertion", "Assertion");
		Element legitimateAssertion = (Element) assertions.item(0);
		var signatureNodes = legitimateAssertion.getElementsByTagNameNS(
				"http://www.w3.org/2000/09/xmldsig#", "Signature");
		Element signatureElement = (Element) signatureNodes.item(0);

		// Create attacker assertion with SAME ID (duplicate-ID attack)
		Element attackerAssertion = doc.createElementNS(
				"urn:oasis:names:tc:SAML:2.0:assertion", "saml:Assertion");
		attackerAssertion.setAttribute("Version", "2.0");
		attackerAssertion.setAttribute("ID", "_assertion-id"); // same ID as legitimate!
		Element attackerSubject = doc.createElementNS(
				"urn:oasis:names:tc:SAML:2.0:assertion", "saml:Subject");
		Element attackerNameId = doc.createElementNS(
				"urn:oasis:names:tc:SAML:2.0:assertion", "saml:NameID");
		attackerNameId.setTextContent("attacker@evil.com");
		attackerSubject.appendChild(attackerNameId);
		attackerAssertion.appendChild(attackerSubject);

		// Move Signature from legitimate assertion into attacker assertion
		legitimateAssertion.removeChild(signatureElement);
		attackerAssertion.appendChild(signatureElement);

		// Append attacker assertion AFTER the legitimate one
		// (legitimate is first in doc order, so getElementById returns it)
		responseElement.appendChild(attackerAssertion);

		// Verification must FAIL. Multiple defense layers may catch this:
		// - JDK secure validation detects duplicate IDs (URIReferenceException)
		// - Our step 4c detects signedElement != parent (envelopment mismatch)
		SamlSignatureVerifier.VerificationResult result =
				SamlSignatureVerifier.verify(doc, selfSignedCert, "nemakiware-sp");
		assertFalse(result.isValid(),
				"Should reject duplicate-ID wrapping attack");
	}

	// ===== Helper methods =====

	/**
	 * Create a signed SAML Response document.
	 * @param kp key pair for signing
	 * @param audience audience restriction value
	 * @param validForMinutes minutes from now before expiration (negative = already expired)
	 */
	private static Document createSignedSAMLResponse(KeyPair kp, String audience, int validForMinutes) throws Exception {
		Document doc = createUnsignedSAMLResponse(audience, validForMinutes);

		// Sign the Response element
		Element responseElement = doc.getDocumentElement();
		responseElement.setIdAttributeNS(null, "ID", true);

		XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");

		Reference ref = fac.newReference(
				"#_response-id",
				fac.newDigestMethod(DigestMethod.SHA256, null),
				List.of(
						fac.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
						fac.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null)
				),
				null, null);

		SignedInfo si = fac.newSignedInfo(
				fac.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
				fac.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
				Collections.singletonList(ref));

		DOMSignContext dsc = new DOMSignContext(kp.getPrivate(), responseElement);
		// Insert Signature after Issuer (standard SAML placement)
		var issuerNodes = responseElement.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "Issuer");
		if (issuerNodes.getLength() > 0) {
			dsc.setNextSibling(issuerNodes.item(0).getNextSibling());
		}

		XMLSignature signature = fac.newXMLSignature(si, null);
		signature.sign(dsc);

		return doc;
	}

	/**
	 * Create a SAML Response with the Assertion element signed (not the Response).
	 */
	private static Document createAssertionSignedSAMLResponse(KeyPair kp, String audience,
			int validForMinutes, String nameId) throws Exception {
		Document doc = createUnsignedSAMLResponse(audience, validForMinutes);

		// Replace NameID text with the provided value
		var nameIdNodes = doc.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "NameID");
		if (nameIdNodes.getLength() > 0) {
			nameIdNodes.item(0).setTextContent(nameId);
		}

		// Sign the Assertion element (not the Response)
		var assertionNodes = doc.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "Assertion");
		Element assertionElement = (Element) assertionNodes.item(0);
		assertionElement.setIdAttributeNS(null, "ID", true);

		XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");

		Reference ref = fac.newReference(
				"#_assertion-id",
				fac.newDigestMethod(DigestMethod.SHA256, null),
				List.of(
						fac.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
						fac.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null)
				),
				null, null);

		SignedInfo si = fac.newSignedInfo(
				fac.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
				fac.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
				Collections.singletonList(ref));

		DOMSignContext dsc = new DOMSignContext(kp.getPrivate(), assertionElement);
		// Insert Signature after the Assertion's Issuer
		var issuerNodes = assertionElement.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "Issuer");
		if (issuerNodes.getLength() > 0) {
			dsc.setNextSibling(issuerNodes.item(0).getNextSibling());
		}

		XMLSignature signature = fac.newXMLSignature(si, null);
		signature.sign(dsc);

		return doc;
	}

	/**
	 * Create an unsigned SAML Response document.
	 */
	private static Document createUnsignedSAMLResponse(String audience, int validForMinutes) throws Exception {
		Instant now = Instant.now();
		Instant notBefore = now.minus(5, ChronoUnit.MINUTES);
		Instant notOnOrAfter = now.plus(validForMinutes, ChronoUnit.MINUTES);
		String issueInstant = now.toString();

		String xml = """
				<samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol"
				                xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion"
				                ID="_response-id"
				                Version="2.0"
				                IssueInstant="%s"
				                Destination="https://sp.example.com/acs">
				    <saml:Issuer>https://idp.example.com</saml:Issuer>
				    <samlp:Status>
				        <samlp:StatusCode Value="urn:oasis:names:tc:SAML:2.0:status:Success"/>
				    </samlp:Status>
				    <saml:Assertion Version="2.0" ID="_assertion-id" IssueInstant="%s">
				        <saml:Issuer>https://idp.example.com</saml:Issuer>
				        <saml:Subject>
				            <saml:NameID Format="urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress">testuser@example.com</saml:NameID>
				        </saml:Subject>
				        <saml:Conditions NotBefore="%s" NotOnOrAfter="%s">
				            <saml:AudienceRestriction>
				                <saml:Audience>%s</saml:Audience>
				            </saml:AudienceRestriction>
				        </saml:Conditions>
				        <saml:AuthnStatement AuthnInstant="%s">
				            <saml:AuthnContext>
				                <saml:AuthnContextClassRef>urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport</saml:AuthnContextClassRef>
				            </saml:AuthnContext>
				        </saml:AuthnStatement>
				        <saml:AttributeStatement>
				            <saml:Attribute Name="email">
				                <saml:AttributeValue>testuser@example.com</saml:AttributeValue>
				            </saml:Attribute>
				        </saml:AttributeStatement>
				    </saml:Assertion>
				</samlp:Response>
				""".formatted(issueInstant, issueInstant, notBefore.toString(), notOnOrAfter.toString(), audience, issueInstant);

		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		DocumentBuilder builder = factory.newDocumentBuilder();
		return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
	}
}
