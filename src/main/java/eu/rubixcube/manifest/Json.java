package eu.rubixcube.manifest;

import java.util.List;
import java.util.Map;

/** Just enough JSON writing for the manifest, so the plugin carries no dependencies. */
final class Json {

    private final StringBuilder out = new StringBuilder();
    private int depth;
    private boolean first = true;

    void open(String token) {
        separator();
        out.append(token);
        depth++;
        first = true;
    }

    void close(String token) {
        depth--;
        out.append('\n');
        indent();
        out.append(token);
        first = false;
    }

    void field(String name, String value) {
        separator();
        out.append(string(name)).append(": ").append(string(value));
    }

    void stringArray(String name, List<String> values) {
        separator();
        out.append(string(name)).append(": [");
        for (int i = 0; i < values.size(); i++) out.append(i == 0 ? "" : ", ").append(string(values.get(i)));
        out.append(']');
    }

    void object(Map<String, ?> fields) {
        separator();
        out.append("{ ");
        boolean firstField = true;
        for (Map.Entry<String, ?> field : fields.entrySet()) {
            out.append(firstField ? "" : ", ").append(string(field.getKey())).append(": ");
            final Object value = field.getValue();
            out.append(value instanceof Boolean ? value.toString() : string(String.valueOf(value)));
            firstField = false;
        }
        out.append(" }");
    }

    private void separator() {
        if (depth == 0) return;
        out.append(first ? "\n" : ",\n");
        indent();
        first = false;
    }

    private void indent() {
        for (int i = 0; i < depth; i++) out.append("  ");
    }

    private static String string(String value) {
        final StringBuilder escaped = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"': escaped.append("\\\""); break;
                case '\\': escaped.append("\\\\"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (c < 0x20) escaped.append(String.format("\\u%04x", (int) c));
                    else escaped.append(c);
            }
        }
        return escaped.append('"').toString();
    }

    @Override
    public String toString() {
        return out.append('\n').toString();
    }
}
