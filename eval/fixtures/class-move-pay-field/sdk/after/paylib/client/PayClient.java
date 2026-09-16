package paylib.client;

public class PayClient {

    public String charge(String key) {
        return "charged:" + key;
    }

    public String status() {
        return "up";
    }

}
