package app;

import searchlib.SearchClient;

public class Service {

    public String single(String key) {
        SearchClient client = new SearchClient();
        return client.query(key).id();
    }

    public String batch(String[] keys) {
        SearchClient client = new SearchClient();
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.query(key).id()).append(';');
        }
        return out.toString();
    }
}
