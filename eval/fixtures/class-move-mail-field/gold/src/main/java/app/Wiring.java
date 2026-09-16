package app;

import maillib.client.MailClient;

public class Wiring {

    public static Service service() {
        return new Service(new MailClient());
    }
}
