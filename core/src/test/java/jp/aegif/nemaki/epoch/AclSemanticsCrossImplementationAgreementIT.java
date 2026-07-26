package jp.aegif.nemaki.epoch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.DeleteDatabaseOptions;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.PutDatabaseOptions;
import com.ibm.cloud.cloudant.v1.model.PutDocumentOptions;
import com.ibm.cloud.sdk.core.security.BasicAuthenticator;

import jp.aegif.nemaki.acl.AclSemantics;
import jp.aegif.nemaki.acl.AclSemanticsCorpus;
import jp.aegif.nemaki.acl.PrincipalLookup;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.impl.delegate.AclServiceDelegate;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.cache.CacheService;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.cache.model.NemakiCache;
import jp.aegif.nemaki.util.constant.SystemConst;

/**
 * The CROSS-IMPLEMENTATION agreement required before the ACL-epoch writer may be wired
 * (design §5.3, increment 5S; upgraded to an IT by review P1-2).
 *
 * <p>Two implementations read the same inheritance chain and must produce the same reader tokens:
 * the CMIS runtime's — which resolves ancestors lazily through the cached content DAO — and the
 * ACL-epoch side's. Increments 3a, 3b, 4b and review P1-1 were each a case of the two quietly
 * disagreeing, and the fence is only meaningful if the value it protects is the value the runtime
 * would have computed.
 *
 * <p><b>Why this is an IT and not a unit test.</b> The first version hand-built the epoch side's
 * {@code Dependency} records from the corpus, which pinned only "given the right chain, the two
 * PROJECTIONS agree" and could not observe the WALK at all. Each corpus chain is now SEEDED into a
 * throwaway CouchDB and read back through the real
 * {@link AclEffectiveEpochService#snapshot(String, String)}.
 *
 * <p><b>What that does and does NOT bind, measured rather than assumed.</b> Routing through the real
 * walk was expected to pin review P1-1 (the walk and the projection stopping inheritance at different
 * nodes) outright. It only pins one DIRECTION of it, because the walk and the readers are not
 * symmetric:
 * <ul>
 *   <li><b>walk UNDER-collects</b> (stops earlier than the projection wants) — CAUGHT here: the
 *       projection cannot resolve a parent it needs and fails closed under strict. Verified by
 *       mutation (forcing the walk to stop immediately makes both tests below error with
 *       "Strict ACL: parent … is unreadable").</li>
 *   <li><b>walk OVER-collects</b> (climbs past a node the projection stops at, which is exactly what
 *       P1-1 was) — NOT caught here: the extra ancestors change the effective EPOCH and the recorded
 *       dependency set, neither of which this test reads. Verified by mutation: restoring the walk's
 *       old stop rule leaves all three tests green.</li>
 * </ul>
 * The over-collection direction is bound instead by
 * {@code AclEffectiveEpochServiceIT.aCorruptRootStopsTheWALKToo_notOnlyTheProjection}, which asserts
 * the epoch and the dependency set (mutation: the epoch goes 5 → 99). Stating this because "the
 * agreement IT covers P1-1" would be an overclaim, and the two tests are only complete together.
 *
 * <p><b>Scope.</b> Known principals and a stable resolver; fault injection belongs to 5T's ITs.
 */
public class AclSemanticsCrossImplementationAgreementIT {

    private static final String ANYONE = "ANYONE_CONVERTED";
    private static final String ANONYMOUS = "ANONYMOUS_CONVERTED";

    /**
     * Corpus cases that CANNOT be compared, each for a stated reason. A case not listed here MUST
     * compare successfully — the earlier `catch (RuntimeException) { continue; }` would have let
     * eight silent skips still report green (review P2-3).
     */
    private static final Set<String> NOT_COMPARABLE = Set.of(
            // Strict mode: the CMIS side throws IllegalStateException on the unreadable parent, the
            // epoch side refuses even earlier (AclEpochUnavailableException at walk time). Both fail
            // closed; there is no reader set to compare.
            "inheriting-node-with-UNRESOLVABLE-parent");

