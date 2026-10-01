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
package jp.aegif.nemaki.verifier;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The Merkle construction of {@code evidence-profile-v1.md} §8.
 *
 * <p><b>This is not RFC 6962.</b> The leaf/node domain separation is the same idea, but the
 * nodes hash CONCATENATED HEX rather than raw bytes. Dropping in an RFC 6962 implementation —
 * which is the obvious thing for a third party to do — produces different roots for every tree
 * of more than one leaf, and the spec says so in bold for that reason.
 */
public final class Merkle {

    private Merkle() {
    }

    /** {@code hashLeaf(v)} — §8. A null value is hashed as the empty string. */
    public static String hashLeaf(String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x00);
        out.writeBytes((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        return Canonical.hex(Canonical.sha256(out.toByteArray()));
    }

    /** {@code hashNode(l, r)} — §8. The arguments are HEX STRINGS and are concatenated as such. */
    public static String hashNode(String left, String right) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x01);
        out.writeBytes(((left == null ? "" : left) + (right == null ? "" : right))
                .getBytes(StandardCharsets.UTF_8));
        return Canonical.hex(Canonical.sha256(out.toByteArray()));
    }

    /**
     * Folds a leaf list into a root — §8.
     *
     * <p>An odd element is CARRIED UP, not duplicated. Duplicating it is the other common
     * convention and produces a different root; it is also the source of RFC 6962's
     * second-preimage note, which does not apply here for that reason.
     *
     * @return null for an empty list. A tree over nothing has no root, and returning the hash
     *         of the empty input would be a root that verifies against no entry at all
     */
    public static String root(List<String> leafValues) {
        if (leafValues == null || leafValues.isEmpty()) {
            return null;
        }
        List<String> level = leafValues.stream().map(Merkle::hashLeaf).toList();
        while (level.size() > 1) {
            List<String> next = new java.util.ArrayList<>();
            int i = 0;
            while (i + 1 < level.size()) {
                next.add(hashNode(level.get(i), level.get(i + 1)));
                i += 2;
            }
            if (i < level.size()) {
                next.add(level.get(i));
            }
            level = next;
        }
        return level.get(0);
    }

    /** One step of an audit path. */
    public record Step(String siblingHash, boolean siblingIsLeft) {
    }

    /**
     * Whether {@code steps} carries {@code entryHash} up to {@code expectedRoot} — §8.
     *
     * <p>A zero-step path is a legitimate proof for a tree of ONE leaf, so it is evaluated
     * rather than refused; what it must not do is pass for a larger tree, and it does not,
     * because the leaf hash is then not the root.
     */
    public static boolean verifies(String entryHash, List<Step> steps, String expectedRoot) {
        if (steps == null || expectedRoot == null) {
            return false;
        }
        String current = hashLeaf(entryHash);
        for (Step step : steps) {
            current = step.siblingIsLeft()
                    ? hashNode(step.siblingHash(), current)
                    : hashNode(current, step.siblingHash());
        }
        return expectedRoot.equals(current);
    }
}
