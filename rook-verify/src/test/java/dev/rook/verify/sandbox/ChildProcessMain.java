package dev.rook.verify.sandbox;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ChildProcessMain {

    private ChildProcessMain() {}

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "exit" -> System.exit(Integer.parseInt(args[1]));
            case "print" -> System.out.println(args[1]);
            case "err" -> System.err.println(args[1]);
            case "env" -> {
                String value = System.getenv(args[1]);
                System.out.println(value == null ? "<unset>" : value);
            }
            case "spam" -> spam(Integer.parseInt(args[1]));
            case "sleep" -> Thread.sleep(Long.parseLong(args[1]));
            case "grandchild" -> grandchild(Path.of(args[1]));
            case "stdin" -> System.out.println("read " + System.in.readAllBytes().length);
            case "cwd" -> System.out.println(Path.of("").toAbsolutePath());
            default -> {
                System.err.println("unknown mode");
                System.exit(2);
            }
        }
    }

    private static void spam(int bytes) {
        PrintStream out = System.out;
        byte[] chunk = new byte[4096];
        java.util.Arrays.fill(chunk, (byte) 'x');
        int remaining = bytes;
        while (remaining > 0) {
            int size = Math.min(remaining, chunk.length);
            out.write(chunk, 0, size);
            remaining -= size;
        }
        out.println();
        out.println("END");
    }

    private static void grandchild(Path pidFile) throws IOException, InterruptedException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child =
                new ProcessBuilder(
                                List.of(
                                        java,
                                        "-cp",
                                        System.getProperty("java.class.path"),
                                        ChildProcessMain.class.getName(),
                                        "sleep",
                                        "60000"))
                        .inheritIO()
                        .start();
        Files.writeString(pidFile, Long.toString(child.pid()));
        Thread.sleep(60_000);
    }
}
