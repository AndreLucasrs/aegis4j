package dev.aegis4j.api.provider;

public class ProviderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String providerId;
    private final int httpStatus;

    public ProviderException(String providerId, int httpStatus, String message) {
        this(providerId, httpStatus, message, null);
    }

    public ProviderException(String providerId, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.providerId = providerId;
        this.httpStatus = httpStatus;
    }

    public String providerId() {
        return providerId;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
