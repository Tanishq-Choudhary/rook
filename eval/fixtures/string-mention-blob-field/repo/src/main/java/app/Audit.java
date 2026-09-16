package app;

public class Audit {

    public static final String EVENT = "upload";

    public String describe() {
        return "uses upload on BlobClient";
    }
}
