package io.github.awsopstoolkit.report;

/** UTF-8 at the writer boundary. Quote cells and mitigate common spreadsheet formula prefixes. */
public final class Csv {
    private Csv() {}

    public static String cell(String value) {
        if (value == null) return "\"\"";
        String text = value;
        String trimmed = text.stripLeading();
        if (!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0
                || text.startsWith("\t")
                || text.startsWith("\r")
                || text.startsWith("\n")) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
