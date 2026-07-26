package jp.aegif.nemaki.epoch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.acl.AclSemantics;
import jp.aegif.nemaki.acl.PrincipalLookup;
import jp.aegif.nemaki.epoch.AclEffectiveEpochService.ContentKind;
import jp.aegif.nemaki.epoch.AclEffectiveEpochService.Dependency;
import jp.aegif.nemaki.epoch.AclEffectiveEpochService.DependencyRole;
import jp.aegif.nemaki.epoch.AclEffectiveEpochService.Snapshot;
import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.util.constant.PrincipalId;

/**
 * The READERS projection of a {@link Snapshot} (design §5.3, increment 5S step 2).
 *
 * <p>Snapshots are built directly here rather than walked out of CouchDB: the walk itself already
 * has its own ITs, and what needs pinning is that the projection reads the SAME recorded chain the
 * epoch was computed from, and runs it through the SHARED semantics.
 *
 * <p>Scope note: this does not claim the projection AGREES with {@code calculateAcl} over a live
 * repository — that is the cross-implementation mutation IT of step 3, and asserting it here with
 * hand-built fixtures would only prove the fixtures match.
 */
public class AclEffectiveEpochReadersProjectionTest {

    private static final String REPO = "proj-repo";
    private static final String ROOT = "root-folder";
    private static final String ANYONE = "GROUP_EVERYONE";
    private static final String ANONYMOUS = "anonymous";

    /** Everything is a group except "u1"; nothing else resolves. */
    private static final AclSemantics.PrincipalResolver RESOLVER = new AclSemantics.PrincipalResolver() {
        @Override public PrincipalLookup lookupUser(String repositoryId, String principalId) {
            return "u1".equals(principalId) ? PrincipalLookup.FOUND : PrincipalLookup.NOT_FOUND;
        }
        @Override public PrincipalLookup lookupGroup(String repositoryId, String principalId) {
            return (ANYONE.equals(principalId) || "g1".equals(principalId))
                    ? PrincipalLookup.FOUND : PrincipalLookup.NOT_FOUND;
        }
    };

    @Test
    public void projectsSelfAndInheritedGrantsFromTheRecordedChain() {
        Snapshot snap = snapshot("leaf",
                dep("leaf", ContentKind.DOCUMENT, ROOT, null, DependencyRole.SELF, ace("u1", "cmis:read")),
                dep(ROOT, ContentKind.FOLDER, null, Boolean.FALSE, DependencyRole.ANCESTOR, ace("g1", "cmis:read")));

        List<String> readers = snap.readers(RESOLVER);

        assertTrue(readers.contains(AclSemantics.formatUserReader(REPO, "u1")), readers.toString());
        assertTrue(readers.contains(AclSemantics.formatGroupReader(REPO, "g1")),
                "the inherited grant must be projected, got " + readers);
    }

    /**
     * The regression the review asked for: an INHERITED system principal must survive the
     * projection. It only does because {@code readers()} goes through {@code resolveAcl}, whose
     * final conversion rewrites {@code CMIS_ANYONE}; {@code mergeAces} alone converts its target
     * only, and an ancestor is always the source. Dropping that conversion collapses the set to
     * admin-only — a silent stale-DENY.
     */
    @Test
    public void anInheritedSystemPrincipalIsConvertedAndNotDropped() {
        Snapshot snap = snapshot("leaf",
                dep("leaf", ContentKind.DOCUMENT, ROOT, null, DependencyRole.SELF),
                dep(ROOT, ContentKind.FOLDER, null, Boolean.FALSE, DependencyRole.ANCESTOR,
                        ace(PrincipalId.ANYONE_IN_DB, "cmis:read")));

        List<String> readers = snap.readers(RESOLVER);

        assertTrue(readers.contains(AclSemantics.formatGroupReader(REPO, ANYONE)),
                "expected the converted anyone group token, got " + readers);
        assertNotAdminOnly(readers);
    }

    @Test
    public void aNonInheritingNodeDoesNotPickUpItsAncestors() {
        Snapshot snap = snapshot("leaf",
                dep("leaf", ContentKind.DOCUMENT, ROOT, Boolean.FALSE, DependencyRole.SELF, ace("u1", "cmis:read")),
                dep(ROOT, ContentKind.FOLDER, null, Boolean.FALSE, DependencyRole.ANCESTOR, ace("g1", "cmis:read")));

        List<String> readers = snap.readers(RESOLVER);

        assertTrue(readers.contains(AclSemantics.formatUserReader(REPO, "u1")), readers.toString());
        assertFalse(readers.contains(AclSemantics.formatGroupReader(REPO, "g1")),
                "a non-inheriting node must not receive its ancestor's grant, got " + readers);
    }

    @Test
    public void aRelationshipIsTheUNIONOfItsEndpointChains() {
        Snapshot snap = snapshot("rel",
                relDep("rel", "src", "tgt"),
                dep("src", ContentKind.DOCUMENT, null, Boolean.FALSE, DependencyRole.RELATIONSHIP_SOURCE,
                        ace("u1", "cmis:read")),
                dep("tgt", ContentKind.DOCUMENT, null, Boolean.FALSE, DependencyRole.RELATIONSHIP_TARGET,
                        ace("g1", "cmis:read")));

        List<String> readers = snap.readers(RESOLVER);

        assertTrue(readers.contains(AclSemantics.formatUserReader(REPO, "u1")), readers.toString());
        assertTrue(readers.contains(AclSemantics.formatGroupReader(REPO, "g1")), readers.toString());
    }

