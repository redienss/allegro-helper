package com.allegrohelper.ui;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Provides color emoji images.
 *
 * <p>Java2D cannot rasterize color fonts (COLR/CPAL or CBDT bitmap glyphs) - it
 * renders them blank or falls back to a monochrome outline. Noto Color Emoji,
 * however, stores a PNG bitmap per glyph in its {@code CBDT}/{@code CBLC}
 * tables, so we read those bitmaps out of the font file directly and hand them
 * to Swing as icons.
 *
 * <p>Only what Noto Color Emoji actually uses is supported: a {@code cmap}
 * format 12 subtable, index formats 1-2 and image formats 17-19. Everything is
 * lazy: the font is memory-mapped on first use and missing fonts simply disable
 * the feature.
 */
final class ColorEmoji {

    /** Codepoints below this are never emoji; skip the lookup entirely. */
    private static final int MIN_EMOJI_CODEPOINT = 0x2000;

    private static final String[] FONT_CANDIDATES = {
            "/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf",
            "/usr/share/fonts/noto/NotoColorEmoji.ttf",
            "/usr/share/fonts/google-noto-emoji/NotoColorEmoji.ttf",
            "/usr/share/fonts/truetype/noto/NotoColorEmoji-Regular.ttf",
            "/usr/local/share/fonts/NotoColorEmoji.ttf",
    };

    private static boolean initialized;
    private static ByteBuffer font;
    private static int cmapGroups;      // offset of the format-12 group array
    private static int cmapGroupCount;
    private static int cblc;
    private static int cbdt;

    private static final Map<Integer, BufferedImage> imageCache = new HashMap<>();
    private static final Map<Long, ImageIcon> iconCache = new HashMap<>();

    private ColorEmoji() {
    }

    /** Returns an icon of the given height for {@code codePoint}, or null if unavailable. */
    static ImageIcon icon(int codePoint, int height) {
        if (codePoint < MIN_EMOJI_CODEPOINT || height <= 0) {
            return null;
        }
        long key = ((long) codePoint << 32) | height;
        ImageIcon cached = iconCache.get(key);
        if (cached != null) {
            return cached;
        }
        if (iconCache.containsKey(key)) {
            return null; // negative result cached
        }
        BufferedImage img = image(codePoint);
        ImageIcon icon = null;
        if (img != null) {
            int w = Math.max(1, Math.round(img.getWidth() * (height / (float) img.getHeight())));
            Image scaled = img.getScaledInstance(w, height, Image.SCALE_SMOOTH);
            icon = new ImageIcon(scaled);
        }
        iconCache.put(key, icon);
        return icon;
    }

