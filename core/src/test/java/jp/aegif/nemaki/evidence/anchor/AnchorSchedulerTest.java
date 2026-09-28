/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.evidence.anchor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.evidence.EvidenceCheckpoint;
import jp.aegif.nemaki.evidence.EvidenceLedgerEntry;
import jp.aegif.nemaki.evidence.EvidenceLedgerService;
import jp.aegif.nemaki.evidence.EvidenceLedgerStore;
import jp.aegif.nemaki.evidence.RecordContentStateRecorder;
import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.rest.controller.IntegrationSettingsService;
import jp.aegif.nemaki.rest.purview.anchor.AnchorKind;
import jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt;
import jp.aegif.nemaki.rest.purview.anchor.AnchorReceipts;
import jp.aegif.nemaki.rest.purview.anchor.AnchorTarget;
import jp.aegif.nemaki.rest.purview.journal.LeaderElection;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.SystemConst;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The scheduler's gates (design anchor-scheduler.md §9), each measured through {@code tick} — the
 * method the 60-second task calls — with the ledger, the receipts, the settings and the leader
 * answered by mocks and the seal observed where it leaves: {@code closeCheckpoint} and
 * {@code anchor}.
 *
 * <p>Each fixture makes exactly one gate the thing that stops the seal: the world is otherwise
 * due, so taking that gate away seals, and the lock sees it.
 */
class AnchorSchedulerTest {

    private static final String REPO = "bedroom";
    private static final String ROOT = "ab".repeat(32);
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");

    private AnchorService anchors;
    private EvidenceLedgerService ledger;
    private EvidenceLedgerStore store;
    private AnchorReceiptStore receipts;
    private LeaderElection election;
    private PropertyManager props;
    private IntegrationSettingsService settingsService;
    private AnchorTarget tsa;
    private AnchorTarget ots;

    /** The domain's own saved values (what the settings screen writes). */
    private final Map<String, String> saved = new HashMap<>();
    /** The deployment-wide values ({@code -D}, environment, global nemaki_conf). */
    private final Map<String, String> deploymentWide = new HashMap<>();

    private final List<EvidenceCheckpoint> checkpoints = new ArrayList<>();
    private final Map<Long, List<AnchorReceipt>> receiptsByCheckpoint = new HashMap<>();
    private long highest = -1;
    private Instant oldestEntryAt = NOW.minus(Duration.ofHours(2));

