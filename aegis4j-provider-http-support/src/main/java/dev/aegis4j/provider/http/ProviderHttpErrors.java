package dev.aegis4j.provider.http;

import dev.aegis4j.api.provider.ProviderAuthException;
import dev.aegis4j.api.provider.ProviderException;
import dev.aegis4j.api.provider.ProviderRateLimitException;

/** Maps an HTTP error response to the right {@link ProviderException} subtype, shared across provider modules. */
public final class ProviderHttpErrors {

    private ProviderHttpErrors() {
    }

    public static ProviderException map(String providerId, int status, String body) {
        if (status == 401 || status == 403) {
            return new ProviderAuthException(providerId, status, body);
        }
        if (status == 429) {
            return new ProviderRateLimitException(providerId, status, body);
        }
        return new ProviderException(providerId, status, body);
    }
}
