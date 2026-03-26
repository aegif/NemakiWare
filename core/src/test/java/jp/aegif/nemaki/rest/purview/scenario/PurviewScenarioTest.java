package jp.aegif.nemaki.rest.purview.scenario;

import jp.aegif.nemaki.rest.purview.PurviewConfig;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.rest.purview.payload.PurviewSchemaManifest;
import jp.aegif.nemaki.rest.purview.payload.PurviewSchemaManifestFactory;
import jp.aegif.nemaki.rest.purview.payload.PurviewSchemaPayloadFactory;
import jp.aegif.nemaki.rest.purview.client.PurviewApiClient;
import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import jp.aegif.nemaki.rest.purview.client.PurviewProbeResult;
import jp.aegif.nemaki.rest.purview.client.PurviewSchemaRegistryClient;
import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.client.PurviewSchemaPublishResult;
import jp.aegif.nemaki.rest.purview.governance.PurviewGovernanceServiceImpl;
import jp.aegif.nemaki.rest.purview.governance.PurviewGovernanceView;
import jp.aegif.nemaki.rest.purview.lineage.PurviewArchiveLineageService;
import jp.aegif.nemaki.rest.purview.lineage.PurviewCloudSyncLineageService;
import jp.aegif.nemaki.rest.purview.publish.PurviewArchivePublishService;
import jp.aegif.nemaki.rest.purview.publish.PurviewCloudMetadataPublishService;
import jp.aegif.nemaki.rest.purview.publish.PurviewDocumentPublishService;
import jp.aegif.nemaki.rest.purview.publish.PurviewTypeDefinitionPublishService;
import jp.aegif.nemaki.rest.purview.relationship.PurviewContainmentRelationshipService;
import jp.aegif.nemaki.rest.purview.relationship.PurviewDocumentArchiveRelationshipService;
import jp.aegif.nemaki.rest.purview.relationship.PurviewDocumentTypeRelationshipService;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaApplyResult;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaApplyServiceImpl;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaBootstrapResult;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaBootstrapServiceImpl;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaDiff;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaPlannerService;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaPlannerServiceImpl;
import jp.aegif.nemaki.rest.purview.state.PurviewCursorStateService;
import jp.aegif.nemaki.rest.purview.state.PurviewDeadLetterStateService;
import jp.aegif.nemaki.rest.purview.state.PurviewJobState;
import jp.aegif.nemaki.rest.purview.state.PurviewJobStateService;
import jp.aegif.nemaki.rest.purview.state.PurviewLockStateService;
import jp.aegif.nemaki.rest.purview.state.PurviewSchemaState;
import jp.aegif.nemaki.rest.purview.state.PurviewSchemaStateService;
import jp.aegif.nemaki.rest.purview.PurviewConnectionServiceImpl;
import jp.aegif.nemaki.rest.purview.PurviewConnectionStatus;
import jp.aegif.nemaki.rest.purview.sync.PurviewFullSyncServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Relationship;

/**
 * Purview 連携のシナリオテスト。
 *
 * 各シナリオは管理者の操作フロー（初期設定 → スキーマ適用 → 全同期 → ガバナンス参照）
 * を再現し、サービス間の連携が正しく機能することを検証する。
 */
public class PurviewScenarioTest {

    // ─── 共通モック ────────────────────────────────────────
    private PurviewConfig config;
    private PurviewApiClient apiClient;
    private PurviewSchemaRegistryClient schemaRegistryClient;
    private PurviewEntityRegistryClient entityRegistryClient;
    private PurviewSchemaStateService schemaStateService;
    private PurviewJobStateService jobStateService;
    private PurviewLockStateService lockStateService;
    private PurviewCursorStateService cursorStateService;
    private PurviewDocumentPublishService documentPublishService;
    private PurviewArchivePublishService archivePublishService;
    private PurviewCloudMetadataPublishService cloudMetadataPublishService;
    private PurviewContainmentRelationshipService containmentRelationshipService;
    private PurviewTypeDefinitionPublishService typeDefinitionPublishService;
    private ContentDaoService contentDaoService;
    private ContentService contentService;
    private ExceptionService exceptionService;

