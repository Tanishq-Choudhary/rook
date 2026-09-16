package app;

public class Audit {

    public static final String EVENT = "charge";

    public String describe() {
        return "uses charge on PayClient";
    }
}
