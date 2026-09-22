package com.allegrohelper.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic coverage only: real {@code ffmpeg} invocation is an external
 * program, the same carve-out {@link Ocr} has for {@code tesseract} — kept out
 * of this hermetic suite and verified manually instead (see CLAUDE.md).
 */
class VideoFramesTest {

    @Test
    void ffmpegArgsExtractOneFrameEveryIntervalSeconds() {
        List<String> args = VideoFrames.ffmpegArgs(
                Path.of("/tmp/video.mp4"), Path.of("/tmp/out"), 5.0);

        assertEquals(List.of("ffmpeg", "-y", "-i", "/tmp/video.mp4",
                "-vf", "fps=1/5.000000", "-q:v", "2", "/tmp/out/frame_%04d.jpg"), args);
    }

    @Test
    void ffmpegArgsAcceptSubSecondIntervals() {
        List<String> args = VideoFrames.ffmpegArgs(
                Path.of("/tmp/video.mp4"), Path.of("/tmp/out"), 0.5);

        assertEquals("fps=1/0.500000", args.get(5));
    }
}
