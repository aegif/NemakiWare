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
package jp.aegif.nemaki.evidence;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.evidence.AuthenticityReport.Section;
import jp.aegif.nemaki.evidence.AuthenticityReport.Verdict;
import jp.aegif.nemaki.fixity.FixityScanService;
import jp.aegif.nemaki.fixity.FixityVerifier;
import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.rest.ingest.CaptureEvidenceField;
import jp.aegif.nemaki.rest.ingest.capture.CaptureIntent;
import jp.aegif.nemaki.rest.ingest.capture.CaptureMaintenanceStore;
import jp.aegif.nemaki.rest.purview.journal.LineageBinaryDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gathers one document's evidence into an {@link AuthenticityReport} (P1-4).
 *
 * <h2>Personal data is not in it by default</h2>
 *
 * <p>The identity section reads the evidence aspects, and some of those properties are personal
 * data — chat participants, mail From/To/Cc, a page's author. The disclosure table already says
 * which ({@code CaptureEvidenceField.internalOnlyCmisPropertyIds()}), so this uses the SAME
 * declaration rather than a list of its own: one table, another door.
 *
 * <p>A report is forwarded, printed and quoted. Including those by default would put personal
 * data in circulation as a side effect of asking "is this record intact?", which nobody asking
 * that question intended.
 */
@Component
public class AuthenticityReportAssembler {

    private static final Logger logger =
            LoggerFactory.getLogger(AuthenticityReportAssembler.class);

    /** How many capture rows the custody section reads before it stops and says so. */
    static final int CUSTODY_ROW_LIMIT = 20;

    /** How many ledger entries the integrity section verifies before it stops and says so. */
    static final int LEDGER_ENTRY_LIMIT = 1000;

    private ContentService contentService;
    private FixityScanService fixityScanService;
    private CaptureMaintenanceStore maintenanceStore;
    private EvidenceLedgerStore ledgerStore;
    private LineageBinaryDigest binaryDigest;

    @Autowired(required = false)
    @Qualifier("contentService")
    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    @Autowired(required = false)
    public void setFixityScanService(FixityScanService fixityScanService) {
        this.fixityScanService = fixityScanService;
    }

    @Autowired(required = false)
    public void setMaintenanceStore(CaptureMaintenanceStore maintenanceStore) {
        this.maintenanceStore = maintenanceStore;
    }

    @Autowired(required = false)
    public void setLedgerStore(EvidenceLedgerStore ledgerStore) {
        this.ledgerStore = ledgerStore;
    }

    /**
     * Injected, never constructed here.
     *
     * <p>{@code LineageBinaryDigest} is a Spring bean whose {@code LineageConfig} and
     * {@code ServletContext} arrive by injection, so a locally-built one has neither and can
     * only ever throw {@code UnmeasurableException} — an environment section that reports
     * UNAVAILABLE for ever while looking like it tried.
     */
    @Autowired(required = false)
    public void setBinaryDigest(LineageBinaryDigest binaryDigest) {
        this.binaryDigest = binaryDigest;
    }

    /**
     * @param includeInternalOnly when true, the identity section also carries the properties the
     *                            disclosure table marks INTERNAL_ONLY. Off by default, and the
     *                            report says when it is on so a reader knows what they are
     *                            holding.
     */
    public AuthenticityReport assemble(String repositoryId, String objectId, String generatedAt,
            boolean includeInternalOnly) {
        List<Section> sections = new ArrayList<>();
        Content content = null;
        try {
            content = contentService == null ? null
                    : contentService.getContent(repositoryId, objectId);
        } catch (Exception e) {
            logger.warn("Authenticity report could not read {}/{}: {}", repositoryId, objectId,
                    e.getMessage());
        }

        sections.add(identitySection(content, includeInternalOnly));
        sections.add(contentSection(repositoryId, content));
        sections.add(custodySection(repositoryId, objectId));
        sections.add(ledgerSection(repositoryId));
        sections.add(duplicationSection(repositoryId, objectId));
        sections.add(versionsSection(content));
        sections.add(accessSection());
        sections.add(environmentSection());

        return new AuthenticityReport(repositoryId, objectId, generatedAt, List.copyOf(sections));
    }

