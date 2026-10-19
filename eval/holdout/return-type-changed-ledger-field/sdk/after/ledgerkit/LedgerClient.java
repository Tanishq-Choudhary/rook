package ledgerkit;

public class LedgerClient {

    public Receipt record(String key) {
        return new Receipt("recorded:" + key);
    }

    public String status() {
        return "up";
    }

}
