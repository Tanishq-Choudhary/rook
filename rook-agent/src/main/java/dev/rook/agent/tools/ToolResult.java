package dev.rook.agent.tools;

public record ToolResult(String text, boolean error, long millis) {

    public ToolResult {
        text = text == null ? "" : text;
    }

    public static ToolResult ok(String text) {
        return new ToolResult(text, false, 0);
    }

    public static ToolResult failure(String text) {
        return new ToolResult(text, true, 0);
    }

    public ToolResult withMillis(long elapsed) {
        return new ToolResult(text, error, elapsed);
    }

    public ToolResult withText(String newText) {
        return new ToolResult(newText, error, millis);
    }
}
