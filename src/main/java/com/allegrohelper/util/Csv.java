package com.allegrohelper.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reader/writer for the offers table.
 *
 * <p>The canonical {@code offers.csv} is tab-delimited. On load the delimiter
 * is auto-detected (tab if the header contains one, otherwise comma) so an
 * arbitrary CSV can be imported; on save the file is always written
 * tab-delimited, which is what the match step expects.
 */
public final class Csv {

    /** Not instantiable: the class is a namespace for {@link #read} and {@link #write}. */
    private Csv() {
    }

    /** Reads rows as ordered maps keyed by the header names (values are strings). */
    public static List<Map<String, String>> read(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<Map<String, String>> rows = new ArrayList<>();
        if (lines.isEmpty()) {
            return rows;
        }
        // Drop a trailing empty line, if any.
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        if (lines.isEmpty()) {
            return rows;
        }
        String header = lines.get(0);
        char delimiter = header.indexOf('\t') >= 0 ? '\t' : ',';
        String[] columns = splitLine(header, delimiter);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                continue;
            }
            String[] cells = splitLine(line, delimiter);
            LinkedHashMap<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < columns.length; c++) {
                row.put(columns[c], c < cells.length ? cells[c] : "");
            }
            rows.add(row);
        }
        return rows;
    }

    /** Writes {@code rows} tab-delimited, using {@code columns} as the header order. */
    public static void write(Path path, List<String> columns, List<List<String>> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\t", columns)).append('\n');
        for (List<String> row : rows) {
            List<String> cells = new ArrayList<>(columns.size());
            for (int c = 0; c < columns.size(); c++) {
                cells.add(c < row.size() ? sanitize(row.get(c)) : "");
            }
            sb.append(String.join("\t", cells)).append('\n');
        }
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Splits one line on the delimiter, keeping trailing empty cells. */
    private static String[] splitLine(String line, char delimiter) {
        // Simple split: the tab-delimited offers file uses no quoting, and cell
        // values themselves never contain the delimiter.
        return line.split(java.util.regex.Pattern.quote(String.valueOf(delimiter)), -1);
    }

    /** Makes one cell safe for the unquoted tab-delimited format. */
    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        // Guard the tab-delimited format against stray tabs/newlines in a cell.
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
