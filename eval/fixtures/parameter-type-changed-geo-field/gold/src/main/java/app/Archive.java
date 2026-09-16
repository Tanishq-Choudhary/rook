package app;

import geokit.GeoClient;
import geokit.Ref;

public class Archive {

    private final GeoClient client = new GeoClient();

    public String once(String key) {
        return client.lookup(new Ref(key));
    }
}
