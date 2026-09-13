package dev.aegis4j.api.provider;

public class ProviderAuthException extends ProviderException {

    private static final long serialVersionUID = 1L;

    public ProviderAuthException(String providerId, int httpStatus, String message) {
        super(providerId, httpStatus, message);
    }
}
