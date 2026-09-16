package app;

import geokit.client.GeoClient;

public class Archive {

    private final GeoClient client = new GeoClient();

    public String once(String key) {
        return client.lookup(key);
    }
}
