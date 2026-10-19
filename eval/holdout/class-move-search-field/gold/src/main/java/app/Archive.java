package app;

import searchlib.client.SearchClient;

public class Archive {

    private final SearchClient client = new SearchClient();

    public String once(String key) {
        return client.query(key);
    }
}
