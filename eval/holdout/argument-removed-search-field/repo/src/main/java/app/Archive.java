package app;

import searchlib.SearchClient;

public class Archive {

    private final SearchClient client = new SearchClient();

    public String once(String key) {
        return client.query(key, true);
    }
}
