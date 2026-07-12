package com.allegrohelper.ui;

import com.allegrohelper.util.Csv;

import javax.swing.table.AbstractTableModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Editable table model backing the "Offer Data" grid. Columns match the
 * canonical {@code offers.csv} schema; the grid can be loaded from any CSV,
 * edited by hand, and saved back tab-delimited.
 */
public final class OfferTableModel extends AbstractTableModel {

    /** Underlying CSV keys, in column order. */
    public static final String[] KEYS = {
            "name", "brand", "model", "condition", "damage", "quantity", "price", "inpost_size"
    };

    /** Human-readable column headers. */
    private static final String[] HEADERS = {
            "Name", "Brand", "Model", "Condition", "Damage", "Quantity", "Price", "InPost Size"
    };

    /** One {@code String[KEYS.length]} per row, in the order the match step pairs them with photos. */
    private final List<String[]> rows = new ArrayList<>();

    /** {@inheritDoc} */
    @Override
    public int getRowCount() {
        return rows.size();
    }

    /** {@inheritDoc} */
    @Override
    public int getColumnCount() {
        return KEYS.length;
    }

    /** The English header; {@link I18n#retranslate} swaps it when the language changes. */
    @Override
    public String getColumnName(int column) {
        return HEADERS[column];
    }

    /** {@inheritDoc} */
    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        return rows.get(rowIndex)[columnIndex];
    }

    /** Every cell is editable: the grid is how the user writes the CSV. */
    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return true;
    }

    /** Stores an edited cell, treating null as an empty string so no cell ever holds null. */
    @Override
    public void setValueAt(Object value, int rowIndex, int columnIndex) {
        rows.get(rowIndex)[columnIndex] = value == null ? "" : value.toString();
        fireTableCellUpdated(rowIndex, columnIndex);
    }

    /** Appends a blank row for a new offer. */
    public void addEmptyRow() {
        rows.add(new String[KEYS.length]);
        int i = rows.size() - 1;
        java.util.Arrays.fill(rows.get(i), "");
        fireTableRowsInserted(i, i);
    }

    /** Removes the row at {@code index}; out-of-range indices are ignored (nothing selected). */
    public void removeRow(int index) {
        if (index >= 0 && index < rows.size()) {
            rows.remove(index);
            fireTableRowsDeleted(index, index);
        }
    }

    /** Replaces the grid contents from a CSV file, mapping columns by header name. */
    public void loadFromCsv(Path csvPath) throws IOException {
        List<Map<String, String>> csvRows = Csv.read(csvPath);
        rows.clear();
        for (Map<String, String> csvRow : csvRows) {
            // Case-insensitive lookup by canonical key.
            String[] cells = new String[KEYS.length];
            for (int c = 0; c < KEYS.length; c++) {
                cells[c] = lookup(csvRow, KEYS[c]);
            }
            rows.add(cells);
        }
        fireTableDataChanged();
    }

    /** Loads from the given path if it exists; otherwise leaves the grid empty. */
    public void loadFromCsvIfPresent(Path csvPath) throws IOException {
        if (Files.isRegularFile(csvPath)) {
            loadFromCsv(csvPath);
        } else {
            rows.clear();
            fireTableDataChanged();
        }
    }

    /** Writes the grid tab-delimited to {@code csvPath}, returning the row count. */
    public int saveToCsv(Path csvPath) throws IOException {
        List<List<String>> out = new ArrayList<>();
        for (String[] row : rows) {
            List<String> cells = new ArrayList<>(KEYS.length);
            for (String cell : row) {
                cells.add(cell == null ? "" : cell);
            }
            out.add(cells);
        }
        Csv.write(csvPath, List.of(KEYS), out);
        return out.size();
    }

    /**
     * A row's value for a canonical key, matched case-insensitively so a CSV
     * from elsewhere ({@code Name}, {@code NAME}) still lines up. Absent columns
     * come back empty rather than null.
     */
    private static String lookup(Map<String, String> row, String key) {
        for (Map.Entry<String, String> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().strip().equalsIgnoreCase(key)) {
                return e.getValue() == null ? "" : e.getValue();
            }
        }
        return "";
    }
}
