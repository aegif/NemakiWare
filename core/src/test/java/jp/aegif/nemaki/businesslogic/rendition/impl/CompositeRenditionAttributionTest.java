package jp.aegif.nemaki.businesslogic.rendition.impl;

import jp.aegif.nemaki.businesslogic.rendition.RenditionManager;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The converter named in the evidence chain must be the one that ran (P3-2).
 *
 * <h2>Volunteering is not converting</h2>
 *
 * <p>The composite walks its delegates and takes the first that CLAIMS the mime type. A claimant
 * can still return null — DiagramRenditionManagerImpl never produces PDF, CadRenditionManagerImpl
 * gives up on an extension it cannot read — and the walk falls through to the next one. So
 * "which delegate claimed it" and "which delegate produced the bytes" are different questions,
 * and only the second one is true of the copy that exists.
 *
 * <p>It matters because the duplication entry records a digest of those bytes next to the
 * converter's name and its disclosure of what that converter loses. Naming the claimant puts a
 * digest of LibreOffice output beside a description of what the CAD path drops.
 */
class CompositeRenditionAttributionTest {

    private static ContentStream stream(String name, String mime, String body) {
        return new ContentStreamImpl(name, BigInteger.valueOf(body.length()), mime,
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static RenditionManager delegate(String id, boolean claims, ContentStream result) {
        RenditionManager delegate = mock(RenditionManager.class);
        when(delegate.checkConvertible(anyString())).thenReturn(claims);
        when(delegate.converterId()).thenReturn(id);
        when(delegate.convertToPdf(any(), anyString())).thenReturn(result);
        return delegate;
    }

    @Test
    @DisplayName("the delegate that PRODUCED the bytes is reported, not the one that claimed")
    void theSucceedingDelegateIsReported() {
        ContentStream pdf = stream("out.pdf", "application/pdf", "%PDF-1.4");
        RenditionManager claimsAndFails = delegate("cad/oda", true, null);
        RenditionManager succeeds = delegate("jodconverter/LibreOffice", true, pdf);
        CompositeRenditionManagerImpl composite = new CompositeRenditionManagerImpl();
        composite.setDelegates(List.of(claimsAndFails, succeeds));

        RenditionManager.Converted converted =
                composite.convertToPdfAttributed(stream("plan.dwg", "image/vnd.dwg", "x"), "plan");

        assertEquals("jodconverter/LibreOffice", converted.converterId(),
                "the delegate that returned nothing was named as the one that converted");
        assertSame(pdf, converted.stream());
    }

    @Test
    @DisplayName("a delegate that never claims the type is never asked")
    void aDelegateThatDoesNotClaimIsNotAsked() {
        ContentStream pdf = stream("out.pdf", "application/pdf", "%PDF-1.4");
        RenditionManager silent = delegate("diagram/none", false, pdf);
        RenditionManager succeeds = delegate("jodconverter/LibreOffice", true, pdf);
        CompositeRenditionManagerImpl composite = new CompositeRenditionManagerImpl();
        composite.setDelegates(List.of(silent, succeeds));

        RenditionManager.Converted converted =
                composite.convertToPdfAttributed(stream("a.docx", "application/msword", "x"), "a");

        assertEquals("jodconverter/LibreOffice", converted.converterId());
        verify(silent, times(0)).convertToPdf(any(), anyString());
    }

    @Test
    @DisplayName("no delegate succeeding is null, not an attribution to the last one tried")
    void nothingConvertedIsNotAConversion() {
        CompositeRenditionManagerImpl composite = new CompositeRenditionManagerImpl();
        composite.setDelegates(List.of(delegate("cad/oda", true, null)));

        assertNull(composite.convertToPdfAttributed(stream("p.dwg", "image/vnd.dwg", "x"), "p"));
        assertNull(composite.convertToPdf(stream("p.dwg", "image/vnd.dwg", "x"), "p"));
    }

    @Test
    @DisplayName("convertToPdf runs the delegates ONCE, not once per caller")
    void theWalkHappensOnce() {
        // convertToPdf delegates to convertToPdfAttributed rather than walking again. Two walks
        // would convert the document twice — and the second conversion is what the caller keeps
        // while the first one's bytes are what was measured.
        ContentStream pdf = stream("out.pdf", "application/pdf", "%PDF-1.4");
        RenditionManager succeeds = delegate("jodconverter/LibreOffice", true, pdf);
        CompositeRenditionManagerImpl composite = new CompositeRenditionManagerImpl();
        composite.setDelegates(List.of(succeeds));

        assertSame(pdf, composite.convertToPdf(stream("a.docx", "application/msword", "x"), "a"));

        verify(succeeds, times(1)).convertToPdf(any(), anyString());
    }
}
