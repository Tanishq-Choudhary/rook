package ledgerkit;

public class LedgerClient {

    public String record(String key, boolean audit) {
        return "recorded:" + key;
    }

    public String status() {
        return "up";
    }

}
