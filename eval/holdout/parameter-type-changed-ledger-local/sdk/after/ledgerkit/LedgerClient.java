package ledgerkit;

public class LedgerClient {

    public String record(Ref ref) {
        return "recorded:" + ref.key();
    }

    public String status() {
        return "up";
    }

}
