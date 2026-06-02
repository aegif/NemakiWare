package jp.aegif.nemaki.cmis.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.util.constant.DomainType;

/**
 * Security regression for {@code createDocumentFromSource}: the caller
 * must be authorized to READ the copy source. Without this, a user who
 * knows a sourceId could copy a document they cannot read into a folder
 * they can write to, then read the copy — an ACL bypass / IDOR.
 *
 * <p>These tests pin that the copy-source permission checks
 * ({@code CAN_GET_PROPERTIES_OBJECT} + {@code CAN_VIEW_CONTENT_OBJECT})
 * run, and that a denial stops the copy before any document is created.
 */
class ObjectServiceImplCopySourceAuthTest {

    private static final String REPO = "bedroom";
    private static final String SOURCE_ID = "src-doc-1";
    private static final String FOLDER_ID = "folder-1";

    private ObjectServiceImpl service;
    private ContentService contentService;
    private ExceptionService exceptionService;

    private CallContext callContext;
    private Document original;

    @BeforeEach
    void setUp() {
        service = new ObjectServiceImpl();
        contentService = mock(ContentService.class);
        exceptionService = mock(ExceptionService.class);
        service.setContentService(contentService);
        service.setExceptionService(exceptionService);

        callContext = mock(CallContext.class);
        original = mock(Document.class);
        when(contentService.getDocument(REPO, SOURCE_ID)).thenReturn(original);
    }

    @Test
    void copySource_propertyReadDenied_blocksCopyBeforeCreation() {
        // Deny READ (CAN_GET_PROPERTIES_OBJECT) on the copy SOURCE.
        doThrow(new CmisPermissionDeniedException("denied"))
                .when(exceptionService).permissionDenied(
                        eq(callContext), eq(REPO),
                        eq(PermissionMapping.CAN_GET_PROPERTIES_OBJECT),
                        eq(original));

        assertThrows(CmisPermissionDeniedException.class, () ->
                service.createDocumentFromSource(callContext, REPO, SOURCE_ID,
                        null, FOLDER_ID, null, null, null, null, null));

        // The source-read check ran on the source object…
        verify(exceptionService).permissionDenied(
                eq(callContext), eq(REPO),
                eq(PermissionMapping.CAN_GET_PROPERTIES_OBJECT), eq(original));
        // …and no document was created (copy aborted).
        verify(contentService, never()).createDocumentFromSource(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void copySource_contentViewDenied_blocksCopyBeforeCreation() {
        // Property read allowed, but content view (CAN_VIEW_CONTENT_OBJECT)
        // denied on the source — still must block, since the content is
        // duplicated into the new document.
        doThrow(new CmisPermissionDeniedException("denied"))
                .when(exceptionService).permissionDenied(
                        eq(callContext), eq(REPO),
                        eq(PermissionMapping.CAN_VIEW_CONTENT_OBJECT),
                        eq(original));

        assertThrows(CmisPermissionDeniedException.class, () ->
                service.createDocumentFromSource(callContext, REPO, SOURCE_ID,
                        null, FOLDER_ID, null, null, null, null, null));

        verify(exceptionService).permissionDenied(
                eq(callContext), eq(REPO),
                eq(PermissionMapping.CAN_VIEW_CONTENT_OBJECT), eq(original));
        verify(contentService, never()).createDocumentFromSource(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void copySource_existenceCheckedOnSource() {
        // objectNotFound must be invoked for the source object so an
        // unknown/unreadable sourceId yields 404 rather than NPE.
        doThrow(new CmisPermissionDeniedException("stop-after-existence"))
                .when(exceptionService).permissionDenied(
                        eq(callContext), eq(REPO),
                        eq(PermissionMapping.CAN_GET_PROPERTIES_OBJECT), eq(original));

        assertThrows(RuntimeException.class, () ->
                service.createDocumentFromSource(callContext, REPO, SOURCE_ID,
                        null, FOLDER_ID, null, null, null, null, null));

        verify(exceptionService).objectNotFound(
                eq(DomainType.OBJECT), eq(original), eq(SOURCE_ID));
    }
}
