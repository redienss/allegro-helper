package com.allegrohelper.core;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Automatic cropping of a photo series to the photographed item.
 *
 * <p>The photos come from a turntable: the item rotates while the background
 * and the table stay put. Brightness alone cannot separate them — the items are
 * often white against a light backdrop — and edge detection latches onto the
 * table's front edge, which spans the whole frame. What does separate them is
 * time: across a series, only the pixels covering the item change. So the
 * subject mask is the per-pixel range (max - min) of luminance over the series,
 * thresholded with Otsu's method, reduced to its largest connected blob.
 *
 * <p>One crop box is computed per series and applied to every photo in it, so
 * the item does not jump around between frames. The box is grown by a margin,
 * expanded to the source aspect ratio and clamped to the frame.
 *
 * <p>Detection is deliberately conservative: if the series is too short, the
 * subject implausibly small, or the resulting box would keep nearly the whole
 * frame, the offer is left uncropped rather than cropped wrongly.
 */
public final class AutoCrop {

    /** Detection runs on frames downscaled to this width; plenty for a bounding box. */
    private static final int WORK_WIDTH = 400;

    /** Fraction of the subject box added as breathing room on each side. */
    private static final double MARGIN = 0.06;

    /** Ranges below this are JPEG noise, not movement. */
    private static final int NOISE_FLOOR = 12;

    /** A blob smaller than this fraction of the frame is not the item. */
    private static final double MIN_SUBJECT_FRACTION = 0.002;

    /** If the crop keeps more than this, it is not worth doing. */
    private static final double MAX_COVERAGE = 0.95;

    /** Detecting movement needs at least this many frames. */
    private static final int MIN_FRAMES = 2;

    private AutoCrop() {
    }

    public static void runAll(Config cfg, Reporter reporter) throws IOException {
        if (!Files.isDirectory(cfg.offersDir)) {
            reporter.log("Directory " + cfg.offersDir + " does not exist, no offers to crop.");
            reporter.stepProgress(1.0);
            return;
        }

        List<Path> offerDirs = listSubdirs(cfg.offersDir);
        int total = offerDirs.size();
        int index = 0;
        for (Path offerDir : offerDirs) {
            cropOffer(offerDir, reporter);
            reporter.stepProgress(total == 0 ? 1.0 : (double) (++index) / total);
        }
        if (total == 0) {
            reporter.stepProgress(1.0);
        }
    }

    /** Crops one offer's photos into its {@code cropped/} directory. */
    public static void cropOffer(Path offerDir, Reporter reporter) throws IOException {
        Path inputDir = cropInput(offerDir);
        Path croppedDir = offerDir.resolve("cropped");
        String name = offerDir.getFileName().toString();

        List<Path> photos = inputDir == null ? List.of() : ImportPhotos.listJpegs(inputDir);
        if (photos.isEmpty()) {
            reporter.log(name + ": no photos to crop.");
            return;
        }
        if (Files.isDirectory(croppedDir) && countEntries(croppedDir) == photos.size()) {
            reporter.log(name + ": auto-cropping already done, skipping.");
            return;
        }
        if (photos.size() < MIN_FRAMES) {
            reporter.log(name + ": needs at least " + MIN_FRAMES
                    + " photos to detect the item, leaving uncropped.");
            return;
        }

        Box box = detect(photos);
        if (box == null) {
            reporter.log(name + ": could not detect the item, leaving uncropped.");
            return;
        }

        Files.createDirectories(croppedDir);
        for (Path photo : photos) {
            BufferedImage img = ImageIO.read(photo.toFile());
            if (img == null) {
                throw new IOException("Could not read image " + photo);
            }
            // Originals may carry EXIF orientation (retouch outputs never do);
            // the box is in upright coordinates, so upright the pixels first.
            img = Exif.applyOrientation(img, Exif.readOrientation(photo));
            BufferedImage cut = img.getSubimage(box.x, box.y, box.w, box.h);
            Retouch.writeJpeg(cut, croppedDir.resolve(photo.getFileName().toString()));
        }
        reporter.log(name + ": cropped " + photos.size() + " photos to "
                + box.w + "x" + box.h + ".");
    }

