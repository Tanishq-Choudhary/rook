package app;

import searchlib.SearchClient;

public class Service {

    public String single(String key) {
        SearchClient client = new SearchClient();
        return client.find(key);
    }

    public String batch(String[] keys) {
        SearchClient client = new SearchClient();
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.find(key)).append(';');
        }
        return out.toString();
    }
}
