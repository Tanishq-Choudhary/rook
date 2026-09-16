package app;

import geokit.GeoClient;
import geokit.Ref;

public class Service {

    public String single(String key) {
        GeoClient client = new GeoClient();
        return client.lookup(new Ref(key));
    }

    public String batch(String[] keys) {
        GeoClient client = new GeoClient();
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.lookup(new Ref(key))).append(';');
        }
        return out.toString();
    }
}