    private Section identitySection(Content content, boolean includeInternalOnly) {
        if (content == null) {
            // UNAVAILABLE, not an empty section: "we could not read it" and "there is nothing"
            // are different answers and an empty section reads as the second.
            return new Section("identity", Verdict.UNAVAILABLE, Map.of(),
                    "The object could not be read, so nothing here was gathered. This is NOT "
                            + "a statement that the object has no identity attributes.");
        }
        java.util.Set<String> internalOnly = CaptureEvidenceField.internalOnlyCmisPropertyIds();
        Map<String, Object> values = new LinkedHashMap<>();
        int withheld = 0;
        if (content.getAspects() != null) {
            for (Aspect aspect : content.getAspects()) {
                if (aspect == null || aspect.getProperties() == null
                        || !jp.aegif.nemaki.businesslogic.EvidenceTypes
                                .isProtected(aspect.getName())) {
                    continue;
                }
                for (Property property : aspect.getProperties()) {
                    if (property == null || property.getKey() == null
                            || property.getValue() == null) {
                        continue;
                    }
                    if (!includeInternalOnly && internalOnly.contains(property.getKey())) {
                        withheld++;
                        continue;
                    }
                    values.put(property.getKey(), String.valueOf(property.getValue()));
                }
            }
        }
        if (values.isEmpty() && withheld == 0) {
            return new Section("identity", Verdict.ABSENT, Map.of(),
                    "This object carries no capture-evidence attributes. It was not brought in "
                            + "through a path that records them — which says nothing about "
                            + "where it came from.");
        }
        Map<String, Object> body = new LinkedHashMap<>(values);
        body.put(AuthenticityReport.WITHHELD_COUNT_KEY, withheld);
        if (includeInternalOnly) {
            body.put(AuthenticityReport.INCLUDES_PERSONAL_DATA_KEY, true);
        }
        return new Section("identity", Verdict.REPORTED, body,
                "These are the attributes AS STORED NOW, attributed to what the source "
                        + "system reported at capture. This section READS them; it does not "
                        + "compare them with the capture hash, so it does not establish that "
                        + "they are unchanged since. They are read-only through CMIS, which is "
                        + "not the same as unchangeable — direct database access is outside "
                        + "that guarantee. And faithful recording would not be truth in any "
                        + "case: nothing here checks that the source told the truth."
                        + (withheld > 0 ? " " + withheld + " propert(y/ies) the disclosure "
                        + "table marks INTERNAL_ONLY are withheld from this report by default."
                        : ""));
    }

    private Section contentSection(String repositoryId, Content content) {
        if (content == null || fixityScanService == null) {
            return new Section("content", Verdict.UNAVAILABLE, Map.of(),
                    "The stored bytes could not be checked. This is NOT a statement that they "
                            + "are wrong, or that they are right.");
        }
        FixityVerifier.Result result = fixityScanService.verifyOne(repositoryId, content);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", result.outcome().name());
        body.put("recordedDigest", result.recordedDigest());
        body.put("computedDigest", result.computedDigest());
        body.put("algorithm", FixityVerifier.ALGORITHM);
        body.put("subject", FixityVerifier.SUBJECT_STORED_REVERIFIED);
        if (result.reason() != null) {
            body.put("reason", result.reason());
        }
        // The four values are carried through unflattened. Collapsing NOT_RECORDED into
        // UNVERIFIABLE here would undo P1-2's whole distinction at the one place a reader
        // actually looks.
        Verdict verdict = switch (result.outcome()) {
            case MATCH -> Verdict.VERIFIED;
            case MISMATCH -> Verdict.FAILED;
            case UNVERIFIABLE -> Verdict.UNAVAILABLE;
            case NOT_RECORDED -> Verdict.ABSENT;
        };
        String limits = switch (result.outcome()) {
            case MATCH -> "The stored bytes hash to what this repository recorded. That is NOT "
                    + "proof they were never altered: the digest is an ordinary stored "
                    + "property, so anything with direct database access can change the bytes "
                    + "AND the digest and keep them agreeing. Independent assurance needs an "
                    + "external anchor.";
            case MISMATCH -> "The stored bytes are not what this repository recorded. That is "
                    + "not by itself evidence of tampering — a migration, a restore or a "
                    + "direct database edit produce the same result. It does mean something "
                    + "changed them outside the path that maintains the digest.";
            case UNVERIFIABLE -> "A digest was recorded and the check could not be carried "
                    + "out. Nothing about the bytes is established either way.";
            case NOT_RECORDED -> "No digest was recorded for this object, so there is nothing "
                    + "to check against. That is a gap in what was captured, not a failure of "
                    + "this check.";
        };
        return new Section("content", verdict, body, limits);
    }

