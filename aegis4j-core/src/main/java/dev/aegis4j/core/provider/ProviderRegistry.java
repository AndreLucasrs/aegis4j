package dev.aegis4j.core.provider;

import dev.aegis4j.api.provider.Provider;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a {@link Provider} by id at runtime. Populated either by explicit
 * {@link #register} calls or by {@link #discover} scanning
 * {@code META-INF/services/dev.aegis4j.api.provider.Provider} on the given
 * classloader — {@code aegis4j-core} never depends on a concrete provider
 * module directly.
 */
public final class ProviderRegistry {

    private final Map<String, Provider> providersById = new ConcurrentHashMap<>();

    public void register(Provider provider) {
        providersById.put(provider.id(), provider);
    }

    public void discover(ClassLoader classLoader) {
        ServiceLoader.load(Provider.class, classLoader).forEach(this::register);
    }

    public Provider resolve(String providerId) {
        Provider provider = providersById.get(providerId);
        if (provider == null) {
            throw new NoSuchElementException("No provider registered with id '" + providerId + "'");
        }
        return provider;
    }

    public boolean isRegistered(String providerId) {
        return providersById.containsKey(providerId);
    }
}
