package app;

import maillib.MailClient;
import maillib.Ref;

public class Archive {

    public String once(String key) {
        MailClient archive = new MailClient();
        return archive.send(new Ref(key));
    }
}
