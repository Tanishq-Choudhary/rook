package app;

public class Report {

    public String line(Ledger ledger, String key) {
        return ledger.publish(key);
    }
}
