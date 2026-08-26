/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     aegif - shared rendition build+persist tail tests (#14 REST consolidation)
 ******************************************************************************/
package jp.aegif.nemaki.businesslogic.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jp.aegif.nemaki.businesslogic.rendition.RenditionManager;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.evidence.FormatDuplicationRecorder;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.util.constant.RenditionKind;

/**
 * Tests for the consolidated {@code ContentService.createPreviewRendition}, the
 * convert+persist+record path shared by the three rendition REST stacks. It must convert to
 * the requested target, build a CMIS_PREVIEW rendition stamped with the actor, store it via
 * the DAO, append the new id to the document's renditionIds and persist the document under
 * the supplied CallContext.
 *
 * <p>The conversion is part of this method rather than the caller's job. While it was the
 * caller's, all three REST stacks persisted derived copies with no P3-2 duplication recorded.
 */
public class ContentServiceImplCreatePreviewRenditionTest {

    private static final String REPO = "bedroom";

    private ContentServiceImpl service;
    private ContentDaoService dao;
    private RenditionManager renditions;

    @BeforeEach
    public void setUp() {
        service = spy(new ContentServiceImpl());
        dao = mock(ContentDaoService.class);
        service.setContentDaoService(dao);
        when(dao.createRendition(eq(REPO), any(Rendition.class), any(ContentStream.class)))
                .thenReturn("rend-123");
        renditions = mock(RenditionManager.class);
        service.setRenditionManager(renditions);
        doReturn(null).when(service).update(any(), any(), any());
    }

    @Test
    public void buildsStampsPersistsAndLinksToDocument() {
        Document document = mock(Document.class);
        when(document.getRenditionIds()).thenReturn(new ArrayList<>(List.of("existing-1")));
        ContentStream source = mock(ContentStream.class);
        ContentStream stream = mock(ContentStream.class);
        when(stream.getLength()).thenReturn(4096L);
        when(renditions.convertToPdfAttributed(any(), any())).thenReturn(
                new RenditionManager.Converted(stream, "jodconverter/LibreOffice"));
        CallContext ctx = mock(CallContext.class);

        Rendition result = service.createPreviewRendition(REPO, document, source,
                FormatDuplicationRecorder.TargetFormat.PDF, "PDF Preview", "alice", ctx);

        // returned rendition carries the DAO-assigned id and the requested fields
        assertEquals("rend-123", result.getId());
        assertEquals("application/pdf", result.getMimetype());
        assertEquals("PDF Preview", result.getTitle());
        assertEquals(RenditionKind.CMIS_PREVIEW.value(), result.getKind());
        assertEquals(4096L, result.getLength());
        assertEquals("alice", result.getCreator());
        assertEquals("alice", result.getModifier());

        // DAO stored the same rendition with the CONVERTED stream
        verify(dao).createRendition(eq(REPO), eq(result), any(ContentStream.class));

        // document's renditionIds got the new id appended (existing preserved)
        ArgumentCaptor<List<String>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(document).setRenditionIds(idsCaptor.capture());
        assertEquals(List.of("existing-1", "rend-123"), idsCaptor.getValue());

        // document persisted under the supplied update context
        verify(service).update(eq(ctx), eq(REPO), eq(document));
    }

    @Test
    public void recordsTheDuplicationItJustMade() {
        // The point of moving the conversion in here. While it was the caller's job, all three
        // REST stacks persisted derived copies and recorded nothing, and the design document
        // described those paths as out of scope — which was wrong; they persist exactly as
        // createPreview does.
        Document document = mock(Document.class);
        when(document.getId()).thenReturn("doc-1");
        when(document.getRenditionIds()).thenReturn(null);
        ContentStream source = mock(ContentStream.class);
        ContentStream converted = mock(ContentStream.class);
        when(renditions.convertToPdfAttributed(any(), any())).thenReturn(
                new RenditionManager.Converted(converted, "nemaki/cad"));
        FormatDuplicationRecorder recorder = mock(FormatDuplicationRecorder.class);
        when(recorder.recordDuplication(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new FormatDuplicationRecorder.Recorded(true, null));
        service.setFormatDuplicationRecorder(recorder);

        service.createPreviewRendition(REPO, document, source,
                FormatDuplicationRecorder.TargetFormat.PDF, "PDF Preview", "alice",
                mock(CallContext.class));

        ArgumentCaptor<FormatDuplicationRecorder.Converter> converter =
                ArgumentCaptor.forClass(FormatDuplicationRecorder.Converter.class);
        ArgumentCaptor<FormatDuplicationRecorder.TargetFormat> target =
                ArgumentCaptor.forClass(FormatDuplicationRecorder.TargetFormat.class);
        verify(recorder).recordDuplication(eq(REPO), eq("doc-1"), any(), any(),
                converter.capture(), target.capture(), any(), any(), any());
        assertEquals(FormatDuplicationRecorder.Converter.CAD_RENDITION, converter.getValue(),
                "the copy was attributed to a converter that did not make it");
        assertEquals(FormatDuplicationRecorder.TargetFormat.PDF, target.getValue());
    }

    @Test
    public void svgGoesThroughTheSvgConverter() {
        // Not a detail: convertToPdfAttributed on an SVG request would store PDF bytes under an
        // SVG media type, and record a duplication whose disclosure describes the wrong losses.
        Document document = mock(Document.class);
        when(document.getId()).thenReturn("doc-1");
        when(document.getRenditionIds()).thenReturn(null);
        ContentStream source = mock(ContentStream.class);
        ContentStream converted = mock(ContentStream.class);
        jp.aegif.nemaki.businesslogic.rendition.ExtendedRenditionManager extended =
                mock(jp.aegif.nemaki.businesslogic.rendition.ExtendedRenditionManager.class);
        when(extended.convertToSvgAttributed(any(), any())).thenReturn(
                new RenditionManager.Converted(converted, "nemaki/diagram"));
        service.setRenditionManager(extended);

        Rendition result = service.createPreviewRendition(REPO, document, source,
                FormatDuplicationRecorder.TargetFormat.SVG, "SVG Preview", "alice",
                mock(CallContext.class));

        verify(extended).convertToSvgAttributed(any(), any());
        verify(extended, never()).convertToPdfAttributed(any(), any());
        assertEquals("image/svg+xml", result.getMimetype());
    }

    @Test
    public void aFailedConversionIsNullAndRecordsNothing() {
        Document document = mock(Document.class);
        when(renditions.convertToPdfAttributed(any(), any())).thenReturn(null);
        FormatDuplicationRecorder recorder = mock(FormatDuplicationRecorder.class);
        service.setFormatDuplicationRecorder(recorder);

        Rendition result = service.createPreviewRendition(REPO, document, mock(ContentStream.class),
                FormatDuplicationRecorder.TargetFormat.PDF, "PDF Preview", "alice",
                mock(CallContext.class));

        assertNull(result, "a rendition was returned for a conversion that produced nothing");
        verify(dao, never()).createRendition(any(), any(), any());
        verify(recorder, never()).recordDuplication(any(), any(), any(), any(), any(), any(),
                any(), any());
    }

    @Test
    public void aPassThroughRecordsNoDuplication() {
        // An already-PDF source comes straight back out. Nothing was duplicated, and an entry
        // would put a copy in the chain that does not exist.
        Document document = mock(Document.class);
        when(document.getRenditionIds()).thenReturn(null);
        ContentStream source = mock(ContentStream.class);
        when(renditions.convertToPdfAttributed(any(), any())).thenReturn(
                new RenditionManager.Converted(source, "jodconverter/LibreOffice"));
        FormatDuplicationRecorder recorder = mock(FormatDuplicationRecorder.class);
        service.setFormatDuplicationRecorder(recorder);

        service.createPreviewRendition(REPO, document, source,
                FormatDuplicationRecorder.TargetFormat.PDF, "PDF Preview", "alice",
                mock(CallContext.class));

        verify(recorder, never()).recordDuplication(any(), any(), any(), any(), any(), any(),
                any(), any());
    }

    @Test
    public void svgWithNoExtendedManagerConvertsNothing() {
        // Rather than falling back to PDF and labelling it SVG.
        Document document = mock(Document.class);

        Rendition result = service.createPreviewRendition(REPO, document,
                mock(ContentStream.class), FormatDuplicationRecorder.TargetFormat.SVG,
                "SVG Preview", "alice", mock(CallContext.class));

        assertNull(result);
        verify(renditions, never()).convertToPdfAttributed(any(), any());
    }

    @Test
    public void initialisesRenditionIdsWhenDocumentHasNone() {
        Document document = mock(Document.class);
        when(document.getRenditionIds()).thenReturn(null);
        ContentStream source = mock(ContentStream.class);
        ContentStream stream = mock(ContentStream.class);
        when(stream.getLength()).thenReturn(10L);
        when(renditions.convertToPdfAttributed(any(), any())).thenReturn(
                new RenditionManager.Converted(stream, "jodconverter/LibreOffice"));
        CallContext ctx = mock(CallContext.class);

        service.createPreviewRendition(REPO, document, source,
                FormatDuplicationRecorder.TargetFormat.PDF, "PDF Preview", "bob", ctx);

        ArgumentCaptor<List<String>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(document).setRenditionIds(idsCaptor.capture());
        assertEquals(List.of("rend-123"), idsCaptor.getValue());
    }
}
