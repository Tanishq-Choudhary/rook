package app;

import blobkit.BlobClient;
import blobkit.Ref;

public class Archive {

    public String once(String key) {
        BlobClient archive = new BlobClient();
        return archive.upload(new Ref(key));
    }
}
