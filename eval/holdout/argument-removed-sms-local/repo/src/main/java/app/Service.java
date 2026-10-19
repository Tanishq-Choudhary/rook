package app;

import smskit.SmsClient;

public class Service {

    public String single(String key) {
        SmsClient client = new SmsClient();
        return client.dispatch(key, true);
    }

    public String batch(String[] keys) {
        SmsClient client = new SmsClient();
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.dispatch(key, true)).append(';');
        }
        return out.toString();
    }
}
