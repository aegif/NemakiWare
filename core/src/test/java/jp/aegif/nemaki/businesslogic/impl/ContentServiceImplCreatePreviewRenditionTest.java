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

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.util.constant.RenditionKind;

/**
 * Tests for the consolidated {@code ContentService.createPreviewRendition}, the
 * build+persist tail shared by the three rendition REST stacks. It must build a
 * CMIS_PREVIEW rendition stamped with the actor, store it via the DAO, append
 * the new id to the document's renditionIds and persist the document under the
 * supplied CallContext.
 */
public class ContentServiceImplCreatePreviewRenditionTest {

    private static final String REPO = "bedroom";

    private ContentServiceImpl service;
    private ContentDaoService dao;

    @BeforeEach
    public void setUp() {
        service = spy(new ContentServiceImpl());
        dao = mock(ContentDaoService.class);
        service.setContentDaoService(dao);
        when(dao.createRendition(eq(REPO), any(Rendition.class), any(ContentStream.class)))
                .thenReturn("rend-123");
        doReturn(null).when(service).update(any(), any(), any());
    }

    @Test
    public void buildsStampsPersistsAndLinksToDocument() {
        Document document = mock(Document.class);
        when(document.getRenditionIds()).thenReturn(new ArrayList<>(List.of("existing-1")));
        ContentStream stream = mock(ContentStream.class);
        when(stream.getLength()).thenReturn(4096L);
        CallContext ctx = mock(CallContext.class);

        Rendition result = service.createPreviewRendition(REPO, document, stream,
                "application/pdf", "PDF Preview", "alice", ctx);

        // returned rendition carries the DAO-assigned id and the requested fields
        assertEquals("rend-123", result.getId());
        assertEquals("application/pdf", result.getMimetype());
        assertEquals("PDF Preview", result.getTitle());
        assertEquals(RenditionKind.CMIS_PREVIEW.value(), result.getKind());
        assertEquals(4096L, result.getLength());
        assertEquals("alice", result.getCreator());
        assertEquals("alice", result.getModifier());

        // DAO stored the same rendition with the source stream
        verify(dao).createRendition(eq(REPO), eq(result), eq(stream));

        // document's renditionIds got the new id appended (existing preserved)
        ArgumentCaptor<List<String>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(document).setRenditionIds(idsCaptor.capture());
        assertEquals(List.of("existing-1", "rend-123"), idsCaptor.getValue());

        // document persisted under the supplied update context
        verify(service).update(eq(ctx), eq(REPO), eq(document));
    }

    @Test
    public void initialisesRenditionIdsWhenDocumentHasNone() {
        Document document = mock(Document.class);
        when(document.getRenditionIds()).thenReturn(null);
        ContentStream stream = mock(ContentStream.class);
        when(stream.getLength()).thenReturn(10L);
        CallContext ctx = mock(CallContext.class);

        service.createPreviewRendition(REPO, document, stream,
                "image/svg+xml", "SVG Preview", "bob", ctx);

        ArgumentCaptor<List<String>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(document).setRenditionIds(idsCaptor.capture());
        assertEquals(List.of("rend-123"), idsCaptor.getValue());
    }
}
