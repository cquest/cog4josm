package org.openstreetmap.josm.plugins.cog.reader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Groups small byte-range reads into fewer, larger requests (in the spirit of
 * GDAL's overlapped/block I/O): ranges are sorted, then merged when the gap
 * between two of them is at most {@code maxGap} bytes and the merged block
 * stays under {@code maxBlockSize}. A single range bigger than the block cap
 * becomes its own block (it cannot be split).
 *
 * <p>Typical uses: tile offsets/byte counts of one IFD, adjacent raster tiles
 * (COG tiles are stored row-major, so consecutive tiles are contiguous).</p>
 */
public final class RangePlanner {

    /** Merged byte block, with the input ranges (indices) it covers. */
    public static final class Block {
        public final long offset;
        public int length;
        /** Indices (in the input list) of the ranges fully covered by this block. */
        public final List<Integer> members = new ArrayList<>();

        private Block(long offset, int length) {
            this.offset = offset;
            this.length = length;
        }

        public long end() {
            return offset + length;
        }

        boolean covers(long off, long len) {
            return off >= offset && off + len <= offset + (long) length;
        }
    }

    private RangePlanner() {
    }

    /**
     * @param ranges       {offset, length} pairs; the input list is not mutated;
     *                     zero-length ranges are dropped
     * @param maxGap       maximum hole between two consecutive ranges to still
     *                     merge them into one request
     * @param maxBlockSize preferred maximum size of a merged block
     * @return blocks sorted by offset; every non-empty input range is covered
     *         by exactly one block and its input index appears in members
     */
    public static List<Block> plan(List<long[]> ranges, long maxGap, long maxBlockSize) {
        Integer[] order = new Integer[ranges.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        final List<long[]> in = ranges;
        Arrays.sort(order, (a, b) -> Long.compare(in.get(a)[0], in.get(b)[0]));
        List<Block> blocks = new ArrayList<>();
        Block cur = null;
        for (int idx : order) {
            long off = in.get(idx)[0];
            long len = in.get(idx)[1];
            if (len <= 0) {
                continue;
            }
            boolean merged = false;
            if (cur != null && off - cur.end() <= maxGap) {
                long newEnd = Math.max(cur.end(), off + len);
                if (newEnd - cur.offset <= maxBlockSize) {
                    cur.length = (int) (newEnd - cur.offset);
                    merged = true;
                }
            }
            if (!merged) {
                cur = new Block(off, (int) Math.min(len, Integer.MAX_VALUE));
                blocks.add(cur);
            }
            cur.members.add(idx);
        }
        return blocks;
    }
}
