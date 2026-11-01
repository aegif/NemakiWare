package jp.aegif.nemaki.cmis.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.chemistry.opencmis.commons.data.BulkUpdateObjectIdAndChangeToken;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.BulkUpdateObjectIdAndChangeTokenImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.util.cache.CacheService;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.lock.ThreadLockService;

/**
 * A bulk update does not answer the JVM failing as one object's failure.
 *
 * <p>A task's Error escapes its catch of exceptions and comes back to the wait wrapped in an ExecutionException, which was
 * logged at DEBUG; the request answered with the other objects as done (closing batch 34 review, Codex P2-2 — an
 * OutOfMemoryError from the store, or from the cache eviction after a failed update, which goes up as it is since closing
 * batch 34).
 */
class ABulkUpdateDoesNotAnswerTheJvmFailingAsDoneTest {

    private static final String REPO = "bedroom";

    private static Document document(String id) {
        Document document = new Document();
        document.setId(id);
        document.setObjectType("cmis:document");
        document.setAspects(new ArrayList<>());
        document.setSecondaryIds(new ArrayList<>());
        return document;
    }

    @Test
    @DisplayName("an OutOfMemoryError in one object's task goes up, after the other object is answered as done")
    void theJvmFailingInOneTaskGoesUp() {
        ContentService contentService = mock(ContentService.class);
        when(contentService.getContent(REPO, "fine")).thenReturn(document("fine"));
        when(contentService.getContent(REPO, "failing")).thenReturn(document("failing"));
        OutOfMemoryError fatal = new OutOfMemoryError("the heap is full");
        when(contentService.updateProperties(any(), eq(REPO), any(), any())).thenAnswer(call -> {
            if ("failing".equals(((Content) call.getArgument(3)).getId())) {
                throw fatal;
            }
            return call.getArgument(3);
        });
        NemakiCachePool caches = mock(NemakiCachePool.class);
        when(caches.get(REPO)).thenReturn(mock(CacheService.class, RETURNS_DEEP_STUBS));
        ThreadLockService locks = mock(ThreadLockService.class);
        when(locks.getWriteLock(anyString(), anyString())).thenAnswer(call -> new ReentrantLock());
        ObjectServiceImpl service = new ObjectServiceImpl();
        service.setContentService(contentService);
        service.setExceptionService(mock(ExceptionService.class));
        service.setTypeManager(mock(TypeManager.class, RETURNS_DEEP_STUBS));
        service.setNemakiCachePool(caches);
        service.setThreadLockService(locks);
        PropertiesImpl properties = new PropertiesImpl();
        properties.addProperty(new PropertyStringImpl("cmis:description", "y"));
        List<BulkUpdateObjectIdAndChangeToken> objects = List.of(new BulkUpdateObjectIdAndChangeTokenImpl("fine", null),
                new BulkUpdateObjectIdAndChangeTokenImpl("failing", null));

        OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> service.bulkUpdateProperties(
                mock(CallContext.class), REPO, objects, properties, null, null, null),
                "the JVM failing in one object's task was answered as that object's refusal, the others as done");
        assertEquals(fatal, thrown, "another failure went up than the JVM's");
    }
}
