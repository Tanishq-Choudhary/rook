package app;

import smskit.SmsClient;

public class Wiring {

    public static Service service() {
        return new Service(new SmsClient());
    }
}
