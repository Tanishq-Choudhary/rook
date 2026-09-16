package blobkit;

public class BlobClient {

    public String upload(String key) {
        return "stored:" + key;
    }

    public String status() {
        return "up";
    }

    public String listBuckets() {
        return "none";
    }

}
