package geokit;

public class GeoClient {

    public String lookup(Ref ref) {
        return "found:" + ref.key();
    }

    public String status() {
        return "up";
    }

}
