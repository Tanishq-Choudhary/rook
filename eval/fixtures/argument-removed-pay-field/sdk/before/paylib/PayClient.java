package paylib;

public class PayClient {

    public String charge(String key, boolean audit) {
        return "charged:" + key;
    }

    public String status() {
        return "up";
    }

}
