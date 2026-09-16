package maillib;

public class MailClient {

    public String deliver(String key) {
        return "sent:" + key;
    }

    public String status() {
        return "up";
    }

}