    private static Cloudant cloudant;
    private static boolean available;

    private String contentDb;
    private AclEffectiveEpochService epochService;

    @BeforeAll
    static void connect() {
        String url = cfg("nemaki.test.couchdb.url", "NEMAKI_TEST_COUCHDB_URL", "http://localhost:5984");
        String user = cfg("nemaki.test.couchdb.user", "NEMAKI_TEST_COUCHDB_USER", "admin");
        String pass = cfg("nemaki.test.couchdb.password", "NEMAKI_TEST_COUCHDB_PASSWORD", "password");
        try {
            BasicAuthenticator auth = new BasicAuthenticator.Builder().username(user).password(pass).build();
            cloudant = new Cloudant("cloudant-service", auth);
            cloudant.setServiceUrl(url);
            cloudant.getDatabaseInformation(
                    new com.ibm.cloud.cloudant.v1.model.GetDatabaseInformationOptions.Builder()
                            .db(SystemConst.NEMAKI_CONF_DB).build()).execute();
            available = true;
        } catch (Exception e) {
            available = false;
        }
        if (!available && Boolean.parseBoolean(
                cfg("nemaki.test.couchdb.required", "NEMAKI_TEST_COUCHDB_REQUIRED", "false"))) {
            throw new IllegalStateException("nemaki.test.couchdb.required=true but CouchDB is not "
                    + "reachable — the cross-implementation agreement IT cannot run");
        }
    }

    @BeforeEach
    void setUp() {
        assumeTrue(available, "CouchDB not reachable — skipping the agreement IT");
        contentDb = "acl-agreement-it-" + UUID.randomUUID();
        cloudant.putDatabase(new PutDatabaseOptions.Builder().db(contentDb).build()).execute();

        CloudantClientWrapper wrapper = new CloudantClientWrapper(cloudant, contentDb, new ObjectMapper());
        CloudantClientPool pool = mock(CloudantClientPool.class);
        lenient().when(pool.getClient(contentDb)).thenReturn(wrapper);

        epochService = new AclEffectiveEpochService();
        epochService.setConnectorPool(pool);
        epochService.setRepositoryInfoMap(repositoryInfoMap());
    }

    @AfterEach
    void tearDown() {
        if (!available) return;
        try {
            cloudant.deleteDatabase(new DeleteDatabaseOptions.Builder().db(contentDb).build()).execute();
        } catch (Exception ignore) { /* best effort */ }
    }

    @Test
    void everyCorpusChainAgreesFromTheREALWalkThroughToTheProjection() {
        int compared = 0;
        int skipped = 0;
        for (AclSemanticsCorpus.Case c : AclSemanticsCorpus.cases()) {
            if (NOT_COMPARABLE.contains(c.name)) {
                skipped++;
                continue;
            }
            seed(c);
            List<String> viaCmis = cmisReaders(c);
            List<String> viaEpoch = epochReaders(c);   // seed -> real snapshot() -> project
            assertEquals(viaCmis, viaEpoch, "the CMIS runtime and the ACL-epoch side disagree for "
                    + "corpus case '" + c.name + "' — the fence would protect a value the runtime "
                    + "would never have computed");
            compared++;
            wipe();
        }
        assertEquals(AclSemanticsCorpus.cases().size(), compared + skipped,
                "every corpus case must be either compared or explicitly exempted");
        assertEquals(NOT_COMPARABLE.size(), skipped, "the skip list must be exact");
    }

