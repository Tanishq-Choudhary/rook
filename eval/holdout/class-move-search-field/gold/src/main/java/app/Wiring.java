package app;

import searchlib.client.SearchClient;

public class Wiring {

    public static Service service() {
        return new Service(new SearchClient());
    }
}
