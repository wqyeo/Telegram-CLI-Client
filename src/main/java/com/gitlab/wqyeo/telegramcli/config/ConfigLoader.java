package com.gitlab.wqyeo.telegramcli.config;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loads bundled defaults and an optional explicit JSON override, without writing files. */
public final class ConfigLoader {
    private static final int MAX_BYTES = 65_536;
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    public AppConfig load(Path override) throws IOException {
        try (var defaults = ConfigLoader.class.getResourceAsStream("/application-defaults.json")) {
            if (defaults == null) {
                throw new IOException("Bundled defaults are unavailable.");
            }
            var settings = (ObjectNode) mapper.readTree(defaults);
            if (override != null) {
                try (var input = Files.newInputStream(override)) {
                    var bytes = input.readNBytes(MAX_BYTES + 1);
                    if (bytes.length > MAX_BYTES) {
                        throw new IOException("Configuration exceeds the size limit.");
                    }
                    var replacement = mapper.readTree(bytes);
                    if (!(replacement instanceof ObjectNode object)) {
                        throw new IOException("Configuration must be a JSON object.");
                    }
                    settings.setAll(object);
                }
            }
            return mapper.treeToValue(settings, AppConfig.class);
        }
    }
}
