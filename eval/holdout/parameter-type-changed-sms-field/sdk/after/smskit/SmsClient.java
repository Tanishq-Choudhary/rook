package smskit;

public class SmsClient {

    public String dispatch(Ref ref) {
        return "dispatched:" + ref.key();
    }

    public String status() {
        return "up";
    }

}
