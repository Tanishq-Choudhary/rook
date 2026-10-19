package app;

import searchlib.client.SearchClient;

public class Archive {

    public String once(String key) {
        SearchClient archive = new SearchClient();
        return archive.query(key);
    }
}
