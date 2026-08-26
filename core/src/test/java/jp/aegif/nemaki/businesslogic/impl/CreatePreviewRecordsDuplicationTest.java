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
package jp.aegif.nemaki.businesslogic.impl;

import jp.aegif.nemaki.businesslogic.rendition.RenditionManager;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.evidence.FormatDuplicationRecorder;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Rendition;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The digest recorded for a converted document is the digest of what was stored.
 *
 * <h2>What is being defended</h2>
 *
 * <p>A {@code MessageDigest} that was never fed still answers — with SHA-256 of the empty input
 * — and nothing about that value marks it as not being the digest of the document. If the DAO
 * ever stops reading the stream (a path that skips the attachment write, a store that takes the
 * reference and defers), the recorder would write that value as the produced digest of a PDF.
 * <b>A false record is worse than a missing one</b>, and this is the shape that produces one
 * silently.
 *
 * <p>The second hazard is narrower and just as real: wrapping a null stream. The DAO skips the
 * attachment write when {@code getStream()} is null, and a wrapper is non-null even around
 * nothing — so an unconditional wrap turns that skip into a read of null.
 */
class CreatePreviewRecordsDuplicationTest {

    private static final String REPO = "bedroom";
    private static final byte[] PDF = "%PDF-1.4 converted".getBytes(StandardCharsets.UTF_8);

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            out.append(Character.forDigit((b >> 4) & 0xf, 16))
                    .append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }

    private static Method createPreview() throws Exception {
        Method method = ContentServiceImpl.class.getDeclaredMethod("createPreview",
                CallContext.class, String.class, ContentStream.class, Document.class);
        method.setAccessible(true);
        return method;
    }

    private record Harness(ContentServiceImpl service, ContentDaoService dao,
                           FormatDuplicationRecorder recorder, Document document) {}

    /** @param convertedStream what the converter hands back; null models a converter with none */
    private static Harness harness(InputStream convertedStream, boolean daoReadsTheStream) {
        ContentServiceImpl service = new ContentServiceImpl();
        ContentDaoService dao = mock(ContentDaoService.class);
        service.setContentDaoService(dao);

        ContentStreamImpl converted = new ContentStreamImpl();
        converted.setFileName("preview.pdf");
        converted.setMimeType("application/pdf");
        converted.setLength(BigInteger.valueOf(PDF.length));
        converted.setStream(convertedStream);

        RenditionManager renditions = mock(RenditionManager.class);
        when(renditions.convertToPdf(any(), anyString())).thenReturn(converted);
        service.setRenditionManager(renditions);

        when(dao.createRendition(eq(REPO), any(Rendition.class), any()))
                .thenAnswer(invocation -> {
                    ContentStream stored = invocation.getArgument(2);
                    if (daoReadsTheStream && stored != null && stored.getStream() != null) {
                        stored.getStream().readAllBytes();
                    }
                    return "rend-1";
                });

        FormatDuplicationRecorder recorder = mock(FormatDuplicationRecorder.class);
        when(recorder.recordDuplication(anyString(), anyString(), any(), any(), any(), any(),
                anyString())).thenReturn(new FormatDuplicationRecorder.Recorded(true, null));
        service.setFormatDuplicationRecorder(recorder);

        Document document = new Document();
        document.setId("doc-1");
        document.setName("minutes.docx");
        document.setType("cmis:document");
        return new Harness(service, dao, recorder, document);
    }

    @Test
    @DisplayName("the recorded digest is the digest of the bytes the DAO actually stored")
    void theRecordedDigestIsOfWhatWasStored() throws Exception {
        Harness harness = harness(new ByteArrayInputStream(PDF), true);

        createPreview().invoke(harness.service(), mock(CallContext.class), REPO,
                new ContentStreamImpl("minutes.docx", BigInteger.valueOf(4),
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        new ByteArrayInputStream("srcb".getBytes(StandardCharsets.UTF_8))),
                harness.document());

        org.mockito.ArgumentCaptor<String> produced =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(harness.recorder()).recordDuplication(eq(REPO), eq("doc-1"), any(),
                produced.capture(), any(), any(), anyString());

        assertEquals(sha256Hex(PDF), produced.getValue(),
                "the recorded digest is not the digest of the converted bytes");
    }

    @Test
    @DisplayName("a stream the DAO never read records NO digest, not the digest of nothing")
    void anUnreadStreamRecordsNoDigest() throws Exception {
        // The false-record shape. MessageDigest answers whether or not it was fed, and
        // SHA-256("") looks exactly like a digest.
        Harness harness = harness(new ByteArrayInputStream(PDF), false);

        createPreview().invoke(harness.service(), mock(CallContext.class), REPO,
                new ContentStreamImpl("minutes.docx", BigInteger.valueOf(4), "application/x",
                        new ByteArrayInputStream("srcb".getBytes(StandardCharsets.UTF_8))),
                harness.document());

        org.mockito.ArgumentCaptor<String> produced =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(harness.recorder()).recordDuplication(eq(REPO), eq("doc-1"), any(),
                produced.capture(), any(), any(), anyString());

        assertNull(produced.getValue(),
                "a digest was recorded for bytes nobody read. SHA-256 of the empty input is "
                        + sha256Hex(new byte[0]) + " and it would have been written as the "
                        + "digest of a converted document");
    }

    @Test
    @DisplayName("a converter that returns no stream does not blow up the rendition")
    void aNullConvertedStreamDoesNotThrow() throws Exception {
        // The DAO skips the attachment write when getStream() is null. A wrapper is non-null
        // around nothing, so wrapping unconditionally turns that skip into a read of null.
        Harness harness = harness(null, true);

        Object renditionId = createPreview().invoke(harness.service(), mock(CallContext.class),
                REPO, new ContentStreamImpl("minutes.docx", BigInteger.valueOf(4),
                        "application/x",
                        new ByteArrayInputStream("srcb".getBytes(StandardCharsets.UTF_8))),
                harness.document());

        assertNotNull(renditionId, "the rendition failed for a converter that returned no bytes");
        org.mockito.ArgumentCaptor<String> produced =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(harness.recorder()).recordDuplication(eq(REPO), eq("doc-1"), any(),
                produced.capture(), any(), any(), anyString());
        assertNull(produced.getValue(), "a digest was recorded for a stream that never existed");
    }

    @Test
    @DisplayName("a failed conversion records nothing")
    void aFailedConversionRecordsNothing() throws Exception {
        // Nothing was duplicated, so there is nothing to record. An entry here would put a
        // copy in the chain that does not exist.
        ContentServiceImpl service = new ContentServiceImpl();
        ContentDaoService dao = mock(ContentDaoService.class);
        service.setContentDaoService(dao);
        RenditionManager renditions = mock(RenditionManager.class);
        when(renditions.convertToPdf(any(), anyString())).thenReturn(null);
        service.setRenditionManager(renditions);
        FormatDuplicationRecorder recorder = mock(FormatDuplicationRecorder.class);
        service.setFormatDuplicationRecorder(recorder);
        Document document = new Document();
        document.setId("doc-1");
        document.setName("minutes.docx");

        Object result = createPreview().invoke(service, mock(CallContext.class), REPO,
                new ContentStreamImpl("minutes.docx", BigInteger.valueOf(4), "application/x",
                        new ByteArrayInputStream("srcb".getBytes(StandardCharsets.UTF_8))),
                document);

        assertNull(result);
        verify(recorder, never()).recordDuplication(anyString(), anyString(), any(), any(),
                any(), any(), anyString());
    }
}
