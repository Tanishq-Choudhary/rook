package app;

public class Audit {

    public static final String EVENT = "publish";

    public String describe() {
        return "uses publish on QueueClient";
    }
}
