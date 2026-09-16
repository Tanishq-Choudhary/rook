package app;

import geokit.GeoClient;
import geokit.Ref;

public class Archive {

    public String once(String key) {
        GeoClient archive = new GeoClient();
        return archive.lookup(new Ref(key));
    }
}
