package queuekit;

public class QueueClient {

    public String enqueue(String key) {
        return "queued:" + key;
    }

    public String status() {
        return "up";
    }

}
