package app;

import blobkit.BlobClient;
import blobkit.Ref;

public class Service {

    public String single(String key) {
        BlobClient client = new BlobClient();
        return client.upload(new Ref(key));
    }

    public String batch(String[] keys) {
        BlobClient client = new BlobClient();
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.upload(new Ref(key))).append(';');
        }
        return out.toString();
    }
}
