package app;

import smskit.SmsClient;

public class Archive {

    private final SmsClient client = new SmsClient();

    public String once(String key) {
        return client.transmit(key);
    }
}
