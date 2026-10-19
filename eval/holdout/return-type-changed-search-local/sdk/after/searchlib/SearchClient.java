package searchlib;

public class SearchClient {

    public Receipt query(String key) {
        return new Receipt("matched:" + key);
    }

    public String status() {
        return "up";
    }

}
