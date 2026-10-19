package searchlib;

public class SearchClient {

    @Deprecated(forRemoval = true)
    public String query(String key) {
        return "matched:" + key;
    }

    public String status() {
        return "up";
    }

}
