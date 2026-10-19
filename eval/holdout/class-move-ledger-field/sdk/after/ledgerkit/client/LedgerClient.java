package ledgerkit.client;

public class LedgerClient {

    public String record(String key) {
        return "recorded:" + key;
    }

    public String status() {
        return "up";
    }

}
