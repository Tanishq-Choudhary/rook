package geokit;

public class GeoClient {

    public Receipt lookup(String key) {
        return new Receipt("found:" + key);
    }

    public String status() {
        return "up";
    }

}
