package com.allegrohelper.core;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the EXIF orientation tag from a JPEG and applies the corresponding
 * transform, reproducing PIL's {@code ImageOps.exif_transpose}. ImageIO does
 * not honour orientation on its own, so without this a photo shot upside-down
 * (orientation 3) or sideways (6/8) would be saved rotated.
 */
public final class Exif {

    private Exif() {
    }

    /** Returns the EXIF orientation (1..8), or 1 if absent/unreadable. */
    public static int readOrientation(Path file) {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            if (read16be(in) != 0xFFD8) { // Start of Image
                return 1;
            }
            while (true) {
                int marker = read16be(in);
                if (marker == -1 || (marker & 0xFF00) != 0xFF00) {
                    return 1;
                }
                // Start of Scan / End of Image: no more metadata segments.
                if (marker == 0xFFDA || marker == 0xFFD9) {
                    return 1;
                }
                int length = read16be(in);
                if (length < 2) {
                    return 1;
                }
                byte[] segment = in.readNBytes(length - 2);
                if (segment.length < length - 2) {
                    return 1;
                }
                if (marker == 0xFFE1) { // APP1 (Exif)
                    int orientation = parseExifOrientation(segment);
                    if (orientation != -1) {
                        return orientation;
                    }
                }
            }
        } catch (IOException e) {
            return 1;
        }
    }

    private static int parseExifOrientation(byte[] seg) {
        // Expect "Exif\0\0" then a TIFF block.
        if (seg.length < 8 || seg[0] != 'E' || seg[1] != 'x' || seg[2] != 'i' || seg[3] != 'f'
                || seg[4] != 0 || seg[5] != 0) {
            return -1;
        }
        int tiff = 6;
        boolean little;
        int b0 = seg[tiff] & 0xFF;
        int b1 = seg[tiff + 1] & 0xFF;
        if (b0 == 'I' && b1 == 'I') {
            little = true;
        } else if (b0 == 'M' && b1 == 'M') {
            little = false;
        } else {
            return -1;
        }
        int ifdOffset = readU32(seg, tiff + 4, little);
        int ifd = tiff + ifdOffset;
        if (ifd + 2 > seg.length) {
            return -1;
        }
        int entries = readU16(seg, ifd, little);
        int p = ifd + 2;
        for (int i = 0; i < entries; i++) {
            if (p + 12 > seg.length) {
                return -1;
            }
            int tag = readU16(seg, p, little);
            if (tag == 0x0112) { // Orientation, stored as a SHORT
                int value = readU16(seg, p + 8, little);
                if (value >= 1 && value <= 8) {
                    return value;
                }
                return -1;
            }
            p += 12;
        }
        return -1;
    }

    /**
     * Applies the orientation transform, returning an upright image. Uses exact
     * pixel remapping (a permutation - no interpolation), matching PIL's
     * {@code Transpose} operations used by {@code exif_transpose}.
     */
    public static BufferedImage applyOrientation(BufferedImage img, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return img;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        int[] src = img.getRGB(0, 0, w, h, null, 0, w);

        boolean swapDims = orientation >= 5;
        int dw = swapDims ? h : w;
        int dh = swapDims ? w : h;
        int[] dst = new int[dw * dh];

        for (int dy = 0; dy < dh; dy++) {
            for (int dx = 0; dx < dw; dx++) {
                int sx;
                int sy;
                switch (orientation) {
                    case 2 -> { sx = w - 1 - dx; sy = dy; }              // mirror horizontal
                    case 3 -> { sx = w - 1 - dx; sy = h - 1 - dy; }      // rotate 180
                    case 4 -> { sx = dx;         sy = h - 1 - dy; }      // mirror vertical
                    case 5 -> { sx = dy;         sy = dx; }              // transpose
                    case 6 -> { sx = dy;         sy = h - 1 - dx; }      // rotate 90 CW
                    case 7 -> { sx = w - 1 - dy; sy = h - 1 - dx; }      // transverse
                    case 8 -> { sx = w - 1 - dy; sy = dx; }             // rotate 90 CCW
                    default -> { sx = dx;        sy = dy; }
                }
                dst[dy * dw + dx] = src[sy * w + sx];
            }
        }

        BufferedImage out = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, dw, dh, dst, 0, dw);
        return out;
    }

    private static int read16be(InputStream in) throws IOException {
        int a = in.read();
        int b = in.read();
        if (a == -1 || b == -1) {
            return -1;
        }
        return (a << 8) | b;
    }

    private static int readU16(byte[] data, int off, boolean little) {
        int a = data[off] & 0xFF;
        int b = data[off + 1] & 0xFF;
        return little ? (b << 8) | a : (a << 8) | b;
    }

    private static int readU32(byte[] data, int off, boolean little) {
        int a = data[off] & 0xFF;
        int b = data[off + 1] & 0xFF;
        int c = data[off + 2] & 0xFF;
        int d = data[off + 3] & 0xFF;
        return little
                ? (d << 24) | (c << 16) | (b << 8) | a
                : (a << 24) | (b << 16) | (c << 8) | d;
    }
}
