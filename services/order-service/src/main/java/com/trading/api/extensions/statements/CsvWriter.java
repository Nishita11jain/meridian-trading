package com.trading.api.extensions.statements;

import java.util.List;

/**
 * Writes RFC 4180 CSV. A cell that starts with =, +, - or @ gets an apostrophe in front, so a spreadsheet
 * opens it as text and cannot run it as a formula (CSV injection).
 */
final class CsvWriter {

    private static final String FORMULA_START = "=+-@";

    private CsvWriter() {
    }

    static String row(List<String> cells) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(cell(cells.get(i)));
        }
        return line.append("\r\n").toString();
    }

    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String safe = value;
        if (!safe.isEmpty() && FORMULA_START.indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        boolean quote = safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r");
        return quote ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }
}
