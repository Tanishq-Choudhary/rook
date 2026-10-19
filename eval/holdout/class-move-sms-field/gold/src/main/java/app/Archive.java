package app;

import smskit.client.SmsClient;

public class Archive {

    private final SmsClient client = new SmsClient();

    public String once(String key) {
        return client.dispatch(key);
    }
}
