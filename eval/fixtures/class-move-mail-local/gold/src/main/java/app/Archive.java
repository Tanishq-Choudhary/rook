package app;

import maillib.client.MailClient;

public class Archive {

    public String once(String key) {
        MailClient archive = new MailClient();
        return archive.send(key);
    }
}
