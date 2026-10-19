package ledgerkit;

public class LedgerClient {

    public String append(String key) {
        return "recorded:" + key;
    }

    public String status() {
        return "up";
    }

}
