package app;

import smskit.Ref;
import smskit.SmsClient;

public class Archive {

    private final SmsClient client = new SmsClient();

    public String once(String key) {
        return client.dispatch(new Ref(key));
    }
}
