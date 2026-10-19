package searchlib;

public class SearchClient {

    public String query(String key, boolean audit) {
        return "matched:" + key;
    }

    public String status() {
        return "up";
    }

}
