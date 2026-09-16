package app;

import geokit.GeoClient;
import geokit.Ref;

public class Service {

    private final GeoClient client;

    public Service(GeoClient client) {
        this.client = client;
    }

    public String single(String key) {
        return client.lookup(new Ref(key));
    }

    public String batch(String[] keys) {
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.lookup(new Ref(key))).append(';');
        }
        return out.toString();
    }
}