    private static synchronized BufferedImage image(int codePoint) {
        if (imageCache.containsKey(codePoint)) {
            return imageCache.get(codePoint);
        }
        BufferedImage img = null;
        try {
            if (init()) {
                int glyph = glyphId(codePoint);
                if (glyph > 0) {
                    byte[] png = glyphPng(glyph);
                    if (png != null) {
                        img = ImageIO.read(new ByteArrayInputStream(png));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            img = null; // treat any parse failure as "no emoji"
        }
        imageCache.put(codePoint, img);
        return img;
    }

    // ------------------------------------------------------------------ sfnt

    private static synchronized boolean init() throws IOException {
        if (initialized) {
            return font != null;
        }
        initialized = true;

        Path path = findFont();
        if (path == null) {
            return false;
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            font = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
        }

        try {
            int numTables = u16(4);
            int cmap = 0;
            for (int i = 0; i < numTables; i++) {
                int rec = 12 + i * 16;
                String tag = "" + (char) u8(rec) + (char) u8(rec + 1) + (char) u8(rec + 2) + (char) u8(rec + 3);
                int offset = (int) u32(rec + 8);
                switch (tag) {
                    case "cmap" -> cmap = offset;
                    case "CBLC" -> cblc = offset;
                    case "CBDT" -> cbdt = offset;
                    default -> { }
                }
            }
            if (cmap == 0 || cblc == 0 || cbdt == 0 || !parseCmap(cmap)) {
                font = null;
                return false;
            }
        } catch (RuntimeException e) {
            font = null; // not a usable font: disable color emoji for good
            return false;
        }
        return true;
    }

    private static Path findFont() {
        String override = System.getenv("ALLEGRO_EMOJI_FONT");
        if (override != null && !override.isBlank() && Files.isRegularFile(Path.of(override))) {
            return Path.of(override);
        }
        for (String candidate : FONT_CANDIDATES) {
            Path p = Path.of(candidate);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    /** Locates a format-12 (UCS-4) cmap subtable; emoji live outside the BMP. */
    private static boolean parseCmap(int cmap) {
        int numSubtables = u16(cmap + 2);
        int best = 0;
        for (int i = 0; i < numSubtables; i++) {
            int rec = cmap + 4 + i * 8;
            int platform = u16(rec);
            int encoding = u16(rec + 2);
            int subtable = cmap + (int) u32(rec + 4);
            if (u16(subtable) != 12) {
                continue;
            }
            if (best == 0 || (platform == 3 && encoding == 10)) {
                best = subtable;
            }
        }
        if (best == 0) {
            return false;
        }
        cmapGroupCount = (int) u32(best + 12);
        cmapGroups = best + 16;
        return true;
    }

    /** Binary search of the sorted format-12 groups. */
    private static int glyphId(int codePoint) {
        int lo = 0;
        int hi = cmapGroupCount - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int g = cmapGroups + mid * 12;
            long start = u32(g);
            long end = u32(g + 4);
            if (codePoint < start) {
                hi = mid - 1;
            } else if (codePoint > end) {
                lo = mid + 1;
            } else {
                return (int) (u32(g + 8) + (codePoint - start));
            }
        }
        return 0;
    }

    /** Finds the embedded PNG for a glyph via the CBLC index into CBDT. */
    private static byte[] glyphPng(int glyph) {
        int numSizes = (int) u32(cblc + 4);
        for (int s = 0; s < numSizes; s++) {
            int rec = cblc + 8 + s * 48;
            int startGlyph = u16(rec + 40);
            int endGlyph = u16(rec + 42);
            if (glyph < startGlyph || glyph > endGlyph) {
                continue;
            }
            int arrayBase = cblc + (int) u32(rec);
            int numIndexSubTables = (int) u32(rec + 8);
            for (int k = 0; k < numIndexSubTables; k++) {
                int a = arrayBase + k * 8;
                int firstGlyph = u16(a);
                int lastGlyph = u16(a + 2);
                if (glyph < firstGlyph || glyph > lastGlyph) {
                    continue;
                }
                int ist = arrayBase + (int) u32(a + 4);
                int indexFormat = u16(ist);
                int imageFormat = u16(ist + 2);
                int imageDataOffset = (int) u32(ist + 4);

                int dataStart;
                int dataLen;
                if (indexFormat == 1) {
                    int idx = glyph - firstGlyph;
                    long off0 = u32(ist + 8 + idx * 4);
                    long off1 = u32(ist + 8 + (idx + 1) * 4);
                    if (off1 <= off0) {
                        return null; // glyph has no bitmap
                    }
                    dataStart = cbdt + imageDataOffset + (int) off0;
                    dataLen = (int) (off1 - off0);
                } else if (indexFormat == 2) {
                    int imageSize = (int) u32(ist + 8);
                    dataStart = cbdt + imageDataOffset + (glyph - firstGlyph) * imageSize;
                    dataLen = imageSize;
                } else {
                    return null;
                }
                return extractPng(dataStart, dataLen, imageFormat);
            }
        }
        return null;
    }

    private static byte[] extractPng(int dataStart, int dataLen, int imageFormat) {
        int pngOffset;
        int pngLen;
        switch (imageFormat) {
            case 17 -> { // smallGlyphMetrics (5 bytes) + uint32 length + PNG
                pngLen = (int) u32(dataStart + 5);
                pngOffset = dataStart + 9;
            }
            case 18 -> { // bigGlyphMetrics (8 bytes) + uint32 length + PNG
                pngLen = (int) u32(dataStart + 8);
                pngOffset = dataStart + 12;
            }
            case 19 -> { // uint32 length + PNG
                pngLen = (int) u32(dataStart);
                pngOffset = dataStart + 4;
            }
            default -> {
                return null;
            }
        }
        if (pngLen <= 0 || pngLen > dataLen || pngOffset + pngLen > font.limit()) {
            return null;
        }
        byte[] png = new byte[pngLen];
        for (int i = 0; i < pngLen; i++) {
            png[i] = font.get(pngOffset + i);
        }
        return png;
    }

    private static int u8(int i) {
        return font.get(i) & 0xFF;
    }

    private static int u16(int i) {
        return ((font.get(i) & 0xFF) << 8) | (font.get(i + 1) & 0xFF);
    }

    private static long u32(int i) {
        return ((long) (font.get(i) & 0xFF) << 24)
                | ((long) (font.get(i + 1) & 0xFF) << 16)
                | ((long) (font.get(i + 2) & 0xFF) << 8)
                | (font.get(i + 3) & 0xFF);
    }
}
