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

import jp.aegif.nemaki.rest.purview.journal.LineageProcessType;

/**
 * The PREMIS event vocabulary, and which of our facts map onto which term (P3-1 §4).
 *
 * <h2>The vocabulary is not ours and is not guessed</h2>
 *
 * <p>These are the fifty terms of the Library of Congress preservation event type vocabulary,
 * read from {@code id.loc.gov} rather than recalled: only the ones this product can currently
 * justify are declared here, each with its authoritative code and label. The code is what goes
 * into {@code eventType} and the URI is what an archivist checks it against.
 *
 * <p><b>This enum is the crosswalk, which is a wider thing than what is emitted today.</b> Be
 * exact about the difference, because "we map it" and "we write it" are not the same claim:
 *
 * <ul>
 *   <li><b>Emitted now</b>: {@link #CAPTURE} and {@link #INGESTION} (from capture rows),
 *       {@link #INFORMATION_PACKAGE_CREATION} (this export).</li>
 *   <li><b>Mapped, not yet emitted</b>: {@link #REPLICATION} — assigned to the archive process
 *       types, which capture rows do not currently carry; {@link #DELETION} — the disposition
 *       trail (P3-3) records these in the evidence ledger and the exporter does not read it
 *       back yet; {@link #MESSAGE_DIGEST_CALCULATION} and {@link #FIXITY_CHECK} — PREMIS
 *       requires {@code eventDateTime} and neither fact carries a time the repository can
 *       stand behind (§4).</li>
 * </ul>
 *
 * <p>A term with no defensible mapping at all is not in this list. An enum entry that maps
 * nothing is a vocabulary claim nobody made, and this project has already found three of those
 * in its own ledger.
 *
 * <h2>What the mapping does and does not settle</h2>
 *
 * <p>It settles which term we write. It does <b>not</b> settle that an archivist would agree —
 * {@link #CAPTURE} versus {@link #INGESTION} for an external import is a genuine judgement, and
 * the reasoning is written down in {@code docs/design/p3-1-eark-sip.md} so a reader can disagree
 * with it rather than having to reverse-engineer it.
 *
 * <p>Design: {@code docs/design/p3-1-eark-sip.md} §4.
 */
public enum PremisEventType {

    /**
     * Content recorded into a digital object from a source outside the repository.
     *
     * <p>Chosen over {@link #INGESTION} for external imports because that is what happens: a
     * Slack message or a mail is a live thing in another system, and what NemakiWare does is fix
     * it as an object. {@code ingestion} is the SIP-to-AIP acceptance an archive performs, which
     * this is not. An archivist may read it the other way; §4 records the argument.
     */
    CAPTURE("cap", "capture"),

    /** Content added to the repository from a filesystem or an upload — a deposit, not a pull. */
    INGESTION("ing", "ingestion"),

    /** A digest computed over stored bytes. What P1-1 does at capture. */
    MESSAGE_DIGEST_CALCULATION("mes", "message digest calculation"),

    /** A stored digest re-checked against the bytes. What P1-2 does. */
    FIXITY_CHECK("fix", "fixity check"),

    /** A bit-identical copy made elsewhere. What the cold move writes before it deletes. */
    REPLICATION("rep", "replication"),

    /**
     * Content removed. What the MOVE-mode cold transition does to the local copy, recorded by
     * P3-3 <em>before</em> it happens.
     */
    DELETION("del", "deletion"),

    /** A submission package built. What this exporter does. */
    INFORMATION_PACKAGE_CREATION("ipc", "information package creation");

    /** The vocabulary these codes come from, written into the package beside every term. */
    public static final String VOCABULARY_URI =
            "http://id.loc.gov/vocabulary/preservation/eventType";

    private final String code;
    private final String label;

    PremisEventType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** The three-letter authoritative code, e.g. {@code cap}. */
    public String code() {
        return code;
    }

    /** The authoritative label, e.g. {@code capture}. */
    public String label() {
        return label;
    }

    /** The term's own URI, which is what makes the code checkable. */
    public String uri() {
        return VOCABULARY_URI + "/" + code;
    }

    /**
     * The PREMIS term for one of our process types, or {@code null} when there is not one.
     *
     * <p>Null is a real answer and is not filled in. Several process types are deliveries and
     * synchronisations that no preservation event describes; writing {@code modification} for
     * them because the column would otherwise be empty is how a crosswalk stops meaning
     * anything. The caller omits the event rather than inventing it.
     */
    public static PremisEventType forProcessType(LineageProcessType processType) {
        if (processType == null) {
            return null;
        }
        return switch (processType) {
            // Pulled from a live external system and fixed as an object.
            case EXTERNAL_NOTE_IMPORT, EXTERNAL_ATTACHMENT_IMPORT, BUSINESS_RECORD_IMPORT,
                 CHAT_MESSAGE_IMPORT, CHAT_ATTACHMENT_IMPORT, MAIL_MESSAGE_IMPORT,
                 MAIL_ATTACHMENT_IMPORT, FILE_SHARE_SYNC_DOWNLOAD, CLOUD_SYNC_DOWNLOAD,
                 GENERIC_EXTERNAL_INGEST -> CAPTURE;
            // Deposited by a person or a filesystem sweep.
            case IMPORT_FILESYSTEM, IMPORT_UPLOADED -> INGESTION;
            // A copy is made; the local deletion that may follow is its own event (P3-3).
            case ARCHIVE_LOCAL, ARCHIVE_COLD -> REPLICATION;
            // Deliveries out. `exporting` exists in the vocabulary, but these are ongoing
            // synchronisations to a working system rather than a preservation act, and calling
            // them exports would put a preservation event in the record for a file copy.
            case CLOUD_SYNC_UPLOAD, FILE_SHARE_SYNC_UPLOAD, EXPORT_FILESYSTEM,
                 EXPORT_ZIP_FOLDER, EXPORT_SELECTED_OBJECTS -> null;
        };
    }
}