    /**
     * Every exemption must be NECESSARY. Asserting only that "compared + skipped == total" is
     * tautological — adding a case to the skip list decreases both sides — so an exemption could be
     * added silently to make a real disagreement disappear. Each exempted case is therefore required
     * to actually FAIL to compare (review P2-3, second pass).
     */
    @Test
    void everyExemptionIsNecessary() {
        for (String name : NOT_COMPARABLE) {
            AclSemanticsCorpus.Case c = caseNamed(name);   // also proves the name is not stale
            seed(c);

            // Review P2-1: requiring only "something threw" let a ONE-SIDED failure justify the
            // exemption — precisely the "CMIS serves it, the epoch side calls it an anomaly" shape
            // that §5.1 turns into a subtree stall. BOTH sides must fail, and with the expected type,
            // so a transient CouchDB fault cannot certify an exemption either.
            RuntimeException cmisFailure = captureFailure(() -> cmisReaders(c));
            RuntimeException epochFailure = captureFailure(() -> epochReaders(c));

            assertNotNull(cmisFailure, "corpus case '" + name + "' is exempted but the CMIS side "
                    + "computes it fine — an exemption that only the epoch side needs is a real "
                    + "disagreement, not a shared limitation");
            assertNotNull(epochFailure, "corpus case '" + name + "' is exempted but the epoch side "
                    + "computes it fine");
            assertTrue(cmisFailure instanceof IllegalStateException,
                    "expected the CMIS strict-mode failure, got " + cmisFailure);
            assertTrue(epochFailure instanceof AclEffectiveEpochService.AclEpochUnavailableException,
                    "expected the epoch walk's retryable failure, got " + epochFailure);
            wipe();
        }
    }

    /**
     * Review P1-2: a root stored with the LEGACY discriminator {@code {"type":"folder"}}.
     *
     * <p>The corpus cannot express this — its nodes go through the {@code Folder} model, whose
     * constructor forces {@code cmis:folder} — yet the DAO (and therefore {@code resolveKind})
     * accepts the short form, so it is legal persisted data. {@code NodeBase.isFolder()} does NOT
     * accept it, so if the epoch side identified the root by its DAO-compatible {@code kind} the two
     * would disagree about what the root IS: the CMIS side would keep climbing while the epoch walk
     * stopped — P1-1 with the sides swapped.
     *
     * <p>Seeded raw, and the CMIS side is rebuilt from the same documents, so both see the legacy
     * spelling.
     */
    @Test
    void aLegacyTypeRootIsTreatedTheSameByBOTHSides() {
        seedRaw("grandparent", "{\"type\":\"cmis:folder\",\"aclSourceEpoch\":9,"
                + "\"acl\":{\"entries\":[{\"principal\":\"must-not-appear\",\"permissions\":[\"cmis:read\"]}]}}");
        // The ROOT, stored with the legacy short form AND corrupted (a parent + inheriting).
        seedRaw(AclSemanticsCorpus.ROOT_ID, "{\"type\":\"folder\",\"parentId\":\"grandparent\","
                + "\"aclInherited\":true,\"aclSourceEpoch\":5,"
                + "\"acl\":{\"entries\":[{\"principal\":\"u2\",\"permissions\":[\"cmis:read\"]}]}}");
        seedRaw("leaf", "{\"type\":\"cmis:document\",\"parentId\":\"" + AclSemanticsCorpus.ROOT_ID
                + "\",\"aclInherited\":true,\"aclSourceEpoch\":1,"
                + "\"acl\":{\"entries\":[{\"principal\":\"u1\",\"permissions\":[\"cmis:read\"]}]}}");

        List<String> viaCmis = cmisReadersOf("leaf", List.of("leaf", AclSemanticsCorpus.ROOT_ID, "grandparent"));
        List<String> viaEpoch = epochService.snapshot(contentDb, "leaf").readers(resolver());

        assertEquals(viaCmis, viaEpoch, "a legacy-typed root must mean the same thing to both sides");
        assertTrue(viaCmis.contains(AclSemantics.formatUserReader(contentDb, "must-not-appear")),
                "NodeBase.isFolder() rejects the legacy spelling, so this node is NOT the root to "
                        + "either side and the grandparent's grant is genuinely inherited: " + viaCmis);
    }

