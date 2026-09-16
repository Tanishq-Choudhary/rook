package paylib;

public class PayClient {

    @Deprecated(forRemoval = true)
    public String charge(String key) {
        return "charged:" + key;
    }

    public String status() {
        return "up";
    }

}
