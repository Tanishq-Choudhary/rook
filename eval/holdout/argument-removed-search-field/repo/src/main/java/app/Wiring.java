package app;

import searchlib.SearchClient;

public class Wiring {

    public static Service service() {
        return new Service(new SearchClient());
    }
}
