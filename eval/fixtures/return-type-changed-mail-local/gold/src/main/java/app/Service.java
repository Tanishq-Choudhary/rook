package app;

import maillib.MailClient;

public class Service {

    public String single(String key) {
        MailClient client = new MailClient();
        return client.send(key).id();
    }

    public String batch(String[] keys) {
        MailClient client = new MailClient();
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.send(key).id()).append(';');
        }
        return out.toString();
    }
}
