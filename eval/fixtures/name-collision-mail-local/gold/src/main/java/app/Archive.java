package app;

import maillib.MailClient;

public class Archive {

    public String once(String key) {
        MailClient archive = new MailClient();
        return archive.deliver(key);
    }
}
