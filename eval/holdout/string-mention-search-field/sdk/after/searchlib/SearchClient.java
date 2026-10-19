package searchlib;

public class SearchClient {

    public String find(String key) {
        return "matched:" + key;
    }

    public String status() {
        return "up";
    }

}
