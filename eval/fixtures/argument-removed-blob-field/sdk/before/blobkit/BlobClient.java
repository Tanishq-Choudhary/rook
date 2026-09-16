package blobkit;

public class BlobClient {

    public String upload(String key, boolean audit) {
        return "stored:" + key;
    }

    public String status() {
        return "up";
    }

}
