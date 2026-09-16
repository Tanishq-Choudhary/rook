package app;

import maillib.client.MailClient;

public class Service {

    public String single(String key) {
        MailClient client = new MailClient();
        return client.send(key);
    }

    public String batch(String[] keys) {
        MailClient client = new MailClient();
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.send(key)).append(';');
        }
        return out.toString();
    }
}
