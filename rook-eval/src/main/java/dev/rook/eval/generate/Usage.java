package dev.rook.eval.generate;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public record Usage(String clientImport, List<String> extraImports, String invoke) {

    public Usage {
        extraImports = List.copyOf(extraImports);
    }

    public static Usage of(String clientImport, String invoke) {
        return new Usage(clientImport, List.of(), invoke);
    }

    public Usage withExtraImport(String qualifiedName) {
        List<String> all = new ArrayList<>(extraImports);
        all.add(qualifiedName);
        return new Usage(clientImport, all, invoke);
    }

    String call(String client, String key) {
        return invoke.replace("{c}", client).replace("{k}", key);
    }

    String imports() {
        StringBuilder text = new StringBuilder();
        Stream.concat(Stream.of(clientImport), extraImports.stream())
                .sorted()
                .forEach(name -> text.append("import ").append(name).append(";\n"));
        return text.toString();
    }
}
