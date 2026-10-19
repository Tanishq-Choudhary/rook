package smskit;

public class SmsClient {

    public String transmit(String key) {
        return "dispatched:" + key;
    }

    public String status() {
        return "up";
    }

}
