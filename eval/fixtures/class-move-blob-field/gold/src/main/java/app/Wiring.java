package app;

import blobkit.client.BlobClient;

public class Wiring {

    public static Service service() {
        return new Service(new BlobClient());
    }
}