    /**
     * What was RECORDED about bringing this object in.
     *
     * <p>The limits text on this one is the most important in the report, and it says the thing
     * a custody chain never says on its own: an operation that did not pass through the
     * recording path leaves no row, and a missing row looks exactly like a thing that did not
     * happen. A chain of five events is not "these five things happened and nothing else did".
     */
    private Section custodySection(String repositoryId, String objectId) {
        String silenceIsNotAbsence = "This lists what was RECORDED. An operation that did not "
                + "pass through the recording path leaves no row here, and its absence looks "
                + "exactly like its not having happened — so this chain cannot be read as "
                + "'and nothing else was done'.";
        if (maintenanceStore == null) {
            return new Section("custody", Verdict.UNAVAILABLE, Map.of(),
                    "The capture boundary is not wired in this deployment, so no custody rows "
                            + "could be read. This is NOT a statement that there are none. "
                            + silenceIsNotAbsence);
        }
        List<Map<String, Object>> rows;
        try {
            rows = maintenanceStore.listCapturedForObject(repositoryId, objectId,
                    CUSTODY_ROW_LIMIT);
        } catch (Exception e) {
            logger.warn("Authenticity report could not read custody rows for {}/{}: {}",
                    repositoryId, objectId, e.getMessage());
            return new Section("custody", Verdict.UNAVAILABLE, Map.of("reason", e.getMessage()),
                    "The custody rows could not be read. " + silenceIsNotAbsence);
        }
        if (rows == null || rows.isEmpty()) {
            return new Section("custody", Verdict.ABSENT, Map.of(),
                    "No RETAINED capture row was found for this object. That has more than one "
                            + "cause: it may not have come in through the external-ingest "
                            + "boundary, it may predate that boundary, its row may have been "
                            + "purged by retention, or the recording may have failed. An empty "
                            + "result cannot tell them apart. " + silenceIsNotAbsence);
        }
        List<Map<String, Object>> events = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> event = new LinkedHashMap<>();
            // The producer's own constants, not literals. The first version guessed the names
            // (`connector`, `state`, `capturedAt`) and matched nothing but sourceObjectId, so
            // every production report showed a capture with no connector, no state and no time.
            for (String key : new String[] { CaptureIntent.FIELD_CONNECTOR_ID,
                    CaptureIntent.FIELD_CAPTURE_STATE, CaptureIntent.FIELD_SOURCE_SYSTEM,
                    CaptureIntent.FIELD_CAPTURED_AT_MS,
                    CaptureIntent.FIELD_INTENT_OPENED_AT_MS,
                    CaptureIntent.FIELD_SOURCE_OBJECT_ID, CaptureIntent.FIELD_PROCESS_TYPE }) {
                Object value = row.get(key);
                if (value != null) {
                    event.put(key, String.valueOf(value));
                }
            }
            // The recorded hashes are named, never their values' provenance re-asserted: this
            // says a hash was recorded, not that it still matches. That check is /verify-metadata.
            event.put("carriesMetadataHash", CaptureIntent.carriesAppliedHash(row));
            events.add(event);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("events", events);
        body.put("eventCount", events.size());
        String limits = silenceIsNotAbsence;
        if (events.size() >= CUSTODY_ROW_LIMIT) {
            // No silent cap. A truncated list that does not say it is truncated is read as
            // complete, which is the one reading this section must never invite.
            body.put("truncated", true);
            limits = limits + " This list is TRUNCATED at " + CUSTODY_ROW_LIMIT
                    + " rows (newest first); older rows exist and are not shown.";
        } else {
            body.put("truncated", false);
        }
        return new Section("custody", Verdict.REPORTED, body, limits);
    }

