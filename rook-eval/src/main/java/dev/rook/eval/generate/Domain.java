package dev.rook.eval.generate;

import java.util.List;

public record Domain(
        String slug,
        String pkg,
        String client,
        String method,
        String renamed,
        String tag,
        String extra) {

    public static List<Domain> all() {
        return List.of(
                new Domain(
                        "pay",
                        "paylib",
                        "PayClient",
                        "charge",
                        "createCharge",
                        "charged",
                        "listCharges"),
                new Domain(
                        "mail", "maillib", "MailClient", "send", "deliver", "sent", "listFolders"),
                new Domain(
                        "blob",
                        "blobkit",
                        "BlobClient",
                        "upload",
                        "putObject",
                        "stored",
                        "listBuckets"),
                new Domain(
                        "geo", "geokit", "GeoClient", "lookup", "resolve", "found", "listRegions"));
    }

    public String qualifiedClient() {
        return pkg + "." + client;
    }

    public String path(String className) {
        return pkg + "/" + className + ".java";
    }
}
