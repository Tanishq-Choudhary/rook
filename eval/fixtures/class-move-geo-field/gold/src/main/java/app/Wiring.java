package app;

import geokit.client.GeoClient;

public class Wiring {

    public static Service service() {
        return new Service(new GeoClient());
    }
}
