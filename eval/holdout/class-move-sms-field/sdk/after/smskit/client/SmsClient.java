package smskit.client;

public class SmsClient {

    public String dispatch(String key) {
        return "dispatched:" + key;
    }

    public String status() {
        return "up";
    }

}