    /**
     * Proves the agreement is load-bearing: a forked merge — folded ROOT-FIRST, so the further node
     * wins instead of the nearer one — makes the two disagree.
     *
     * <p>The chain matters. A first attempt used a case where both depths grant read: the merge
     * direction is INVISIBLE to the token layer there, because whichever ACE wins the principal still
     * has read. It becomes observable only when the nearer node grants a non-read permission that
     * the ancestor's read would otherwise restore.
     */
    @Test
    void forkingTheSharedMergeMakesThemDISAGREE() {
        AclSemanticsCorpus.Case c = caseNamed("nearer-node-REVOKES-read-that-the-ancestor-grants");
        seed(c);

        List<String> viaCmis = cmisReaders(c);
        List<String> forked = AclSemantics.readerTokens(contentDb, forkedEffectiveAces(c), resolver());

        assertNotEquals(viaCmis, forked, "if a forked merge still agrees, this chain no longer "
                + "distinguishes the merge direction and the agreement test proves nothing");
        assertEquals(epochReaders(c), viaCmis, "the UNFORKED epoch projection still agrees");
    }

    // ── side A: the CMIS runtime (the real delegate over the same chain) ──

    private List<String> cmisReaders(AclSemanticsCorpus.Case c) {
        List<String> ids = new ArrayList<>();
        for (AclSemanticsCorpus.Node n : c.chain) {
            ids.add(n.id);
        }
        return cmisReadersOf(c.leaf().id, ids);
    }

    /**
     * The CMIS runtime's readers for a seeded chain.
     *
     * <p>Review P2-4: this used to build its {@code Content} objects from the in-memory corpus, so
     * {@code CouchAcl} and {@code parseLocalAces} were never compared — which is exactly the
     * comparison P2-5 needed. Both sides now start from the SEEDED documents; this one goes through
     * the real {@code CouchAcl.convertToNemakiAcl}, the epoch side through {@code parseLocalAces}.
     */
    private List<String> cmisReadersOf(String leafId, List<String> chainIds) {
        Map<String, Content> byId = new LinkedHashMap<>();
        for (String id : chainIds) {
            byId.put(id, readBackOne(id));
        }

        ContentService contentService = mock(ContentService.class);
        ContentDaoService contentDaoService = mock(ContentDaoService.class);
        NemakiCachePool cachePool = mock(NemakiCachePool.class);
        PropertyManager propertyManager = mock(PropertyManager.class);

        @SuppressWarnings("unchecked")
        NemakiCache<Acl> aclCache = mock(NemakiCache.class);
        CacheService caches = mock(CacheService.class);
        lenient().when(cachePool.get(anyString())).thenReturn(caches);
        lenient().when(caches.getAclCache()).thenReturn(aclCache);
        lenient().when(aclCache.get(anyString())).thenReturn(null); // a COLD cache: always compute
        lenient().when(propertyManager.readBoolean(any())).thenReturn(false);

        lenient().when(contentService.isRoot(anyString(), any(Content.class))).thenAnswer(inv -> {
            Content ct = inv.getArgument(1);
            return ct != null && Boolean.TRUE.equals(ct.isFolder())
                    && AclSemanticsCorpus.ROOT_ID.equals(ct.getId());
        });
        lenient().when(contentService.isTopLevel(anyString(), any(Content.class))).thenAnswer(inv -> {
            Content ct = inv.getArgument(1);
            return ct != null && AclSemanticsCorpus.ROOT_ID.equals(ct.getParentId());
        });
        lenient().when(contentService.getFolder(anyString(), anyString())).thenAnswer(inv -> {
            Content ct = byId.get(inv.<String>getArgument(1));
            if (ct == null) return null;
            return (ct instanceof Folder) ? (Folder) ct : new Folder(ct);
        });

        AclServiceDelegate delegate = new AclServiceDelegate(contentService, contentDaoService,
                cachePool, repositoryInfoMap(), propertyManager);
        Acl acl = delegate.calculateAcl(contentDb, byId.get(leafId), true);
        return AclSemantics.readerTokens(contentDb, acl.getAllAces(), resolver());
    }


