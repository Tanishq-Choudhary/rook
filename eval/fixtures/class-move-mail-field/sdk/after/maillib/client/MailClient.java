package maillib.client;

public class MailClient {

    public String send(String key) {
        return "sent:" + key;
    }

    public String status() {
        return "up";
    }

}
