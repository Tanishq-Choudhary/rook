package dev.rook.verify.sandbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class DockerCommand {

    static final String MANAGED_LABEL = "rook.managed";
    static final String STARTED_LABEL = "rook.started";

    private DockerCommand() {}

    static List<String> run(SandboxSpec spec, String name, DockerConfig config, long startedEpoch) {
        List<String> args = new ArrayList<>();
        args.add(config.binary());
        args.add("run");
        args.add("--name");
        args.add(name);
        args.add("--pull");
        args.add(config.pullMissingImages() ? "missing" : "never");
        args.add("--init");
        args.add("--entrypoint");
        args.add("");
        args.add("--network");
        args.add(spec.network() == NetworkMode.NONE ? "none" : "bridge");
        args.add("--read-only");
        args.add("--tmpfs");
        args.add("/tmp:rw,nosuid,size=" + config.tmpfsSize());
        args.add("--cap-drop");
        args.add("ALL");
        args.add("--security-opt");
        args.add("no-new-privileges");
        args.add("--user");
        args.add(config.user());
        args.add("--memory");
        args.add(spec.memoryMb() + "m");
        args.add("--memory-swap");
        args.add(spec.memoryMb() + "m");
        args.add("--cpus");
        args.add(trim(spec.cpus()));
        args.add("--pids-limit");
        args.add(Integer.toString(spec.pids()));
        args.add("--ulimit");
        args.add("nofile=4096:4096");
        args.add("--ulimit");
        args.add("core=0");
        args.add("--workdir");
        args.add(spec.workdir());
        Map<String, String> labels = new TreeMap<>(spec.labels());
        labels.put(MANAGED_LABEL, "true");
        labels.put(STARTED_LABEL, Long.toString(startedEpoch));
        labels.forEach(
                (key, value) -> {
                    args.add("--label");
                    args.add(key + "=" + value);
                });
        new TreeMap<>(spec.env())
                .forEach(
                        (key, value) -> {
                            args.add("--env");
                            args.add(key + "=" + value);
                        });
        for (Mount mount : spec.mounts()) {
            String source = mount.host().toAbsolutePath().toString();
            if (source.contains(",") || source.contains("=")) {
                throw new SandboxException("mount path contains a reserved character: " + source);
            }
            args.add("--mount");
            args.add(
                    "type=bind,source="
                            + source
                            + ",target="
                            + mount.container()
                            + (mount.readOnly() ? ",readonly" : ""));
        }
        args.add(spec.image());
        args.addAll(spec.command());
        return args;
    }

    private static String trim(double cpus) {
        return cpus == Math.rint(cpus) ? Long.toString((long) cpus) : Double.toString(cpus);
    }
}
