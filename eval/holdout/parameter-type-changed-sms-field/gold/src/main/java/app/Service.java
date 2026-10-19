package app;

import smskit.Ref;
import smskit.SmsClient;

public class Service {

    private final SmsClient client;

    public Service(SmsClient client) {
        this.client = client;
    }

    public String single(String key) {
        return client.dispatch(new Ref(key));
    }

    public String batch(String[] keys) {
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.dispatch(new Ref(key))).append(';');
        }
        return out.toString();
    }
}
