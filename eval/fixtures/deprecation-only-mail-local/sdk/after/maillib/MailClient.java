package maillib;

public class MailClient {

    @Deprecated(forRemoval = true)
    public String send(String key) {
        return "sent:" + key;
    }

    public String status() {
        return "up";
    }

}
