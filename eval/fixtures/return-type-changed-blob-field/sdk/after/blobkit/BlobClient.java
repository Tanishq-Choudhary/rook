package blobkit;

public class BlobClient {

    public Receipt upload(String key) {
        return new Receipt("stored:" + key);
    }

    public String status() {
        return "up";
    }

}
