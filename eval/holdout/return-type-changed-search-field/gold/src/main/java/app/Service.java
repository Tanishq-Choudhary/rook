package app;

import searchlib.SearchClient;

public class Service {

    private final SearchClient client;

    public Service(SearchClient client) {
        this.client = client;
    }

    public String single(String key) {
        return client.query(key).id();
    }

    public String batch(String[] keys) {
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.query(key).id()).append(';');
        }
        return out.toString();
    }
}
