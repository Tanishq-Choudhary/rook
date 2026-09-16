package app;

import blobkit.BlobClient;

public class Archive {

    public String once(String key) {
        BlobClient archive = new BlobClient();
        return archive.upload(key).id();
    }
}
