package com.allegrohelper.core;

import com.allegrohelper.util.Json;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fills the Allegro Lokalnie "Wystaw przedmiot" form in Google Chrome —
 * photos, title and description — via the DevTools protocol ({@link Cdp}).
 * It never submits: the user reviews the form and clicks "Wystaw" themselves.
 *
 * <p>Each field needs its own trick, because the form is a reactive SPA that
 * ignores plainly mutated DOM:
 * <ul>
 *   <li><b>Photos</b> go through {@code DOM.setFileInputFiles} on the hidden
 *       file input — the protocol's replacement for picking files in the
 *       dialog, after which the page uploads them itself.</li>
 *   <li><b>Title</b> is a framework-controlled input, so the value is written
 *       through the native {@code HTMLInputElement.value} setter and an
 *       {@code input} event is dispatched for the framework to notice.</li>
 *   <li><b>Description</b> is a TipTap/ProseMirror rich-text editor, not a
 *       textarea; it only listens to real editing input. The text is therefore
 *       typed via {@code Input.insertText}, line by line with synthesized
 *       Enter key events in between, which both the live editor and a plain
 *       contenteditable handle like actual typing.</li>
 * </ul>
 *
 * <p>The selectors are Allegro's own {@code data-testid} hooks — the most
 * redesign-resistant anchors the page offers. If the form stops filling,
 * check these against the live page first.
 */
public final class AllegroForm {

    private static final String TITLE_SELECTOR = "input[data-testid=\"offer-title-input\"]";
    private static final String PHOTOS_SELECTOR = "input[data-testid=\"drag-drop-photo-upload\"]";
    private static final String DESCRIPTION_SELECTOR = ".ProseMirror[contenteditable=\"true\"]";

    /** How long to wait for the form — generous, the user may need to log in first. */
    private static final int FORM_WAIT_SECONDS = 180;

    private AllegroForm() {
    }

    /**
     * Opens {@code url} in the app's Chrome instance and fills the offer form.
     * Blank/empty inputs are skipped individually, so partial data still fills
     * what it can.
     */
    public static void fill(Config cfg, String url, List<Path> photos, String title,
                            String description, Reporter reporter) throws IOException {
        int port = Cdp.ensureChrome(cfg.chromeProfileDir, cfg.chromeBin, reporter);
        String wsUrl = Cdp.openTab(port, url);
        reporter.log("Opened " + url + " in Chrome.");

        try (Cdp cdp = Cdp.connect(wsUrl)) {
            waitForForm(cdp, reporter);

            if (photos.isEmpty()) {
                reporter.log("No photos selected, skipping the photo upload.");
            } else {
                int nodeId = cdp.querySelector(PHOTOS_SELECTOR);
                if (nodeId == 0) {
                    throw new IOException("Photo upload input not found on the form.");
                }
                List<String> files = new ArrayList<>();
                for (Path photo : photos) {
                    files.add(photo.toAbsolutePath().toString());
                }
                cdp.setFileInputFiles(nodeId, files);
                reporter.log("Handed " + files.size() + " photos to the form's upload input.");
            }

            if (title.isBlank()) {
                reporter.log("The title is empty, skipping it.");
            } else {
                fillTitle(cdp, title);
                reporter.log("Filled the title.");
            }

            if (description.isBlank()) {
                reporter.log("The description is empty, skipping it.");
            } else {
                fillDescription(cdp, description);
                reporter.log("Typed the description into the editor.");
            }
        }
        reporter.log("Form filled. Review it in Chrome, complete the remaining fields"
                + " and submit it yourself.");
    }

    /**
     * Polls until the title input exists. The tab may sit on a login page for
     * a while — the user logs in by hand and Allegro brings the form back.
     */
    private static void waitForForm(Cdp cdp, Reporter reporter) throws IOException {
        reporter.log("Waiting for the offer form (log in to Allegro in the Chrome"
                + " window if it asks)…");
        long deadline = System.nanoTime() + FORM_WAIT_SECONDS * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            Object present = cdp.eval("!!document.querySelector('" + TITLE_SELECTOR + "')");
            if (Boolean.TRUE.equals(present)) {
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for the form.");
            }
        }
        throw new IOException("The offer form did not appear within "
                + FORM_WAIT_SECONDS + "s. Log in to Allegro in the Chrome window and retry.");
    }

    private static void fillTitle(Cdp cdp, String title) throws IOException {
        // Json.write produces a valid JS string literal, escaping included.
        String js = "(() => {"
                + "const el = document.querySelector('" + TITLE_SELECTOR + "');"
                + "if (!el) return null;"
                + "const set = Object.getOwnPropertyDescriptor("
                + "    HTMLInputElement.prototype, 'value').set;"
                + "set.call(el, " + Json.write(title, false) + ");"
                + "el.dispatchEvent(new Event('input', {bubbles: true}));"
                + "el.dispatchEvent(new Event('change', {bubbles: true}));"
                + "return el.value;"
                + "})()";
        Object value = cdp.eval(js);
        if (!title.equals(value)) {
            throw new IOException("The title did not stick (form field reported: " + value + ").");
        }
    }

    private static void fillDescription(Cdp cdp, String description) throws IOException {
        Object focused = cdp.eval("(() => {"
                + "const el = document.querySelector('" + DESCRIPTION_SELECTOR + "');"
                + "if (!el) return false;"
                + "el.focus();"
                + "return document.activeElement === el || el.contains(document.activeElement);"
                + "})()");
        if (!Boolean.TRUE.equals(focused)) {
            throw new IOException("Could not focus the description editor.");
        }

        String[] lines = description.replace("\r\n", "\n").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                pressEnter(cdp);
            }
            if (!lines[i].isEmpty()) {
                cdp.send("Input.insertText", Map.of("text", lines[i]));
            }
        }
    }

    /** A full synthesized Enter keystroke — how new paragraphs are made when typing. */
    private static void pressEnter(Cdp cdp) throws IOException {
        cdp.send("Input.dispatchKeyEvent", Map.of(
                "type", "rawKeyDown", "windowsVirtualKeyCode", 13,
                "key", "Enter", "code", "Enter"));
        cdp.send("Input.dispatchKeyEvent", Map.of(
                "type", "char", "text", "\r", "key", "Enter", "code", "Enter"));
        cdp.send("Input.dispatchKeyEvent", Map.of(
                "type", "keyUp", "windowsVirtualKeyCode", 13,
                "key", "Enter", "code", "Enter"));
    }
}
