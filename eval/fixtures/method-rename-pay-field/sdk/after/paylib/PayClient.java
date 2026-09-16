package paylib;

public class PayClient {

    public String createCharge(String key) {
        return "charged:" + key;
    }

    public String status() {
        return "up";
    }

}
