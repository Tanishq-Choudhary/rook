package maillib;

public class MailClient {

    public String send(Ref ref) {
        return "sent:" + ref.key();
    }

    public String status() {
        return "up";
    }

}
