package app;

import geokit.GeoClient;

public class Archive {

    private final GeoClient client = new GeoClient();

    public String once(String key) {
        return client.lookup(key);
    }
}
