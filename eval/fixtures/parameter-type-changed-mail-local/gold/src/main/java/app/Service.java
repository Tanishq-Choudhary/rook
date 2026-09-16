package app;

import maillib.MailClient;
import maillib.Ref;

public class Service {

    public String single(String key) {
        MailClient client = new MailClient();
        return client.send(new Ref(key));
    }

    public String batch(String[] keys) {
        MailClient client = new MailClient();
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.send(new Ref(key))).append(';');
        }
        return out.toString();
    }
}
