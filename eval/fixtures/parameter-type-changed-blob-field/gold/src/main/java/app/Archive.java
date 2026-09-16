package app;

import blobkit.BlobClient;
import blobkit.Ref;

public class Archive {

    private final BlobClient client = new BlobClient();

    public String once(String key) {
        return client.upload(new Ref(key));
    }
}
