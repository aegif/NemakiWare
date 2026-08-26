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
package jp.aegif.nemaki.rest.eark;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.evidence.AuthenticityReport;
import jp.aegif.nemaki.evidence.AuthenticityReportAssembler;
import jp.aegif.nemaki.evidence.EvidenceLedgerEntry;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;

import org.roda_project.commons_ip.utils.METSEnums;
import org.roda_project.commons_ip2.model.IPAgent;
import org.roda_project.commons_ip2.model.IPAgentNoteTypeEnum;
import org.roda_project.commons_ip2.model.IPContentInformationType;
import org.roda_project.commons_ip2.model.IPContentType;
import org.roda_project.commons_ip2.model.IPDescriptiveMetadata;
import org.roda_project.commons_ip2.model.IPFile;
import org.roda_project.commons_ip2.model.IPMetadata;
import org.roda_project.commons_ip2.model.IPRepresentation;
import org.roda_project.commons_ip2.model.MetadataType;
import org.roda_project.commons_ip2.model.SIP;
import org.roda_project.commons_ip2.model.impl.eark.EARKSIP;
import org.roda_project.commons_ip2.model.impl.eark.out.writers.factory.ZipWriteStrategyFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One stored record, packaged as an E-ARK SIP (P3-1 §3).
 *
 * <h2>An export is a disclosure, and this one cannot be taken back</h2>
 *
 * <p>The authenticity report already withholds the properties the disclosure table marks
 * {@code INTERNAL_ONLY} — chat participants, the acting user — unless an admin asks for them.
 * The same boundary applies here and matters more: a report is a response to one caller, and a
 * SIP is <b>handed to another organisation's archive</b>. It is copied, ingested, replicated
 * and preserved on purpose. There is no un-sending it. So the default withholds, the caller has
 * to opt in explicitly, and the number withheld is reported either way — a package that quietly
 * dropped fields would be read as a complete record of what was captured.
 *
 * <h2>The package carries what it does not establish</h2>
 *
 * <p>Descriptive metadata alone reads as stronger than it is: an identifier, a title and a date
 * look like assertions this repository is standing behind, when most of them are what the source
 * system said at capture and were never checked. So the authenticity report — verdicts and per
 * section limits included — travels inside the package as other-metadata. Whoever receives it
 * gets the claims and the caveats in the same envelope, which is the only arrangement where the
 * caveats survive.
 *
 * <p>Design: {@code docs/design/p3-1-eark-sip.md}.
 */
@Component
public class EarkSipExporter {

    private static final Logger logger = LoggerFactory.getLogger(EarkSipExporter.class);

    /**
     * The CSIP version generated AND validated against.
     *
     * <p>One constant for both on purpose. {@code EARKSIP}'s three-argument constructor defaults
     * to 2.1.0 and writes {@code mets/@PROFILE=...-v2-1-0.xml}, which a 2.2.0 validator rejects
     * outright (SIP2, MUST). Generating under one version and checking under another is a way
     * to fail that says nothing useful.
     */
    public static final String CSIP_VERSION = "2.2.0";

    /** The organisation agent's role. MUST be CREATOR with a TYPE that is not OTHER (SIP15). */
    private static final String SUBMITTER_ROLE = "CREATOR";

    /** What the evidence package does and does not let a third party conclude. */
    static final String EVIDENCE_PACKAGE_LIMITS =
            "The audit path proves that the entry named here was in the span its checkpoint "
                    + "sealed, given the checkpoint. It does NOT prove the checkpoint itself "
                    + "was not rewritten — that needs the checkpoint hash to exist somewhere "
                    + "outside this repository's database, which is what an external anchor is "
                    + "for. It also says nothing about whether the capture was complete or its "
                    + "metadata true: the chain fixes WHAT WAS RECORDED and WHEN, not whether "
                    + "the record is accurate.";

