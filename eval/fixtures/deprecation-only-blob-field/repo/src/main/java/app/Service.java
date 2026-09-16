package app;

import blobkit.BlobClient;

public class Service {

    private final BlobClient client;

    public Service(BlobClient client) {
        this.client = client;
    }

    public String single(String key) {
        return client.upload(key);
    }

    public String batch(String[] keys) {
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.upload(key)).append(';');
        }
        return out.toString();
    }
}
