package jp.aegif.nemaki.businesslogic.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.PrincipalDaoService;
import jp.aegif.nemaki.model.Group;

/**
 * Regression pin for the read-side nested-group cycle guard in
 * {@link PrincipalServiceImpl#getGroupIdsContainingUser}. Before the visited-set
 * fix, an indirect group cycle (A -> B -> A) made {@code containsUserInGroup}
 * recurse forever, so a non-member searcher StackOverflowed every non-admin
 * CMIS/RAG query. The write side rejects new cycles, but existing/hostile data
 * must still be handled safely at read time.
 */
public class PrincipalServiceImplCycleTest {

    private static final String REPO = "bedroom";

    private PrincipalDaoService dao;
    private PrincipalServiceImpl service;

    @BeforeEach
    public void setUp() {
        dao = mock(PrincipalDaoService.class);
        RepositoryInfoMap infoMap = mock(RepositoryInfoMap.class);
        RepositoryInfo info = mock(RepositoryInfo.class);
        lenient().when(infoMap.get(REPO)).thenReturn(info);
        lenient().when(info.getPrincipalIdAnonymous()).thenReturn("anonymous");
        lenient().when(info.getPrincipalIdAnyone()).thenReturn("anyone");

        service = new PrincipalServiceImpl();
        service.setPrincipalDaoService(dao);
        service.setRepositoryInfoMap(infoMap);
    }

    private Group group(String id, java.util.List<String> users, java.util.List<String> subgroups) {
        Group g = mock(Group.class);
        lenient().when(g.getGroupId()).thenReturn(id);
        lenient().when(g.getName()).thenReturn(id);
        lenient().when(g.getUsers()).thenReturn(users);
        lenient().when(g.getGroups()).thenReturn(subgroups);
        lenient().when(dao.getGroupById(REPO, id)).thenReturn(g);
        return g;
    }

    @Test
    public void indirectCycleDoesNotStackOverflowForNonMember() {
        // groupA -> groupB -> groupA (cycle). "carol" is in neither group.
        Group a = group("groupA", Arrays.asList(), Arrays.asList("groupB"));
        Group b = group("groupB", Arrays.asList(), Arrays.asList("groupA"));
        when(dao.getGroups(REPO)).thenReturn(Arrays.asList(a, b));

        Set<String> ids = assertDoesNotThrow(() -> service.getGroupIdsContainingUser(REPO, "carol"));
        assertFalse(ids.contains("groupA"), "carol is not a member of the cyclic component");
        assertFalse(ids.contains("groupB"));
        assertTrue(ids.contains("anyone"), "every user is implicitly in 'anyone'");
    }

    @Test
    public void memberReachableThroughCycleIsStillFound() {
        // groupA -> groupB -> groupA (cycle); "dave" is a direct member of groupB.
        Group a = group("groupA", Arrays.asList(), Arrays.asList("groupB"));
        Group b = group("groupB", Arrays.asList("dave"), Arrays.asList("groupA"));
        when(dao.getGroups(REPO)).thenReturn(Arrays.asList(a, b));

        Set<String> ids = assertDoesNotThrow(() -> service.getGroupIdsContainingUser(REPO, "dave"));
        assertTrue(ids.contains("groupA"), "dave reaches groupA transitively via groupB (despite the cycle)");
        assertTrue(ids.contains("groupB"), "dave is a direct member of groupB");
    }
}
