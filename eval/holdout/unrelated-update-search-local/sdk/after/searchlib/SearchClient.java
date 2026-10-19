package searchlib;

public class SearchClient {

    public String query(String key) {
        return "matched:" + key;
    }

    public String status() {
        return "up";
    }

    public String listIndexes() {
        return "none";
    }

}
