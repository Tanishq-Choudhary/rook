package app;

import searchlib.Ref;
import searchlib.SearchClient;

public class Archive {

    public String once(String key) {
        SearchClient archive = new SearchClient();
        return archive.query(new Ref(key));
    }
}
