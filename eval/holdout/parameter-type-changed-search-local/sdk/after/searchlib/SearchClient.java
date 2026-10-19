package searchlib;

public class SearchClient {

    public String query(Ref ref) {
        return "matched:" + ref.key();
    }

    public String status() {
        return "up";
    }

}
