package blobkit;

public class BlobClient {

    public String upload(Ref ref) {
        return "stored:" + ref.key();
    }

    public String status() {
        return "up";
    }

}
