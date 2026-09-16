package geokit;

public class GeoClient {

    public String lookup(String key, boolean audit) {
        return "found:" + key;
    }

    public String status() {
        return "up";
    }

}