    private ContentService contentService;
    private AuthenticityReportAssembler reportAssembler;
    private jp.aegif.nemaki.evidence.EvidenceLedgerStore ledgerStore;
    private jp.aegif.nemaki.evidence.EvidenceLedgerService ledgerService;

    @Autowired(required = false)
    public void setLedgerStore(jp.aegif.nemaki.evidence.EvidenceLedgerStore ledgerStore) {
        this.ledgerStore = ledgerStore;
    }

    @Autowired(required = false)
    public void setLedgerService(jp.aegif.nemaki.evidence.EvidenceLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @Autowired(required = false)
    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    @Autowired(required = false)
    public void setReportAssembler(AuthenticityReportAssembler reportAssembler) {
        this.reportAssembler = reportAssembler;
    }

    /** What the caller asked for. */
    public record Options(boolean includeInternalOnly, String submittingOrganisation) {

        /** Withholds personal data; names the deployment rather than an organisation. */
        public static Options withholdingPersonalData() {
            return new Options(false, "NemakiWare deployment");
        }
    }

    /** What was produced, and what it does not contain. */
    public record Exported(Path sip, int withheldPropertyCount, List<String> notes,
            Validation validation) {}

    /**
     * What the reference validator said about the package that was just built.
     *
     * <p>Three outcomes, not two. {@code ran=false} means the validator could not be run at
     * all — and "we could not check" is not "it is fine", which is the substitution this whole
     * layer exists to refuse. A package built on a node where validation could not run is
     * still handed over, because refusing would make an unrelated local failure look like a
     * defect in the record; but it is handed over saying so.
     */
    public record Validation(boolean ran, boolean valid, int errors, int warnings,
            String detail) {

        static Validation notRun(String why) {
            return new Validation(false, false, 0, 0, why);
        }

        /** What a receiver must not read into it. */
        public String limits() {
            if (!ran) {
                return "This package was NOT checked against the CSIP " + CSIP_VERSION
                        + " reference validator on this node (" + detail + "). That is a "
                        + "statement about this node, not about the package: it may well be "
                        + "valid, and nothing here says it is not.";
            }
            return "The CSIP " + CSIP_VERSION + " reference validator accepted this package's "
                    + "STRUCTURE and METS. It says nothing about whether the record inside is "
                    + "genuine, complete, or what its metadata claims — those are the "
                    + "authenticity report's business, with its own limits.";
        }
    }

    /** Raised instead of writing a package that would be read as complete when it is not. */
    public static class ExportRefusedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ExportRefusedException(String message) {
            super(message);
        }

        /** Keeps the cause. A refusal reading "could not be built: null" wastes a day. */
        public ExportRefusedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Packages one object.
     *
     * @param workDir a directory this method may write into; the caller owns its lifetime
     * @return the built SIP and what was left out of it
     * @throws ExportRefusedException when a package could only be produced by leaving out
     *         something a receiver would assume was there
     */
    public Exported export(String repositoryId, String objectId, Options options, Path workDir) {
        if (contentService == null) {
            throw new ExportRefusedException("the content service is not wired on this node, so "
                    + "nothing can be packaged");
        }
        Content content = contentService.getContent(repositoryId, objectId);
        if (content == null) {
            throw new ExportRefusedException("object " + objectId + " could not be read in "
                    + repositoryId + ". This is NOT a statement that it does not exist.");
        }
        if (!(content instanceof Document document)) {
            throw new ExportRefusedException("object " + objectId + " is not a document, so it "
                    + "has no content to package");
        }

        List<String> notes = new ArrayList<>();
        try {
            Path payload = writePayload(repositoryId, document, workDir);
            AuthenticityReport report = report(repositoryId, objectId, options, notes);

            SIP sip = new EARKSIP(sipId(repositoryId, objectId), IPContentType.getMIXED(),
                    IPContentInformationType.getMIXED(), CSIP_VERSION);
            sip.addCreatorSoftwareAgent("NemakiWare", nemakiVersion());
            // SIP15 (MUST): who submitted has to be a WHO. addCreatorSoftwareAgent writes
            // TYPE="OTHER" OTHERTYPE="SOFTWARE", which the rule does not accept.
            sip.addAgent(new IPAgent(options.submittingOrganisation(), SUBMITTER_ROLE, null,
                    METSEnums.CreatorType.ORGANIZATION, null, repositoryId,
                    IPAgentNoteTypeEnum.IDENTIFICATIONCODE));

            Map<String, String> disclosable = disclosableIdentity(report);
            int withheld = withheldCount(report);
            sip.addDescriptiveMetadata(new IPDescriptiveMetadata(
                    new IPFile(writeDublinCore(workDir, repositoryId, objectId, document,
                            disclosable)),
                    new MetadataType(MetadataType.MetadataTypeEnum.DC), null));

            // The report, verdicts and limits and all, in the same envelope as the claims.
            sip.addOtherMetadata(new IPMetadata(
                    new IPFile(writeReport(workDir, report)),
                    new MetadataType(MetadataType.MetadataTypeEnum.OTHER)));

            // PREMIS, as PRESERVATION metadata rather than "other": a receiving archive looks
            // for provenance there, and a standard vocabulary filed under a non-standard place
            // is only marginally better than not writing it.
            String packagedAt = java.time.Instant.now().toString();
            sip.addPreservationMetadata(new IPMetadata(
                    new IPFile(writePremis(workDir, repositoryId, objectId, report, packagedAt)),
                    new MetadataType(MetadataType.MetadataTypeEnum.PREMIS)));

            // The evidence package: the inclusion proof that ties THIS record to the chain,
            // plus the checkpoint it was sealed under. Without the proof, a package carrying a
            // checkpoint would only say "this repository's chain was sealed at some point",
            // which says nothing about the document beside it — decoration, not evidence.
            Map<String, Object> evidence = evidencePackage(repositoryId, objectId, notes);
            sip.addOtherMetadata(new IPMetadata(
                    new IPFile(writeEvidencePackage(workDir, evidence)),
                    new MetadataType(MetadataType.MetadataTypeEnum.OTHER)));

            IPRepresentation representation = new IPRepresentation("rep1");
            representation.addFile(new IPFile(payload));
            sip.addRepresentation(representation);

            Path buildDir = Files.createDirectories(workDir.resolve("build"));
            Path built = sip.build(new ZipWriteStrategyFactory().create(buildDir),
                    sipId(repositoryId, objectId));
            if (built == null || !Files.exists(built)) {
                throw new ExportRefusedException("the SIP was not written");
            }
            // Validated HERE, not only in a test. A package is a disclosure to another
            // organisation and cannot be un-sent, so "our builder produces valid packages"
            // being true in CI is not the same as this package being valid. The reference
            // validator ships inside commons-ip2 and runs in this process.
            Validation validation = validate(built);
            if (validation.ran() && !validation.valid()) {
                throw new ExportRefusedException("the CSIP " + CSIP_VERSION + " reference "
                        + "validator rejected this package, so it was not returned: "
                        + validation.detail());
            }
            if (!validation.ran()) {
                notes.add(validation.limits());
            } else if (validation.warnings() > 0) {
                notes.add("The reference validator accepted this package with "
                        + validation.warnings() + " warning(s). " + validation.limits());
            }
            if (withheld > 0) {
                notes.add(withheld + " propert(y/ies) the disclosure table marks INTERNAL_ONLY "
                        + "are NOT in this package. A receiver reading it as a complete record "
                        + "of what was captured would be wrong.");
            }
            if (options.includeInternalOnly()) {
                // The guarantee has to run both ways. Without this, "nothing was withheld" and
                // "everything was deliberately included" produce an identical Exported and an
                // identical dc.xml, and the one that carries personal data is the one nobody
                // is told about.
                notes.add("This package was built with includeInternalOnly=true: it CONTAINS "
                        + "the properties the disclosure table marks as personal data. Once it "
                        + "has been handed over there is no recall.");
            }
            logger.info("Exported {}/{} as an E-ARK SIP ({} propert(y/ies) withheld)",
                    repositoryId, objectId, withheld);
            return new Exported(built, withheld, List.copyOf(notes), validation);
        } catch (ExportRefusedException e) {
            throw e;
        } catch (Exception e) {
            // Wrapped, never swallowed: a half-built package on disk that nobody was told about
            // is the one outcome worse than no package.
            throw new ExportRefusedException("the SIP for " + repositoryId + "/" + objectId
                    + " could not be built: " + (e.getMessage() == null
                            ? e.getClass().getName() : e.getMessage()), e);
        }
    }

    /**
     * Runs the reference validator, and distinguishes "rejected" from "could not check".
     *
     * <p>A validator that throws is not a verdict. It could be a local problem — a temporary
     * file it cannot write, a JVM without the parser it wants — and turning that into a refusal
     * would make an unrelated local failure read as a defect in the record. So the exception
     * becomes {@code ran=false} and travels with the package as a stated gap.
     *
     * <p>A validator that RUNS and rejects is a verdict, and that one refuses. There is no
     * option to skip it: an option to hand over a package the validator rejects is an option
     * that gets used, and the receiving end has no way to tell such a package from a checked
     * one.
     */
    private Validation validate(Path sip) {
        java.io.ByteArrayOutputStream reportOut = new java.io.ByteArrayOutputStream();
        try {
            org.roda_project.commons_ip2.validator.reporter.ValidationReportOutputJson report =
                    new org.roda_project.commons_ip2.validator.reporter.ValidationReportOutputJson(
                            sip, reportOut);
            org.roda_project.commons_ip2.validator.EARKSIPValidator validator =
                    new org.roda_project.commons_ip2.validator.EARKSIPValidator(report,
                            CSIP_VERSION);
            boolean valid = validator.validate(CSIP_VERSION);
            String detail = valid
                    ? report.getSuccess() + " check(s) passed"
                    : truncate(reportOut.toString(java.nio.charset.StandardCharsets.UTF_8));
            return new Validation(true, valid, report.getErrors(), report.getWarnings(), detail);
        } catch (Exception | LinkageError e) {
            String why = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
            logger.warn("The CSIP reference validator could not be run for {}: {}", sip, why);
            return Validation.notRun(why);
        }
    }

    /**
     * Enough of the validator's report to act on, and not so much that it buries the refusal.
     *
     * <p>The whole report is JSON of every check including the passes. A refusal message is
     * read in a log line or an API error body; a 200 kB one is read by nobody, which makes the
     * reason for the refusal effectively absent.
     */
    private static String truncate(String report) {
        String text = report == null ? "" : report.strip();
        if (text.length() <= 4000) {
            return text;
        }
        return text.substring(0, 4000) + " ... [" + (text.length() - 4000)
                + " more characters of the validator's report were not included here]";
    }

    private AuthenticityReport report(String repositoryId, String objectId, Options options,
            List<String> notes) {
        if (reportAssembler == null) {
            // Not fatal, but not silent either. Without the report the package carries claims
            // and none of the caveats, and a receiver cannot tell that from a clean bill.
            notes.add("The authenticity report is not available on this node, so this package "
                    + "carries descriptive metadata with NO statement of what it does and does "
                    + "not establish.");
            return null;
        }
        return reportAssembler.assemble(repositoryId, objectId,
                java.time.Instant.now().toString(), options.includeInternalOnly());
    }

    /**
     * The identity attributes that may leave the building.
     *
     * <p>Read off the report rather than the object, so the export cannot disagree with what the
     * report would have shown: the disclosure decision is made in ONE place. When the report is
     * unavailable this is empty, and the package says so instead of falling back to reading the
     * aspects directly — that fallback is exactly how a second, laxer disclosure rule appears.
     */
    private Map<String, String> disclosableIdentity(AuthenticityReport report) {
        Map<String, String> values = new LinkedHashMap<>();
        if (report == null) {
            return values;
        }
        for (AuthenticityReport.Section section : report.sections()) {
            if (!"identity".equals(section.name())) {
                continue;
            }
            for (Map.Entry<String, Object> entry : section.content().entrySet()) {
                if (entry.getValue() == null
                        || AuthenticityReport.IDENTITY_BOOKKEEPING_KEYS.contains(
                                entry.getKey())) {
                    continue;
                }
                values.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        return values;
    }

    private int withheldCount(AuthenticityReport report) {
        if (report == null) {
            return 0;
        }
        for (AuthenticityReport.Section section : report.sections()) {
            if ("identity".equals(section.name())
                    && section.content().get(AuthenticityReport.WITHHELD_COUNT_KEY)
                            instanceof Number n) {
                return n.intValue();
            }
        }
        return 0;
    }

    private Path writePayload(String repositoryId, Document document, Path workDir)
            throws IOException {
        String attachmentId = document.getAttachmentNodeId();
        if (attachmentId == null || attachmentId.isBlank()) {
            throw new ExportRefusedException("document " + document.getId() + " has no "
                    + "attachment, so there is nothing to package. A SIP with an empty "
                    + "representation would be read as a record whose content was preserved.");
        }
        AttachmentNode attachment = contentService.getAttachment(repositoryId, attachmentId);
        if (attachment == null) {
            throw new ExportRefusedException("the attachment of " + document.getId()
                    + " could not be read. This is NOT a statement that it is missing.");
        }
        Path payloadDir = Files.createDirectories(workDir.resolve("payload"));
        Path payload = payloadDir.resolve(fileName(document, attachment));
        try (InputStream in = attachment.getInputStream()) {
            if (in == null) {
                throw new ExportRefusedException("the attachment of " + document.getId()
                        + " produced no stream");
            }
            Files.copy(in, payload);
        }
        return payload;
    }

    /**
     * Windows refuses these names, with or without an extension, on every drive.
     *
     * <p>An archive unpacked onto Windows would fail on the file, not on the package, which is
     * the kind of failure that gets blamed on the receiver.
     */
    private static final java.util.Set<String> RESERVED_DEVICE_NAMES = java.util.Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    /** Bytes, not chars: this is about NAME_MAX (255 on ext4/APFS), which counts bytes. */
    private static final int MAX_FILE_NAME_BYTES = 200;

    /**
     * A file name for a payload inside a package somebody else unpacks.
     *
     * <p>The first version stripped everything outside {@code [A-Za-z0-9._-]}, which is safe and
     * <b>destroys every Japanese file name in a Japanese ECM</b>: 議事録.txt became ___.txt, and
     * 議事録.txt and 報告書.txt became the same file. Non-ASCII is kept. What is removed is what
     * actually breaks something:
     *
     * <ul>
     *   <li>path separators and traversal — this ends up as a path inside an archive</li>
     *   <li>characters no Windows filesystem accepts, and control characters</li>
     *   <li>reserved device names, trailing dots and spaces (Windows silently drops them)</li>
     *   <li>length beyond {@value #MAX_FILE_NAME_BYTES} bytes, keeping the extension</li>
     * </ul>
     *
     * <p>Normalised to NFC so that the same name typed two ways is the same file.
     */
    private static String fileName(Document document, AttachmentNode attachment) {
        String candidate = attachment.getName() != null && !attachment.getName().isBlank()
                ? attachment.getName() : document.getName();
        if (candidate == null || candidate.isBlank()) {
            return "content.bin";
        }
        String cleaned = java.text.Normalizer.normalize(candidate,
                java.text.Normalizer.Form.NFC);
        // Separators FIRST, before anything else can reintroduce one.
        cleaned = cleaned.replace('\\', '/');
        cleaned = cleaned.substring(cleaned.lastIndexOf('/') + 1);
        // Characters Windows rejects, plus control characters. Colon also covers the "C:name"
        // alternate-data-stream shape once the separators are gone.
        cleaned = cleaned.replaceAll("[\\u0000-\\u001f\\u007f<>:\"|?*]", "_");
        // Leading dots would make it hidden, or be "." / ".." outright.
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        // Trailing dots and spaces: Windows drops them on extraction, so two names that differ
        // only there would collide after the fact rather than here.
        while (cleaned.endsWith(".") || cleaned.endsWith(" ")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        cleaned = truncateToBytes(cleaned, MAX_FILE_NAME_BYTES);
        if (cleaned.isBlank()) {
            return "content.bin";
        }
        String stem = cleaned.contains(".")
                ? cleaned.substring(0, cleaned.indexOf('.')) : cleaned;
        if (RESERVED_DEVICE_NAMES.contains(stem.toUpperCase(java.util.Locale.ROOT))) {
            return "_" + cleaned;
        }
        return cleaned;
    }

    /** Truncates on a character boundary so the result is still valid UTF-8. */
    private static String truncateToBytes(String value, int maxBytes) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        String extension = value.contains(".")
                ? value.substring(value.lastIndexOf('.')) : "";
        if (extension.getBytes(StandardCharsets.UTF_8).length > 32) {
            extension = "";
        }
        int room = maxBytes - extension.getBytes(StandardCharsets.UTF_8).length;
        StringBuilder kept = new StringBuilder();
        int used = 0;
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            int width = new String(Character.toChars(codePoint))
                    .getBytes(StandardCharsets.UTF_8).length;
            if (used + width > room) {
                break;
            }
            kept.appendCodePoint(codePoint);
            used += width;
            i += Character.charCount(codePoint);
        }
        return kept + extension;
    }

    private Path writeDublinCore(Path workDir, String repositoryId, String objectId,
            Document document, Map<String, String> identity) throws IOException {
        StringBuilder xml = new StringBuilder(512);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<dc:record xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
                .append("xmlns:dcterms=\"http://purl.org/dc/terms/\">\n");
        element(xml, "dc:identifier", repositoryId + "/" + objectId);
        element(xml, "dc:title", document.getName());
        element(xml, "dc:source", identity.get("nemaki:sourceSystem"));
        element(xml, "dc:type", identity.get("nemaki:sourceObjectType"));
        element(xml, "dcterms:created", document.getCreated() == null ? null
                : document.getCreated().toInstant().toString());
        // Every disclosable identity attribute as its own dc:relation, keyed by property id.
        // Not folded into prose: a receiver that wants one of them should not have to parse a
        // sentence, and a sentence would also let two attributes merge into a claim neither
        // makes on its own.
        for (Map.Entry<String, String> entry : identity.entrySet()) {
            element(xml, "dc:relation", entry.getKey() + "=" + entry.getValue());
        }
        xml.append("</dc:record>\n");
        Path metadataDir = Files.createDirectories(workDir.resolve("metadata"));
        Path file = metadataDir.resolve("dc.xml");
        Files.writeString(file, xml.toString(), StandardCharsets.UTF_8);
        return file;
    }

    /**
     * The PREMIS document for this object.
     *
     * <p>The content digest comes from the report's content section rather than being
     * recomputed: the point is to state what this repository RECORDED, and a fresh computation
     * would silently paper over the case where the two disagree — which is exactly the case a
     * receiving archive most needs to see.
     */
    private Path writePremis(Path workDir, String repositoryId, String objectId,
            AuthenticityReport report, String packagedAt) throws IOException {
        String digest = null;
        String algorithm = null;
        if (report != null) {
            for (AuthenticityReport.Section section : report.sections()) {
                if ("content".equals(section.name())) {
                    Object recorded = section.content().get("recordedDigest");
                    digest = recorded == null ? null : String.valueOf(recorded);
                    Object algo = section.content().get("algorithm");
                    algorithm = algo == null ? null : String.valueOf(algo);
                }
            }
        }
        String xml = PremisWriter.toXml(repositoryId + "/" + objectId, digest, algorithm,
                PremisWriter.eventsFor(report, packagedAt), "NemakiWare");
        Path metadataDir = Files.createDirectories(workDir.resolve("metadata"));
        Path file = metadataDir.resolve("premis.xml");
        Files.writeString(file, xml, StandardCharsets.UTF_8);
        return file;
    }

    /**
     * What a third party needs to check the chain claim without this server.
     *
     * <p>Assembled from the ledger, not from the report: the report RENDERS a ledger section,
     * and what a verifier needs is the audit path itself. When any of it is missing the package
     * says so in {@code limits} rather than omitting the file — an absent evidence package and
     * one that could not be built look identical from the outside, and only one of them means
     * the record was never chained.
     */
    private Map<String, Object> evidencePackage(String repositoryId, String objectId,
            List<String> notes) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("repositoryId", repositoryId);
        evidence.put("objectId", objectId);
        evidence.put("chainedEntries", List.of());
        evidence.put("inclusionProof", null);
        evidence.put("limits", EVIDENCE_PACKAGE_LIMITS);
        if (ledgerStore == null || ledgerService == null) {
            evidence.put("status", "unavailable");
            evidence.put("message", "the evidence ledger is not wired on the node that built "
                    + "this package. This is NOT a statement that the record was never chained.");
            notes.add("This package carries no inclusion proof: the evidence ledger was not "
                    + "reachable when it was built.");
            return evidence;
        }
        List<Map<String, Object>> chained = new ArrayList<>();
        List<EvidenceLedgerEntry> entries;
        try {
            // Looked up by the OBJECT id, which finds the entries whose subject is the object:
            // fixity passes, format duplications, custody receipts.
            //
            // It does NOT find the capture entry. That one's subject is the capture intent id
            // (EvidenceLedgerRecorder appends under intent.intentId()), and this method is
            // given an object. The comment here used to say "both are tried"; only one was, so
            // a package could report "not-chained" for a record whose capture IS in the chain.
            // Saying which lookup was performed is the honest fix — following the intent id
            // needs the capture row, which is a different store and a wider change.
            entries = new ArrayList<>(ledgerStore.findBySubject(repositoryId, objectId, 50));
        } catch (RuntimeException e) {
            evidence.put("status", "error");
            evidence.put("message", "the evidence ledger could not be read (" + e.getMessage()
                    + "). This is NOT a statement that the record was never chained.");
            notes.add("This package carries no inclusion proof: the evidence ledger could not "
                    + "be read when it was built.");
            return evidence;
        }
        if (entries.isEmpty()) {
            evidence.put("status", "not-chained");
            evidence.put("message", "no ledger entry names this OBJECT. Note what that does and "
                    + "does not cover: the capture entry for an externally ingested record is "
                    + "filed under its capture intent id, not under the object, so a record "
                    + "whose capture IS in the chain still reports nothing here. The chain also "
                    + "only holds what was written to it from the day the producer shipped, "
                    + "with no back-fill. None of this says the record is not genuine.");
            return evidence;
        }
        for (EvidenceLedgerEntry entry : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sequence", entry.sequence());
            row.put("subjectKind", entry.subjectKind().name());
            row.put("payloadDigest", entry.payloadDigest());
            row.put("occurredAt", entry.occurredAt());
            row.put("entryHash", entry.entryHash());
            row.put("prevEntryHash", entry.prevEntryHash());
            chained.add(row);
        }
        evidence.put("chainedEntries", chained);
        evidence.put("status", "success");
        // The proof for the FIRST entry: the capture. Later entries about the same object are
        // listed above but not proved individually — a package with fifty audit paths in it
        // would be no more checkable and much harder to read.
        evidence.put("inclusionProof",
                ledgerService.inclusionProof(repositoryId, entries.get(0).sequence()));
        return evidence;
    }

    private Path writeEvidencePackage(Path workDir, Map<String, Object> evidence)
            throws IOException {
        Path metadataDir = Files.createDirectories(workDir.resolve("metadata"));
        Path file = metadataDir.resolve("nemaki-evidence.json");
        Files.writeString(file, jp.aegif.nemaki.config.ObjectMapperFactory
                .createDefaultObjectMapper().writeValueAsString(evidence),
                StandardCharsets.UTF_8);
        return file;
    }

    private Path writeReport(Path workDir, AuthenticityReport report) throws IOException {
        Path metadataDir = Files.createDirectories(workDir.resolve("metadata"));
        Path file = metadataDir.resolve("nemaki-authenticity-report.json");
        String json = report == null
                ? "{\"status\":\"unavailable\",\"message\":\"The authenticity report could not "
                        + "be assembled on the node that built this package. Nothing here "
                        + "states what the descriptive metadata does and does not establish.\"}"
                // The sanctioned construction. Constructing a mapper bare would silently take
                // Jackson 3's defaults, and this file is read by another organisation's
                // software — the one place a quiet formatting change is hardest to notice.
                // (The banned expression is deliberately not spelled out here: the guard
                // scans SOURCE TEXT, so writing it even in a comment trips it. That is the
                // guard working, and I tripped it once already writing this line.)
                : jp.aegif.nemaki.config.ObjectMapperFactory.createDefaultObjectMapper()
                        .writeValueAsString(report.asMap());
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private static void element(StringBuilder xml, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        xml.append("  <").append(name).append('>').append(escapeXml(value))
                .append("</").append(name).append(">\n");
    }

    /** Values come from source systems; none of them may be able to close an element. */
    static String escapeXml(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            // A well-formed pair is a legal supplementary character and is kept whole; a lone
            // half is not encodable as UTF-8 and is dropped rather than allowed to fail the
            // write with "Input length = 1".
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                    out.append(c).append(value.charAt(i + 1));
                    i++;
                }
                continue;
            }
            if (Character.isLowSurrogate(c)) {
                continue;
            }
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    if (isLegalXml10(c)) {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /**
     * Whether a char may appear in an XML 1.0 document at all.
     *
     * <p>Wider than "not a control character", which is where the first version stopped: it let
     * <b>U+FFFE and U+FFFF</b> through, and both make the written {@code dc.xml} unparseable at
     * the far end — the exact outcome the escaping exists to prevent, reached by the escaping.
     * Unpaired surrogates go too; they cannot be encoded as UTF-8 and would otherwise fail the
     * whole export at the file write, with a message about input length.
     *
     * <p>Per XML 1.0 §2.2: tab, LF, CR, U+0020..U+D7FF, U+E000..U+FFFD, U+10000..U+10FFFF.
     * Surrogate code units are legal only as a pair, which is handled by the caller checking
     * each unit — a lone one of either half is dropped.
     */
    static boolean isLegalXml10(char c) {
        return c == '\t' || c == '\n' || c == '\r'
                || (c >= 0x20 && c <= 0xD7FF)
                || (c >= 0xE000 && c <= 0xFFFD);
    }

    private static String sipId(String repositoryId, String objectId) {
        return ("nemaki-" + repositoryId + "-" + objectId).replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private static String nemakiVersion() {
        String version = EarkSipExporter.class.getPackage() == null ? null
                : EarkSipExporter.class.getPackage().getImplementationVersion();
        return version == null ? "unknown" : version;
    }
}
