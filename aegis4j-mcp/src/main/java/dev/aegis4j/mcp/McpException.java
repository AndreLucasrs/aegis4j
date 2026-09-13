package dev.aegis4j.mcp;

public class McpException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public McpException(int code, String message) {
        super(message);
        this.code = code;
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
        this.code = 0;
    }

    public int code() {
        return code;
    }
}
