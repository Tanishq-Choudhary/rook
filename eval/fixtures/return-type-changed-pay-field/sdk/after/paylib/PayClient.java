package paylib;

public class PayClient {

    public Receipt charge(String key) {
        return new Receipt("charged:" + key);
    }

    public String status() {
        return "up";
    }

}
