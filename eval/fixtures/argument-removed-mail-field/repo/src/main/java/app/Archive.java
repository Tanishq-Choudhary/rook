package app;

import maillib.MailClient;

public class Archive {

    private final MailClient client = new MailClient();

    public String once(String key) {
        return client.send(key, true);
    }
}
