package app;

import smskit.client.SmsClient;

public class Archive {

    public String once(String key) {
        SmsClient archive = new SmsClient();
        return archive.dispatch(key);
    }
}
