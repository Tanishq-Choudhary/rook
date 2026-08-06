package dev.rook.changes.openapi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class SpecFixtures {

    private SpecFixtures() {}

    public static String resource(String name) {
        try (InputStream in = SpecFixtures.class.getResourceAsStream("/openapi/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static OpenApiDocument v1() {
        return OpenApiLoader.load(resource("acme-v1.yaml"));
    }

    public static OpenApiDocument v2() {
        return OpenApiLoader.load(resource("acme-v2.yaml"));
    }
}
