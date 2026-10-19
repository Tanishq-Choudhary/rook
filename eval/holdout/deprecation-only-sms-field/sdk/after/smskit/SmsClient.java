package smskit;

public class SmsClient {

    @Deprecated(forRemoval = true)
    public String dispatch(String key) {
        return "dispatched:" + key;
    }

    public String status() {
        return "up";
    }

}
