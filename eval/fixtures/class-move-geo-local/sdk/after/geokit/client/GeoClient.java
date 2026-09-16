package geokit.client;

public class GeoClient {

    public String lookup(String key) {
        return "found:" + key;
    }

    public String status() {
        return "up";
    }

}
