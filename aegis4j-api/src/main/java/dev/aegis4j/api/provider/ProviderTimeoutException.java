package dev.aegis4j.api.provider;

public class ProviderTimeoutException extends ProviderException {

    private static final long serialVersionUID = 1L;

    public ProviderTimeoutException(String providerId, String message, Throwable cause) {
        super(providerId, 0, message, cause);
    }
}
