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

    private ContentService contentService;
    private AuthenticityReportAssembler reportAssembler;

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
    public record Exported(Path sip, int withheldPropertyCount, List<String> notes) {}

    /** Raised instead of writing a package that would be read as complete when it is not. */
    public static class ExportRefusedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ExportRefusedException(String message) {
            super(message);
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

            IPRepresentation representation = new IPRepresentation("rep1");
            representation.addFile(new IPFile(payload));
            sip.addRepresentation(representation);

            Path buildDir = Files.createDirectories(workDir.resolve("build"));
            Path built = sip.build(new ZipWriteStrategyFactory().create(buildDir),
                    sipId(repositoryId, objectId));
            if (built == null || !Files.exists(built)) {
                throw new ExportRefusedException("the SIP was not written");
            }
            if (withheld > 0) {
                notes.add(withheld + " propert(y/ies) the disclosure table marks INTERNAL_ONLY "
                        + "are NOT in this package. A receiver reading it as a complete record "
                        + "of what was captured would be wrong.");
            }
            logger.info("Exported {}/{} as an E-ARK SIP ({} propert(y/ies) withheld)",
                    repositoryId, objectId, withheld);
            return new Exported(built, withheld, List.copyOf(notes));
        } catch (ExportRefusedException e) {
            throw e;
        } catch (Exception e) {
            // Wrapped, never swallowed: a half-built package on disk that nobody was told about
            // is the one outcome worse than no package.
            throw new ExportRefusedException("the SIP for " + repositoryId + "/" + objectId
                    + " could not be built: " + e.getMessage());
        }
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
                        || "withheldInternalOnlyCount".equals(entry.getKey())
                        || "includesPersonalData".equals(entry.getKey())) {
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
                    && section.content().get("withheldInternalOnlyCount") instanceof Number n) {
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

    /** A file name safe on every filesystem a receiving archive might unpack onto. */
    private static String fileName(Document document, AttachmentNode attachment) {
        String candidate = attachment.getName() != null && !attachment.getName().isBlank()
                ? attachment.getName() : document.getName();
        if (candidate == null || candidate.isBlank()) {
            return "content.bin";
        }
        // Separators and traversal removed BEFORE anything else touches it: this string comes
        // from a source system, and it ends up as a path inside an archive somebody else
        // unpacks.
        String cleaned = candidate.replace('\\', '/');
        cleaned = cleaned.substring(cleaned.lastIndexOf('/') + 1);
        cleaned = cleaned.replaceAll("[^A-Za-z0-9._-]", "_");
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        return cleaned.isBlank() ? "content.bin" : cleaned;
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

    private Path writeReport(Path workDir, AuthenticityReport report) throws IOException {
        Path metadataDir = Files.createDirectories(workDir.resolve("metadata"));
        Path file = metadataDir.resolve("nemaki-authenticity-report.json");
        String json = report == null
                ? "{\"status\":\"unavailable\",\"message\":\"The authenticity report could not "
                        + "be assembled on the node that built this package. Nothing here "
                        + "states what the descriptive metadata does and does not establish.\"}"
                // The sanctioned construction. A bare `new ObjectMapper()` would silently take
                // Jackson 3's defaults (JacksonMigrationBoundaryTest bans it by scanning the
                // source), and this file is read by another organisation's software — the one
                // place a quiet formatting change is hardest to notice.
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
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    // XML 1.0 forbids most control characters outright; a source system that
                    // put one in a title must not make the whole package unparseable.
                    if (c == '\t' || c == '\n' || c == '\r' || c >= 0x20) {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
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
