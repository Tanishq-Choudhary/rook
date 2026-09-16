package app;

import maillib.MailClient;

public class Wiring {

    public static Service service() {
        return new Service(new MailClient());
    }
}
