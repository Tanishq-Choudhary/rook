package queuekit;

public class QueueClient {

    @Deprecated(forRemoval = true)
    public String publish(String key) {
        return "queued:" + key;
    }

    public String status() {
        return "up";
    }

}