    /**
     * The most-processed input available: auto-contrasted, else white-balanced,
     * else the pre-split {@code retouched/}, else the originals in
     * {@code photos/}. The retouch outputs are already upright; the originals
     * still carry EXIF orientation, which ImageIO ignores, so every decode in
     * this step applies {@link Exif} orientation itself — otherwise a crop from
     * an original could cut a sideways box out of an upright scene.
     */
    private static Path cropInput(Path offerDir) {
        for (String dirName : new String[] {
                Retouch.Mode.AUTO_CONTRAST.dirName, Retouch.Mode.WHITE_BALANCE.dirName,
                "retouched", "photos"}) {
            Path dir = offerDir.resolve(dirName);
            if (Files.isDirectory(dir)) {
                return dir;
            }
        }
        return null;
    }

    /** The crop rectangle in full-resolution source pixels. */
    private record Box(int x, int y, int w, int h) {
    }

    /**
     * Finds the crop box for a series, or null when the item cannot be located
     * confidently.
     */
    private static Box detect(List<Path> photos) throws IOException {
        Frame first = readLuma(photos.get(0));
        int w = first.w;
        int h = first.h;
        int n = w * h;

        int[] max = first.luma.clone();
        int[] min = first.luma.clone();
        for (int i = 1; i < photos.size(); i++) {
            Frame f = readLuma(photos.get(i));
            if (f.w != w || f.h != h || f.srcW != first.srcW || f.srcH != first.srcH) {
                return null; // mixed resolutions: not one series
            }
            for (int p = 0; p < n; p++) {
                int v = f.luma[p];
                if (v > max[p]) {
                    max[p] = v;
                }
                if (v < min[p]) {
                    min[p] = v;
                }
            }
        }

        int[] hist = new int[256];
        int[] range = new int[n];
        for (int p = 0; p < n; p++) {
            int r = max[p] - min[p];
            range[p] = r;
            hist[r]++;
        }

        int threshold = Math.max(otsu(hist, n), NOISE_FLOOR);
        boolean[] mask = new boolean[n];
        for (int p = 0; p < n; p++) {
            mask[p] = range[p] > threshold;
        }

        int[] blob = largestComponent(mask, w, h);
        if (blob == null || blob[4] < MIN_SUBJECT_FRACTION * n) {
            return null;
        }

        // Work pixels -> source pixels, then margin, aspect and clamping.
        double sx = first.srcW / (double) w;
        double sy = first.srcH / (double) h;
        double x0 = blob[0] * sx;
        double y0 = blob[1] * sy;
        double x1 = (blob[2] + 1) * sx;
        double y1 = (blob[3] + 1) * sy;

        double mx = (x1 - x0) * MARGIN;
        double my = (y1 - y0) * MARGIN;
        x0 -= mx;
        y0 -= my;
        x1 += mx;
        y1 += my;

        Box box = expandToAspect(x0, y0, x1, y1, first.srcW, first.srcH);
        double coverage = (double) box.w * box.h / ((double) first.srcW * first.srcH);
        return coverage > MAX_COVERAGE ? null : box;
    }

    /**
     * Grows the box to the source aspect ratio around its centre, then slides it
     * back inside the frame.
     */
    private static Box expandToAspect(double x0, double y0, double x1, double y1,
                                      int srcW, int srcH) {
        double aspect = srcW / (double) srcH;
        double w = x1 - x0;
        double h = y1 - y0;
        if (w / h < aspect) {
            w = h * aspect;
        } else {
            h = w / aspect;
        }
        if (w > srcW) {
            w = srcW;
            h = w / aspect;
        }
        if (h > srcH) {
            h = srcH;
            w = h * aspect;
        }

        double cx = (x0 + x1) / 2.0;
        double cy = (y0 + y1) / 2.0;
        double nx = Math.max(0, Math.min(cx - w / 2.0, srcW - w));
        double ny = Math.max(0, Math.min(cy - h / 2.0, srcH - h));

        int ix = (int) Math.round(nx);
        int iy = (int) Math.round(ny);
        int iw = Math.min((int) Math.round(w), srcW - ix);
        int ih = Math.min((int) Math.round(h), srcH - iy);
        return new Box(ix, iy, iw, ih);
    }

