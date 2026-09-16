package app;

import geokit.GeoClient;

public class Service {

    public String single(String key) {
        GeoClient client = new GeoClient();
        return client.lookup(key, true);
    }

    public String batch(String[] keys) {
        GeoClient client = new GeoClient();
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.lookup(key, true)).append(';');
        }
        return out.toString();
    }
}