    /**
     * The evidence ledger checked against itself (P1-3).
     *
     * <p>Note what this is NOT scoped to: this object. The ledger's guarantee is over the chain,
     * so the honest section is "the chain this object's entries live in is/is not internally
     * consistent". Reporting a per-object subset would suggest the subset was independently
     * verifiable, which is the opposite of how a hash chain works.
     */
    private Section ledgerSection(String repositoryId) {
        String notIndependent = "This is the ledger checked AGAINST ITSELF. It detects "
                + "rewriting, reordering and deletion within the chain; it is independent "
                + "evidence only once its checkpoints are anchored outside this database, "
                + "which is P2 and is not done. Anything that can rewrite an entry can "
                + "recompute the chain after it.";
        if (ledgerStore == null) {
            return new Section("ledger", Verdict.UNAVAILABLE, Map.of(),
                    "The evidence ledger is not wired in this deployment, so nothing was "
                            + "checked. This is NOT a statement that the chain is intact. "
                            + notIndependent);
        }
        // The chain's domain IS the repository id — p1-3-evidence-ledger.md §3, decided so the
        // sequencer's existing per-repository CAS also settles the chain.
        String domain = repositoryId;
        List<EvidenceLedgerEntry> entries;
        long highest;
        try {
            highest = ledgerStore.highestSequence(domain);
            entries = highest < 0 ? List.of()
                    : ledgerStore.range(domain, Math.max(0, highest - LEDGER_ENTRY_LIMIT + 1),
                            highest, LEDGER_ENTRY_LIMIT);
        } catch (Exception e) {
            logger.warn("Authenticity report could not read the ledger for {}: {}", repositoryId,
                    e.getMessage());
            return new Section("ledger", Verdict.UNAVAILABLE, Map.of("reason", e.getMessage()),
                    "The ledger could not be read, so it was not checked. " + notIndependent);
        }
        if (entries == null || entries.isEmpty()) {
            return new Section("ledger", Verdict.ABSENT, Map.of("domain", domain),
                    "This repository's evidence ledger has no entries. " + notIndependent);
        }
        EvidenceChainVerifier.Report report = EvidenceChainVerifier.verify(entries);
        Map<String, Object> body = new LinkedHashMap<>(report.asMap());
        body.put("domain", domain);
        String limits = notIndependent;
        if (highest >= LEDGER_ENTRY_LIMIT) {
            body.put("truncated", true);
            limits = limits + " Only the most recent " + LEDGER_ENTRY_LIMIT + " entries were "
                    + "checked; earlier entries were NOT, and this verdict says nothing "
                    + "about them.";
        } else {
            body.put("truncated", false);
        }
        return new Section("ledger", report.intact() ? Verdict.VERIFIED : Verdict.FAILED, body,
                limits);
    }

