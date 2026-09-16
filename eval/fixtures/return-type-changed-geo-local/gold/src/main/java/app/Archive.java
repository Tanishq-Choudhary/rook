package app;

import geokit.GeoClient;

public class Archive {

    public String once(String key) {
        GeoClient archive = new GeoClient();
        return archive.lookup(key).id();
    }
}
