package app;

import maillib.MailClient;
import maillib.Ref;

public class Service {

    private final MailClient client;

    public Service(MailClient client) {
        this.client = client;
    }

    public String single(String key) {
        return client.send(new Ref(key));
    }

    public String batch(String[] keys) {
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.send(new Ref(key))).append(';');
        }
        return out.toString();
    }
}
