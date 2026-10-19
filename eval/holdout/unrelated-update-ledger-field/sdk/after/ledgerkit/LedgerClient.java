package ledgerkit;

public class LedgerClient {

    public String record(String key) {
        return "recorded:" + key;
    }

    public String status() {
        return "up";
    }

    public String listAccounts() {
        return "none";
    }

}