    /**
     * Who read and changed this object.
     *
     * <p>NemakiWare's audit trail is written to an SLF4J logger, so it leaves this process and
     * this API cannot read it back. That makes the honest verdict UNAVAILABLE — not ABSENT,
     * which would say there was no audit trail, and not an empty section, which reads as the
     * same thing.
     */
    private Section accessSection() {
        return new Section("access", Verdict.UNAVAILABLE, Map.of(),
                "This repository writes its audit trail to the application log, which leaves "
                        + "this process; this API cannot read it back, so no access history is "
                        + "gathered here. That is a limit of this report, NOT a statement that "
                        + "nothing was audited — and the log itself is silent about any period "
                        + "in which auditing was disabled or its level excluded reads.");
    }

    /**
     * Copies of this record that exist in another format (P3-2).
     *
     * <p>Here because a disclosure nobody reads is not a disclosure. The recorder attaches one
     * to every duplication and the ledger entry carries only a digest, so until this section
     * existed the sentence "this is a convenience copy, not a preservation format" lived in a
     * Java enum and reached no reader. A PDF sitting beside a Word file, both with digests,
     * both in the chain, is taken for two records — which is the exact reading the disclosure
     * is written to prevent.
     *
     * <p>ABSENT rather than empty when there are none: "no copy was made" and "we could not
     * look" are different answers, and only one of them is about the record.
     */
    private Section duplicationSection(String repositoryId, String objectId) {
        if (ledgerStore == null) {
            return new Section("duplications", Verdict.UNAVAILABLE, Map.of(),
                    "The evidence ledger could not be read, so it is unknown whether copies of "
                            + "this record exist in other formats. This is NOT a statement that "
                            + "none do.");
        }
        List<EvidenceLedgerEntry> entries;
        try {
            entries = ledgerStore.findBySubject(repositoryId, objectId, LEDGER_ENTRY_LIMIT);
        } catch (RuntimeException e) {
            return new Section("duplications", Verdict.UNAVAILABLE,
                    Map.of("reason", String.valueOf(e.getMessage())),
                    "The evidence ledger could not be read, so it is unknown whether copies of "
                            + "this record exist in other formats. This is NOT a statement that "
                            + "none do.");
        }
        // The read comes back in ASCENDING sequence order with the limit applied by the view,
        // so a full result means the entries that were dropped are the LATEST ones — the
        // opposite of the ledger section, which asks for the most recent window on purpose.
        // Fixity results accumulate on every scan, so a long-lived record reaches the limit
        // without anything being wrong; and then a duplication recorded last week is simply
        // not in what we read. Answering ABSENT there would say "no copy of this record in
        // another format is recorded" with the PDF sitting beside it, which is the reading
        // this whole section exists to prevent.
        boolean truncated = entries.size() >= LEDGER_ENTRY_LIMIT;
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (EvidenceLedgerEntry entry : entries) {
            if (entry.subjectKind() != EvidenceLedgerEntry.SubjectKind.FORMAT_DUPLICATION) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sequence", entry.sequence());
            row.put("occurredAt", entry.occurredAt());
            row.put("payloadDigest", entry.payloadDigest());
            rows.add(row);
        }
        if (rows.isEmpty()) {
            if (truncated) {
                return new Section("duplications", Verdict.UNAVAILABLE,
                        Map.of("truncated", true, "entriesRead", entries.size()),
                        "The first " + LEDGER_ENTRY_LIMIT + " chain entries for this record were "
                                + "read and none of them is a duplication, but there are more "
                                + "entries than that and the ones NOT read are the most recent. "
                                + "So this is not an answer about whether copies exist.");
            }
            return new Section("duplications", Verdict.ABSENT, Map.of(),
                    "No copy of this record in another format is recorded in the chain. Copies "
                            + "made outside the path that records them would not appear here.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        // The disclosure FIRST, before the rows. A reader skimming a block about derived copies
        // has to meet "these are not the record" before the identifiers, not after them.
        body.put("disclosure", DUPLICATION_DISCLOSURE);
        List<Map<String, Object>> present = renditionsNow(repositoryId, objectId);
        if (!present.isEmpty()) {
            // Where these came from, BEFORE the values. They are read from the rendition rows,
            // which the chain does not cover — so a reader must not take them for chained
            // facts, and the sentence saying so has to arrive first.
            body.put("renditionsNowSource", RENDITIONS_NOW_SOURCE);
            body.put("renditionsNow", present);
        }
        body.put("duplications", rows);
        body.put("count", rows.size());
        body.put("truncated", truncated);
        String limits = "These are copies of this record made in another format, as recorded in "
                + "the evidence chain. The chain entry commits to a digest only, so the "
                + "converter and the exact losses are not repeated here — the disclosure above "
                + "states what every such copy is and is not. A copy made by a path that does "
                + "not record duplications would not be listed, so this is NOT a complete "
                + "inventory of derived copies.";
        if (truncated) {
            limits = limits + " Only the first " + LEDGER_ENTRY_LIMIT + " chain entries for this "
                    + "record were read, and the ones beyond that are the most recent, so copies "
                    + "made later than the ones listed are missing from this list.";
        }
        return new Section("duplications", Verdict.REPORTED, body, limits);
    }

    /**
     * What every recorded duplication is, said once where a reader will meet it.
     *
     * <h2>Why it does not name a format</h2>
     *
     * <p>The first version said "this product converts to PDF without requesting or validating
     * a PDF/A profile". That was true while the only recorded path produced PDF. It stopped
     * being true the moment the REST rendition stacks were wired, because two of them also
     * produce SVG — and a reader looking at a drawing's SVG copy would have been told about
     * PDF/A, which is not a thing an SVG could have had. Fixing the per-converter disclosure
     * in the recorder did not reach here: this section is built from the LEDGER, and the entry
     * carries a digest.
     *
     * <p>So the honest form names no format at all. What the entry does is <b>commit</b> to the
     * converter and the target format — they are inputs to the digest — without carrying them
     * in the clear. A party who holds those values can check this entry against them; this
     * report cannot recover them, and saying so is better than picking the one that used to be
     * the only possibility.
     *
     * <p>Putting them in the clear would mean a second field in {@link EvidenceLedgerEntry},
     * which changes what the entry hash covers and invalidates every stored hash. Adding it
     * OUTSIDE the hash would be worse than not having it: an annotation the chain does not
     * commit to, presented in a report next to things it does.
     */
    /**
     * Where the in-the-clear rendition facts come from, and what that costs them.
     *
     * <p>The chain commits to the archival-profile verdict — it is an input to the duplication
     * entry's digest — and carries only that digest. So the verdict cannot be read out of the
     * chain, and for a while this report could not tell anyone what the product had found. It
     * is readable here because it is ALSO stored on the rendition row, in the clear.
     *
     * <p>That row is mutable, and — this is the part the first wording got wrong — a reader of
     * this report CANNOT check it against the chain. The entry's digest is taken over the
     * converter, the source and produced digests, the media type, the verdict and the actor.
     * This section shows the verdict, the flavour and the media type; the entry shows a digest.
     * The converter, both digests and the actor are in neither, so there is nothing to
     * recompute from. Only a party that still holds the inputs as they were at write time can
     * do the comparison.
     *
     * <p>Saying that precisely matters more than it sounds: "checkable by anyone holding both"
     * invites a reader to treat the row as chained evidence on the strength of a check they
     * cannot actually perform. The alternative — putting the verdict into the entry — changes
     * what the entry hash covers and invalidates every stored hash.
     */
    static final String RENDITIONS_NOW_SOURCE =
            "These are read from the rendition rows AS THEY STAND NOW, not from the evidence "
                    + "chain. The chain COMMITS to each copy's archival-profile verdict — it is "
                    + "an input to the corresponding entry's digest — but does not carry it in "
                    + "the clear, and the other inputs to that digest (the converter, the source "
                    + "and produced digests, the actor) are not in this report either. So NOTHING "
                    + "HERE CAN BE CHECKED AGAINST THE CHAIN FROM THIS REPORT ALONE: only a "
                    + "party still holding the values recorded at write time can do that. Until "
                    + "then these are a claim by this repository, and a row edited after the fact "
                    + "would appear here exactly like an honest one.";

    static final String DUPLICATION_DISCLOSURE =
            "These are CONVENIENCE COPIES, not preservation formats and not additional records. "
                    + "What a copy preserves depends on the converter and on the format it was "
                    + "produced in: layout, fonts, comments, tracked changes, embedded objects, "
                    + "CAD layers and diagram structure are among the things that may not "
                    + "survive, and no output format produced here is requested or validated "
                    + "against an archival profile. The chain entry COMMITS to which converter "
                    + "and which format — they are inputs to its digest — but does not carry "
                    + "them in the clear, so this report cannot tell you which applied to any "
                    + "one copy below — and the same is true of whether a copy was checked "
                    + "against an archival profile such as PDF/A, and of what that check found. "
                    + "This report therefore does NOT say a copy failed such a check, and does "
                    + "NOT say one passed. The ORIGINAL is unchanged and remains the record.";

    /**
     * The derived copies this object carries now, with what was found about each.
     *
     * <p>Deliberately separate from the chained entries above: those are what was recorded,
     * these are what is here. Merging them would let a mutable row borrow the chain's standing.
     */
    private List<Map<String, Object>> renditionsNow(String repositoryId, String objectId) {
        if (contentService == null) {
            return List.of();
        }
        List<jp.aegif.nemaki.model.Rendition> renditions;
        try {
            renditions = contentService.getRenditions(repositoryId, objectId);
        } catch (RuntimeException e) {
            logger.warn("The renditions of {}/{} could not be read for the report: {}",
                    repositoryId, objectId, e.getMessage());
            return List.of();
        }
        if (renditions == null) {
            return List.of();
        }
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (jp.aegif.nemaki.model.Rendition rendition : renditions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("renditionId", rendition.getId());
            row.put("mediaType", rendition.getMimetype());
            // Absent is NOT "it failed": nothing was checked, which is a statement about this
            // deployment's configuration and not about the copy.
            row.put("archivalProfileChecked", rendition.getPdfaOutcome() != null);
            row.put("archivalProfileOutcome", rendition.getPdfaOutcome());
            row.put("archivalProfileFlavour", rendition.getPdfaFlavour());
            rows.add(row);
        }
        return rows;
    }

    private Section versionsSection(Content content) {
        if (!(content instanceof Document document)) {
            return new Section("versions", Verdict.ABSENT, Map.of(),
                    "Only documents carry a version series.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("versionSeriesId", document.getVersionSeriesId());
        body.put("versionLabel", document.getVersionLabel());
        body.put("latestVersion", document.isLatestVersion());
        return new Section("versions", Verdict.REPORTED, body,
                "The version series as this repository holds it. This lists WHICH versions "
                        + "exist; it does not say what changed between them, and it does not "
                        + "establish that no version was removed.");
    }

    private Section environmentSection() {
        String selfReported = "The fingerprint below is REPORTED BY the deployment it "
                + "describes, so accepting it because this API returned it is circular: "
                + "compare it against a digest computed independently from the approved "
                + "artefact.";
        if (binaryDigest == null) {
            return new Section("environment", Verdict.UNAVAILABLE, Map.of(),
                    "The distribution digest is not wired on this node, so this report cannot "
                            + "say which binary produced it. " + selfReported);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            body.put("binaryDigest", binaryDigest.digest());
            body.put("domain", LineageBinaryDigest.DOMAIN);
        } catch (Exception e) {
            return new Section("environment", Verdict.UNAVAILABLE, Map.of(),
                    "The running distribution could not be measured (" + e.getMessage() + "), "
                            + "so this report cannot say which binary produced it. "
                            + selfReported);
        }
        return new Section("environment", Verdict.REPORTED, body, selfReported);
    }
}