    // ── side B: seed → the REAL walk → project ─────────────────────

    private List<String> epochReaders(AclSemanticsCorpus.Case c) {
        AclEffectiveEpochService.Snapshot snap = epochService.snapshot(contentDb, c.leaf().id);
        assertNotNull(snap, "the seeded leaf must exist: " + c.leaf().id);
        return snap.readers(resolver());
    }

    /** PUT an EXACT raw JSON body, so legacy / malformed shapes survive the model layer. */
    private void seedRaw(String id, String json) {
        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(cfg("nemaki.test.couchdb.url", "NEMAKI_TEST_COUCHDB_URL",
                            "http://localhost:5984") + "/" + contentDb + "/" + id))
                    .header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(
                            (cfg("nemaki.test.couchdb.user", "NEMAKI_TEST_COUCHDB_USER", "admin") + ":"
                                    + cfg("nemaki.test.couchdb.password", "NEMAKI_TEST_COUCHDB_PASSWORD",
                                    "password")).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .header("Content-Type", "application/json")
                    .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                    .build();
            java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newHttpClient()
                    .send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertTrue(resp.statusCode() < 300, "seedRaw " + id + " failed: " + resp.body());
        } catch (Exception e) {
            throw new IllegalStateException("seedRaw failed for " + id, e);
        }
    }

    private static RuntimeException captureFailure(Runnable r) {
        try {
            r.run();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    /**
     * Read the seeded documents back and rebuild them as the CMIS layer would — in particular running
     * the persisted ACL through the REAL {@code CouchAcl.convertToNemakiAcl}, so its coercion rules
     * are compared against {@code parseLocalAces} rather than assumed to match (review P2-4/P2-5).
     */
    /** Rebuild ONE seeded document as the CMIS layer would, via the real {@code CouchAcl}. */
    @SuppressWarnings("unchecked")
    private Content readBackOne(String id) {
        Document raw = cloudant.getDocument(new com.ibm.cloud.cloudant.v1.model.GetDocumentOptions
                .Builder().db(contentDb).docId(id).build()).execute().getResult();
        Map<String, Object> p = raw.getProperties();

        Content ct = "cmis:folder".equals(p.get("type"))
                ? new jp.aegif.nemaki.model.Folder() : new jp.aegif.nemaki.model.Document();
        ct.setId(raw.getId());
        ct.setType((String) p.get("type"));
        ct.setName((String) p.get("name"));
        ct.setParentId((String) p.get("parentId"));
        ct.setAclInherited((Boolean) p.get("aclInherited"));

        jp.aegif.nemaki.model.couch.CouchAcl couchAcl = new jp.aegif.nemaki.model.couch.CouchAcl();
        Map<String, Object> aclMap = (Map<String, Object>) p.get("acl");
        org.json.simple.JSONArray entries = new org.json.simple.JSONArray();
        if (aclMap != null && aclMap.get("entries") instanceof List) {
            entries.addAll((List<Object>) aclMap.get("entries"));
        }
        couchAcl.setEntries(entries);
        ct.setAcl(couchAcl.convertToNemakiAcl());
        return ct;
    }

    /** Write the corpus chain into CouchDB in the real persisted shape. */
    private void seed(AclSemanticsCorpus.Case c) {
        for (AclSemanticsCorpus.Node n : c.chain) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", n.folder ? "cmis:folder" : "cmis:document");
            p.put("name", n.id);
            if (n.parentId != null) p.put("parentId", n.parentId);
            if (n.aclInherited != null) p.put("aclInherited", n.aclInherited);
            p.put("aclSourceEpoch", 1L);
            if (!n.localAces.isEmpty()) {
                List<Map<String, Object>> entries = new ArrayList<>();
                for (Ace a : n.localAces) {
                    entries.add(Map.of("principal", a.getPrincipalId(),
                            "permissions", a.getPermissions() == null
                                    ? List.of() : new ArrayList<>(a.getPermissions())));
                }
                p.put("acl", Map.of("entries", entries));
            }
            Document d = new Document();
            d.setId(n.id);
            d.setProperties(p);
            cloudant.putDocument(new PutDocumentOptions.Builder()
                    .db(contentDb).docId(n.id).document(d).build()).execute();
        }
    }

    /** Drop the database and recreate it, so each corpus case is seeded into a clean chain. */
    private void wipe() {
        cloudant.deleteDatabase(new DeleteDatabaseOptions.Builder().db(contentDb).build()).execute();
        cloudant.putDatabase(new PutDatabaseOptions.Builder().db(contentDb).build()).execute();
    }

    // ── the fork ───────────────────────────────────────────────────

    private List<Ace> forkedEffectiveAces(AclSemanticsCorpus.Case c) {
        Map<String, List<Ace>> byId = new LinkedHashMap<>();
        for (AclSemanticsCorpus.Node n : c.chain) {
            byId.put(n.id, copyOf(n.localAces));
        }
        // mergeAces always lets its TARGET win, so folding the chain from the ROOT outwards makes the
        // FURTHER node win — the exact inversion of the real rule. (Folding leaf-first is not a fork
        // at all: the leaf ends up as the target on every step and still wins.)
        List<Ace> acc = new ArrayList<>();
        List<AclSemanticsCorpus.Node> rootFirst = new ArrayList<>(c.chain);
        Collections.reverse(rootFirst);
        for (AclSemanticsCorpus.Node n : rootFirst) {
            acc = AclSemantics.mergeAces(acc, byId.get(n.id), ANYONE, ANONYMOUS);
        }
        AclSemantics.convertSystemPrincipalIds(acc, ANYONE, ANONYMOUS);
        return acc;
    }

    // ── shared fixtures ────────────────────────────────────────────

    /** Resolves every corpus principal as a USER; the converted anyone id as a GROUP. */
    private AclSemantics.PrincipalResolver resolver() {
        return new AclSemantics.PrincipalResolver() {
            @Override public PrincipalLookup lookupUser(String repositoryId, String principalId) {
                return ANYONE.equals(principalId) ? PrincipalLookup.NOT_FOUND : PrincipalLookup.FOUND;
            }
            @Override public PrincipalLookup lookupGroup(String repositoryId, String principalId) {
                return ANYONE.equals(principalId) ? PrincipalLookup.FOUND : PrincipalLookup.NOT_FOUND;
            }
        };
    }

    private RepositoryInfoMap repositoryInfoMap() {
        RepositoryInfo info = mock(RepositoryInfo.class);
        lenient().when(info.getRootFolderId()).thenReturn(AclSemanticsCorpus.ROOT_ID);
        lenient().when(info.getPrincipalIdAnyone()).thenReturn(ANYONE);
        lenient().when(info.getPrincipalIdAnonymous()).thenReturn(ANONYMOUS);
        RepositoryInfoMap map = mock(RepositoryInfoMap.class);
        lenient().when(map.get(anyString())).thenReturn(info);
        return map;
    }

    private static List<Ace> copyOf(List<Ace> aces) {
        List<Ace> out = new ArrayList<>(aces.size());
        for (Ace a : aces) {
            out.add(AclSemantics.deepCopy(a));
        }
        return out;
    }

    private static AclSemanticsCorpus.Case caseNamed(String name) {
        for (AclSemanticsCorpus.Case c : AclSemanticsCorpus.cases()) {
            if (c.name.equals(name)) return c;
        }
        throw new IllegalStateException("corpus case not found: " + name
                + " — this test is pinned to a specific chain shape");
    }

    private static String cfg(String sysProp, String envVar, String dflt) {
        String v = System.getProperty(sysProp);
        if (v == null || v.isBlank()) v = System.getenv(envVar);
        return (v == null || v.isBlank()) ? dflt : v;
    }
}