    @BeforeEach
    public void setUp() throws Exception {
        config = mock(PurviewConfig.class);
        apiClient = mock(PurviewApiClient.class);
        schemaRegistryClient = mock(PurviewSchemaRegistryClient.class);
        entityRegistryClient = mock(PurviewEntityRegistryClient.class);
        schemaStateService = mock(PurviewSchemaStateService.class);
        jobStateService = mock(PurviewJobStateService.class);
        lockStateService = mock(PurviewLockStateService.class);
        cursorStateService = mock(PurviewCursorStateService.class);
        documentPublishService = mock(PurviewDocumentPublishService.class);
        archivePublishService = mock(PurviewArchivePublishService.class);
        cloudMetadataPublishService = mock(PurviewCloudMetadataPublishService.class);
        containmentRelationshipService = mock(PurviewContainmentRelationshipService.class);
        typeDefinitionPublishService = mock(PurviewTypeDefinitionPublishService.class);
        contentDaoService = mock(ContentDaoService.class);
        contentService = mock(ContentService.class);
        exceptionService = mock(ExceptionService.class);

        when(config.isEnabled()).thenReturn(true);
        when(config.getEndpoint()).thenReturn("https://my-purview.purview.azure.com");
        when(config.getAtlasBasePath()).thenReturn("datamap/api/atlas/v2");
        when(config.getTenantId()).thenReturn("tenant-001");
        when(config.getClientId()).thenReturn("client-001");
        when(config.getClientSecret()).thenReturn("secret-001");
        when(config.getCollection()).thenReturn("NemakiWare");
        when(config.getConnectTimeoutMs()).thenReturn(5000);
        when(config.getReadTimeoutMs()).thenReturn(30000);

        when(jobStateService.saveJobState(any())).thenAnswer(inv -> inv.getArgument(0));
        when(cursorStateService.saveCursorState(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lockStateService.tryAcquireRepositoryLock(any(), any(), any(), any())).thenReturn(true);
    }

    // =====================================================================
    //  シナリオ 1: 初期セットアップ (接続確認 → スキーマ適用 → 全同期)
    // =====================================================================

    @Nested
    @DisplayName("シナリオ1: 管理者が初めてPurviewを設定し、全データを同期する")
    class InitialSetupAndFullSync {

        @Test
        @DisplayName("接続テスト成功 → スキーマ適用 → 全同期完了の一連フローが成功する")
        public void testCompleteInitialSetupFlow() throws Exception {
            // ── Step 1: 接続テスト ──
            PurviewConnectionServiceImpl connectionService = new PurviewConnectionServiceImpl(config, apiClient);
            when(apiClient.probeConnection(any()))
                    .thenReturn(PurviewProbeResult.success(200, "OK"));

            PurviewConnectionStatus connectionStatus = connectionService.testConnection();
            assertTrue(connectionStatus.isConnected(), "Purviewへの接続が成功すること");

            // ── Step 2: スキーマ適用 ──
            PurviewSchemaManifestFactory manifestFactory = new PurviewSchemaManifestFactory();
            PurviewSchemaPlannerServiceImpl plannerService = new PurviewSchemaPlannerServiceImpl(
                    config, schemaStateService, manifestFactory);
            when(schemaStateService.getSchemaState("NemakiWare"))
                    .thenReturn(new PurviewSchemaState("NemakiWare", "", "", "", "", ""));

            PurviewSchemaDiff diff = plannerService.getSchemaDiff();
            assertTrue(diff.isApplyRequired(), "初回はスキーマ適用が必要");
            assertFalse(diff.getCustomTypeNames().isEmpty(), "カスタム型が定義されていること");
            assertTrue(diff.getCustomTypeNames().contains("nemaki_document"), "nemaki_document型が含まれること");

            PurviewSchemaApplyServiceImpl applyService = new PurviewSchemaApplyServiceImpl(
                    config, plannerService, schemaStateService,
                    new PurviewSchemaManifestFactory(), new PurviewSchemaPayloadFactory(),
                    schemaRegistryClient,
                    new jp.aegif.nemaki.rest.purview.schema.AtlasSchemaCompatHelper());
            when(schemaRegistryClient.applySchema(any(), any()))
                    .thenReturn(PurviewSchemaPublishResult.success("applied"));

            PurviewSchemaBootstrapServiceImpl bootstrapService = new PurviewSchemaBootstrapServiceImpl(
                    config, applyService, jobStateService, lockStateService);
            PurviewSchemaBootstrapResult bootstrapResult = bootstrapService.startTypeBootstrap("admin");

            assertEquals("COMPLETED", bootstrapResult.getJobState().getStatus(), "スキーマ適用ジョブが完了すること");
            assertTrue(bootstrapResult.getApplyResult().isApplied(), "スキーマが実際に適用されたこと");
            verify(schemaRegistryClient).applySchema(any(), any());

            // ── Step 3: 全同期 ──
            // スキーマ適用後はSchemaDiffが最新になった想定
            PurviewSchemaPlannerService schemaPlannerService = mock(PurviewSchemaPlannerService.class);
            when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                    "NemakiWare", "1", "hash-001", "1", "hash-001", false,
                    List.of(), List.of(), List.of()));
            when(documentPublishService.publishRepositoryHierarchy("bedroom")).thenReturn(10);
            when(archivePublishService.publishRepositoryArchives("bedroom")).thenReturn(3);
            when(cloudMetadataPublishService.publishRepositoryCloudSyncLineage("bedroom")).thenReturn(2);
            when(typeDefinitionPublishService.publishRepositoryTypeDefinitions("bedroom")).thenReturn(5);
            when(archivePublishService.buildRepositoryArchiveSnapshot(any())).thenReturn("");
            when(cloudMetadataPublishService.buildRepositoryCloudMetadataSnapshot(any())).thenReturn("");
            when(containmentRelationshipService.buildRepositoryContainmentSnapshot(any())).thenReturn("");
            when(typeDefinitionPublishService.buildRepositoryTypeDefinitionSnapshot(any())).thenReturn("");
            when(contentDaoService.getLatestChange("bedroom")).thenReturn(createChange("500"));

            PurviewFullSyncServiceImpl fullSyncService = new PurviewFullSyncServiceImpl(
                    schemaPlannerService, jobStateService, lockStateService, cursorStateService,
                    documentPublishService, archivePublishService, cloudMetadataPublishService,
                    containmentRelationshipService, typeDefinitionPublishService, contentDaoService);

            PurviewJobState syncResult = fullSyncService.startFullSync("bedroom", "admin");

            assertEquals("COMPLETED", syncResult.getStatus(), "全同期ジョブが完了すること");
            assertEquals(20, syncResult.getProcessedCount(), "ドキュメント10+アーカイブ3+クラウド2+型定義5 = 20件処理");
            assertEquals("500", syncResult.getCheckpoint(), "最新変更トークンがチェックポイントに設定されること");
            verify(cursorStateService, times(5)).saveCursorState(any());
        }

