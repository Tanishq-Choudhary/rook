package app;

import searchlib.Ref;
import searchlib.SearchClient;

public class Service {

    public String single(String key) {
        SearchClient client = new SearchClient();
        return client.query(new Ref(key));
    }

    public String batch(String[] keys) {
        SearchClient client = new SearchClient();
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.query(new Ref(key))).append(';');
        }
        return out.toString();
    }
}
