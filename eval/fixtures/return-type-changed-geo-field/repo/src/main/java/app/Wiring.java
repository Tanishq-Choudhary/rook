package app;

import geokit.GeoClient;

public class Wiring {

    public static Service service() {
        return new Service(new GeoClient());
    }
}