        @Test
        @DisplayName("接続テスト失敗時は、スキーマ適用に進まずエラーを返す")
        public void testConnectionFailureBlocksEntireSetup() throws Exception {
            PurviewConnectionServiceImpl connectionService = new PurviewConnectionServiceImpl(config, apiClient);
            when(apiClient.probeConnection(any()))
                    .thenThrow(new PurviewClientException("ネットワーク到達不可"));

            PurviewConnectionStatus connectionStatus = connectionService.testConnection();

            assertFalse(connectionStatus.isConnected(), "接続が失敗すること");
            assertTrue(connectionStatus.getMessage().contains("ネットワーク到達不可"),
                    "エラーメッセージにネットワークエラーの内容が含まれること");
        }
    }

    // =====================================================================
    //  シナリオ 2: スキーマ未適用のまま同期しようとした場合
    // =====================================================================

    @Nested
    @DisplayName("シナリオ2: スキーマを適用せずに全同期を実行した場合")
    class SyncWithoutSchemaBootstrap {

        @Test
        @DisplayName("全同期がスキーマ未適用エラーで即座に失敗する")
        public void testFullSyncFailsWhenSchemaNotApplied() {
            PurviewSchemaPlannerService schemaPlannerService = mock(PurviewSchemaPlannerService.class);
            when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                    "NemakiWare", "", "", "1", "desired-hash", true,
                    List.of("nemaki_document"), List.of("nemaki_folder_contains_document"), List.of("nemakiGovernance")));
            when(documentPublishService.publishRepositoryHierarchy(any())).thenReturn(0);
            when(archivePublishService.publishRepositoryArchives(any())).thenReturn(0);
            when(archivePublishService.buildRepositoryArchiveSnapshot(any())).thenReturn("");
            when(cloudMetadataPublishService.publishRepositoryCloudSyncLineage(any())).thenReturn(0);
            when(cloudMetadataPublishService.buildRepositoryCloudMetadataSnapshot(any())).thenReturn("");
            when(containmentRelationshipService.buildRepositoryContainmentSnapshot(any())).thenReturn("");
            when(typeDefinitionPublishService.publishRepositoryTypeDefinitions(any())).thenReturn(0);
            when(typeDefinitionPublishService.buildRepositoryTypeDefinitionSnapshot(any())).thenReturn("");

            PurviewFullSyncServiceImpl fullSyncService = new PurviewFullSyncServiceImpl(
                    schemaPlannerService, jobStateService, lockStateService, cursorStateService,
                    documentPublishService, archivePublishService, cloudMetadataPublishService,
                    containmentRelationshipService, typeDefinitionPublishService, contentDaoService);

            PurviewJobState result = fullSyncService.startFullSync("bedroom", "admin");

            assertEquals("FAILED", result.getStatus(), "スキーマ未適用のため失敗すること");
            assertTrue(result.getErrorSummary().contains("schema bootstrap"),
                    "エラーメッセージがスキーマ適用の必要性を示すこと");
            verify(documentPublishService, never()).publishRepositoryHierarchy(any());
            verify(archivePublishService, never()).publishRepositoryArchives(any());
        }
    }

    // =====================================================================
    //  シナリオ 3: 同期済みドキュメントのガバナンス情報を参照する
    // =====================================================================

    @Nested
    @DisplayName("シナリオ3: ユーザーがドキュメントのガバナンス情報（分類・用語集・ラベル）を確認する")
    class ViewGovernanceMetadata {

        private PurviewGovernanceServiceImpl governanceService;

        @BeforeEach
        public void setUpGovernanceService() {
            governanceService = new PurviewGovernanceServiceImpl(
                    config, contentService, exceptionService,
                    entityRegistryClient, new PurviewEntityPayloadFactory());
        }

        @Test
        @DisplayName("同期済みドキュメントの分類・用語集・ラベル・ビジネスメタデータが全て取得できる")
        public void testGovernanceMetadataFullyVisibleForSyncedDocument() throws Exception {
            Document document = new Document();
            document.setId("contract-001");
            document.setName("年次契約書.pdf");
            document.setObjectType("D:legal:contract");
            when(contentService.getContent("bedroom", "contract-001")).thenReturn(document);
            when(entityRegistryClient.getEntityByUniqueAttribute(any(), any(), any(), any()))
                    .thenReturn(Map.of("entity", Map.of(
                            "typeName", "nemaki_document",
                            "classifications", List.of(
                                    Map.of("typeName", "Confidential", "entityStatus", "ACTIVE"),
                                    Map.of("typeName", "PersonalData", "entityStatus", "ACTIVE")),
                            "meanings", List.of(
                                    Map.of("displayText", "法務契約", "termGuid", "term-100",
                                            "relationGuid", "rel-100", "status", "VALIDATED")),
                            "labels", List.of("legal", "annual", "2026"),
                            "businessAttributes", Map.of(
                                    "nemakiGovernance", Map.of(
                                            "ownerDepartment", "法務部",
                                            "retentionPolicy", "10y",
                                            "dataClassification", "機密")))));

            PurviewGovernanceView view = governanceService.getGovernance(
                    "bedroom", "contract-001", mock(CallContext.class));

            assertTrue(view.isEntityFound(), "Purview上でエンティティが見つかること");
            assertEquals(2, view.getClassifications().size(), "分類が2件取得できること");
            assertEquals("Confidential", view.getClassifications().get(0).get("typeName"));
            assertEquals("PersonalData", view.getClassifications().get(1).get("typeName"));
            assertEquals(1, view.getGlossaryTerms().size(), "用語集が1件取得できること");
            assertEquals("法務契約", view.getGlossaryTerms().get(0).get("displayText"));
            assertEquals(List.of("legal", "annual", "2026"), view.getLabels());
            assertEquals("法務部", view.getBusinessMetadata().get("nemakiGovernance").get("ownerDepartment"));
            assertEquals("10y", view.getBusinessMetadata().get("nemakiGovernance").get("retentionPolicy"));
        }

        @Test
        @DisplayName("未同期のドキュメントは「まだ同期されていません」というメッセージで表示される")
        public void testGovernanceShowsNotSyncedForUnsyncedDocument() throws Exception {
            Document document = new Document();
            document.setId("draft-001");
            document.setName("ドラフト企画書.docx");
            document.setObjectType("cmis:document");
            when(contentService.getContent("bedroom", "draft-001")).thenReturn(document);
            when(entityRegistryClient.getEntityByUniqueAttribute(any(), any(), any(), any()))
                    .thenReturn(null);

            PurviewGovernanceView view = governanceService.getGovernance(
                    "bedroom", "draft-001", mock(CallContext.class));

            assertTrue(view.isAvailable(), "Purview連携が有効であること");
            assertFalse(view.isEntityFound(), "Purview上にエンティティがまだ存在しないこと");
            assertTrue(view.getMessage().contains("not synced"), "未同期メッセージが表示されること");
        }

        @Test
        @DisplayName("リレーションシップオブジェクトはガバナンス対象外であることが明確に返される")
        public void testGovernanceRejectsNonDocumentNonFolderObjects() {
            Relationship relationship = new Relationship();
            relationship.setId("rel-001");
            when(contentService.getContent("bedroom", "rel-001")).thenReturn(relationship);

            PurviewGovernanceView view = governanceService.getGovernance(
                    "bedroom", "rel-001", mock(CallContext.class));

            assertFalse(view.isSupportedObjectType(), "リレーションシップはガバナンス対象外であること");
            assertFalse(view.isAvailable(), "ガバナンス情報は取得不可であること");
        }
    }

    // =====================================================================
    //  シナリオ 4: Purview 障害時の同期
    // =====================================================================

    @Nested
    @DisplayName("シナリオ4: Purviewサービスが障害中に管理者が同期を試みた場合")
    class SyncDuringPurviewOutage {

        @Test
        @DisplayName("Purviewが503を返す場合、全同期が失敗ジョブとして記録される")
        public void testFullSyncRecordsFailureWhenPurviewIsDown() {
            PurviewSchemaPlannerService schemaPlannerService = mock(PurviewSchemaPlannerService.class);
            when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                    "NemakiWare", "1", "hash", "1", "hash", false,
                    List.of(), List.of(), List.of()));
            when(documentPublishService.publishRepositoryHierarchy("bedroom"))
                    .thenThrow(new IllegalStateException("HTTP 503 Service Unavailable"));
            when(archivePublishService.buildRepositoryArchiveSnapshot(any())).thenReturn("");
            when(cloudMetadataPublishService.buildRepositoryCloudMetadataSnapshot(any())).thenReturn("");
            when(containmentRelationshipService.buildRepositoryContainmentSnapshot(any())).thenReturn("");
            when(typeDefinitionPublishService.buildRepositoryTypeDefinitionSnapshot(any())).thenReturn("");

            PurviewFullSyncServiceImpl fullSyncService = new PurviewFullSyncServiceImpl(
                    schemaPlannerService, jobStateService, lockStateService, cursorStateService,
                    documentPublishService, archivePublishService, cloudMetadataPublishService,
                    containmentRelationshipService, typeDefinitionPublishService, contentDaoService);

            PurviewJobState result = fullSyncService.startFullSync("bedroom", "admin");

            assertEquals("FAILED", result.getStatus(), "ジョブがFAILEDとして記録されること");
            assertTrue(result.getErrorSummary().contains("503"), "503エラーがエラーメッセージに含まれること");
            assertEquals(1, result.getFailedCount(), "失敗カウントが1であること");
            verify(lockStateService).releaseRepositoryLock("bedroom", "FULL_SYNC", result.getJobId());
        }

        @Test
        @DisplayName("ガバナンス参照時にPurviewが応答しない場合、エラーメッセージ付きViewが返される")
        public void testGovernanceShowsErrorWhenPurviewIsUnreachable() throws Exception {
            PurviewGovernanceServiceImpl governanceService = new PurviewGovernanceServiceImpl(
                    config, contentService, exceptionService,
                    entityRegistryClient, new PurviewEntityPayloadFactory());
            Document document = new Document();
            document.setId("doc-001");
            document.setObjectType("cmis:document");
            when(contentService.getContent("bedroom", "doc-001")).thenReturn(document);
            when(entityRegistryClient.getEntityByUniqueAttribute(any(), any(), any(), any()))
                    .thenThrow(new PurviewClientException("java.net.ConnectException: 接続がタイムアウトしました"));

            PurviewGovernanceView view = governanceService.getGovernance(
                    "bedroom", "doc-001", mock(CallContext.class));

            assertTrue(view.isFeatureEnabled(), "Purview連携は有効設定のままであること");
            assertFalse(view.isEntityFound(), "エンティティは取得できないこと");
            assertFalse(view.isAvailable(), "ガバナンス情報は利用不可であること");
            assertTrue(view.getMessage().contains("タイムアウト"), "タイムアウトメッセージが表示されること");
        }
    }

    // =====================================================================
    //  シナリオ 5: 同時実行の排他制御
    // =====================================================================

    @Nested
    @DisplayName("シナリオ5: 2人の管理者が同時に全同期を実行した場合")
    class ConcurrentSyncPrevention {

        @Test
        @DisplayName("先着の同期が実行中の場合、後発はREJECTEDで即座に返却される")
        public void testSecondConcurrentSyncIsRejected() {
            PurviewSchemaPlannerService schemaPlannerService = mock(PurviewSchemaPlannerService.class);
            when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                    "NemakiWare", "1", "hash", "1", "hash", false,
                    List.of(), List.of(), List.of()));
            when(documentPublishService.publishRepositoryHierarchy(any())).thenReturn(5);
            when(archivePublishService.publishRepositoryArchives(any())).thenReturn(0);
            when(cloudMetadataPublishService.publishRepositoryCloudSyncLineage(any())).thenReturn(0);
            when(typeDefinitionPublishService.publishRepositoryTypeDefinitions(any())).thenReturn(0);
            when(archivePublishService.buildRepositoryArchiveSnapshot(any())).thenReturn("");
            when(cloudMetadataPublishService.buildRepositoryCloudMetadataSnapshot(any())).thenReturn("");
            when(containmentRelationshipService.buildRepositoryContainmentSnapshot(any())).thenReturn("");
            when(typeDefinitionPublishService.buildRepositoryTypeDefinitionSnapshot(any())).thenReturn("");

            // 1回目のロック取得: 成功、2回目: 失敗（既にロック中）
            when(lockStateService.tryAcquireRepositoryLock(any(), any(), any(), any()))
                    .thenReturn(true)
                    .thenReturn(false);

            PurviewFullSyncServiceImpl fullSyncService = new PurviewFullSyncServiceImpl(
                    schemaPlannerService, jobStateService, lockStateService, cursorStateService,
                    documentPublishService, archivePublishService, cloudMetadataPublishService,
                    containmentRelationshipService, typeDefinitionPublishService, contentDaoService);

            PurviewJobState firstResult = fullSyncService.startFullSync("bedroom", "admin-A");
            PurviewJobState secondResult = fullSyncService.startFullSync("bedroom", "admin-B");

            assertEquals("COMPLETED", firstResult.getStatus(), "先着の同期は成功すること");
            assertEquals("REJECTED", secondResult.getStatus(), "後発の同期はREJECTEDで返されること");
            assertTrue(secondResult.getErrorSummary().contains("already running"),
                    "排他制御メッセージが表示されること");
        }
    }

    // =====================================================================
    //  シナリオ 6: Purview無効状態での各機能の振る舞い
    // =====================================================================

    @Nested
    @DisplayName("シナリオ6: Purview連携が無効化されている場合")
    class PurviewDisabledBehavior {

        @Test
        @DisplayName("ガバナンス参照時に「Purview連携が無効です」という明確なメッセージが返される")
        public void testGovernanceShowsDisabledMessageWhenPurviewIsOff() {
            when(config.isEnabled()).thenReturn(false);
            PurviewGovernanceServiceImpl governanceService = new PurviewGovernanceServiceImpl(
                    config, contentService, exceptionService,
                    entityRegistryClient, new PurviewEntityPayloadFactory());

            Document document = new Document();
            document.setId("doc-001");
            document.setObjectType("cmis:document");
            when(contentService.getContent("bedroom", "doc-001")).thenReturn(document);

            PurviewGovernanceView view = governanceService.getGovernance(
                    "bedroom", "doc-001", mock(CallContext.class));

            assertFalse(view.isFeatureEnabled(), "Purview連携が無効であること");
            assertFalse(view.isAvailable(), "ガバナンス情報が利用不可であること");
            assertTrue(view.isSupportedObjectType(), "ドキュメントはサポート対象型であること");
            assertTrue(view.getMessage().contains("disabled"), "無効メッセージが表示されること");
        }
    }

    // =====================================================================
    //  シナリオ 7: スキーマバージョン更新後の再適用
    // =====================================================================

    @Nested
    @DisplayName("シナリオ7: NemakiWareアップデート後にスキーマの再適用が必要になった場合")
    class SchemaVersionUpgrade {

        @Test
        @DisplayName("スキーマハッシュが変わった場合、再適用が必要と判定されスキーマブートストラップが実行される")
        public void testSchemaReapplyDetectedAfterVersionUpgrade() throws Exception {
            PurviewSchemaManifestFactory manifestFactory = new PurviewSchemaManifestFactory();
            PurviewSchemaPlannerServiceImpl plannerService = new PurviewSchemaPlannerServiceImpl(
                    config, schemaStateService, manifestFactory);

            // 旧バージョンのハッシュが保存されている状態を模擬
            PurviewSchemaManifest currentManifest = manifestFactory.buildManifest();
            when(schemaStateService.getSchemaState("NemakiWare"))
                    .thenReturn(new PurviewSchemaState(
                            "NemakiWare",
                            currentManifest.getSchemaVersion(),
                            "outdated-hash-from-previous-version",
                            "2026-03-01T00:00:00Z",
                            "admin",
                            "applied"));

            PurviewSchemaDiff diff = plannerService.getSchemaDiff();

            assertTrue(diff.isApplyRequired(), "ハッシュが異なるため再適用が必要");
            assertEquals(currentManifest.getSchemaHash(), diff.getDesiredSchemaHash(),
                    "目標ハッシュが現在のマニフェストのハッシュと一致すること");
            assertFalse(diff.getDesiredSchemaHash().equals(diff.getCurrentSchemaHash()),
                    "現在のハッシュと目標ハッシュが異なること");

            // スキーマブートストラップ実行
            PurviewSchemaApplyServiceImpl applyService = new PurviewSchemaApplyServiceImpl(
                    config, plannerService, schemaStateService,
                    new PurviewSchemaManifestFactory(), new PurviewSchemaPayloadFactory(),
                    schemaRegistryClient,
                    new jp.aegif.nemaki.rest.purview.schema.AtlasSchemaCompatHelper());
            when(schemaRegistryClient.applySchema(any(), any()))
                    .thenReturn(PurviewSchemaPublishResult.success("applied"));

            PurviewSchemaBootstrapServiceImpl bootstrapService = new PurviewSchemaBootstrapServiceImpl(
                    config, applyService, jobStateService, lockStateService);
            PurviewSchemaBootstrapResult result = bootstrapService.startTypeBootstrap("admin");

            assertEquals("COMPLETED", result.getJobState().getStatus(), "再適用が成功すること");
            assertTrue(result.getApplyResult().isApplied(), "スキーマが実際に適用されたこと");
        }
    }

    // =====================================================================
    //  シナリオ 8: スキーマ定義の整合性
    // =====================================================================

    @Nested
    @DisplayName("シナリオ8: Purviewスキーマ定義の整合性を確認する")
    class SchemaIntegrity {

        @Test
        @DisplayName("スキーママニフェストのハッシュは呼び出しごとに常に同じ値を返す（冪等性）")
        public void testSchemaManifestIsIdempotent() {
            PurviewSchemaManifestFactory manifestFactory = new PurviewSchemaManifestFactory();

            PurviewSchemaManifest manifest1 = manifestFactory.buildManifest();
            PurviewSchemaManifest manifest2 = manifestFactory.buildManifest();

            assertEquals(manifest1.getSchemaHash(), manifest2.getSchemaHash(),
                    "2回連続で生成したマニフェストのハッシュが一致すること");
            assertEquals(manifest1.getSchemaVersion(), manifest2.getSchemaVersion(),
                    "バージョン文字列も一致すること");
        }

        @Test
        @DisplayName("スキーマペイロードに必要なエンティティ型・リレーションシップ・ビジネスメタデータが全て含まれる")
        @SuppressWarnings("unchecked")
        public void testSchemaPayloadContainsAllRequiredDefinitions() {
            PurviewSchemaManifestFactory manifestFactory = new PurviewSchemaManifestFactory();
            PurviewSchemaPayloadFactory payloadFactory = new PurviewSchemaPayloadFactory();

            Map<String, Object> payload = payloadFactory.buildTypeDefinitionsPayload(manifestFactory.buildManifest());
            List<Map<String, Object>> entityDefs = (List<Map<String, Object>>) payload.get("entityDefs");
            List<Map<String, Object>> relationshipDefs = (List<Map<String, Object>>) payload.get("relationshipDefs");
            List<Map<String, Object>> businessMetadataDefs = (List<Map<String, Object>>) payload.get("businessMetadataDefs");

            // コア型が存在すること
            List<String> entityNames = entityDefs.stream()
                    .map(def -> def.get("name").toString()).toList();
            assertTrue(entityNames.contains("nemaki_repository"), "リポジトリ型");
            assertTrue(entityNames.contains("nemaki_folder"), "フォルダ型");
            assertTrue(entityNames.contains("nemaki_document"), "ドキュメント型");
            assertTrue(entityNames.contains("nemaki_archive"), "アーカイブ型");
            assertTrue(entityNames.contains("nemaki_type_definition"), "型定義型");
            assertTrue(entityNames.contains("nemaki_external_asset"), "外部アセット型");

            // リレーションシップが存在すること
            List<String> relNames = relationshipDefs.stream()
                    .map(def -> def.get("name").toString()).toList();
            assertTrue(relNames.contains("nemaki_repository_contains_folder"), "リポジトリ-フォルダ関係");
            assertTrue(relNames.contains("nemaki_folder_contains_document"), "フォルダ-ドキュメント関係");
            assertTrue(relNames.contains("nemaki_document_has_type_definition"), "ドキュメント-型定義関係");
            assertTrue(relNames.contains("nemaki_document_has_archive"), "ドキュメント-アーカイブ関係");

            // ビジネスメタデータが存在すること
            assertEquals(1, businessMetadataDefs.size(), "ビジネスメタデータ定義が1つ");
            assertEquals("nemakiGovernance", businessMetadataDefs.get(0).get("name"),
                    "nemakiGovernanceビジネスメタデータ");
        }
    }

    // ─── ヘルパー ─────────────────────────────────────────

    private Change createChange(String token) {
        Change change = new Change();
        change.setToken(token);
        change.setObjectId("object-" + token);
        change.setChangeType(ChangeType.UPDATED);
        return change;
    }
}
