package app;

import blobkit.BlobClient;

public class Archive {

    private final BlobClient client = new BlobClient();

    public String once(String key) {
        return client.upload(key).id();
    }
}
