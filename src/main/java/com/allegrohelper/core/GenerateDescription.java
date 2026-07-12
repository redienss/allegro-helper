package com.allegrohelper.core;

import com.allegrohelper.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates {@code description.txt} for each offer from {@code data.json} (plus
 * {@code more_data.txt} and {@code ocr.txt} when present) using
 * the OpenAI Chat Completions API. Item-specific facts (condition, damage,
 * quantity) come exclusively from the CSV data; the price is taken directly
 * from the CSV.
 *
 * <p>The prompt and generated text are intentionally kept in Polish, since the
 * listing is published on Allegro Lokalnie (Polish market).
 *
 * <p>{@link #SYSTEM_PROMPT} and {@link #USER_PROMPT} are the built-in defaults;
 * the values actually sent come from {@link Config} ({@code OPENAI_SYSTEM_PROMPT}
 * / {@code OPENAI_USER_PROMPT}, editable in File &gt; Settings &gt; OpenAI API).
 */
public final class GenerateDescription {

    /** Default system prompt, used when {@code OPENAI_SYSTEM_PROMPT} is not set. */
    public static final String SYSTEM_PROMPT =
            "Piszesz rzeczowe opisy ofert sprzedaży używanych przedmiotów prywatnych "
                    + "na Allegro Lokalnie, w języku polskim.\n\n"
                    + "Dane o KONKRETNYM egzemplarzu (pola 'condition', 'damage', 'quantity') pochodzą "
                    + "WYŁĄCZNIE z podanego JSON-a - nie zmyślaj ani nie upiększaj tych informacji. Pole "
                    + "'condition' przepisz dokładnie tak, jak zostało podane - nie dodawaj własnej oceny "
                    + "jakości (np. 'w bardzo dobrym stanie', 'świetny stan'), jeśli takiej oceny nie ma w "
                    + "danych wejściowych. Jeśli pole 'damage' zawiera coś innego niż 'brak', wymień to "
                    + "wprost. Jeśli 'quantity' > 1, zaznacz to w opisie.\n\n"
                    + "Dodatkowo możesz uzupełnić opis o OGÓLNE, oficjalne dane techniczne produktu (np. moc, "
                    + "napięcie, rodzaj złącza, pojemność, wymiary, kompatybilność), jeśli na podstawie 'brand' "
                    + "i 'model' jesteś w stanie z dużą pewnością wskazać te dane - czyli są one publicznie "
                    + "znane dla tego dokładnego modelu, a nie zgadywane. Jeśli nie masz pewności co do "
                    + "konkretnej specyfikacji, po prostu ją pomiń - nie pisz zdań w stylu 'wymiary nie zostały "
                    + "podane' ani innych wzmianek o brakujących danych. Te dane dotyczą produktu jako takiego, "
                    + "a nie stanu czy historii tego konkretnego egzemplarza - nie myl ich z polem 'condition'.\n\n"
                    + "Czasem JSON zawiera też pole 'additional_notes' z surową notatką o tym produkcie - może "
                    + "to być np. wynik testu, historia użytkowania, zawartość zestawu, specyfikacja techniczna, "
                    + "ale może to też być skopiowany fragment strony sklepu/producenta. Wykorzystaj z niej "
                    + "tylko fakty o samym produkcie, które są przydatne kupującemu (specyfikacja, zawartość "
                    + "zestawu, kompatybilność, wyniki testów) i przeredaguj je do stylu reszty opisu. Pomiń w "
                    + "niej wszystko, co nie jest przydatne dla kupującego lub nie dotyczy tego konkretnego, "
                    + "sprzedawanego egzemplarza: reklamy innych produktów, politykę zwrotów/gwarancji sklepu, "
                    + "dane kontaktowe, adres producenta, numery katalogowe/EAN oraz ogólne instrukcje "
                    + "bezpieczeństwa i obsługi (np. ostrzeżenia z instrukcji użytkownika). Jeśli notatka "
                    + "zawiera stwierdzenie o stanie produktu (np. 'stan: nowy'), które jest sprzeczne z polem "
                    + "'condition' - zignoruj je; pole 'condition' zawsze ma pierwszeństwo, bo dotyczy "
                    + "faktycznie sprzedawanego egzemplarza, a nie ogólnej oferty ze strony źródłowej.\n\n"
                    + "Czasem JSON zawiera pole 'ocr_text' - surowy tekst odczytany automatycznie (OCR) ze "
                    + "zdjęć przedmiotu: etykiety, tabliczki znamionowe, napisy na obudowie i opakowaniu. "
                    + "Nagłówki w nawiasach kwadratowych to nazwy plików zdjęć - pomiń je. Tekst może "
                    + "zawierać błędy rozpoznawania i przypadkowe fragmenty; wykorzystaj z niego tylko te "
                    + "informacje, które potrafisz pewnie zinterpretować (np. dokładne oznaczenie modelu, "
                    + "parametry z tabliczki znamionowej), a niezrozumiałe fragmenty zignoruj. W razie "
                    + "sprzeczności z pozostałymi polami JSON-a pierwszeństwo mają te pola.\n\n"
                    + "Styl: konkretny, rzeczowy, bez marketingowego zachwytu - unikaj sformułowań typu 'idealny "
                    + "do', 'świetny wybór', 'gwarantuje najwyższą jakość', a także wezwań do zakupu typu "
                    + "'zapraszam do zakupu', 'zachęcam do zakupu', 'kup teraz', również przy opisywaniu "
                    + "specyfikacji technicznych.\n\n"
                    + "Nie podawaj w treści opisu pól 'price' ani 'inpost_size' (cena i gabaryt paczki) - te "
                    + "informacje są dodawane automatycznie osobno, pod opisem.";

    /** Placeholder in the user prompt that {@link #buildUserPrompt} replaces with the offer JSON. */
    public static final String OFFER_JSON_PLACEHOLDER = "{{OFFER_JSON}}";

    /** Default user prompt template, used when {@code OPENAI_USER_PROMPT} is not set. */
    public static final String USER_PROMPT =
            "Na podstawie danych poniżej wygeneruj opis oferty dla Allegro Lokalnie. "
                    + "Dodaj trochę ikonek dla czytelności opisu.\n"
                    + "Offer data (JSON):\n"
                    + "<<<JSON>>>\n"
                    + OFFER_JSON_PLACEHOLDER + "\n"
                    + "<<<END JSON>>>";

    /** Not instantiable: the class is a namespace for {@link #runAll}. */
    private GenerateDescription() {
    }

    /**
     * Generates a description for every offer that lacks one.
     *
     * @throws PipelineException if no API key is configured, or the API rejects
     *         a request — a failed call must not leave a half-written offer
     */
    public static void runAll(Config cfg, Reporter reporter) throws IOException, PipelineException {
        if (cfg.openaiApiKey == null || cfg.openaiApiKey.isBlank()) {
            throw new PipelineException(
                    "Missing OPENAI_API_KEY. Set it in the .env file (copy .env.example) "
                            + "or in the environment before running this step.");
        }
        if (!Files.isDirectory(cfg.offersDir)) {
            reporter.log("Directory " + cfg.offersDir + " does not exist, no offers to describe.");
            reporter.stepProgress(1.0);
            return;
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        List<Path> offerDirs = listSubdirs(cfg.offersDir);
        int total = offerDirs.size();
        int index = 0;
        boolean[] omitTemperature = {false}; // set once per run, on the model's first rejection
        for (Path offerDir : offerDirs) {
            generateForOffer(client, cfg, offerDir, reporter, omitTemperature);
            reporter.stepProgress(total == 0 ? 1.0 : (double) (++index) / total);
        }
        if (total == 0) {
            reporter.stepProgress(1.0);
        }
    }

    /**
     * Generates one offer's {@code description.txt}, appending the price and
     * InPost size from the CSV — those are copied, never generated, so the model
     * cannot invent them. Idempotent: an offer that already has the file is
     * skipped, which also means a re-run does not spend money twice.
     */
    private static void generateForOffer(HttpClient client, Config cfg, Path offerDir, Reporter reporter,
                                         boolean[] omitTemperature)
            throws IOException, PipelineException {
        Path dataPath = offerDir.resolve("data.json");
        Path descriptionPath = offerDir.resolve("description.txt");

        if (!Files.isRegularFile(dataPath)) {
            return;
        }
        if (Files.exists(descriptionPath)) {
            reporter.log(offerDir.getFileName() + ": description.txt already exists, skipping.");
            return;
        }

        Map<String, Object> data = Json.parseObject(Files.readString(dataPath, StandardCharsets.UTF_8));

        Path moreDataPath = offerDir.resolve("more_data.txt");
        String extraNotes = Files.exists(moreDataPath)
                ? Files.readString(moreDataPath, StandardCharsets.UTF_8).strip()
                : "";
        Path ocrPath = offerDir.resolve("ocr.txt");
        String ocrText = Files.exists(ocrPath)
                ? Files.readString(ocrPath, StandardCharsets.UTF_8).strip()
                : "";

        String descriptionText = callOpenAi(client, cfg,
                buildUserPrompt(cfg, data, extraNotes, ocrText), reporter, omitTemperature).strip();

        double price = parsePrice(str(data, "price"));
        String content = descriptionText + "\n\n"
                + "---\n"
                + String.format(java.util.Locale.ROOT, "Cena: %.0f zł", price) + "\n"
                + "Gabaryt InPost: " + str(data, "inpost_size") + "\n";

        Files.writeString(descriptionPath, content, StandardCharsets.UTF_8);
        reporter.log(offerDir.getFileName() + ": [" + apiLabel(cfg) + "] generated description.txt.");
    }

    /**
     * The offer as the JSON the model sees: the CSV facts in a fixed key order,
     * plus {@code additional_notes} ({@code more_data.txt}) and {@code ocr_text}
     * ({@code ocr.txt}) when those are non-empty — omitted rather than sent
     * blank, so the model is never handed an empty field to fill in.
     */
    static String buildOfferJson(Map<String, Object> data, String extraNotes, String ocrText) {
        LinkedHashMap<String, Object> offer = new LinkedHashMap<>();
        offer.put("name", str(data, "name"));
        offer.put("brand", str(data, "brand"));
        offer.put("model", str(data, "model"));
        offer.put("condition", str(data, "condition"));
        offer.put("damage", str(data, "damage"));
        offer.put("quantity", str(data, "quantity"));
        offer.put("price", str(data, "price"));
        offer.put("inpost_size", str(data, "inpost_size"));
        if (!extraNotes.isEmpty()) {
            offer.put("additional_notes", extraNotes);
        }
        if (!ocrText.isEmpty()) {
            offer.put("ocr_text", ocrText);
        }
        return Json.write(offer, true);
    }

    /**
     * Fills the configured user-prompt template with the offer JSON. A template
     * the user edited the {@link #OFFER_JSON_PLACEHOLDER} out of would silently
     * ask the model to describe nothing, so the data is appended in the default
     * framing instead of being dropped.
     */
    static String buildUserPrompt(Config cfg, Map<String, Object> data, String extraNotes, String ocrText) {
        String offerJson = buildOfferJson(data, extraNotes, ocrText);
        String template = cfg.openaiUserPrompt;
        if (template.contains(OFFER_JSON_PLACEHOLDER)) {
            return template.replace(OFFER_JSON_PLACEHOLDER, offerJson);
        }
        // A customized template without the placeholder would silently drop the
        // offer data, so append it in the default framing instead.
        return template + "\nOffer data (JSON):\n<<<JSON>>>\n" + offerJson + "\n<<<END JSON>>>";
    }

    /**
     * Posts one chat completion and returns the generated text.
     *
     * @param omitTemperature a single-element flag shared across the run: set
     *        once a model rejects the temperature parameter, so only the first
     *        offer pays for the retry
     */
    private static String callOpenAi(HttpClient client, Config cfg, String userPrompt,
                                     Reporter reporter, boolean[] omitTemperature)
            throws IOException, PipelineException {
        List<Object> messages = new ArrayList<>();
        messages.add(message("system", cfg.openaiSystemPrompt));
        messages.add(message("user", userPrompt));

        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.openaiModel);
        body.put("messages", messages);
        if (!omitTemperature[0]) {
            body.put("temperature", 0.4);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cfg.openaiBaseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cfg.openaiApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body, false), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PipelineException("Description generation was interrupted.");
        }

        if (response.statusCode() != 200) {
            // Reasoning models (gpt-5, the o-series) accept only the default
            // temperature and reject the request otherwise. Detect that exact
            // rejection instead of hardcoding a model list, and drop the
            // parameter for the rest of the run.
            if (!omitTemperature[0] && response.statusCode() == 400
                    && rejectsTemperature(response.body())) {
                reporter.log("Model " + cfg.openaiModel
                        + " does not support a custom temperature, retrying without it.");
                omitTemperature[0] = true;
                return callOpenAi(client, cfg, userPrompt, reporter, omitTemperature);
            }
            throw new PipelineException("OpenAI API returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }

        return extractContent(response.body());
    }

    /** Whether an error response rejects the {@code temperature} parameter specifically. */
    private static boolean rejectsTemperature(String responseBody) {
        try {
            Map<String, Object> root = Json.parseObject(responseBody);
            return root.get("error") instanceof Map<?, ?> error
                    && "temperature".equals(error.get("param"));
        } catch (RuntimeException e) {
            return false; // not the documented error shape - let the caller report it verbatim
        }
    }

    /**
     * Digs the message text out of a chat-completions response. Any shape other
     * than the documented one becomes a {@link PipelineException} carrying the
     * raw body — better than writing a description built from a misread reply.
     */
    @SuppressWarnings("unchecked")
    private static String extractContent(String responseBody) throws PipelineException {
        try {
            Map<String, Object> root = Json.parseObject(responseBody);
            List<Object> choices = (List<Object>) root.get("choices");
            Map<String, Object> first = (Map<String, Object>) choices.get(0);
            Map<String, Object> message = (Map<String, Object>) first.get("message");
            Object content = message.get("content");
            return content == null ? "" : content.toString();
        } catch (RuntimeException e) {
            throw new PipelineException("Unexpected OpenAI API response: " + responseBody);
        }
    }

    /** One chat message object for the request body. */
    private static Map<String, Object> message(String role, String content) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /**
     * Human-readable name of the description API, shown in the log. Currently the
     * only backend is the OpenAI (Chat Completions) API; when API selection
     * becomes configurable, switch on the config here.
     */
    private static String apiLabel(Config cfg) {
        return "OpenAI API";
    }

    /** A {@code data.json} field as a string; empty when absent. */
    private static String str(Map<String, Object> data, String key) {
        Object v = data.get(key);
        return v == null ? "" : v.toString();
    }

    /** The CSV price as a number; 0 when it is missing or unparseable. */
    private static double parsePrice(String price) {
        try {
            return Double.parseDouble(price.strip());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /** The offer directories under {@code dir}, in name order. */
    private static List<Path> listSubdirs(Path dir) throws IOException {
        List<Path> dirs = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory).forEach(dirs::add);
        }
        dirs.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return dirs;
    }
}
