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

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.evidence.FormatDuplicationRecorder;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Rendition;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Check-out copies a document's renditions, and those copies are derived copies (P3-2).
 *
 * <h2>Two defects, and only one of them was about evidence</h2>
 *
 * <p>The copy loop lived in {@code AttachmentServiceDelegate}, persisted renditions with its
 * own {@code createRendition} call, and recorded nothing. Its only caller <b>discarded the ids
 * it returned</b>, so every check-out stored a full set of rendition copies that no document
 * referenced — orphan rows and orphan bytes.
 *
 * <p>The order matters for the record as much as for the storage. Recording a duplication
 * against a working copy that does not carry the rendition would say a copy of it exists when
 * it does not; so the copy now happens after the working copy exists, and attaches to it.
 */
class CopyRenditionsAreRecordedTest {

    private static final String REPO = "bedroom";

    private ContentServiceImpl service;
    private ContentDaoService dao;
    private FormatDuplicationRecorder recorder;

    @BeforeEach
    void setUp() {
        service = new ContentServiceImpl();
        dao = mock(ContentDaoService.class);
        service.setContentDaoService(dao);
        recorder = mock(FormatDuplicationRecorder.class);
        when(recorder.recordDuplication(anyString(), anyString(), any(), any(), any(), any(),
                any(), any(), anyString())).thenReturn(new FormatDuplicationRecorder.Recorded(true, null));
        service.setFormatDuplicationRecorder(recorder);
    }

    private static Rendition rendition(String id, String mimeType, int length) {
        Rendition r = new Rendition();
        r.setId(id);
        r.setMimetype(mimeType);
        r.setLength(length);
        r.setKind("cmis:preview");
        r.setInputStream(new ByteArrayInputStream("x".repeat(length).getBytes(StandardCharsets.UTF_8)));
        return r;
    }

    private void copyOnto(Document source, Document target) throws Exception {
        Method m = ContentServiceImpl.class.getDeclaredMethod("copyRenditionsOnto",
                CallContext.class, String.class, Document.class, Document.class);
        m.setAccessible(true);
        m.invoke(service, mock(CallContext.class), REPO, source, target);
    }

    private Document source(String... renditionIds) {
        Document d = new Document();
        d.setId("doc-source");
        d.setRenditionIds(new java.util.ArrayList<>(List.of(renditionIds)));
        return d;
    }

    private Document target() {
        Document d = new Document();
        d.setId("doc-pwc");
        return d;
    }

    @Test
    @DisplayName("the copies are attached to the working copy, not orphaned")
    void theCopiesAreAttached() throws Exception {
        when(dao.getRendition(REPO, "r-1")).thenReturn(rendition("r-1", "application/pdf", 8));
        when(dao.getRendition(REPO, "r-2")).thenReturn(rendition("r-2", "image/svg+xml", 8));
        when(dao.createRendition(eq(REPO), any(Rendition.class), any()))
                .thenReturn("copy-1", "copy-2");
        Document target = target();

        copyOnto(source("r-1", "r-2"), target);

        assertEquals(List.of("copy-1", "copy-2"), target.getRenditionIds(),
                "the copies were stored and then nobody pointed at them");
    }

    @Test
    @DisplayName("each copy is recorded, attributed to COPYING and not to a converter")
    void eachCopyIsRecorded() throws Exception {
        when(dao.getRendition(REPO, "r-1")).thenReturn(rendition("r-1", "application/pdf", 8));
        when(dao.createRendition(eq(REPO), any(Rendition.class), any())).thenAnswer(call -> {
            ContentStream stored = call.getArgument(2);
            stored.getStream().readAllBytes();
            jp.aegif.nemaki.dao.impl.couch.delegate.AttachmentDaoDelegate
                    .renditionContentStored.set(Boolean.TRUE);
            return "copy-1";
        });

        copyOnto(source("r-1"), target());

        ArgumentCaptor<FormatDuplicationRecorder.Converter> converter =
                ArgumentCaptor.forClass(FormatDuplicationRecorder.Converter.class);
        ArgumentCaptor<FormatDuplicationRecorder.TargetFormat> format =
                ArgumentCaptor.forClass(FormatDuplicationRecorder.TargetFormat.class);
        verify(recorder).recordDuplication(eq(REPO), eq("doc-pwc"), any(), any(),
                converter.capture(), format.capture(), any(), any(), anyString());

        assertEquals(FormatDuplicationRecorder.Converter.COPIED_RENDITION, converter.getValue(),
                "a copy was attributed to a converter that did not run for it");
        assertEquals(FormatDuplicationRecorder.TargetFormat.PDF, format.getValue());
    }

