package smskit;

public class SmsClient {

    public Receipt dispatch(String key) {
        return new Receipt("dispatched:" + key);
    }

    public String status() {
        return "up";
    }

}
