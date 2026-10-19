package app;

import searchlib.SearchClient;

public class Archive {

    public String once(String key) {
        SearchClient archive = new SearchClient();
        return archive.find(key);
    }
}
