package com.allegrohelper.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Extracts still frames from a video at a fixed time interval, for {@link
 * SeriesRecognition.Mode#VIDEO}. Shells out to the {@code ffmpeg} CLI — an
 * external program the app invokes, like {@code tesseract} ({@link Ocr}) or
 * Chrome ({@link Cdp}); the project's zero-dependency rule is about Java
 * libraries only.
 *
 * <p>{@code ffmpeg}'s own {@code fps=1/N} video filter picks the frames: it
 * resamples the video to one frame every {@code N} seconds, which is exactly
 * "extract a frame every N seconds" without this class having to reason about
 * the source frame rate or timestamps itself.
 */
public final class VideoFrames {

    /** Kill an ffmpeg run that hangs on a pathological file. */
    private static final int FFMPEG_TIMEOUT_SECONDS = 300;

    /** Not instantiable: the class is a namespace for {@link #extract}. */
    private VideoFrames() {
    }

    /** Fails the run up front, with an install hint, when ffmpeg is not available. */
    static void requireFfmpeg() throws PipelineException {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                throw new PipelineException("'ffmpeg -version' failed; check the installation.");
            }
        } catch (IOException e) {
            throw new PipelineException("Video import needs the ffmpeg CLI, which was not found.\n"
                    + "Install it with: sudo apt install ffmpeg");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PipelineException("Video import was interrupted.");
        }
    }

    /**
     * Extracts frames from {@code video} into {@code destDir} as {@code
     * frame_0001.jpg}, {@code frame_0002.jpg}, etc., one every {@code
     * intervalSeconds}.
     *
     * @throws IOException if ffmpeg fails, times out, or produces no frames at
     *         all — a corrupt input can otherwise exit 0 having written nothing
     */
    static void extract(Path video, Path destDir, double intervalSeconds, Reporter reporter)
            throws IOException {
        Files.createDirectories(destDir);
        List<String> args = ffmpegArgs(video, destDir, intervalSeconds);
        Process process;
        try {
            process = new ProcessBuilder(args).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IOException("could not start ffmpeg: " + e.getMessage(), e);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!process.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("ffmpeg timed out after " + FFMPEG_TIMEOUT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("interrupted");
        }
        if (process.exitValue() != 0) {
            throw new IOException("ffmpeg exit code " + process.exitValue() + ": " + snippet(output));
        }
        int frameCount = ImportPhotos.listJpegs(destDir).size();
        if (frameCount == 0) {
            throw new IOException("ffmpeg produced no frames: " + snippet(output));
        }
        reporter.log(video.getFileName() + ": extracted " + frameCount + " frames.");
    }

    /**
     * The {@code ffmpeg} argument list, factored out so it can be unit-tested
     * without actually invoking ffmpeg.
     */
    static List<String> ffmpegArgs(Path video, Path destDir, double intervalSeconds) {
        List<String> args = new ArrayList<>();
        args.add("ffmpeg");
        args.add("-y");
        args.add("-i");
        args.add(video.toString());
        args.add("-vf");
        args.add("fps=1/" + String.format(Locale.ROOT, "%.6f", intervalSeconds));
        args.add("-q:v");
        args.add("2");
        args.add(destDir.resolve("frame_%04d.jpg").toString());
        return args;
    }

    /** The last few lines of ffmpeg's (often long) output, enough to show what went wrong. */
    private static String snippet(String output) {
        String[] lines = output.strip().split("\n");
        int from = Math.max(0, lines.length - 5);
        return String.join(" | ", java.util.Arrays.asList(lines).subList(from, lines.length));
    }
}