    /** Otsu's threshold: the value maximising between-class variance. */
    private static int otsu(int[] hist, int total) {
        long sumAll = 0;
        for (int i = 0; i < 256; i++) {
            sumAll += (long) i * hist[i];
        }
        long sumB = 0;
        int weightB = 0;
        double bestVar = -1;
        int best = 0;
        for (int t = 0; t < 256; t++) {
            weightB += hist[t];
            if (weightB == 0) {
                continue;
            }
            int weightF = total - weightB;
            if (weightF == 0) {
                break;
            }
            sumB += (long) t * hist[t];
            double meanB = sumB / (double) weightB;
            double meanF = (sumAll - sumB) / (double) weightF;
            double var = (double) weightB * weightF * (meanB - meanF) * (meanB - meanF);
            if (var > bestVar) {
                bestVar = var;
                best = t;
            }
        }
        return best;
    }

    /**
     * Largest 4-connected blob of the mask, as {@code {x0, y0, x1, y1, area}},
     * or null when the mask is empty.
     */
    private static int[] largestComponent(boolean[] mask, int w, int h) {
        boolean[] seen = new boolean[mask.length];
        int[] stack = new int[mask.length];
        int[] best = null;

        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || seen[start]) {
                continue;
            }
            int top = 0;
            stack[top++] = start;
            seen[start] = true;

            int area = 0;
            int x0 = start % w;
            int x1 = x0;
            int y0 = start / w;
            int y1 = y0;

            while (top > 0) {
                int i = stack[--top];
                area++;
                int x = i % w;
                int y = i / w;
                if (x < x0) {
                    x0 = x;
                }
                if (x > x1) {
                    x1 = x;
                }
                if (y < y0) {
                    y0 = y;
                }
                if (y > y1) {
                    y1 = y;
                }
                if (x > 0 && push(mask, seen, stack, top, i - 1)) {
                    top++;
                }
                if (x < w - 1 && push(mask, seen, stack, top, i + 1)) {
                    top++;
                }
                if (y > 0 && push(mask, seen, stack, top, i - w)) {
                    top++;
                }
                if (y < h - 1 && push(mask, seen, stack, top, i + w)) {
                    top++;
                }
            }
            if (best == null || area > best[4]) {
                best = new int[]{x0, y0, x1, y1, area};
            }
        }
        return best;
    }

    private static boolean push(boolean[] mask, boolean[] seen, int[] stack, int top, int i) {
        if (!mask[i] || seen[i]) {
            return false;
        }
        seen[i] = true;
        stack[top] = i;
        return true;
    }

    /** A frame downscaled to {@link #WORK_WIDTH}, plus its full-resolution size. */
    private record Frame(int[] luma, int w, int h, int srcW, int srcH) {
    }

    /**
     * Reads a JPEG downscaled to roughly {@link #WORK_WIDTH} wide, as luminance.
     * Subsampling happens in the decoder, so the full image is never held. EXIF
     * orientation is applied to the downscaled frame (and the reported source
     * size), so the mask and box live in upright coordinates even for originals.
     */
    private static Frame readLuma(Path file) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) {
                throw new IOException("Could not open image " + file);
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("No image reader for " + file);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int srcW = reader.getWidth(0);
                int srcH = reader.getHeight(0);

                ImageReadParam param = reader.getDefaultReadParam();
                int sub = Math.max(1, srcW / WORK_WIDTH);
                param.setSourceSubsampling(sub, sub, 0, 0);
                BufferedImage img = reader.read(0, param);

                int orientation = Exif.readOrientation(file);
                img = Exif.applyOrientation(img, orientation);
                if (orientation >= 5) { // the transform swapped width and height
                    int t = srcW;
                    srcW = srcH;
                    srcH = t;
                }

                int w = img.getWidth();
                int h = img.getHeight();
                int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
                int[] luma = new int[w * h];
                for (int i = 0; i < luma.length; i++) {
                    int p = rgb[i];
                    int r = (p >> 16) & 0xFF;
                    int g = (p >> 8) & 0xFF;
                    int b = p & 0xFF;
                    luma[i] = (r * 299 + g * 587 + b * 114) / 1000;
                }
                return new Frame(luma, w, h, srcW, srcH);
            } finally {
                reader.dispose();
            }
        }
    }

    private static long countEntries(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.count();
        }
    }

    private static List<Path> listSubdirs(Path dir) throws IOException {
        List<Path> dirs = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory).forEach(dirs::add);
        }
        dirs.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return dirs;
    }
}
