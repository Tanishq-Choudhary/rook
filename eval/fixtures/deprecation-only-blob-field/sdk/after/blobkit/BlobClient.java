package blobkit;

public class BlobClient {

    @Deprecated(forRemoval = true)
    public String upload(String key) {
        return "stored:" + key;
    }

    public String status() {
        return "up";
    }

}