    @BeforeEach
    void wire() {
        anchors = mock(AnchorService.class);
        ledger = mock(EvidenceLedgerService.class);
        store = mock(EvidenceLedgerStore.class);
        receipts = mock(AnchorReceiptStore.class);
        election = mock(LeaderElection.class);
        props = mock(PropertyManager.class);
        settingsService = mock(IntegrationSettingsService.class);
        tsa = rung(AnchorKind.RFC3161_TSA, true);
        ots = rung(AnchorKind.OPENTIMESTAMPS, false);

        when(anchors.targets()).thenAnswer(inv -> List.of(tsa, ots));
        // Null-safe: a test that re-stubs with when(anchors.anchor(any())) calls this with null.
        when(anchors.anchor(any())).thenAnswer(inv -> {
            EvidenceCheckpoint cp = inv.getArgument(0);
            return cp == null ? null
                    : new AnchorService.Outcome(cp.domain(), cp.toSequence(), cp.merkleRoot(), List.of(), null);
        });
        when(anchors.retryUnsettled(any())).thenAnswer(inv -> {
            EvidenceCheckpoint cp = inv.getArgument(0);
            return cp == null ? null
                    : new AnchorService.Outcome(cp.domain(), cp.toSequence(), cp.merkleRoot(), List.of(), null);
        });
        when(anchors.upgradePending(anyString(), anyInt())).thenReturn(new AnchorService.Upgraded(List.of(), null));
        when(ledger.closeCheckpoint(anyString(), anyString())).thenReturn(Map.of("status", "success"));

        when(store.isActive()).thenReturn(true);
        // Every other domain (record-content) holds nothing unless a test says so: a
        // deployment-wide "enabled" reaches it too, and it must not seal behind a test's back.
        when(store.highestSequence(anyString())).thenReturn(-1L);
        when(store.highestSequence(REPO)).thenAnswer(inv -> highest);
        when(store.latestCheckpoint(REPO)).thenAnswer(inv -> checkpoints.isEmpty() ? null
                : checkpoints.get(checkpoints.size() - 1));
        when(store.checkpointEndingBefore(eq(REPO), anyLong())).thenAnswer(inv -> {
            long from = inv.getArgument(1);
            for (EvidenceCheckpoint cp : checkpoints) {
                if (cp.toSequence() == from - 1) {
                    return cp;
                }
            }
            return null;
        });
        when(store.range(eq(REPO), anyLong(), anyLong(), anyInt())).thenAnswer(inv -> {
            long from = inv.getArgument(1);
            return List.of(new EvidenceLedgerEntry(REPO, from, EvidenceLedgerEntry.SubjectKind.FIXITY_RESULT,
                    "subject", "d".repeat(64), oldestEntryAt.toString(), null, "e".repeat(64)));
        });
        when(receipts.isActive()).thenReturn(true);
        when(receipts.forCheckpoint(eq(REPO), anyLong())).thenAnswer(inv ->
                receiptsByCheckpoint.getOrDefault((Long) inv.getArgument(1), List.of()));

        when(election.isLeader("anchor")).thenReturn(true);
        when(props.getConfiguration(SystemConst.NEMAKI_CONF_DB)).thenReturn(new Configuration());
        when(props.readValue(anyString())).thenAnswer(inv -> deploymentWide.get((String) inv.getArgument(0)));
        when(settingsService.readRepositorySettings(eq(REPO), any())).thenAnswer(inv -> new HashMap<>(saved));
        when(settingsService.readRepositorySettings(eq(RecordContentStateRecorder.DOMAIN), any()))
                .thenAnswer(inv -> Map.of());
    }

