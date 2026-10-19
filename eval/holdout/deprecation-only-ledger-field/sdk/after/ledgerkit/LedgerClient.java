package ledgerkit;

public class LedgerClient {

    @Deprecated(forRemoval = true)
    public String record(String key) {
        return "recorded:" + key;
    }

    public String status() {
        return "up";
    }

}