    @Test
    public void aDanglingEndpointContributesNothingButDoesNotFail() {
        Snapshot snap = snapshot("rel",
                relDep("rel", "src", "gone"),
                dep("src", ContentKind.DOCUMENT, null, Boolean.FALSE, DependencyRole.RELATIONSHIP_SOURCE,
                        ace("u1", "cmis:read")),
                Dependency.absent("gone", DependencyRole.RELATIONSHIP_TARGET));

        List<String> readers = snap.readers(RESOLVER);

        assertEquals(List.of(AclSemantics.formatUserReader(REPO, "u1")), readers);
    }

    /**
     * {@code mergeAces} converts its target IN PLACE, so a projection that handed out the recorded
     * ACEs directly would mutate the snapshot — a record that a CAS restart re-projects.
     *
     * <p>Asserting only that two projections AGREE does not catch this: the conversion
     * ({@code CMIS_ANYONE} to the configured {@code GROUP_EVERYONE}) is idempotent at the token
     * level, so a consumed snapshot still yields the same answer. Verified by mutation — removing
     * the defensive copy left an equality-only version of this test green. The assertion therefore
     * inspects the RECORD, which is the property that actually matters.
     */
    @Test
    public void projectingDoesNotCONSUMETheSnapshot() {
        Dependency leaf = dep("leaf", ContentKind.DOCUMENT, ROOT, null, DependencyRole.SELF,
                ace(PrincipalId.ANYONE_IN_DB, "cmis:read"));
        Snapshot snap = snapshot("leaf", leaf,
                dep(ROOT, ContentKind.FOLDER, null, Boolean.FALSE, DependencyRole.ANCESTOR,
                        ace(PrincipalId.ANYONE_IN_DB, "cmis:read")));

        List<String> first = snap.readers(RESOLVER);

        assertEquals(PrincipalId.ANYONE_IN_DB, leaf.localAces.get(0).getPrincipalId(),
                "the recorded ACE was rewritten in place — the snapshot is no longer the "
                        + "authoritative record of what was read");
        assertEquals(first, snap.readers(RESOLVER), "re-projection must be stable");
    }

    /**
     * Review P3: the FALSE side of {@code emptyReadersIsAuthoritative} was unbound. An empty reader
     * set is only authoritative when BOTH endpoints are gone; one surviving endpoint, or ordinary
     * content, must not be able to claim it (the writer would then persist an invisible object).
     */
    @Test
    public void onlyARelationshipWithBOTHEndpointsGoneMayBeEmpty() {
        Snapshot bothGone = snapshot("rel", relDep("rel", "gone-a", "gone-b"),
                Dependency.absent("gone-a", DependencyRole.RELATIONSHIP_SOURCE),
                Dependency.absent("gone-b", DependencyRole.RELATIONSHIP_TARGET));
        assertTrue(bothGone.emptyReadersIsAuthoritative());

        Snapshot oneAlive = snapshot("rel", relDep("rel", "src", "gone"),
                dep("src", ContentKind.DOCUMENT, null, Boolean.FALSE, DependencyRole.RELATIONSHIP_SOURCE,
                        ace("u1", "cmis:read")),
                Dependency.absent("gone", DependencyRole.RELATIONSHIP_TARGET));
        assertFalse(oneAlive.emptyReadersIsAuthoritative(),
                "one surviving endpoint means an empty result would be a FAILED computation");

        Snapshot ordinary = snapshot("leaf",
                dep("leaf", ContentKind.DOCUMENT, null, Boolean.FALSE, DependencyRole.SELF));
        assertFalse(ordinary.emptyReadersIsAuthoritative(),
                "ordinary content can never legitimately have an empty reader set");
    }

    @Test
    public void anEmptyAclFailsClosedToAdminOnly() {
        Snapshot snap = snapshot("leaf",
                dep("leaf", ContentKind.DOCUMENT, ROOT, null, DependencyRole.SELF),
                dep(ROOT, ContentKind.FOLDER, null, Boolean.FALSE, DependencyRole.ANCESTOR));

        assertEquals(AclSemantics.adminOnlyReaders(REPO), snap.readers(RESOLVER));
    }

    @Test
    public void readersRefusesToGuessWhenTheRepositoryInfoIsNotWired() {
        Snapshot unwired = new Snapshot(REPO, "leaf",
                List.of(dep("leaf", ContentKind.DOCUMENT, null, Boolean.FALSE, DependencyRole.SELF)),
                1L, null, null, null);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> unwired.readers(RESOLVER));
        assertTrue(e.getMessage().contains("repositoryInfoMap"), e.getMessage());
    }

    // ── fixtures ───────────────────────────────────────────────────

    private static void assertNotAdminOnly(List<String> readers) {
        assertFalse(AclSemantics.adminOnlyReaders(REPO).equals(readers),
                "collapsed to ADMIN-ONLY — this is the silent stale-DENY this test exists to catch");
    }

    private static Snapshot snapshot(String objectId, Dependency... deps) {
        return new Snapshot(REPO, objectId, Arrays.asList(deps), 42L, ROOT, ANYONE, ANONYMOUS);
    }

    private static Dependency dep(String id, ContentKind kind, String parentId, Boolean aclInherited,
                                  DependencyRole role, Ace... aces) {
        return new Dependency(id, "1-x", true, 1L, null, parentId, aclInherited, null, null,
                kind, role, new ArrayList<>(Arrays.asList(aces)));
    }

    private static Dependency relDep(String id, String sourceId, String targetId) {
        return new Dependency(id, "1-x", true, 1L, null, null, Boolean.FALSE, sourceId, targetId,
                ContentKind.RELATIONSHIP, DependencyRole.SELF, new ArrayList<>());
    }

    private static Ace ace(String principalId, String... permissions) {
        return new Ace(principalId, new ArrayList<>(Arrays.asList(permissions)), true);
    }
}
