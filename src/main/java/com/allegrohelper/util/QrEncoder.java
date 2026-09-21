package com.allegrohelper.util;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Minimal, dependency-free QR Code encoder (ISO/IEC 18004), used to stamp a
 * scannable link onto a photo.
 *
 * <p>Deliberately narrow, matching what a listing photo's QR code actually
 * needs: <b>byte mode only</b> (a URL's lowercase letters and punctuation fall
 * outside QR's alphanumeric-mode charset, which excludes lowercase entirely)
 * and a single, fixed <b>error correction level M</b> (~15% recovery — not
 * user-configurable, to keep this to one code path instead of four). The
 * encoder picks the smallest of the 40 versions that fits the input.
 *
 * <p>The version-capacity table ({@link #CAPACITY_M}) is the spec's published
 * data for level M and cannot be derived; everything else — alignment-pattern
 * positions, the format/version-info BCH codes, the data zigzag, mask scoring
 * — is computed from the spec's algorithms rather than transcribed as a table,
 * since a formula can be sanity-checked and a 40-row table of magic numbers
 * cannot.
 */
public final class QrEncoder {

    /** Not instantiable: the class is a namespace for {@link #encode}. */
    private QrEncoder() {
    }

    // ------------------------------------------------------------- capacity

    /**
     * Level-M capacity per version 1..40 (index 0 is version 1): {@code
     * {dataCodewords, eccCodewordsPerBlock, group1Blocks, group1DataCodewords,
     * group2Blocks, group2DataCodewords}} — ISO/IEC 18004 Table 9, the level-M
     * column only, since that is the only level this encoder produces.
     *
     * <p>Every row is cross-checked in tests against {@code totalCodewords =
     * dataCodewords + eccCodewordsPerBlock * (group1Blocks + group2Blocks)},
     * which must equal the module count this class's own finder/timing/
     * alignment/format/version placement leaves free. That check catches a
     * wrong <em>total</em>, but not a wrong split of an otherwise-correct
     * total — versions 23-25 and 27-34 passed that check while still being
     * wrong (a hand-recalled {@code eccCodewordsPerBlock} of 30 instead of
     * 28, with the per-block data lengths shifted to compensate), caught only
     * by decoding actual output with an independent, non-self-written
     * decoder (see {@code QrEncoderTest}'s class javadoc): those versions
     * built a matrix that looked internally consistent to this class's own
     * round trip but was not decodable by a real scanner.
     */
    private static final int[][] CAPACITY_M = {
        {16, 10, 1, 16, 0, 0},
        {28, 16, 1, 28, 0, 0},
        {44, 26, 1, 44, 0, 0},
        {64, 18, 2, 32, 0, 0},
        {86, 24, 2, 43, 0, 0},
        {108, 16, 4, 27, 0, 0},
        {124, 18, 4, 31, 0, 0},
        {154, 22, 2, 38, 2, 39},
        {182, 22, 3, 36, 2, 37},
        {216, 26, 4, 43, 1, 44},
        {254, 30, 1, 50, 4, 51},
        {290, 22, 6, 36, 2, 37},
        {334, 22, 8, 37, 1, 38},
        {365, 24, 4, 40, 5, 41},
        {415, 24, 5, 41, 5, 42},
        {453, 28, 7, 45, 3, 46},
        {507, 28, 10, 46, 1, 47},
        {563, 26, 9, 43, 4, 44},
        {627, 26, 3, 44, 11, 45},
        {669, 26, 3, 41, 13, 42},
        {714, 26, 17, 42, 0, 0},
        {782, 28, 17, 46, 0, 0},
        {860, 28, 4, 47, 14, 48},
        {914, 28, 6, 45, 14, 46},
        {1000, 28, 8, 47, 13, 48},
        {1062, 28, 19, 46, 4, 47},
        {1128, 28, 22, 45, 3, 46},
        {1193, 28, 3, 45, 23, 46},
        {1267, 28, 21, 45, 7, 46},
        {1373, 28, 19, 47, 10, 48},
        {1455, 28, 2, 46, 29, 47},
        {1541, 28, 10, 46, 23, 47},
        {1631, 28, 14, 46, 21, 47},
        {1725, 28, 14, 46, 23, 47},
        {1812, 28, 12, 47, 26, 48},
        {1914, 28, 6, 47, 34, 48},
        {1992, 28, 29, 46, 14, 47},
        {2102, 28, 13, 46, 32, 47},
        {2216, 28, 40, 47, 7, 48},
        {2334, 28, 18, 47, 31, 48},
    };

    /** Package-private for {@code QrEncoderTest}'s own round-trip decoder. */
    static int[] capacityRow(int version) {
        return CAPACITY_M[version - 1];
    }

    /**
     * Bytes a version can hold at level M: total data bits minus the byte-mode
     * header (4-bit mode indicator plus an 8- or 16-bit length, the break at
     * version 10 per the spec), divided into whole bytes.
     */
    private static int maxBytes(int version) {
        int countBits = version <= 9 ? 8 : 16;
        return (CAPACITY_M[version - 1][0] * 8 - 4 - countBits) / 8;
    }

    /**
     * The smallest version that fits {@code dataLength} bytes at level M.
     *
     * @throws IllegalArgumentException if the text is too long even at version 40
     */
    private static int chooseVersion(int dataLength) {
        for (int v = 1; v <= 40; v++) {
            if (dataLength <= maxBytes(v)) {
                return v;
            }
        }
        throw new IllegalArgumentException("Text is " + dataLength + " bytes, too long for a "
                + "QR code even at version 40 (max " + maxBytes(40) + " bytes at level M).");
    }

    // ------------------------------------------------------------- encoding

    /**
     * Encodes {@code text} as a QR code, returning its modules ({@code true} =
     * dark) as {@code modules[row][col]}, including the mandatory quiet zone's
     * worth of nothing — the caller draws that margin itself, since it depends
     * on the module pixel size the caller chooses.
     *
     * <p>Encoded as raw bytes (ISO-8859-1: a URL is ASCII per RFC 3986, and
     * byte mode's default interpretation without an ECI segment is Latin-1 —
     * adding ECI support for arbitrary Unicode is not worth it for a link).
     *
     * @throws IllegalArgumentException if the text is too long for any version at level M
     */
    public static boolean[][] encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.ISO_8859_1);
        int version = chooseVersion(data.length);
        int[] cap = CAPACITY_M[version - 1];
        BitWriter bits = buildBitStream(data, version, cap[0]);
        int[] dataCodewords = toCodewords(bits);
        int[] finalCodewords = interleave(dataCodewords, cap);
        return buildMatrix(version, finalCodewords);
    }

    /**
     * The byte-mode bit stream: a 4-bit mode indicator, an 8- or 16-bit length
     * (the break is at version 10, matching {@link #maxBytes}), the data
     * itself, a terminator of up to 4 zero bits (fewer if the capacity is
     * almost exhausted), padding to a byte boundary, then alternating
     * {@code 0xEC}/{@code 0x11} pad codewords up to the version's capacity —
     * all exactly as ISO/IEC 18004 §8.4 specifies.
     */
    private static BitWriter buildBitStream(byte[] data, int version, int capacityCodewords) {
        BitWriter bw = new BitWriter();
        bw.write(0b0100, 4);
        int countBits = version <= 9 ? 8 : 16;
        bw.write(data.length, countBits);
        for (byte b : data) {
            bw.write(b & 0xFF, 8);
        }
        int capacityBits = capacityCodewords * 8;
        int terminator = Math.min(4, capacityBits - bw.length());
        if (terminator > 0) {
            bw.write(0, terminator);
        }
        while (bw.length() % 8 != 0) {
            bw.write(0, 1);
        }
        boolean useEc = true;
        while (bw.length() < capacityBits) {
            bw.write(useEc ? 0xEC : 0x11, 8);
            useEc = !useEc;
        }
        return bw;
    }

    /** Packs a settled bit stream (already byte-aligned) into codewords. */
    private static int[] toCodewords(BitWriter bw) {
        int n = bw.length() / 8;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int v = 0;
            for (int b = 0; b < 8; b++) {
                v = (v << 1) | (bw.get(i * 8 + b) ? 1 : 0);
            }
            out[i] = v;
        }
        return out;
    }

    /**
     * Splits data codewords into the version's blocks, computes each block's
     * Reed-Solomon error correction codewords, and interleaves data then ECC
     * column-by-column (short data blocks simply run out first) — ISO/IEC
     * 18004 §8.6, what lets a scanner recover a block-sized burst of damage
     * instead of losing everything after the first error.
     */
    private static int[] interleave(int[] dataCodewords, int[] cap) {
        int eccLen = cap[1];
        int g1Blocks = cap[2];
        int g1Len = cap[3];
        int g2Blocks = cap[4];
        int g2Len = cap[5];

        List<int[]> dataBlocks = new ArrayList<>();
        List<int[]> ecBlocks = new ArrayList<>();
        int offset = 0;
        for (int i = 0; i < g1Blocks; i++) {
            int[] block = Arrays.copyOfRange(dataCodewords, offset, offset + g1Len);
            offset += g1Len;
            dataBlocks.add(block);
            ecBlocks.add(ReedSolomon.encode(block, eccLen));
        }
        for (int i = 0; i < g2Blocks; i++) {
            int[] block = Arrays.copyOfRange(dataCodewords, offset, offset + g2Len);
            offset += g2Len;
            dataBlocks.add(block);
            ecBlocks.add(ReedSolomon.encode(block, eccLen));
        }

        int maxDataLen = Math.max(g1Len, g2Len);
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < maxDataLen; i++) {
            for (int[] block : dataBlocks) {
                if (i < block.length) {
                    result.add(block[i]);
                }
            }
        }
        for (int i = 0; i < eccLen; i++) {
            for (int[] block : ecBlocks) {
                result.add(block[i]);
            }
        }
        int[] out = new int[result.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = result.get(i);
        }
        return out;
    }

    // --------------------------------------------------------- bit writer

    /** A growable, MSB-first bit sequence — simpler than hand-tracking byte/bit offsets. */
    static final class BitWriter {
        private final java.util.BitSet bits = new java.util.BitSet();
        private int length = 0;

        void write(int value, int numBits) {
            for (int i = numBits - 1; i >= 0; i--) {
                if (((value >>> i) & 1) != 0) {
                    bits.set(length);
                }
                length++;
            }
        }

        int length() {
            return length;
        }

        boolean get(int i) {
            return bits.get(i);
        }
    }

    // ------------------------------------------------------------ GF(256) / RS

    /**
     * Reed-Solomon error correction over GF(256) with QR's primitive polynomial
     * {@code x^8+x^4+x^3+x^2+1} (0x11D). Package-private so {@code
     * QrEncoderTest} can compute syndromes over the encoder's own output as a
     * correctness check — with no way to scan a real QR code in this
     * environment, "the ECC codewords are valid parity for the data codewords"
     * is the strongest automatable proof available that this arithmetic and
     * the block interleaving agree with each other.
     */
    static final class ReedSolomon {
        private static final int[] EXP = new int[512];
        private static final int[] LOG = new int[256];

        static {
            int x = 1;
            for (int i = 0; i < 255; i++) {
                EXP[i] = x;
                LOG[x] = i;
                x <<= 1;
                if ((x & 0x100) != 0) {
                    x ^= 0x11D;
                }
            }
            for (int i = 255; i < 512; i++) {
                EXP[i] = EXP[i - 255];
            }
        }

        private ReedSolomon() {
        }

        static int multiply(int a, int b) {
            if (a == 0 || b == 0) {
                return 0;
            }
            return EXP[LOG[a] + LOG[b]];
        }

        /** The degree-{@code eccLen} generator polynomial, highest-degree coefficient first. */
        static int[] generator(int eccLen) {
            int[] g = {1};
            for (int i = 0; i < eccLen; i++) {
                int[] term = {1, EXP[i]};
                g = multiplyPoly(g, term);
            }
            return g;
        }

        private static int[] multiplyPoly(int[] a, int[] b) {
            int[] result = new int[a.length + b.length - 1];
            for (int i = 0; i < a.length; i++) {
                for (int j = 0; j < b.length; j++) {
                    result[i + j] ^= multiply(a[i], b[j]);
                }
            }
            return result;
        }

        /** The {@code eccLen} remainder codewords of {@code data} divided by the generator. */
        static int[] encode(int[] data, int eccLen) {
            int[] generator = generator(eccLen);
            int[] remainder = new int[eccLen];
            for (int d : data) {
                int factor = d ^ remainder[0];
                System.arraycopy(remainder, 1, remainder, 0, eccLen - 1);
                remainder[eccLen - 1] = 0;
                for (int i = 0; i < eccLen; i++) {
                    remainder[i] ^= multiply(generator[i + 1], factor);
                }
            }
            return remainder;
        }
    }

    // -------------------------------------------------------------- matrix

    private static boolean[][] buildMatrix(int version, int[] codewords) {
        int size = version * 4 + 17;
        boolean[][] modules = new boolean[size][size];
        boolean[][] isFunction = new boolean[size][size];
        placeFunctionPatterns(modules, isFunction, version, size);
        placeData(modules, isFunction, size, codewords);

        boolean[][] best = null;
        long bestPenalty = Long.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            boolean[][] candidate = applyMask(modules, isFunction, size, mask);
            placeFormatInfo(candidate, size, mask);
            long score = penalty(candidate, size);
            if (score < bestPenalty) {
                bestPenalty = score;
                best = candidate;
            }
        }
        return best;
    }

    /** Everything but the data: finder/timing/alignment patterns, the format-info reservation, version info. */
    private static void placeFunctionPatterns(boolean[][] modules, boolean[][] isFunction, int version, int size) {
        placeFinderPattern(modules, isFunction, size, 0, 0);
        placeFinderPattern(modules, isFunction, size, 0, size - 7);
        placeFinderPattern(modules, isFunction, size, size - 7, 0);
        placeTimingPatterns(modules, isFunction, size);
        placeAlignmentPatterns(modules, isFunction, version, size);
        reserveFormatInfo(isFunction, size);
        modules[size - 8][8] = true; // the one always-dark module
        isFunction[size - 8][8] = true;
        if (version >= 7) {
            placeVersionInfo(modules, isFunction, version, size);
        }
    }

    /**
     * Test-only: the reserved/function-pattern mask for a version (no data
     * placed) — {@code QrEncoderTest}'s round-trip decoder needs to know
     * which cells of a real, encoded matrix are data before it can unmask
     * and read them back.
     */
    static boolean[][] functionMask(int version) {
        int size = version * 4 + 17;
        boolean[][] modules = new boolean[size][size];
        boolean[][] isFunction = new boolean[size][size];
        placeFunctionPatterns(modules, isFunction, version, size);
        return isFunction;
    }

    /**
     * The 7x7 finder core plus its light separator ring, via Chebyshev
     * distance from the core's center: distance 2 is light (the ring between
     * the dark border and the dark 3x3 center), everything else in the 7x7
     * is dark, and the separator beyond it is always light.
     */
    private static void placeFinderPattern(boolean[][] modules, boolean[][] isFunction,
                                            int size, int originRow, int originCol) {
        for (int dr = -1; dr <= 7; dr++) {
            for (int dc = -1; dc <= 7; dc++) {
                int r = originRow + dr;
                int c = originCol + dc;
                if (r < 0 || r >= size || c < 0 || c >= size) {
                    continue;
                }
                isFunction[r][c] = true;
                boolean inCore = dr >= 0 && dr <= 6 && dc >= 0 && dc <= 6;
                boolean dark = inCore && Math.max(Math.abs(dr - 3), Math.abs(dc - 3)) != 2;
                modules[r][c] = dark;
            }
        }
    }

    /** Row 6 and column 6, alternating dark/light, between the three finder patterns. */
    private static void placeTimingPatterns(boolean[][] modules, boolean[][] isFunction, int size) {
        for (int i = 8; i < size - 8; i++) {
            boolean dark = i % 2 == 0;
            modules[6][i] = dark;
            isFunction[6][i] = true;
            modules[i][6] = dark;
            isFunction[i][6] = true;
        }
    }

    private static void placeAlignmentPatterns(boolean[][] modules, boolean[][] isFunction,
                                                int version, int size) {
        int[] positions = alignmentPatternPositions(version, size);
        int n = positions.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                // The three combinations nearest a finder pattern overlap it; skip them.
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) {
                    continue;
                }
                drawAlignmentPattern(modules, isFunction, positions[i], positions[j]);
            }
        }
    }

    /** A 5x5 block: dark border, light ring, dark center — Chebyshev distance 1 is the ring. */
    private static void drawAlignmentPattern(boolean[][] modules, boolean[][] isFunction, int r, int c) {
        for (int dr = -2; dr <= 2; dr++) {
            for (int dc = -2; dc <= 2; dc++) {
                modules[r + dr][c + dc] = Math.max(Math.abs(dr), Math.abs(dc)) != 1;
                isFunction[r + dr][c + dc] = true;
            }
        }
    }

    /**
     * Alignment pattern centers along one axis, by the spec's own formula
     * (ISO/IEC 18004 Annex E) rather than the 40-row table most references
     * publish — a formula this small can be checked by hand against a few
     * known versions, where a hand-copied table of 40 rows cannot.
     */
    private static int[] alignmentPatternPositions(int version, int size) {
        if (version == 1) {
            return new int[0];
        }
        int numAlign = version / 7 + 2;
        int step = version == 32
                ? 26
                : (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2;
        int[] result = new int[numAlign];
        result[0] = 6;
        int pos = size - 7;
        for (int i = numAlign - 1; i >= 1; i--, pos -= step) {
            result[i] = pos;
        }
        return result;
    }

    /**
     * Places codewords into the non-function modules in the spec's zigzag
     * order: two columns at a time from the right edge, alternating scan
     * direction, with column 6 (the vertical timing pattern) skipped by
     * merging it into the pair to its left.
     */
    private static void placeData(boolean[][] modules, boolean[][] isFunction, int size, int[] codewords) {
        int bitIndex = 0;
        int totalBits = codewords.length * 8;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) {
                right = 5;
            }
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x]) {
                        boolean bit = bitIndex < totalBits
                                && ((codewords[bitIndex / 8] >> (7 - bitIndex % 8)) & 1) != 0;
                        modules[y][x] = bit;
                        bitIndex++;
                    }
                }
            }
        }
    }

    /**
     * Test-only: reads codewords back out of a matrix in the same zigzag
     * order {@link #placeData} wrote them, given the version's function mask.
     */
    static int[] readCodewords(boolean[][] modules, boolean[][] isFunction, int size, int totalCodewords) {
        int totalBits = totalCodewords * 8;
        boolean[] bitBuf = new boolean[totalBits];
        int bitIndex = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) {
                right = 5;
            }
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && bitIndex < totalBits) {
                        bitBuf[bitIndex] = modules[y][x];
                        bitIndex++;
                    }
                }
            }
        }
        int[] out = new int[totalCodewords];
        for (int i = 0; i < totalCodewords; i++) {
            int v = 0;
            for (int b = 0; b < 8; b++) {
                v = (v << 1) | (bitBuf[i * 8 + b] ? 1 : 0);
            }
            out[i] = v;
        }
        return out;
    }

    // ---------------------------------------------------- format / version info

    /**
     * Format info copy A's 15 cell positions, around the top-left finder:
     * row 8 columns 0-5, then (8,7)/(8,8) (column 6 skipped — the timing
     * column), then column 8 rows 7,5,4,3,2,1,0 (row 6 skipped, same reason).
     */
    static final int[][] FORMAT_A = {
        {8, 0}, {8, 1}, {8, 2}, {8, 3}, {8, 4}, {8, 5}, {8, 7}, {8, 8},
        {7, 8}, {5, 8}, {4, 8}, {3, 8}, {2, 8}, {1, 8}, {0, 8},
    };

    /** Format info copy B's cell for bit {@code i}: split across the bottom-left and top-right finders. */
    static int[] formatBPos(int i, int size) {
        if (i <= 5) {
            return new int[]{size - 1 - i, 8};
        }
        if (i == 6) {
            return new int[]{size - 7, 8};
        }
        if (i == 7) {
            return new int[]{8, size - 8};
        }
        return new int[]{8, size - 15 + i};
    }

    private static void reserveFormatInfo(boolean[][] isFunction, int size) {
        for (int[] p : FORMAT_A) {
            isFunction[p[0]][p[1]] = true;
        }
        for (int i = 0; i < 15; i++) {
            int[] p = formatBPos(i, size);
            isFunction[p[0]][p[1]] = true;
        }
    }

    private static void placeFormatInfo(boolean[][] modules, int size, int mask) {
        int bits = formatBits(mask);
        for (int i = 0; i < 15; i++) {
            // The spec reads the format string MSB-first along the L-shaped path:
            // FORMAT_A[0] (8,0) carries bit 14, FORMAT_A[14] (0,8) carries bit 0.
            boolean bit = ((bits >> (14 - i)) & 1) != 0;
            int[] a = FORMAT_A[i];
            modules[a[0]][a[1]] = bit;
            int[] b = formatBPos(i, size);
            modules[b[0]][b[1]] = bit;
        }
    }

    /**
     * The 15-bit format string: 2 bits error-correction level (level M is
     * {@code 00}, one of the spec's four fixed level codes — not 0..3 in
     * enum order), 3 bits mask pattern, 10 bits BCH error correction over
     * generator {@code 0x537}, all XORed with the spec's fixed mask
     * {@code 0x5412} so an all-zero data bit pattern still shows structure.
     */
    static int formatBits(int mask) {
        int data = mask & 0b111; // (level M = 00) << 3 | mask
        int rem = data;
        for (int i = 0; i < 10; i++) {
            rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        }
        int combined = (data << 10) | (rem & 0x3FF);
        return (combined ^ 0x5412) & 0x7FFF;
    }

    /**
     * The 18-bit version info string (versions 7+ only): 6 bits version
     * number, 12 bits BCH error correction over generator {@code 0x1F25} —
     * no XOR mask, unlike format info.
     */
    static int versionInfoBits(int version) {
        int rem = version;
        for (int i = 0; i < 12; i++) {
            rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        }
        return (version << 12) | (rem & 0xFFF);
    }

    /**
     * Places the two version-info blocks (transposes of each other): a 3x6
     * block above the bottom-left finder and a 6x3 block left of the
     * top-right finder, bit {@code b} at (row {@code b%3}, col {@code b/3})
     * in the first and the transposed cell in the second.
     */
    private static void placeVersionInfo(boolean[][] modules, boolean[][] isFunction, int version, int size) {
        int bits = versionInfoBits(version);
        for (int b = 0; b < 18; b++) {
            boolean dark = ((bits >> b) & 1) != 0;
            int row = b % 3;
            int col = b / 3;
            modules[size - 11 + row][col] = dark;
            isFunction[size - 11 + row][col] = true;
            modules[col][size - 11 + row] = dark;
            isFunction[col][size - 11 + row] = true;
        }
    }

    // ------------------------------------------------------------ masking

    private static boolean[][] applyMask(boolean[][] modules, boolean[][] isFunction, int size, int mask) {
        boolean[][] out = new boolean[size][size];
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                boolean v = modules[r][c];
                if (!isFunction[r][c] && maskBit(mask, r, c)) {
                    v = !v;
                }
                out[r][c] = v;
            }
        }
        return out;
    }

    /** The 8 standard QR mask formulas (ISO/IEC 18004 Table 10). */
    static boolean maskBit(int mask, int r, int c) {
        return switch (mask) {
            case 0 -> (r + c) % 2 == 0;
            case 1 -> r % 2 == 0;
            case 2 -> c % 3 == 0;
            case 3 -> (r + c) % 3 == 0;
            case 4 -> (r / 2 + c / 3) % 2 == 0;
            case 5 -> (r * c) % 2 + (r * c) % 3 == 0;
            case 6 -> ((r * c) % 2 + (r * c) % 3) % 2 == 0;
            case 7 -> ((r + c) % 2 + (r * c) % 3) % 2 == 0;
            default -> throw new IllegalArgumentException("mask " + mask);
        };
    }

    // ----------------------------------------------------- mask penalty scoring

    /**
     * The four ISO/IEC 18004 penalty rules, lowest total wins. Every mask
     * produces a fully valid, decodable code — this only picks which one is
     * kindest to a real scanner (long runs, 2x2 blocks and finder-like
     * patterns confuse edge/timing detection; a skewed dark/light balance
     * hurts exposure-based thresholding).
     */
    private static long penalty(boolean[][] m, int size) {
        return runPenalty(m, size, true) + runPenalty(m, size, false)
                + blockPenalty(m, size)
                + finderPenalty(m, size, true) + finderPenalty(m, size, false)
                + balancePenalty(m, size);
    }

    /** N1: 5+ same-color modules in a row/column, cost 3 plus 1 per module past 5. */
    private static long runPenalty(boolean[][] m, int size, boolean rows) {
        long total = 0;
        for (int i = 0; i < size; i++) {
            int runLen = 1;
            boolean prev = rows ? m[i][0] : m[0][i];
            for (int j = 1; j < size; j++) {
                boolean v = rows ? m[i][j] : m[j][i];
                if (v == prev) {
                    runLen++;
                } else {
                    if (runLen >= 5) {
                        total += 3 + (runLen - 5);
                    }
                    runLen = 1;
                    prev = v;
                }
            }
            if (runLen >= 5) {
                total += 3 + (runLen - 5);
            }
        }
        return total;
    }

    /** N2: each same-color 2x2 block, cost 3 — overlapping blocks each count. */
    private static long blockPenalty(boolean[][] m, int size) {
        long total = 0;
        for (int r = 0; r < size - 1; r++) {
            for (int c = 0; c < size - 1; c++) {
                boolean v = m[r][c];
                if (m[r][c + 1] == v && m[r + 1][c] == v && m[r + 1][c + 1] == v) {
                    total += 3;
                }
            }
        }
        return total;
    }

    /** The finder-pattern-like 1:1:3:1:1 ratio (dark-light-dark-dark-dark-light-dark) with 4 light modules on one side. */
    private static final boolean[] FINDER_LIKE_FORWARD =
            {true, false, true, true, true, false, true, false, false, false, false};
    private static final boolean[] FINDER_LIKE_REVERSE =
            {false, false, false, false, true, false, true, true, true, false, true};

    /** N3: that pattern anywhere in a row/column, cost 40 per occurrence. */
    private static long finderPenalty(boolean[][] m, int size, boolean rows) {
        long total = 0;
        for (int i = 0; i < size; i++) {
            for (int j = 0; j <= size - FINDER_LIKE_FORWARD.length; j++) {
                if (matchesRun(m, i, j, rows, FINDER_LIKE_FORWARD)
                        || matchesRun(m, i, j, rows, FINDER_LIKE_REVERSE)) {
                    total += 40;
                }
            }
        }
        return total;
    }

    private static boolean matchesRun(boolean[][] m, int i, int j, boolean rows, boolean[] pattern) {
        for (int k = 0; k < pattern.length; k++) {
            boolean v = rows ? m[i][j + k] : m[j + k][i];
            if (v != pattern[k]) {
                return false;
            }
        }
        return true;
    }

    /** N4: 10 points per 5% the dark/light balance sits away from 50/50. */
    private static long balancePenalty(boolean[][] m, int size) {
        int dark = 0;
        for (boolean[] row : m) {
            for (boolean v : row) {
                if (v) {
                    dark++;
                }
            }
        }
        int percentDark = dark * 100 / (size * size);
        int deviation = Math.abs(percentDark - 50);
        return (deviation / 5) * 10L;
    }
}
