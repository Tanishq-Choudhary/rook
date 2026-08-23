package dev.rook.agent.patch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class LineDiff {

    enum Kind {
        EQUAL,
        DELETE,
        INSERT
    }

    record Op(Kind kind, int oldIndex, int newIndex) {}

    static final int MAX_LINES = 20_000;

    private LineDiff() {}

    static List<Op> diff(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        if (n > MAX_LINES || m > MAX_LINES) {
            throw new PatchRejected(
                    PatchRejected.Reason.TOO_LARGE, "file is too large to diff line by line");
        }
        int max = n + m;
        int offset = max;
        int[] v = new int[2 * max + 2];
        List<int[]> trace = new ArrayList<>();
        outer:
        for (int d = 0; d <= max; d++) {
            trace.add(v.clone());
            for (int k = -d; k <= d; k += 2) {
                int x;
                if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
                    x = v[offset + k + 1];
                } else {
                    x = v[offset + k - 1] + 1;
                }
                int y = x - k;
                while (x < n && y < m && a.get(x).equals(b.get(y))) {
                    x++;
                    y++;
                }
                v[offset + k] = x;
                if (x >= n && y >= m) {
                    break outer;
                }
            }
        }
        return backtrack(trace, n, m, offset);
    }

    private static List<Op> backtrack(List<int[]> trace, int n, int m, int offset) {
        List<Op> ops = new ArrayList<>();
        int x = n;
        int y = m;
        for (int d = trace.size() - 1; d >= 0; d--) {
            int[] v = trace.get(d);
            int k = x - y;
            int prevK;
            if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
                prevK = k + 1;
            } else {
                prevK = k - 1;
            }
            int prevX = v[offset + prevK];
            int prevY = prevX - prevK;
            while (x > prevX && y > prevY) {
                x--;
                y--;
                ops.add(new Op(Kind.EQUAL, x, y));
            }
            if (d > 0) {
                if (x == prevX) {
                    y--;
                    ops.add(new Op(Kind.INSERT, -1, y));
                } else {
                    x--;
                    ops.add(new Op(Kind.DELETE, x, -1));
                }
            }
        }
        Collections.reverse(ops);
        return ops;
    }
}
