package maillib;

public class MailClient {

    public Receipt send(String key) {
        return new Receipt("sent:" + key);
    }

    public String status() {
        return "up";
    }

}
