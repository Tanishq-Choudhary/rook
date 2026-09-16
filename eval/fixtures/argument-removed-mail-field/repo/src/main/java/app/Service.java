package app;

import maillib.MailClient;

public class Service {

    private final MailClient client;

    public Service(MailClient client) {
        this.client = client;
    }

    public String single(String key) {
        return client.send(key, true);
    }

    public String batch(String[] keys) {
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.send(key, true)).append(';');
        }
        return out.toString();
    }
}
