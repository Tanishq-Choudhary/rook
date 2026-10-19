package app;

import smskit.Ref;
import smskit.SmsClient;

public class Service {

    public String single(String key) {
        SmsClient client = new SmsClient();
        return client.dispatch(new Ref(key));
    }

    public String batch(String[] keys) {
        SmsClient client = new SmsClient();
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.dispatch(new Ref(key))).append(';');
        }
        return out.toString();
    }
}
