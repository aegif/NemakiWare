package jp.aegif.nemaki.rest.ingest.mail;

import java.util.List;

/**
 * Which warnings of a mail import say that a PART of the mail was not imported.
 *
 * <p>The mail import ({@code CanonicalImportServiceImpl.executeMailImport}) imports the message,
 * then its raw .eml and each attachment as documents of their own, each linked to the message; it
 * reports a part it could not import or link as a warning and still answers success — or skipped,
 * when the message was imported before and the missing parts were tried again. Those warnings are
 * written by the import in these forms: {@code Attachment '…' import failed: …} /
 * {@code Attachment '…' failed: …}, {@code Raw .eml preservation …}, and the link refusals
 * {@code Relationship failed: …} / {@code Relationship not authorised: …} /
 * {@code the relationship was not created: …} — every refusal the import's link step writes.
 *
 * <p>Its other warnings are about the evidence it records — capture time, provenance, the message
 * metadata, decorations refused on an object that already carried them, a link created without its
 * duplicate check — and do not mean a part of the mail is missing. Matching every warning
 * dead-lettered each of those as a mail that could not be replayed; matching only
 * {@code attachment} missed the link failures (review, P1), and the first list of link failures
 * missed the refusal for a folder that could not be read.
 */
public final class MailImportWarnings {

    private MailImportWarnings() {}

    /** Whether a warning says a part of the mail — an attachment, its link, the raw .eml — was not imported. */
    public static boolean saysAPartIsMissing(String warning) {
        return warning != null && (warning.startsWith("Attachment '")
                || warning.startsWith("Raw .eml preservation")
                || warning.startsWith("Relationship failed")
                || warning.startsWith("Relationship not authorised")
                || warning.startsWith("the relationship was not created"));
    }

    /** The warnings that say a part of the mail was not imported. */
    public static List<String> missingParts(List<String> warnings) {
        return warnings == null ? List.of() : warnings.stream().filter(MailImportWarnings::saysAPartIsMissing).toList();
    }
}
