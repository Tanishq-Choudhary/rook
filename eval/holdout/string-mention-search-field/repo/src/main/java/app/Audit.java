package app;

public class Audit {

    public static final String EVENT = "query";

    public String describe() {
        return "uses query on SearchClient";
    }
}
