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

    private GenerateDescription() {
    }

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
        for (Path offerDir : offerDirs) {
            generateForOffer(client, cfg, offerDir, reporter);
            reporter.stepProgress(total == 0 ? 1.0 : (double) (++index) / total);
        }
        if (total == 0) {
            reporter.stepProgress(1.0);
        }
    }

    private static void generateForOffer(HttpClient client, Config cfg, Path offerDir, Reporter reporter)
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

        String descriptionText =
                callOpenAi(client, cfg, buildUserPrompt(cfg, data, extraNotes, ocrText)).strip();

        double price = parsePrice(str(data, "price"));
        String content = descriptionText + "\n\n"
                + "---\n"
                + String.format(java.util.Locale.ROOT, "Cena: %.0f zł", price) + "\n"
                + "Gabaryt InPost: " + str(data, "inpost_size") + "\n";

        Files.writeString(descriptionPath, content, StandardCharsets.UTF_8);
        reporter.log(offerDir.getFileName() + ": [" + apiLabel(cfg) + "] generated description.txt.");
    }

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

    private static String callOpenAi(HttpClient client, Config cfg, String userPrompt)
            throws IOException, PipelineException {
        List<Object> messages = new ArrayList<>();
        messages.add(message("system", cfg.openaiSystemPrompt));
        messages.add(message("user", userPrompt));

        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.openaiModel);
        body.put("messages", messages);
        body.put("temperature", 0.4);

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
            throw new PipelineException("OpenAI API returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }

        return extractContent(response.body());
    }

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

    private static String str(Map<String, Object> data, String key) {
        Object v = data.get(key);
        return v == null ? "" : v.toString();
    }

    private static double parsePrice(String price) {
        try {
            return Double.parseDouble(price.strip());
        } catch (NumberFormatException e) {
            return 0.0;
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
