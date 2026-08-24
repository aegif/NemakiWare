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
package jp.aegif.nemaki.evidence.validity;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * When this deployment considers an algorithm no longer sound (P2-3 §2).
 *
 * <h2>It declares; it does not judge</h2>
 *
 * <p>Whether SHA-1 is safe is not something a document management system can determine. What it
 * can do is hold the operator's declaration and answer against it mechanically. The bundled
 * defaults were written by reading public guidance (NIST SP 800-131A, BSI TR-02102) and are a
 * STARTING POINT, not an assurance from us — {@link #DEFAULTS_ARE_NOT_A_WARRANTY} says so in the
 * words that go into the report.
 *
 * <h2>Why RFC 4998 makes this necessary</h2>
 *
 * <p>RFC 4998 defines two renewal operations and then says, in as many words, that watching for
 * the moment renewal becomes necessary is out of scope. So adopting ERS does not give you the
 * part that notices. Without it a renewal implementation simply never fires — and renewal cannot
 * be applied retroactively (see {@link RenewalNeed}), so never firing is not a delay, it is a
 * permanent loss.
 *
 * <h2>An unlisted algorithm is not a sound one</h2>
 *
 * <p>{@link Soundness#UNKNOWN} rather than a permissive default. A table that treats anything it
 * does not recognise as sound reports everything as sound the moment somebody forgets to update
 * it, which is precisely when it is being relied on.
 *
 * <p>Design: {@code docs/design/p2-3-long-term-validity.md}.
 */
public class AlgorithmRegistry {

    /** The sentence that travels with any report built on this table. */
    public static final String DEFAULTS_ARE_NOT_A_WARRANTY =
            "These dates are this deployment's DECLARATION, seeded from public guidance (NIST SP "
            + "800-131A, BSI TR-02102). They are not an assurance from NemakiWare that an "
            + "algorithm is safe before its date or broken after it. Cryptographic soundness is "
            + "watched outside this system — RFC 4998 says so explicitly — and this table is "
            + "only how that watch is written down.";

    /** Three values, because a migration needs somewhere to stand. */
    public enum Soundness {
        /** Declared sound. */
        SOUND,
        /**
         * In its migration window: do not choose it for new evidence, but evidence already
         * resting on it is not thereby void. Collapsing this into either neighbour removes the
         * period in which a plan can be made.
         */
        DEPRECATED,
        /** Past its declared end date. */
        UNSOUND,
        /** Not in the table. NOT an assertion either way. */
        UNKNOWN
    }

    /**
     * One declaration.
     *
     * @param deprecatedFrom when the migration window opens, or null for "not yet"
     * @param unsoundFrom    when it closes, or null for "no end declared"
     */
    public record Declaration(String algorithm, LocalDate deprecatedFrom, LocalDate unsoundFrom,
                              String source) {

        public Declaration {
            if (algorithm == null || algorithm.isBlank()) {
                throw new IllegalArgumentException("a declaration must name an algorithm");
            }
            if (deprecatedFrom != null && unsoundFrom != null
                    && unsoundFrom.isBefore(deprecatedFrom)) {
                // Otherwise the window is negative and the algorithm becomes unsound before it
                // is deprecated, which no operator meant to write.
                throw new IllegalArgumentException("algorithm '" + algorithm + "' would be "
                        + "unsound (" + unsoundFrom + ") before it is deprecated ("
                        + deprecatedFrom + ")");
            }
        }

        public Soundness at(LocalDate when) {
            if (unsoundFrom != null && !when.isBefore(unsoundFrom)) {
                return Soundness.UNSOUND;
            }
            if (deprecatedFrom != null && !when.isBefore(deprecatedFrom)) {
                return Soundness.DEPRECATED;
            }
            return Soundness.SOUND;
        }
    }

    private final Map<String, Declaration> declarations = new LinkedHashMap<>();

    /**
     * The bundled starting point.
     *
     * <p>Dates are the operator's to change. What is NOT theirs to change is the shape: every
     * entry names where its date came from, so an auditor can ask the same question of the same
     * source rather than of us.
     */
    public static AlgorithmRegistry withDefaults() {
        AlgorithmRegistry registry = new AlgorithmRegistry();
        registry.declare(new Declaration("MD5", LocalDate.of(2005, 1, 1),
                LocalDate.of(2010, 1, 1), "collisions demonstrated 2004; NIST withdrew it"));
        registry.declare(new Declaration("SHA-1", LocalDate.of(2011, 1, 1),
                LocalDate.of(2030, 1, 1),
                "NIST SP 800-131A: disallowed for digital signature generation"));
        registry.declare(new Declaration("SHA-224", LocalDate.of(2030, 1, 1), null,
                "NIST SP 800-131A transition schedule"));
        // No end date for the SHA-2 family that is actually in use. A guessed one would be a
        // number this project invented, and every report would carry it as though it meant
        // something.
        registry.declare(new Declaration("SHA-256", null, null,
                "no deprecation declared; review against current guidance"));
        registry.declare(new Declaration("SHA-384", null, null,
                "no deprecation declared; review against current guidance"));
        registry.declare(new Declaration("SHA-512", null, null,
                "no deprecation declared; review against current guidance"));
        registry.declare(new Declaration("RSA-1024", LocalDate.of(2011, 1, 1),
                LocalDate.of(2014, 1, 1), "NIST SP 800-131A: disallowed below 2048 bits"));
        registry.declare(new Declaration("RSA-2048", null, null,
                "no deprecation declared; review against current guidance"));
        return registry;
    }

    public void declare(Declaration declaration) {
        declarations.put(normalise(declaration.algorithm()), declaration);
    }

    /** The declaration for this algorithm, or null when it is not in the table. */
    public Declaration declarationFor(String algorithm) {
        return algorithm == null ? null : declarations.get(normalise(algorithm));
    }

    public Soundness soundnessOf(String algorithm, LocalDate when) {
        Declaration declaration = declarationFor(algorithm);
        if (declaration == null) {
            // Not "probably fine". The table is the whole basis for an answer here, and having
            // no entry is a fact about the table, not about the algorithm.
            return Soundness.UNKNOWN;
        }
        return declaration.at(when);
    }

    public Map<String, Declaration> all() {
        return Map.copyOf(declarations);
    }

    /**
     * {@code sha-1}, {@code SHA1} and {@code SHA-1} are one algorithm.
     *
     * <p>Not cosmetic: an OID-derived name and a hand-typed one differ exactly this much, and a
     * lookup miss here returns UNKNOWN — which reads as "we could not say" when the truth is "we
     * said it, under a different spelling".
     */
    private static String normalise(String algorithm) {
        return algorithm.trim().toUpperCase(Locale.ROOT).replace("_", "-")
                .replaceFirst("^SHA(\\d)", "SHA-$1");
    }
}
