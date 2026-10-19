package app;

import smskit.client.SmsClient;

public class Wiring {

    public static Service service() {
        return new Service(new SmsClient());
    }
}
