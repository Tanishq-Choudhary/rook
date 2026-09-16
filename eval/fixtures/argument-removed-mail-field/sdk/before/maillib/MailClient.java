package maillib;

public class MailClient {

    public String send(String key, boolean audit) {
        return "sent:" + key;
    }

    public String status() {
        return "up";
    }

}
