package app;

import geokit.client.GeoClient;

public class Archive {

    public String once(String key) {
        GeoClient archive = new GeoClient();
        return archive.lookup(key);
    }
}
