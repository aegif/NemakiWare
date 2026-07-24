package jp.aegif.nemaki.patch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ibm.cloud.cloudant.v1.model.Document;

import jp.aegif.nemaki.epoch.AclEpochCounterService;

/**
 * Pure unit tests for {@link Patch_AclEpochCounter#resolveAfterCreateConflict} — the
 * post-{@code 409} decision. A create {@code 409} is NOT proof the counter exists (a
 * tombstone conflict 409s with no live counter), so success is recorded only when the
 * re-GET returns a live, strictly-valid counter. Both branches are exercised here
 * deterministically (no real race needed); the live tombstone path is in
 * {@code Patch_AclEpochCounterIT}.
 */
public class Patch_AclEpochCounterTest {

    private static Document doc(Object type, Object value, String rev) {
        Document d = new Document();
        d.setId("acl-epoch-counter::r");
        d.setRev(rev);
        Map<String, Object> props = new LinkedHashMap<>();
        if (type != null) props.put("type", type);
        if (value != null) props.put("value", value);
        d.setProperties(props);
        return d;
    }

    @Test
    public void conflictWithNoLiveCounterFails() {
        // 409 → re-GET returns null (tombstone conflict): must throw so PatchHistory is
        // NOT recorded and the seed retries next startup.
        assertThrows(IllegalStateException.class,
                () -> Patch_AclEpochCounter.resolveAfterCreateConflict(null, "r"));
    }

    @Test
    public void conflictWithLiveValidCounterSucceeds() {
        // 409 → re-GET returns a live, valid counter (a concurrent create won): success.
        Document live = doc(AclEpochCounterService.DOC_TYPE, 0L, "1-abc");
        assertDoesNotThrow(() -> Patch_AclEpochCounter.resolveAfterCreateConflict(live, "r"));
    }

    @Test
    public void conflictWithCorruptCounterFails() {
        // 409 → re-GET returns a doc with a fractional value: corruption must throw.
        Document corrupt = doc(AclEpochCounterService.DOC_TYPE, 1.5d, "1-abc");
        assertThrows(IllegalStateException.class,
                () -> Patch_AclEpochCounter.resolveAfterCreateConflict(corrupt, "r"));
    }

    @Test
    public void conflictWithWrongTypeCounterFails() {
        Document wrong = doc("notACounter", 0L, "1-abc");
        assertThrows(IllegalStateException.class,
                () -> Patch_AclEpochCounter.resolveAfterCreateConflict(wrong, "r"));
    }
}
