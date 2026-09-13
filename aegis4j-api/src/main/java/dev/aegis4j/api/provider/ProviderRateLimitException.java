package dev.aegis4j.api.provider;

public class ProviderRateLimitException extends ProviderException {

    private static final long serialVersionUID = 1L;

    public ProviderRateLimitException(String providerId, int httpStatus, String message) {
        super(providerId, httpStatus, message);
    }
}
