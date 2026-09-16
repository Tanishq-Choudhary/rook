package blobkit;

public class BlobClient {

    public String putObject(String key) {
        return "stored:" + key;
    }

    public String status() {
        return "up";
    }

}
