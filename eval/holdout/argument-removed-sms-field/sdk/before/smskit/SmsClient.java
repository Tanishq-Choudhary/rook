package smskit;

public class SmsClient {

    public String dispatch(String key, boolean audit) {
        return "dispatched:" + key;
    }

    public String status() {
        return "up";
    }

}