    private AnchorScheduler scheduler() throws Exception {
        AnchorScheduler s = new AnchorScheduler();
        RepositoryInfoMap repos = mock(RepositoryInfoMap.class);
        when(repos.keys()).thenReturn(new LinkedHashSet<>(List.of(REPO)));
        set(s, "anchorService", anchors);
        set(s, "ledgerService", ledger);
        set(s, "ledgerStore", store);
        set(s, "receiptStore", receipts);
        set(s, "leaderElection", election);
        set(s, "propertyManager", props);
        set(s, "settingsService", settingsService);
        set(s, "repositoryInfoMap", repos);
        return s;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static AnchorTarget rung(AnchorKind kind, boolean configured) {
        AnchorTarget target = mock(AnchorTarget.class);
        when(target.kind()).thenReturn(kind);
        when(target.isConfigured()).thenReturn(configured);
        return target;
    }

    /** Checkpoints over the ledger, oldest first, each sealed at {@code sealedAt}. */
    private void sealed(long from, long to, Instant sealedAt, AnchorReceipt... held) {
        EvidenceCheckpoint previous = checkpoints.isEmpty() ? null : checkpoints.get(checkpoints.size() - 1);
        EvidenceCheckpoint cp = EvidenceCheckpoint.of(REPO, from, to, ROOT,
                previous == null ? null : previous.checkpointHash(), sealedAt.toString());
        checkpoints.add(cp);
        receiptsByCheckpoint.put(to, List.of(held));
    }

    private static AnchorReceipt confirmedTsa() {
        return AnchorReceipts.confirmed(AnchorKind.RFC3161_TSA, ROOT, NOW.minus(Duration.ofDays(1)),
                new byte[] {1, 2, 3}, Map.of());
    }

    private static AnchorReceipt failedTsa() {
        return AnchorReceipt.failed(AnchorKind.RFC3161_TSA, ROOT, NOW.minus(Duration.ofDays(1)), "TSA down");
    }

    /** Enabled, with both arms armed: 60 minutes, or 1 entry. */
    private void dueByEitherArm() {
        saved.put(AnchorScheduleSettings.ENABLED, "true");
        saved.put(AnchorScheduleSettings.INTERVAL_MINUTES, "60");
        saved.put(AnchorScheduleSettings.MAX_UNANCHORED_ENTRIES, "1");
        highest = 4;
        oldestEntryAt = NOW.minus(Duration.ofHours(2));
    }

    private void verifyNothingSealed() {
        verify(ledger, never()).closeCheckpoint(anyString(), anyString());
        verify(anchors, never()).anchor(any());
    }

    // ---- the four gates before anything is read ----

    @Test
    @DisplayName("disabled: a world that is due on both arms is not sealed")
    void aDisabledScheduleSealsNothing() throws Exception {
        dueByEitherArm();
        saved.put(AnchorScheduleSettings.ENABLED, "false");
        AnchorScheduler scheduler = scheduler();

        scheduler.tick(NOW);

        verifyNothingSealed();
        assertTrue(scheduler.state(REPO).lastReason.contains("disabled"), scheduler.state(REPO).lastReason);
    }

    @Test
    @DisplayName("enabled with no rung configured: nothing is sealed, because there is nowhere to send")
    void withNoRungConfiguredNothingIsSealed() throws Exception {
        dueByEitherArm();
        tsa = rung(AnchorKind.RFC3161_TSA, false);
        AnchorScheduler scheduler = scheduler();

        scheduler.tick(NOW);

        verifyNothingSealed();
        assertTrue(scheduler.state(REPO).lastReason.contains("no rung"), scheduler.state(REPO).lastReason);
    }

    @Test
    @DisplayName("a node that is not the leader for 'anchor' seals nothing")
    void aNodeThatIsNotTheLeaderSealsNothing() throws Exception {
        dueByEitherArm();
        when(election.isLeader("anchor")).thenReturn(false);
        AnchorScheduler scheduler = scheduler();

        scheduler.tick(NOW);

        verifyNothingSealed();
    }

    @Test
    @DisplayName("enabled without an interval (a -D the endpoint never saw): nothing is sealed, and it is WARNed once, not every minute")
    void enabledWithoutAnIntervalSealsNothingAndWarnsOnce() throws Exception {
        dueByEitherArm();
        saved.remove(AnchorScheduleSettings.INTERVAL_MINUTES);
        AnchorScheduler scheduler = scheduler();
        ch.qos.logback.classic.Logger log = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AnchorScheduler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        log.addAppender(appender);
        try {
            scheduler.tick(NOW);
            scheduler.tick(NOW.plus(Duration.ofMinutes(1)));
        } finally {
            log.detachAppender(appender);
        }

        verifyNothingSealed();
        long warnings = appender.list.stream().filter(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains(AnchorScheduleSettings.INTERVAL_MINUTES)).count();
        assertEquals(1, warnings, "the same idle reason was warned " + warnings + " times over two ticks");
    }

    // ---- the two arms, each on its own ----

    @Test
    @DisplayName("the TIME arm alone seals: no count limit, the oldest unanchored entry older than the interval")
    void theTimeArmAloneSeals() throws Exception {
        saved.put(AnchorScheduleSettings.ENABLED, "true");
        saved.put(AnchorScheduleSettings.INTERVAL_MINUTES, "60");
        highest = 2;
        oldestEntryAt = NOW.minus(Duration.ofMinutes(61));
        sealWillCreateACheckpoint();
        AnchorScheduler scheduler = scheduler();

        scheduler.tick(NOW);

        verify(ledger, times(1)).closeCheckpoint(eq(REPO), anyString());
        verify(anchors, times(1)).anchor(any());
    }

    @Test
    @DisplayName("the COUNT arm alone seals: the interval not reached, the count limit reached")
    void theCountArmAloneSeals() throws Exception {
        saved.put(AnchorScheduleSettings.ENABLED, "true");
        saved.put(AnchorScheduleSettings.INTERVAL_MINUTES, "1440");
        saved.put(AnchorScheduleSettings.MAX_UNANCHORED_ENTRIES, "3");
        highest = 2;
        oldestEntryAt = NOW.minus(Duration.ofMinutes(5));
        AnchorScheduler scheduler = scheduler();

        scheduler.tick(NOW);

        verify(ledger, times(1)).closeCheckpoint(eq(REPO), anyString());
    }

    @Test
    @DisplayName("neither arm reached: nothing is sealed — the control for the two above")
    void neitherArmReachedSealsNothing() throws Exception {
        saved.put(AnchorScheduleSettings.ENABLED, "true");
        saved.put(AnchorScheduleSettings.INTERVAL_MINUTES, "1440");
        saved.put(AnchorScheduleSettings.MAX_UNANCHORED_ENTRIES, "10");
        highest = 2;
        oldestEntryAt = NOW.minus(Duration.ofMinutes(5));
        AnchorScheduler scheduler = scheduler();

        scheduler.tick(NOW);

        verifyNothingSealed();
    }

    // ---- the gates after the world is read ----

    @Test
    @DisplayName("due, and the last seal is within the minimum interval: nothing is sealed")
    void withinTheMinimumIntervalNothingIsSealed() throws Exception {
        saved.put(AnchorScheduleSettings.ENABLED, "true");
        saved.put(AnchorScheduleSettings.INTERVAL_MINUTES, "60");
        saved.put(AnchorScheduleSettings.MIN_INTERVAL_MINUTES, "5");
        sealed(0, 4, NOW.minus(Duration.ofMinutes(2)), confirmedTsa());
        highest = 9;
        oldestEntryAt = NOW.minus(Duration.ofHours(2));
        AnchorScheduler scheduler = scheduler();

        scheduler.tick(NOW);

        verifyNothingSealed();
        assertTrue(scheduler.state(REPO).lastReason.contains("minimum interval"), scheduler.state(REPO).lastReason);
    }

    @Test
    @DisplayName("receipts that cannot be read: nothing is sealed, and no count is given — 'could not read' is not 0")
    void anAnchoredPositionThatCannotBeReadSealsNothingAndShowsNoCount() throws Exception {
        dueByEitherArm();
        sealed(0, 4, NOW.minus(Duration.ofHours(3)));
        highest = 9;
        when(receipts.forCheckpoint(eq(REPO), anyLong())).thenThrow(new IllegalStateException("view timed out"));
        AnchorScheduler scheduler = scheduler();

        scheduler.tick(NOW);

        verifyNothingSealed();
        assertEquals("UNAVAILABLE", scheduler.state(REPO).lastOutcome);
        Map<String, Object> observation = scheduler.observation(REPO);
        assertEquals("UNAVAILABLE", observation.get("status"));
        assertFalse(observation.containsKey("count"), "an unreadable position was given a count: " + observation);
    }

    @Test
    @DisplayName("a receipt query that FAILED (an empty answer flagged as failed) is not 'nothing anchored'")
    void aReceiptQueryThatFailedIsNotNothingAnchored() throws Exception {
        dueByEitherArm();
        sealed(0, 4, NOW.minus(Duration.ofHours(3)));
        highest = 9;
        when(receipts.lastQueryFailed()).thenReturn(true);
        AnchorScheduler scheduler = scheduler();

        scheduler.tick(NOW);

        verifyNothingSealed();
        assertEquals("UNAVAILABLE", scheduler.state(REPO).lastOutcome);
    }

    @Test
    @DisplayName("nothing unanchored: nothing is sealed")
    void nothingUnanchoredSealsNothing() throws Exception {
        dueByEitherArm();
        sealed(0, 4, NOW.minus(Duration.ofHours(3)), confirmedTsa());
        highest = 4;
        AnchorScheduler scheduler = scheduler();

        scheduler.tick(NOW);

        verifyNothingSealed();
    }

    @Test
    @DisplayName("due, but every unanchored entry is already sealed (its anchor failed): sealing again seals nothing, so it is not called")
    void dueButEverythingIsSealedCallsNothing() throws Exception {
        dueByEitherArm();
        sealed(0, 9, NOW.minus(Duration.ofHours(3)), failedTsa());
        highest = 9;
        AnchorScheduler scheduler = scheduler();

        scheduler.tick(NOW);

        verifyNothingSealed();
        assertTrue(scheduler.state(REPO).lastReason.contains("already sealed"), scheduler.state(REPO).lastReason);
    }

    @Test
    @DisplayName("twenty sealed checkpoints and none anchored: the count is a lower bound, and a bound that reaches the limit still seals")
    void anExhaustedWalkStillSeals() throws Exception {
        saved.put(AnchorScheduleSettings.ENABLED, "true");
        saved.put(AnchorScheduleSettings.INTERVAL_MINUTES, "1440");
        saved.put(AnchorScheduleSettings.MAX_UNANCHORED_ENTRIES, "10");
        for (long seq = 0; seq <= 20; seq++) {
            sealed(seq, seq, NOW.minus(Duration.ofHours(3)), failedTsa());
        }
        highest = 24;
        oldestEntryAt = NOW.minus(Duration.ofMinutes(5));
        AnchorScheduler scheduler = scheduler();

        scheduler.tick(NOW);

        verify(ledger, times(1)).closeCheckpoint(eq(REPO), anyString());
        assertEquals(Boolean.TRUE, scheduler.observation(REPO).get("countIsLowerBound"));
    }

    @Test
    @DisplayName("a seal that errors is not tried again every minute: the next attempt waits the minimum interval")
    void anErrorWaitsTheMinimumIntervalBeforeTryingAgain() throws Exception {
        dueByEitherArm();
        saved.put(AnchorScheduleSettings.MIN_INTERVAL_MINUTES, "5");
        highest = 2;
        when(ledger.closeCheckpoint(anyString(), anyString()))
                .thenReturn(Map.of("status", "error", "message", "backlog"));
        AnchorScheduler scheduler = scheduler();

        scheduler.tick(NOW);
        scheduler.tick(NOW.plus(Duration.ofMinutes(2)));
        verify(ledger, times(1)).closeCheckpoint(eq(REPO), anyString());

        scheduler.tick(NOW.plus(Duration.ofMinutes(6)));
        verify(ledger, times(2)).closeCheckpoint(eq(REPO), anyString());
    }

    // ---- the settings: read fresh, the domain's own value first, never a fallback for "could not read" ----

    @Test
    @DisplayName("saved settings that cannot be read do not fall back to a start-up 'enabled'")
    void anUnreadableSavedSettingDoesNotFallBackToTheStartupValue() throws Exception {
        dueByEitherArm();
        deploymentWide.putAll(saved);
        saved.clear();
        when(settingsService.readRepositorySettings(eq(REPO), any())).thenThrow(new IllegalStateException("couch down"));
        AnchorScheduler scheduler = scheduler();

        scheduler.tick(NOW);

        verifyNothingSealed();
        assertEquals("UNAVAILABLE", scheduler.state(REPO).lastOutcome);
    }

    @Test
    @DisplayName("what is saved for the domain is what the NEXT tick acts on")
    void aSavedValueIsWhatTheNextTickActsOn() throws Exception {
        dueByEitherArm();
        saved.put(AnchorScheduleSettings.ENABLED, "false");
        AnchorScheduler scheduler = scheduler();

        scheduler.tick(NOW);
        verifyNothingSealed();

        saved.put(AnchorScheduleSettings.ENABLED, "true"); // the settings screen saved it
        scheduler.tick(NOW.plus(Duration.ofMinutes(1)));

        verify(ledger, times(1)).closeCheckpoint(eq(REPO), anyString());
    }

    @Test
    @DisplayName("the domain's own saved value wins over the deployment-wide one (the settings screen over -D)")
    void theDomainsOwnValueWinsOverTheDeploymentWideOne() throws Exception {
        dueByEitherArm();
        deploymentWide.putAll(saved);
        saved.put(AnchorScheduleSettings.ENABLED, "false");
        AnchorScheduler scheduler = scheduler();

        scheduler.tick(NOW);

        verifyNothingSealed();
    }

    @Test
    @DisplayName("record-content — where an exported package's anchor comes from — is a domain the scheduler seals")
    void theRecordContentDomainIsSealedToo() throws Exception {
        String domain = RecordContentStateRecorder.DOMAIN;
        when(settingsService.readRepositorySettings(eq(domain), any())).thenReturn(Map.of(
                AnchorScheduleSettings.ENABLED, "true", AnchorScheduleSettings.INTERVAL_MINUTES, "60"));
        when(store.highestSequence(domain)).thenReturn(3L);
        when(store.range(eq(domain), anyLong(), anyLong(), anyInt())).thenReturn(List.of(new EvidenceLedgerEntry(
                domain, 0, EvidenceLedgerEntry.SubjectKind.FIXITY_RESULT, "s", "d".repeat(64),
                NOW.minus(Duration.ofHours(2)).toString(), null, "e".repeat(64))));
        AnchorScheduler scheduler = scheduler();

        scheduler.tick(NOW);

        verify(ledger, times(1)).closeCheckpoint(eq(domain), anyString());
    }

    // ---- the two timers ----

    @Test
    @DisplayName("upgrade-pending is never called when OpenTimestamps is not a configured rung")
    void upgradeIsNeverCalledWithoutTheOpenTimestampsRung() throws Exception {
        dueByEitherArm();
        sealed(0, 4, NOW.minus(Duration.ofHours(3)), confirmedTsa());
        highest = 4;
        when(receipts.pending(eq(REPO), anyInt())).thenReturn(List.of(new AnchorReceiptStore.PendingReceipt(REPO, 4,
                AnchorReceipt.pending(AnchorKind.OPENTIMESTAMPS, ROOT, NOW, new byte[] {1}, null, Map.of()))));
        AnchorScheduler scheduler = scheduler();

        scheduler.tick(NOW);

        verify(anchors, never()).upgradePending(anyString(), anyInt());
    }

    @Test
    @DisplayName("with OpenTimestamps configured, upgrade-pending runs on ITS period, not every tick")
    void upgradeRunsOnItsOwnPeriod() throws Exception {
        dueByEitherArm();
        saved.put(AnchorScheduleSettings.UPGRADE_INTERVAL_MINUTES, "60");
        ots = rung(AnchorKind.OPENTIMESTAMPS, true);
        AnchorReceipt pending = AnchorReceipt.pending(AnchorKind.OPENTIMESTAMPS, ROOT, NOW, new byte[] {1}, null, Map.of());
        sealed(0, 4, NOW.minus(Duration.ofHours(3)), pending);
        highest = 4;
        when(receipts.pending(eq(REPO), anyInt())).thenReturn(List.of(new AnchorReceiptStore.PendingReceipt(REPO, 4, pending)));
        AnchorScheduler scheduler = scheduler();

        scheduler.tick(NOW);
        scheduler.tick(NOW.plus(Duration.ofMinutes(30)));
        verify(anchors, times(1)).upgradePending(eq(REPO), anyInt());

        scheduler.tick(NOW.plus(Duration.ofMinutes(61)));
        verify(anchors, times(2)).upgradePending(eq(REPO), anyInt());
    }

    /** The seal the next tick makes seals checkpoint 0..2; what anchor() answers is the test's. */
    private AtomicReference<EvidenceCheckpoint> sealWillCreateACheckpoint() {
        AtomicReference<EvidenceCheckpoint> made = new AtomicReference<>();
        when(ledger.closeCheckpoint(eq(REPO), anyString())).thenAnswer(inv -> {
            EvidenceCheckpoint cp = EvidenceCheckpoint.of(REPO, 0, highest, ROOT, null, inv.getArgument(1));
            checkpoints.add(cp);
            made.set(cp);
            return Map.of("status", "success");
        });
        return made;
    }

    @Test
    @DisplayName("an anchor that FAILED on every rung is retried on the retry period — the way back works")
    void theRetryRunsOnItsPeriodForAFailedAnchor() throws Exception {
        dueByEitherArm();
        saved.put(AnchorScheduleSettings.RETRY_UNSETTLED_INTERVAL_MINUTES, "60");
        highest = 2;
        sealWillCreateACheckpoint();
        when(anchors.anchor(any())).thenAnswer(inv -> {
            EvidenceCheckpoint cp = inv.getArgument(0);
            return new AnchorService.Outcome(cp.domain(), cp.toSequence(), cp.merkleRoot(), List.of(failedTsa()),
                    "every configured rung FAILED, so this checkpoint is not anchored anywhere");
        });
        AnchorScheduler scheduler = scheduler();

        scheduler.tick(NOW);
        verify(anchors, never()).retryUnsettled(any());

        scheduler.tick(NOW.plus(Duration.ofMinutes(61)));
        verify(anchors, times(1)).retryUnsettled(any());
    }

    @Test
    @DisplayName("a commitment whose receipt was NOT stored holds the retry for that checkpoint — a retry would make another")
    void aLostCommitmentHoldsTheRetryForThatCheckpoint() throws Exception {
        dueByEitherArm();
        saved.put(AnchorScheduleSettings.RETRY_UNSETTLED_INTERVAL_MINUTES, "60");
        highest = 2;
        sealWillCreateACheckpoint();
        AnchorReceipt lost = confirmedTsa();
        when(anchors.anchor(any())).thenAnswer(inv -> {
            EvidenceCheckpoint cp = inv.getArgument(0);
            return new AnchorService.Outcome(cp.domain(), cp.toSequence(), cp.merkleRoot(), List.of(lost),
                    "a commitment was made and its receipt was NOT stored", List.of(lost));
        });
        AnchorScheduler scheduler = scheduler();

        scheduler.tick(NOW);
        scheduler.tick(NOW.plus(Duration.ofMinutes(61)));

        verify(anchors, never()).retryUnsettled(any());
        assertEquals(2L, scheduler.state(REPO).retryHeldForCheckpoint);
    }

    @Test
    @DisplayName("a RETRY whose receipt could not be stored holds the next retry too")
    void aRetryThatLostItsReceiptHoldsTheNext() throws Exception {
        dueByEitherArm();
        saved.put(AnchorScheduleSettings.RETRY_UNSETTLED_INTERVAL_MINUTES, "60");
        sealed(0, 4, NOW.minus(Duration.ofHours(3)), failedTsa());
        highest = 4;
        AnchorReceipt lost = confirmedTsa();
        when(anchors.retryUnsettled(any())).thenAnswer(inv -> {
            EvidenceCheckpoint cp = inv.getArgument(0);
            return new AnchorService.Outcome(cp.domain(), cp.toSequence(), cp.merkleRoot(), List.of(lost), null,
                    List.of(lost));
        });
        AnchorScheduler scheduler = scheduler();

        scheduler.tick(NOW);
        verify(anchors, times(1)).retryUnsettled(any());

        scheduler.tick(NOW.plus(Duration.ofMinutes(61)));
        verify(anchors, times(1)).retryUnsettled(any());
    }
}
