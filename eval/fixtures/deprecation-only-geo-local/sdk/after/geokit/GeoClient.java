package geokit;

public class GeoClient {

    @Deprecated(forRemoval = true)
    public String lookup(String key) {
        return "found:" + key;
    }

    public String status() {
        return "up";
    }

}
