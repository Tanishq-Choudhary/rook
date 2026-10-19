package queuekit;

public class QueueClient {

    public String publish(Ref ref) {
        return "queued:" + ref.key();
    }

    public String status() {
        return "up";
    }

}
