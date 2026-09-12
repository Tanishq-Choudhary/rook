package dev.rook.eval.generate;

import java.util.List;
import java.util.Map;

record Blueprint(
        String slug,
        String title,
        String category,
        String difficulty,
        boolean noop,
        List<String> changeKinds,
        Map<String, String> sdkBefore,
        Map<String, String> sdkAfter,
        Usage before,
        Usage gold,
        Map<String, String> extraRepoFiles,
        List<String> untouchable,
        String needle) {

    Blueprint {
        changeKinds = List.copyOf(changeKinds);
        sdkBefore = Map.copyOf(sdkBefore);
        sdkAfter = Map.copyOf(sdkAfter);
        extraRepoFiles = Map.copyOf(extraRepoFiles);
        untouchable = List.copyOf(untouchable);
    }
}