    @Test
    @DisplayName("the format comes from the copied rendition's own media type")
    void theFormatIsTheOneBeingCopied() throws Exception {
        when(dao.getRendition(REPO, "r-1")).thenReturn(rendition("r-1", "image/svg+xml", 8));
        when(dao.createRendition(eq(REPO), any(Rendition.class), any())).thenReturn("copy-1");
        ArgumentCaptor<Rendition> stored = ArgumentCaptor.forClass(Rendition.class);

        copyOnto(source("r-1"), target());

        verify(dao).createRendition(eq(REPO), stored.capture(), any());
        assertEquals("image/svg+xml", stored.getValue().getMimetype(),
                "the copy's media type was overwritten on the way through the recording path");
        ArgumentCaptor<FormatDuplicationRecorder.TargetFormat> format =
                ArgumentCaptor.forClass(FormatDuplicationRecorder.TargetFormat.class);
        verify(recorder).recordDuplication(any(), any(), any(), any(), any(), format.capture(),
                any(), any(), any());
        assertEquals(FormatDuplicationRecorder.TargetFormat.SVG, format.getValue());
    }

    @Test
    @DisplayName("a media type this build does not know is UNKNOWN, not the nearest guess")
    void anUnknownMediaTypeIsNotGuessed() throws Exception {
        when(dao.getRendition(REPO, "r-1")).thenReturn(rendition("r-1", "image/tiff", 8));
        when(dao.createRendition(eq(REPO), any(Rendition.class), any())).thenReturn("copy-1");

        copyOnto(source("r-1"), target());

        ArgumentCaptor<FormatDuplicationRecorder.TargetFormat> format =
                ArgumentCaptor.forClass(FormatDuplicationRecorder.TargetFormat.class);
        verify(recorder).recordDuplication(any(), any(), any(), any(), any(), format.capture(),
                any(), any(), any());
        assertEquals(FormatDuplicationRecorder.TargetFormat.UNKNOWN, format.getValue(),
                "a TIFF rendition was recorded as though its caveats were PDF's");
    }

    @Test
    @DisplayName("a rendition that has since been deleted is skipped, not an NPE")
    void aMissingOriginalIsSkipped() throws Exception {
        when(dao.getRendition(REPO, "r-gone")).thenReturn(null);
        Document target = target();

        copyOnto(source("r-gone"), target);

        verify(dao, never()).createRendition(any(), any(), any());
        verify(recorder, never()).recordDuplication(any(), any(), any(), any(), any(), any(),
                any(), any(), any());
        assertTrue(target.getRenditionIds() == null || target.getRenditionIds().isEmpty());
    }

    @Test
    @DisplayName("a source with no renditions does nothing at all")
    void nothingToCopy() throws Exception {
        Document source = new Document();
        source.setId("doc-source");
        Document target = target();

        copyOnto(source, target);

        verify(dao, never()).createRendition(any(), any(), any());
        verify(recorder, never()).recordDuplication(any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    @DisplayName("the disclosure for a copy does not claim a conversion happened")
    void theDisclosureSaysNothingWasConverted() {
        String text = FormatDuplicationRecorder.Converter.COPIED_RENDITION.disclosureFor(
                FormatDuplicationRecorder.TargetFormat.PDF);

        assertTrue(text.contains("Nothing was converted"), text);
        assertTrue(text.contains("does not record which tool"),
                "it implies the earlier converter is known: " + text);
        assertTrue(text.contains("ORIGINAL is unchanged"), text);
    }
}
