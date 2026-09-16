package app;

public class Audit {

    public static final String EVENT = "send";

    public String describe() {
        return "uses send on MailClient";
    }
}
