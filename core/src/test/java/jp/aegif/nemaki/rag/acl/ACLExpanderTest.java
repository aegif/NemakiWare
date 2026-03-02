package jp.aegif.nemaki.rag.acl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Group;
import jp.aegif.nemaki.model.User;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Unit tests for ACLExpander.
 * 
 * Tests ACL expansion functionality including:
 * - Expanding ACL to reader list
 * - Permission checking (cmis:read, cmis:all)
 * - Group membership expansion
 * - Special principals (cmis:anyone, cmis:anonymous)
 * - Solr filter query building
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ACLExpanderTest {

    private static final String REPO_ID = "test-repo";

    @Mock
    private PrincipalService principalService;

    @Mock
    private ContentService contentService;

    @Mock
    private Content content;

    @Mock
    private Acl acl;

    private ACLExpander aclExpander;

    @BeforeEach
    public void setUp() {
        aclExpander = new ACLExpander(principalService, contentService);
        // Default: contentService.calculateAcl returns the content's ACL
        when(contentService.calculateAcl(eq(REPO_ID), any(Content.class))).thenAnswer(
                invocation -> invocation.getArgument(1, Content.class).getAcl());
    }

    // ========== expandToReaders Tests ==========

    @Test
    public void testExpandToReadersWithNullAcl() {
        when(content.getAcl()).thenReturn(null);
        when(content.getId()).thenReturn("doc-1");
        when(contentService.calculateAcl(REPO_ID, content)).thenReturn(null);

        List<String> readers = aclExpander.expandToReaders(REPO_ID, content);

        assertNotNull(readers, "Readers should not be null");
        // Null ACL should now default to admin-only for security
        assertTrue(readers.contains("user:admin"), "Null ACL should result in admin-only readers");
    }

    @Test
    public void testExpandToReadersWithEmptyAces() {
        when(content.getAcl()).thenReturn(acl);
        when(content.getId()).thenReturn("doc-1");
        when(acl.getAllAces()).thenReturn(new ArrayList<>());
        when(contentService.calculateAcl(REPO_ID, content)).thenReturn(acl);

        List<String> readers = aclExpander.expandToReaders(REPO_ID, content);

        assertNotNull(readers, "Readers should not be null");
        // Empty ACEs should now default to admin-only for security
        assertTrue(readers.contains("user:admin"), "Empty ACEs should result in admin-only readers");
    }

    @Test
    public void testExpandToReadersWithNullAces() {
        when(content.getAcl()).thenReturn(acl);
        when(content.getId()).thenReturn("doc-1");
        when(acl.getAllAces()).thenReturn(null);
        when(contentService.calculateAcl(REPO_ID, content)).thenReturn(acl);

        List<String> readers = aclExpander.expandToReaders(REPO_ID, content);

        assertNotNull(readers, "Readers should not be null");
        // Null ACEs should now default to admin-only for security
        assertTrue(readers.contains("user:admin"), "Null ACEs should result in admin-only readers");
    }

    @Test
    public void testExpandToReadersWithUserReadPermission() {
        Ace ace = createAce("user1", Arrays.asList("cmis:read"));
        when(content.getAcl()).thenReturn(acl);
        when(acl.getAllAces()).thenReturn(Arrays.asList(ace));
        
        User user1 = mock(User.class);
        when(principalService.getUserById(REPO_ID, "user1")).thenReturn(user1);

        List<String> readers = aclExpander.expandToReaders(REPO_ID, content);

        assertNotNull(readers, "Readers should not be null");
        assertTrue(readers.contains("user:user1"), "Should contain user:user1");
    }

    @Test
    public void testExpandToReadersWithUserAllPermission() {
        Ace ace = createAce("user1", Arrays.asList("cmis:all"));
        when(content.getAcl()).thenReturn(acl);
        when(acl.getAllAces()).thenReturn(Arrays.asList(ace));
        
        User user1 = mock(User.class);
        when(principalService.getUserById(REPO_ID, "user1")).thenReturn(user1);

        List<String> readers = aclExpander.expandToReaders(REPO_ID, content);

        assertNotNull(readers, "Readers should not be null");
        assertTrue(readers.contains("user:user1"), "cmis:all should grant read access");
    }

    @Test
    public void testExpandToReadersWithCmisAnyone() {
        Ace ace = createAce("cmis:anyone", Arrays.asList("cmis:read"));
        when(content.getAcl()).thenReturn(acl);
        when(acl.getAllAces()).thenReturn(Arrays.asList(ace));

        List<String> readers = aclExpander.expandToReaders(REPO_ID, content);

        assertNotNull(readers, "Readers should not be null");
        assertTrue(readers.contains("anyone"), "Should contain 'anyone'");
    }

    @Test
    public void testExpandToReadersWithCmisAnonymous() {
        Ace ace = createAce("cmis:anonymous", Arrays.asList("cmis:read"));
        when(content.getAcl()).thenReturn(acl);
        when(acl.getAllAces()).thenReturn(Arrays.asList(ace));

        List<String> readers = aclExpander.expandToReaders(REPO_ID, content);

        assertNotNull(readers, "Readers should not be null");
        assertTrue(readers.contains("anyone"), "Should contain 'anyone'");
    }

    @Test
    public void testExpandToReadersWithGroup() {
        Ace ace = createAce("group1", Arrays.asList("cmis:read"));
        when(content.getAcl()).thenReturn(acl);
        when(acl.getAllAces()).thenReturn(Arrays.asList(ace));
        
        when(principalService.getUserById(REPO_ID, "group1")).thenReturn(null);
        
        Group group1 = mock(Group.class);
        when(group1.getGroupId()).thenReturn("group1");
        when(group1.getUsers()).thenReturn(Arrays.asList("user1", "user2"));
        when(principalService.getGroupById(REPO_ID, "group1")).thenReturn(group1);
        
        User user1 = mock(User.class);
        User user2 = mock(User.class);
        when(principalService.getUserById(REPO_ID, "user1")).thenReturn(user1);
        when(principalService.getUserById(REPO_ID, "user2")).thenReturn(user2);

        List<String> readers = aclExpander.expandToReaders(REPO_ID, content);

        assertNotNull(readers, "Readers should not be null");
        assertTrue(readers.contains("group:group1"), "Should contain group:group1");
        assertTrue(readers.contains("user:user1"), "Should contain user:user1");
        assertTrue(readers.contains("user:user2"), "Should contain user:user2");
    }

    @Test
    public void testExpandToReadersWithNestedGroups() {
        Ace ace = createAce("parentGroup", Arrays.asList("cmis:read"));
        when(content.getAcl()).thenReturn(acl);
        when(acl.getAllAces()).thenReturn(Arrays.asList(ace));
        
        when(principalService.getUserById(REPO_ID, "parentGroup")).thenReturn(null);
        
        Group parentGroup = mock(Group.class);
        when(parentGroup.getGroupId()).thenReturn("parentGroup");
        when(parentGroup.getUsers()).thenReturn(Arrays.asList("childGroup", "user1"));
        when(principalService.getGroupById(REPO_ID, "parentGroup")).thenReturn(parentGroup);

        when(principalService.getUserById(REPO_ID, "childGroup")).thenReturn(null);

        Group childGroup = mock(Group.class);
        when(childGroup.getGroupId()).thenReturn("childGroup");
        when(childGroup.getUsers()).thenReturn(Arrays.asList("user2"));
        when(principalService.getGroupById(REPO_ID, "childGroup")).thenReturn(childGroup);
        
        User user1 = mock(User.class);
        User user2 = mock(User.class);
        when(principalService.getUserById(REPO_ID, "user1")).thenReturn(user1);
        when(principalService.getUserById(REPO_ID, "user2")).thenReturn(user2);

        List<String> readers = aclExpander.expandToReaders(REPO_ID, content);

        assertNotNull(readers, "Readers should not be null");
        assertTrue(readers.contains("group:parentGroup"), "Should contain group:parentGroup");
        assertTrue(readers.contains("group:childGroup"), "Should contain group:childGroup");
        assertTrue(readers.contains("user:user1"), "Should contain user:user1");
        assertTrue(readers.contains("user:user2"), "Should contain user:user2");
    }

    @Test
    public void testExpandToReadersWithMultipleAces() {
        Ace ace1 = createAce("user1", Arrays.asList("cmis:read"));
        Ace ace2 = createAce("user2", Arrays.asList("cmis:all"));
        Ace ace3 = createAce("user3", Arrays.asList("cmis:write")); // No read permission
        
        when(content.getAcl()).thenReturn(acl);
        when(acl.getAllAces()).thenReturn(Arrays.asList(ace1, ace2, ace3));
        
        User user1 = mock(User.class);
        User user2 = mock(User.class);
        when(principalService.getUserById(REPO_ID, "user1")).thenReturn(user1);
        when(principalService.getUserById(REPO_ID, "user2")).thenReturn(user2);

        List<String> readers = aclExpander.expandToReaders(REPO_ID, content);

        assertNotNull(readers, "Readers should not be null");
        assertTrue(readers.contains("user:user1"), "Should contain user:user1");
        assertTrue(readers.contains("user:user2"), "Should contain user:user2");
        assertFalse(readers.contains("user:user3"), "Should not contain user:user3 (no read permission)");
    }

    @Test
    public void testExpandToReadersWithNoReadPermission() {
        Ace ace = createAce("user1", Arrays.asList("cmis:write"));
        when(content.getAcl()).thenReturn(acl);
        when(acl.getAllAces()).thenReturn(Arrays.asList(ace));
        when(content.getId()).thenReturn("doc-1");
        
        // No readers found, should fall back to admins
        List<User> admins = new ArrayList<>();
        User admin = mock(User.class);
        when(admin.getUserId()).thenReturn("admin");
        admins.add(admin);
        when(principalService.getAdmins(REPO_ID)).thenReturn(admins);

        List<String> readers = aclExpander.expandToReaders(REPO_ID, content);

        assertNotNull(readers, "Readers should not be null");
        assertTrue(readers.contains("user:admin"), "Should fall back to admin users");
    }

    // ========== formatUserReader and formatGroupReader Tests ==========

    @Test
    public void testFormatUserReader() {
        String result = ACLExpander.formatUserReader("testuser");
        assertEquals("user:testuser", result);
    }

    @Test
    public void testFormatGroupReader() {
        String result = ACLExpander.formatGroupReader("testgroup");
        assertEquals("group:testgroup", result);
    }

    // ========== buildReaderFilterQuery Tests ==========

    @Test
    public void testBuildReaderFilterQueryBasic() {
        when(principalService.getGroupIdsContainingUser(REPO_ID, "user1"))
                .thenReturn(new HashSet<>());

        String query = aclExpander.buildReaderFilterQuery(REPO_ID, "user1");

        assertNotNull(query, "Query should not be null");
        assertTrue(query.startsWith("readers:("), "Query should start with readers:(");
        assertTrue(query.contains("anyone"), "Query should contain 'anyone'");
        assertTrue(query.contains("\"user:user1\""), "Query should contain quoted user");
        assertTrue(query.endsWith(")"), "Query should end with )");
    }

    @Test
    public void testBuildReaderFilterQueryWithGroups() {
        Set<String> groups = new HashSet<>();
        groups.add("group1");
        groups.add("group2");
        when(principalService.getGroupIdsContainingUser(REPO_ID, "user1"))
                .thenReturn(groups);

        String query = aclExpander.buildReaderFilterQuery(REPO_ID, "user1");

        assertNotNull(query, "Query should not be null");
        assertTrue(query.contains("anyone"), "Query should contain 'anyone'");
        assertTrue(query.contains("\"user:user1\""), "Query should contain quoted user");
        assertTrue(query.contains("\"group:group1\""), "Query should contain quoted group1");
        assertTrue(query.contains("\"group:group2\""), "Query should contain quoted group2");
    }

    @Test
    public void testBuildReaderFilterQueryWithNullGroups() {
        when(principalService.getGroupIdsContainingUser(REPO_ID, "user1"))
                .thenReturn(null);

        String query = aclExpander.buildReaderFilterQuery(REPO_ID, "user1");

        assertNotNull(query, "Query should not be null");
        assertTrue(query.contains("anyone"), "Query should contain 'anyone'");
        assertTrue(query.contains("\"user:user1\""), "Query should contain quoted user");
    }

    @Test
    public void testBuildReaderFilterQueryColonEscaping() {
        when(principalService.getGroupIdsContainingUser(REPO_ID, "user1"))
                .thenReturn(new HashSet<>());

        String query = aclExpander.buildReaderFilterQuery(REPO_ID, "user1");

        // Values with colons should be quoted to prevent Solr from interpreting them
        assertTrue(query.contains("\"user:user1\""), "User should be quoted");
    }

    // ========== Helper Methods ==========

    private Ace createAce(String principalId, List<String> permissions) {
        Ace ace = mock(Ace.class);
        when(ace.getPrincipalId()).thenReturn(principalId);
        when(ace.getPermissions()).thenReturn(permissions);
        return ace;
    }

    // ========== Edge Cases ==========

    @Test
    public void testExpandToReadersWithNullPrincipalId() {
        Ace ace = createAce(null, Arrays.asList("cmis:read"));
        when(content.getAcl()).thenReturn(acl);
        when(acl.getAllAces()).thenReturn(Arrays.asList(ace));
        when(content.getId()).thenReturn("doc-1");
        
        // Should fall back to admins
        when(principalService.getAdmins(REPO_ID)).thenReturn(new ArrayList<>());

        List<String> readers = aclExpander.expandToReaders(REPO_ID, content);

        assertNotNull(readers, "Readers should not be null");
        // Should not throw exception
    }

    @Test
    public void testExpandToReadersWithEmptyPrincipalId() {
        Ace ace = createAce("", Arrays.asList("cmis:read"));
        when(content.getAcl()).thenReturn(acl);
        when(acl.getAllAces()).thenReturn(Arrays.asList(ace));
        when(content.getId()).thenReturn("doc-1");
        
        when(principalService.getAdmins(REPO_ID)).thenReturn(new ArrayList<>());

        List<String> readers = aclExpander.expandToReaders(REPO_ID, content);

        assertNotNull(readers, "Readers should not be null");
        // Should not throw exception
    }

    @Test
    public void testExpandToReadersWithNullPermissions() {
        Ace ace = mock(Ace.class);
        when(ace.getPrincipalId()).thenReturn("user1");
        when(ace.getPermissions()).thenReturn(null);
        
        when(content.getAcl()).thenReturn(acl);
        when(acl.getAllAces()).thenReturn(Arrays.asList(ace));
        when(content.getId()).thenReturn("doc-1");
        
        when(principalService.getAdmins(REPO_ID)).thenReturn(new ArrayList<>());

        List<String> readers = aclExpander.expandToReaders(REPO_ID, content);

        assertNotNull(readers, "Readers should not be null");
        assertFalse(readers.contains("user:user1"), "User without permissions should not be a reader");
    }

    @Test
    public void testExpandToReadersWithEmptyPermissions() {
        Ace ace = createAce("user1", new ArrayList<>());
        when(content.getAcl()).thenReturn(acl);
        when(acl.getAllAces()).thenReturn(Arrays.asList(ace));
        when(content.getId()).thenReturn("doc-1");
        
        when(principalService.getAdmins(REPO_ID)).thenReturn(new ArrayList<>());

        List<String> readers = aclExpander.expandToReaders(REPO_ID, content);

        assertNotNull(readers, "Readers should not be null");
        assertFalse(readers.contains("user:user1"), "User with empty permissions should not be a reader");
    }

    @Test
    public void testExpandToReadersCaseInsensitivePermission() {
        Ace ace = createAce("user1", Arrays.asList("CMIS:READ"));
        when(content.getAcl()).thenReturn(acl);
        when(acl.getAllAces()).thenReturn(Arrays.asList(ace));
        
        User user1 = mock(User.class);
        when(principalService.getUserById(REPO_ID, "user1")).thenReturn(user1);

        List<String> readers = aclExpander.expandToReaders(REPO_ID, content);

        assertNotNull(readers, "Readers should not be null");
        assertTrue(readers.contains("user:user1"), "Permission check should be case-insensitive");
    }

    @Test
    public void testExpandToReadersCaseInsensitiveCmisAnyone() {
        Ace ace = createAce("CMIS:ANYONE", Arrays.asList("cmis:read"));
        when(content.getAcl()).thenReturn(acl);
        when(acl.getAllAces()).thenReturn(Arrays.asList(ace));

        List<String> readers = aclExpander.expandToReaders(REPO_ID, content);

        assertNotNull(readers, "Readers should not be null");
        assertTrue(readers.contains("anyone"), "cmis:anyone check should be case-insensitive");
    }
}
