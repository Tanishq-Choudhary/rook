package app;

import blobkit.client.BlobClient;

public class Archive {

    private final BlobClient client = new BlobClient();

    public String once(String key) {
        return client.upload(key);
    }
}
