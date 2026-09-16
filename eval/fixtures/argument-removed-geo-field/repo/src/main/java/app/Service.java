package app;

import geokit.GeoClient;

public class Service {

    private final GeoClient client;

    public Service(GeoClient client) {
        this.client = client;
    }

    public String single(String key) {
        return client.lookup(key, true);
    }

    public String batch(String[] keys) {
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.lookup(key, true)).append(';');
        }
        return out.toString();
    }
}
