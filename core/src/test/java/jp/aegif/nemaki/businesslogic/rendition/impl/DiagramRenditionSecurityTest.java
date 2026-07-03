package jp.aegif.nemaki.businesslogic.rendition.impl;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.junit.jupiter.api.Test;

/**
 * Security regression tests for {@link DiagramRenditionManagerImpl}.
 *
 * PlantUML's default profile is LEGACY, which lets {@code !include} /
 * {@code !includeurl} read local files and fetch URLs (a local-file-read / SSRF
 * sink) when it renders untrusted diagram source. The manager forces the
 * SANDBOX profile and bounds source size / render time / output size.
 */
public class DiagramRenditionSecurityTest {

    private final DiagramRenditionManagerImpl mgr = new DiagramRenditionManagerImpl();

    private static ContentStream stream(String source, String mime) {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        ContentStreamImpl cs = new ContentStreamImpl();
        cs.setStream(new ByteArrayInputStream(bytes));
        cs.setMimeType(mime);
        cs.setLength(BigInteger.valueOf(bytes.length));
        return cs;
    }

    @Test
    public void plantumlSecurityProfileIsSandbox() {
        // The static initializer must have forced SANDBOX before any PlantUML
        // class cached the profile (unless an operator overrode it).
        assertEquals("SANDBOX", System.getProperty("PLANTUML_SECURITY_PROFILE"),
                "DiagramRenditionManagerImpl must default the PlantUML security profile to SANDBOX");
        assertEquals(net.sourceforge.plantuml.security.SecurityProfile.SANDBOX,
                net.sourceforge.plantuml.security.SecurityUtils.getSecurityProfile(),
                "PlantUML must resolve the SANDBOX profile at runtime");
    }

    @Test
    public void benignDiagramStillRenders() throws Exception {
        ContentStream out = mgr.convertToSvg(
                stream("@startuml\nAlice -> Bob : hello\n@enduml", "text/x-plantuml"), "ok.puml");
        assertNotNull(out, "a benign PlantUML diagram must still render");
        String svg = new String(out.getStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(svg.contains("<svg"), "output must be SVG");
    }

    @Test
    public void includeOfLocalFileDoesNotLeakContents() throws Exception {
        // Write a secret file and try to !include it. Under SANDBOX the include
        // is refused, so the secret marker must never appear in the rendered SVG.
        String marker = "NEMAKI_SECRET_" + Long.toHexString(System.nanoTime());
        Path secret = Files.createTempFile("nemaki-rendition-secret-", ".iuml");
        try {
            Files.writeString(secret, "title " + marker + "\n");
            String src = "@startuml\n!include " + secret.toAbsolutePath() + "\nAlice -> Bob\n@enduml";
            ContentStream out = mgr.convertToSvg(stream(src, "text/x-plantuml"), "attack.puml");
            String svg = (out == null) ? "" : new String(out.getStream().readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(svg.contains(marker),
                    "SANDBOX must block !include of local files (secret leaked into rendition)");
        } finally {
            Files.deleteIfExists(secret);
        }
    }

    @Test
    public void oversizedSourceIsRejected() {
        StringBuilder sb = new StringBuilder("@startuml\n");
        // Exceed the 512 KB source cap with filler.
        while (sb.length() < 600 * 1024) {
            sb.append("' padding line to inflate the diagram source\n");
        }
        sb.append("@enduml");
        ContentStream out = mgr.convertToSvg(stream(sb.toString(), "text/x-plantuml"), "big.puml");
        assertNull(out, "diagram source larger than the cap must be rejected");
    }
}
