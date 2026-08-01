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
package jp.aegif.nemaki.rest.purview.journal;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What each {@link LineageProcessType} may connect, as whole shapes rather than two allowlists.
 *
 * <h2>Why a shape and not "allowed input kinds" + "allowed output kinds"</h2>
 *
 * <p>{@code IMPORT_UPLOADED} has two producers with genuinely different shapes: the zip/ACP upload
 * in {@code ImportExportResource} imports an artifact into a folder, and
 * {@code IngestLineageEmitter} falls back to it for an external asset becoming a document when the
 * request carries no archetype. Two independent allowlists would accept the union of the sides —
 * an upload artifact producing a document, or an external asset producing a folder — and neither
 * of those is anything a producer emits or a sink can project.
 *
 * <p>So the table holds <em>alternatives</em>, and an event has to match one of them completely.
 *
 * <h2>These are the v2 shapes, not a transcription of what v1 emits today</h2>
 *
 * <p>Several v1 producers are known to be wrong in ways increment A-2 fixes, and copying their
 * present shape here would freeze the defect:
 *
 * <ul>
 *   <li>{@code EXPORT_ZIP_FOLDER} and {@code EXPORT_SELECTED_OBJECTS} currently emit
 *       <b>no output at all</b> — a lineage Process with inputs and nothing produced. The design's
 *       §3 gives both a {@code nemaki_export_artifact} output.</li>
 *   <li>{@code IMPORT_UPLOADED} and {@code IMPORT_FILESYSTEM} currently use the {@code upload://}
 *       and {@code file://} strings the design retires, in place of
 *       {@code nemaki_import_artifact}.</li>
 *   <li>{@code ARCHIVE_COLD} uses {@code cold://}, now a {@code COLD_STORAGE} endpoint.</li>
 * </ul>
 *
 * <p>Nothing here breaks those producers: no production path validates against this table until
 * A-2 rewrites them. The table states where they are going.
 *
 * <h2>Chunking</h2>
 *
 * <p>Cardinality applies <b>per chunk</b>. A selected-objects export of 5,000 documents is split
 * by §2's chunking into several events, each publishing as its own Process, and each of those
 * still has one export artifact as its output.
 */
public final class LineageProcessShape {

    private LineageProcessShape() {
    }

    /** Unbounded upper bound, spelled out so a reader does not have to recognise the constant. */
    private static final int MANY = Integer.MAX_VALUE;

    /**
     * How many endpoints of which kinds one side of a shape accepts.
     *
     * @param kinds the kinds allowed on this side; an endpoint of any other kind fails
     * @param min   the fewest endpoints this side accepts
     * @param max   the most; {@link #MANY} for unbounded
     */
    public record Side(Set<EndpointKind> kinds, int min, int max) {

        public Side {
            if (kinds == null || kinds.isEmpty()) {
                throw new IllegalArgumentException("a side must allow at least one kind");
            }
            if (min < 0 || max < min) {
                throw new IllegalArgumentException("invalid cardinality [" + min + ", " + max + "]");
            }
            kinds = Set.copyOf(kinds);
        }

        boolean accepts(List<LineageEndpoint> endpoints) {
            if (endpoints.size() < min || endpoints.size() > max) {
                return false;
            }
            for (LineageEndpoint endpoint : endpoints) {
                if (!kinds.contains(endpoint.kind())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            String bound = max == MANY ? min + "..n" : (min == max ? String.valueOf(min)
                    : min + ".." + max);
            return new java.util.TreeSet<>(kinds.stream().map(Enum::name).toList()) + "×" + bound;
        }
    }

    /** One complete input/output pairing a process type accepts. */
    public record Shape(Side inputs, Side outputs) {

        public Shape {
            if (inputs == null || outputs == null) {
                throw new IllegalArgumentException("both sides of a shape are required");
            }
        }

        boolean accepts(List<LineageEndpoint> in, List<LineageEndpoint> out) {
            return inputs.accepts(in) && outputs.accepts(out);
        }

        @Override
        public String toString() {
            return inputs + " -> " + outputs;
        }
    }

    private static Side one(EndpointKind kind) {
        return new Side(Set.of(kind), 1, 1);
    }

    private static Side oneOrMore(EndpointKind... kinds) {
        return new Side(new LinkedHashSet<>(List.of(kinds)), 1, MANY);
    }

    private static Shape shape(Side inputs, Side outputs) {
        return new Shape(inputs, outputs);
    }

    /**
     * Every process type, and the shapes it accepts.
     *
     * <p>{@code FILE_SHARE_SYNC_UPLOAD} maps to an empty list rather than being absent: absent
     * would be indistinguishable from "somebody added a constant and forgot the rule", which is
     * exactly what {@code everyProcessTypeHasARule} exists to catch. Empty says "reserved, and
     * deliberately unconstructible" (§1, §10).
     */
    private static final Map<LineageProcessType, List<Shape>> SHAPES = shapes();

    private static Map<LineageProcessType, List<Shape>> shapes() {
        Map<LineageProcessType, List<Shape>> table = new EnumMap<>(LineageProcessType.class);

        // An archived object moving out to cold storage, and a live document being archived.
        table.put(LineageProcessType.ARCHIVE_COLD,
                List.of(shape(one(EndpointKind.ARCHIVE), one(EndpointKind.COLD_STORAGE))));
        table.put(LineageProcessType.ARCHIVE_LOCAL,
                List.of(shape(one(EndpointKind.CMIS_DOCUMENT), one(EndpointKind.ARCHIVE))));

        // Cloud drive, in both directions.
        table.put(LineageProcessType.CLOUD_SYNC_UPLOAD,
                List.of(shape(one(EndpointKind.CMIS_DOCUMENT), one(EndpointKind.CLOUD_OBJECT))));
        table.put(LineageProcessType.CLOUD_SYNC_DOWNLOAD,
                List.of(shape(one(EndpointKind.CLOUD_OBJECT), one(EndpointKind.CMIS_DOCUMENT))));

        // Import: an artifact (the upload, or the source directory) becomes a folder tree.
        table.put(LineageProcessType.IMPORT_FILESYSTEM,
                List.of(shape(one(EndpointKind.IMPORT_ARTIFACT), one(EndpointKind.CMIS_FOLDER))));
        // The two-shape type. Neither cross-pairing is legal.
        table.put(LineageProcessType.IMPORT_UPLOADED, List.of(
                shape(one(EndpointKind.IMPORT_ARTIFACT), one(EndpointKind.CMIS_FOLDER)),
                shape(one(EndpointKind.EXTERNAL_ASSET), one(EndpointKind.CMIS_DOCUMENT))));

        // Export: repository content becomes an artifact.
        table.put(LineageProcessType.EXPORT_FILESYSTEM,
                List.of(shape(one(EndpointKind.CMIS_FOLDER), one(EndpointKind.EXPORT_ARTIFACT))));
        table.put(LineageProcessType.EXPORT_ZIP_FOLDER,
                List.of(shape(one(EndpointKind.CMIS_FOLDER), one(EndpointKind.EXPORT_ARTIFACT))));
        // The only type whose inputs are legitimately mixed and unbounded: the user picks them.
        table.put(LineageProcessType.EXPORT_SELECTED_OBJECTS, List.of(shape(
                oneOrMore(EndpointKind.CMIS_DOCUMENT, EndpointKind.CMIS_FOLDER),
                one(EndpointKind.EXPORT_ARTIFACT))));

        // External ingest. All of these are one external asset becoming one document; they differ
        // in what the source system is, which travels as the endpoint's sourceSystem attribute
        // rather than as a distinct shape.
        for (LineageProcessType ingest : List.of(
                LineageProcessType.EXTERNAL_NOTE_IMPORT,
                LineageProcessType.EXTERNAL_ATTACHMENT_IMPORT,
                LineageProcessType.BUSINESS_RECORD_IMPORT,
                LineageProcessType.CHAT_ATTACHMENT_IMPORT,
                LineageProcessType.FILE_SHARE_SYNC_DOWNLOAD,
                LineageProcessType.MAIL_MESSAGE_IMPORT,
                LineageProcessType.MAIL_ATTACHMENT_IMPORT)) {
            table.put(ingest, List.of(
                    shape(one(EndpointKind.EXTERNAL_ASSET), one(EndpointKind.CMIS_DOCUMENT))));
        }

        // Reserved: no producer may create one. See LineageProcessType's javadoc.
        table.put(LineageProcessType.FILE_SHARE_SYNC_UPLOAD, List.of());

        return java.util.Collections.unmodifiableMap(table);
    }

    /** The shapes a type accepts, in declaration order. Empty means the type is reserved. */
    public static List<Shape> shapesOf(LineageProcessType processType) {
        if (processType == null) {
            throw new IllegalArgumentException("processType must not be null");
        }
        List<Shape> shapes = SHAPES.get(processType);
        if (shapes == null) {
            // Only reachable if a constant was added without a rule. Fail rather than wave it
            // through: an unconstrained process type is one nothing downstream can project.
            throw new IllegalStateException("no shape rule for processType=" + processType
                    + " — add one to LineageProcessShape");
        }
        return shapes;
    }

    /** True if this type is declared but deliberately not producible. */
    public static boolean isReserved(LineageProcessType processType) {
        return shapesOf(processType).isEmpty();
    }

    /**
     * @throws IllegalArgumentException if the endpoints match none of the type's shapes, or if the
     *                                  type is reserved
     */
    public static void validate(LineageProcessType processType, List<LineageEndpoint> inputs,
                                List<LineageEndpoint> outputs) {
        List<Shape> shapes = shapesOf(processType);
        List<LineageEndpoint> in = inputs == null ? List.of() : inputs;
        List<LineageEndpoint> out = outputs == null ? List.of() : outputs;

        if (shapes.isEmpty()) {
            throw new IllegalArgumentException("processType=" + processType + " is reserved and"
                    + " must not be produced — see the design's §1 and §10");
        }
        for (Shape shape : shapes) {
            if (shape.accepts(in, out)) {
                return;
            }
        }
        throw new IllegalArgumentException("processType=" + processType + " does not accept "
                + describe(in) + " -> " + describe(out) + "; accepted shapes: " + shapes);
    }

    private static String describe(List<LineageEndpoint> endpoints) {
        if (endpoints.isEmpty()) {
            return "[]";
        }
        Map<EndpointKind, Integer> counts = new EnumMap<>(EndpointKind.class);
        for (LineageEndpoint endpoint : endpoints) {
            counts.merge(endpoint.kind(), 1, Integer::sum);
        }
        // Kinds and counts only. A qualified name can carry an external stable key, which the
        // design forbids putting in a log.
        return counts.toString();
    }
}
