package app;

import maillib.MailClient;
import maillib.Ref;

public class Archive {

    private final MailClient client = new MailClient();

    public String once(String key) {
        return client.send(new Ref(key));
    }
}
