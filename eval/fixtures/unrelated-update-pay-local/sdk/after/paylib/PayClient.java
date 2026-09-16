package paylib;

public class PayClient {

    public String charge(String key) {
        return "charged:" + key;
    }

    public String status() {
        return "up";
    }

    public String listCharges() {
        return "none";
    }

}
