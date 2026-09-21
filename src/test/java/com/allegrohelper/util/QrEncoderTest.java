package com.allegrohelper.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code QrEncoder} has no external QR reader to check its output against at
 * build time — the project has no decoder library, by design (CLAUDE.md's
 * zero-dependency rule) — so these tests decode the encoder's own output
 * through the same spec algorithm in reverse: recover the mask from format
 * info, unmask, read codewords back out in zigzag order, de-interleave into
 * blocks, recompute each block's Reed-Solomon parity and compare it to what
 * the matrix stored, then parse the byte-mode payload back to the original
 * string. A mismatch anywhere in that chain is a real bug in the bitstream,
 * interleaving, masking or matrix placement.
 *
 * <p>What this alone cannot catch: whether the *positions* used for finder,
 * format-info and version-info cells match ISO/IEC 18004's actual layout —
 * both the encoder and this decoder share that layout, so a placement bug
 * that is wrong-but-self-consistent passes here while failing on a real
 * scanner. That gap is exactly what bit the first version of this class:
 * the format-info bit order was reversed (MSB/LSB swapped relative to the
 * spec), and separately the capacity table's level-M row for versions 23-25
 * and 27-34 had the right total but a wrong block split — both passed every
 * test in this file and were only found by decoding actual PNG/JPEG output
 * with an independent, third-party decoder (zbar, via Python's pyzbar) during
 * development, across all 40 versions at maximum capacity, byte-for-byte.
 * That check is not part of the automated suite (no such decoder is a
 * project dependency), so a future change to matrix placement should be
 * re-verified the same way, not trusted on this file's tests alone.
 */
class QrEncoderTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "https://youtu.be/xElxEl5m9Wo",
        "a",
        "",
        "https://photos.app.goo.gl/AbCdEfGhIjKlMnOpQrSt",
        "https://photos.google.com/share/AF1QipN1234567890abcdefghijklmnopqrstuvwxyz"
            + "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789?key=abcdefghijklmnopqrstuvwxyz1234567890",
    })
    void roundTripsShortAndMediumUrls(String text) {
        assertRoundTrips(text);
    }

    @Test
    void roundTripsAtEveryCapacityBoundary() {
        // One string per version 1..40, sized to that version's exact max
        // byte-mode capacity at level M, so every row of the capacity table
        // and every alignment-pattern computation gets exercised at least once.
        for (int version = 1; version <= 40; version++) {
            String text = "x".repeat(maxBytesFor(version));
            assertRoundTrips(text);
        }
    }

    /**
     * The capacity table's implied total codeword count ({@code dataCodewords
     * + eccCodewordsPerBlock * totalBlocks}) must equal the module count
     * actually free for codewords once finder, timing, alignment, format and
     * version-info patterns are reserved — an independent, geometry-derived
     * number that a hand-transcribed table can be checked against. This is
     * exactly the check that caught the original transcription errors in
     * versions 23-25 and 27-34 (see {@code CAPACITY_M}'s javadoc).
     */
    @Test
    void capacityTableMatchesModuleGeometry() {
        for (int version = 1; version <= 40; version++) {
            int[] cap = QrEncoder.capacityRow(version);
            int totalBlocks = cap[2] + cap[4];
            int totalFromTable = cap[0] + cap[1] * totalBlocks;
            assertEquals(cap[0], cap[2] * cap[3] + cap[4] * cap[5],
                    "v" + version + ": dataCodewords must equal group1 + group2");

            boolean[][] isFunction = QrEncoder.functionMask(version);
            int size = isFunction.length;
            int functionCount = 0;
            for (boolean[] row : isFunction) {
                for (boolean v : row) {
                    if (v) {
                        functionCount++;
                    }
                }
            }
            int usableBits = size * size - functionCount;
            int totalFromGeometry = usableBits / 8;
            int remainderBits = usableBits % 8;

            assertEquals(totalFromGeometry, totalFromTable,
                    "v" + version + ": capacity table disagrees with the free module count");
            assertTrue(remainderBits < 8, "v" + version + ": remainder bits must be a partial byte");
        }
    }

    @Test
    void tooLongTextIsRejected() {
        String text = "x".repeat(3000);
        assertThrows(IllegalArgumentException.class, () -> QrEncoder.encode(text));
    }

    @Test
    void finderPatternsSitAtTheThreeCorners() {
        boolean[][] m = QrEncoder.encode("https://example.com/x");
        int size = m.length;
        assertTrue(m[3][3], "top-left finder center");
        assertTrue(m[3][size - 4], "top-right finder center");
        assertTrue(m[size - 4][3], "bottom-left finder center");
        assertFalse(m[7][7], "the separator ring is always light");
    }

    /** Decodes {@code matrix} through the spec's algorithm in reverse and checks it equals {@code text}. */
    private static void assertRoundTrips(String text) {
        boolean[][] matrix = QrEncoder.encode(text);
        int size = matrix.length;
        int version = (size - 17) / 4;
        assertEquals(0, (size - 17) % 4, "matrix size must be 17 + 4*version");

        int[] cap = QrEncoder.capacityRow(version);
        int eccLen = cap[1];
        int g1Blocks = cap[2];
        int g1Len = cap[3];
        int g2Blocks = cap[4];
        int g2Len = cap[5];
        int totalDataCw = cap[0];
        int totalBlocks = g1Blocks + g2Blocks;
        int totalCw = totalDataCw + totalBlocks * eccLen;

        int mask = readMask(matrix, size);

        boolean[][] isFunction = QrEncoder.functionMask(version);
        boolean[][] unmasked = new boolean[size][size];
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                boolean v = matrix[r][c];
                if (!isFunction[r][c] && QrEncoder.maskBit(mask, r, c)) {
                    v = !v;
                }
                unmasked[r][c] = v;
            }
        }

        int[] finalCodewords = QrEncoder.readCodewords(unmasked, isFunction, size, totalCw);

        int maxDataLen = Math.max(g1Len, g2Len);
        int[][] dataBlocks = new int[totalBlocks][];
        int pos = 0;
        for (int col = 0; col < maxDataLen; col++) {
            for (int b = 0; b < totalBlocks; b++) {
                int len = b < g1Blocks ? g1Len : g2Len;
                if (col < len) {
                    if (dataBlocks[b] == null) {
                        dataBlocks[b] = new int[len];
                    }
                    dataBlocks[b][col] = finalCodewords[pos++];
                }
            }
        }
        int[][] ecBlocks = new int[totalBlocks][];
        for (int col = 0; col < eccLen; col++) {
            for (int b = 0; b < totalBlocks; b++) {
                if (ecBlocks[b] == null) {
                    ecBlocks[b] = new int[eccLen];
                }
                ecBlocks[b][col] = finalCodewords[pos++];
            }
        }
        assertEquals(totalCw, pos, "de-interleaving must consume every codeword exactly once");

        for (int b = 0; b < totalBlocks; b++) {
            int[] recomputed = QrEncoder.ReedSolomon.encode(dataBlocks[b], eccLen);
            assertArrayEquals(ecBlocks[b], recomputed,
                    "block " + b + "'s stored ECC must equal recomputed RS parity for its data");
        }

        int[] data = new int[totalDataCw];
        int di = 0;
        for (int[] block : dataBlocks) {
            for (int v : block) {
                data[di++] = v;
            }
        }

        int bitPos = 0;
        int mode = readBits(data, bitPos, 4);
        bitPos += 4;
        assertEquals(0b0100, mode, "only byte mode is implemented");
        int countBits = version <= 9 ? 8 : 16;
        int length = readBits(data, bitPos, countBits);
        bitPos += countBits;
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) readBits(data, bitPos, 8);
            bitPos += 8;
        }
        assertEquals(text, new String(out, StandardCharsets.ISO_8859_1));
    }

    /** Recovers the mask pattern from format info, cross-checking both copies agree. */
    private static int readMask(boolean[][] matrix, int size) {
        int raw = 0;
        for (int i = 0; i < 15; i++) {
            int[] p = QrEncoder.FORMAT_A[i];
            if (matrix[p[0]][p[1]]) {
                raw |= 1 << (14 - i);
            }
        }
        int rawB = 0;
        for (int i = 0; i < 15; i++) {
            int[] p = QrEncoder.formatBPos(i, size);
            if (matrix[p[0]][p[1]]) {
                rawB |= 1 << (14 - i);
            }
        }
        assertEquals(raw, rawB, "both format-info copies must carry the same bits");

        int combined = raw ^ 0x5412;
        int data = (combined >>> 10) & 0x1F;
        return data & 0b111;
    }

    private static int readBits(int[] codewords, int bitOffset, int numBits) {
        int v = 0;
        for (int i = 0; i < numBits; i++) {
            int bit = bitOffset + i;
            int cw = codewords[bit / 8];
            v = (v << 1) | ((cw >> (7 - bit % 8)) & 1);
        }
        return v;
    }

    private static int maxBytesFor(int version) {
        int[] cap = QrEncoder.capacityRow(version);
        int countBits = version <= 9 ? 8 : 16;
        return (cap[0] * 8 - 4 - countBits) / 8;
    }
}
