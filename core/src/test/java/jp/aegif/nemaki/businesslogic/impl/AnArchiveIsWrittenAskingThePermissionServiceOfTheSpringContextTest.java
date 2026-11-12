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
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package jp.aegif.nemaki.businesslogic.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import jp.aegif.nemaki.cmis.aspect.PermissionService;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.cache.CacheService;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.cache.model.NemakiCache;
import jp.aegif.nemaki.util.spring.SpringContext;

/**
 * The content service writes an archive asking the permission service of the Spring context who could read the object
 * as it is deleted, and answers by that record (R323, 3.4.2).
 *
 * <p>An archive's content and show answer the users the permission service answered as the object was deleted, kept in
 * the archive. The permission service depends on the content service, so the content service looks it up rather than
 * having it wired. The record's own lock hands the archive delegate a permission service; this one drives the product's
 * {@code ContentServiceImpl}, so a delegate built without the look-up — which records nothing, and answers every
 * archive to an administrator only — goes red here.
 *
 * <p>What is locked: with a permission service in the Spring context, the archive written records the registered users
 * it grants, and {@code wasArchiveReadableAtDeletion} answers them; with none, the archive is still written, records
 * nothing, and is answered to no one but an administrator. The DAO, the cached ACL and the permission service are
 * stand-ins.
 */
class AnArchiveIsWrittenAskingThePermissionServiceOfTheSpringContextTest {

    private static final String REPO = "bedroom";
    private static final String DOC = "the-version";

    private final ApplicationContext context = mock(ApplicationContext.class);
    private final AtomicReference<Archive> written = new AtomicReference<>();
    private ApplicationContext previousContext;

    @BeforeEach
    void seedTheSpringContext() {
        previousContext = SpringContext.getApplicationContext();
        new SpringContext().setApplicationContext(context);
    }

    @AfterEach
    void restoreTheSpringContext() {
        new SpringContext().setApplicationContext(previousContext);
    }

    @SuppressWarnings("unchecked")
    private ContentServiceImpl service() {
        Document document = new Document();
        document.setId(DOC);
        document.setName("Z.txt");
        document.setType("cmis:document");
        document.setObjectType("cmis:document");
        document.setCreator("creator");
        ContentDaoService dao = mock(ContentDaoService.class);
        when(dao.getContent(REPO, DOC)).thenReturn(document);
        List<UserItem> users = new ArrayList<>();
        for (String name : new String[] {"deleter", "reader"}) {
            UserItem user = new UserItem();
            user.setUserId(name);
            users.add(user);
        }
        when(dao.getUserItems(REPO)).thenReturn(users);
        when(dao.createArchive(eq(REPO), any(Archive.class), any())).thenAnswer(call -> {
            written.set(call.getArgument(1));
            return call.getArgument(1);
        });
        Acl acl = new Acl();
        acl.setLocalAces(new ArrayList<>());
        acl.setInheritedAces(new ArrayList<>());
        NemakiCache<Acl> aclCache = mock(NemakiCache.class);
        when(aclCache.get(DOC)).thenReturn(acl);
        CacheService cache = mock(CacheService.class);
        when(cache.getAclCache()).thenReturn(aclCache);
        NemakiCachePool pool = mock(NemakiCachePool.class);
        when(pool.get(REPO)).thenReturn(cache);
        ContentServiceImpl service = new ContentServiceImpl();
        service.setNemakiCachePool(pool);
        service.setRepositoryInfoMap(mock(RepositoryInfoMap.class));
        service.setPropertyManager(mock(PropertyManager.class));
        service.setContentDaoService(dao);
        return service;
    }

    private Archive archiveWrittenBy(String deleter) {
        CallContext caller = mock(CallContext.class);
        when(caller.getUsername()).thenReturn(deleter);
        written.set(null);
        ContentServiceImpl service = service();
        assertDoesNotThrow(() -> service.createArchive(caller, REPO, DOC, Boolean.FALSE), "writing the archive of " + DOC);
        assertNotNull(written.get(), "the archive of " + DOC + " was not written");
        return written.get();
    }

    @Test
    @DisplayName("the archive records whom the Spring context's permission service grants, and is answered by that record")
    void thePermissionServiceOfTheSpringContextIsAsked() {
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.checkPermission(any(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(call -> "reader".equals(((CallContext) call.getArgument(0)).getUsername()));
        when(context.getBean("PermissionService", PermissionService.class)).thenReturn(permissions);
        Archive archive = archiveWrittenBy("deleter");
        assertEquals(List.of("reader"), archive.getReadableAtDeletionBy(), "the users recorded who could read its properties");
        assertEquals(List.of("reader"), archive.getContentReadableAtDeletionBy(), "the users recorded who could read its content");
        ContentServiceImpl service = service();
        assertTrue(service.wasArchiveReadableAtDeletion(REPO, archive, "reader", true), "the reader the permission service granted");
        assertFalse(service.wasArchiveReadableAtDeletion(REPO, archive, "deleter", false), "the deleter the permission service refused");
    }

    @Test
    @DisplayName("with no permission service in the Spring context, the archive is still written, records nothing, and is answered to no one but an administrator")
    void withNoPermissionServiceNothingIsRecorded() {
        when(context.getBean("PermissionService", PermissionService.class))
                .thenThrow(new org.springframework.beans.factory.NoSuchBeanDefinitionException("PermissionService"));
        Archive archive = archiveWrittenBy("deleter");
        assertNull(archive.getReadableAtDeletionBy(), "the properties' readers recorded with no permission service");
        assertNull(archive.getContentReadableAtDeletionBy(), "the content's readers recorded with no permission service");
        assertFalse(service().wasArchiveReadableAtDeletion(REPO, archive, "reader", false),
                "an archive that recorded nothing, to a user who is not an administrator");
    }
}
