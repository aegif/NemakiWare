package jp.aegif.nemaki.cmis.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link ObjectServiceImpl#computeRangeAwareLength}.
 *
 * Background: getContentStream sliced the body for range requests
 * (AttachmentNode.getInputStream) but declared the FULL attachment length on
 * the returned ContentStream. AtomPub then sent Content-Length = full size
 * with a truncated body, so clients failed with "Premature EOF"
 * (TCK ContentRangesTest, live-reproduced: 36 advertised for a 33-byte body).
 */
public class ObjectServiceImplRangeLengthTest {

    private static final long TOTAL = 36; // "0123456789abcdefghijklmnopqrstuvwxyz"

    private static BigInteger bi(long v) {
        return BigInteger.valueOf(v);
    }

    @Test
    public void fullStreamWhenNoRange() {
        assertEquals(TOTAL, ObjectServiceImpl.computeRangeAwareLength(TOTAL, null, null));
    }

    @Test
    public void offsetOnly() {
        // TCK case {offset=3, length=null} -> 33 remaining bytes
        assertEquals(33, ObjectServiceImpl.computeRangeAwareLength(TOTAL, bi(3), null));
    }

    @Test
    public void lengthOnly() {
        // TCK case {offset=null, length=12} -> exactly 12 bytes
        assertEquals(12, ObjectServiceImpl.computeRangeAwareLength(TOTAL, null, bi(12)));
    }

    @Test
    public void offsetAndLengthWithinBounds() {
        // TCK case {offset=5, length=17} -> 17 bytes
        assertEquals(17, ObjectServiceImpl.computeRangeAwareLength(TOTAL, bi(5), bi(17)));
    }

    @Test
    public void lengthClampedToRemaining() {
        // TCK case {offset=9, length=123} -> clamped to 27 remaining bytes
        assertEquals(27, ObjectServiceImpl.computeRangeAwareLength(TOTAL, bi(9), bi(123)));
    }

    @Test
    public void offsetAtOrPastEndYieldsEmpty() {
        assertEquals(0, ObjectServiceImpl.computeRangeAwareLength(TOTAL, bi(TOTAL), null));
        assertEquals(0, ObjectServiceImpl.computeRangeAwareLength(TOTAL, bi(TOTAL + 10), bi(5)));
    }

    @Test
    public void negativeOffsetTreatedAsZero() {
        assertEquals(TOTAL, ObjectServiceImpl.computeRangeAwareLength(TOTAL, bi(-4), null));
        assertEquals(10, ObjectServiceImpl.computeRangeAwareLength(TOTAL, bi(-4), bi(10)));
    }

    @Test
    public void negativeLengthYieldsEmpty() {
        assertEquals(0, ObjectServiceImpl.computeRangeAwareLength(TOTAL, bi(3), bi(-1)));
    }

    @Test
    public void unknownTotalStaysUnknown() {
        assertEquals(-1, ObjectServiceImpl.computeRangeAwareLength(-1, bi(3), bi(12)));
    }
}
