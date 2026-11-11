package org.openstreetmap.josm.plugins.cog.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Merging of small byte-ranges into larger block requests. */
class RangePlannerTest {

    @Test
    void mergesAdjacentRanges() {
        List<RangePlanner.Block> blocks = RangePlanner.plan(
                List.of(new long[]{100, 50}, new long[]{150, 50}), 8, 1 << 20);
        assertEquals(1, blocks.size());
        assertEquals(100, blocks.get(0).offset);
        assertEquals(100, blocks.get(0).length);
        assertEquals(List.of(0, 1), blocks.get(0).members);
    }

    @Test
    void splitsBeyondGap() {
        List<RangePlanner.Block> blocks = RangePlanner.plan(
                List.of(new long[]{100, 50}, new long[]{170, 50}), 8, 1 << 20);
        assertEquals(2, blocks.size());
        assertEquals(100, blocks.get(0).offset);
        assertEquals(50, blocks.get(0).length);
        assertEquals(170, blocks.get(1).offset);
    }

    @Test
    void respectsBlockCap() {
        List<RangePlanner.Block> blocks = RangePlanner.plan(
                List.of(new long[]{0, 600}, new long[]{600, 600}), 8, 1000);
        assertEquals(2, blocks.size());
    }

    @Test
    void oversizedRangeOwnsItsBlock() {
        List<RangePlanner.Block> blocks = RangePlanner.plan(
                List.of(new long[]{0, 5000}, new long[]{5000, 10}), 8, 1000);
        assertEquals(2, blocks.size());
        assertEquals(5000, blocks.get(0).length);
        assertEquals(List.of(0), blocks.get(0).members);
        assertEquals(List.of(1), blocks.get(1).members);
    }

    @Test
    void sortsUnorderedInputAndKeepsMemberIndices() {
        List<RangePlanner.Block> blocks = RangePlanner.plan(
                List.of(new long[]{500, 10}, new long[]{100, 10}, new long[]{110, 10}), 8, 1 << 20);
        assertEquals(2, blocks.size());
        assertEquals(100, blocks.get(0).offset);
        assertEquals(20, blocks.get(0).length);
        assertEquals(List.of(1, 2), blocks.get(0).members);
        assertEquals(List.of(0), blocks.get(1).members);
    }

    @Test
    void dropsEmptyRanges() {
        List<RangePlanner.Block> blocks = RangePlanner.plan(
                List.of(new long[]{5, 0}, new long[]{10, 4}), 8, 1000);
        assertEquals(1, blocks.size());
        assertEquals(10, blocks.get(0).offset);
        assertEquals(List.of(1), blocks.get(0).members);
    }

    @Test
    void coversEveryInputExactlyOnce() {
        List<long[]> input = List.of(
                new long[]{700, 33}, new long[]{10, 64}, new long[]{80, 12},
                new long[]{82, 4}, new long[]{5000, 3_000_000}, new long[]{5_000_000, 900});
        List<RangePlanner.Block> blocks = RangePlanner.plan(input, 4, 1 << 20);
        for (int i = 0; i < input.size(); i++) {
            long[] r = input.get(i);
            int hits = 0;
            for (RangePlanner.Block b : blocks) {
                if (b.members.contains(i)) {
                    hits++;
                    assertTrue(b.offset <= r[0] && r[0] + r[1] <= b.end(),
                            "range " + r[0] + "+" + r[1] + " in block " + b.offset + "+" + b.length);
                }
            }
            assertEquals(1, hits, "range index " + i);
        }
    }
}
