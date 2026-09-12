package dev.rook.verify.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceQuotaTest {

    @TempDir Path temp;

    private Path file(String relative, int bytes) throws IOException {
        Path file = temp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[bytes]);
        return file;
    }

    @Test
    void usageBelowTheLimitsIsFine() throws IOException {
        file("a.txt", 100);
        file("dir/b.txt", 100);
        assertThat(new WorkspaceQuota(1000, 10).exceeded(List.of(temp))).isFalse();
    }

    @Test
    void tooManyBytesExceedTheQuota() throws IOException {
        file("a.bin", 600);
        file("dir/b.bin", 600);
        assertThat(new WorkspaceQuota(1000, 100).exceeded(List.of(temp))).isTrue();
    }

    @Test
    void tooManyFilesExceedTheQuotaEvenWhenTheyAreEmpty() throws IOException {
        for (int i = 0; i < 12; i++) {
            file("many/f" + i, 0);
        }
        assertThat(new WorkspaceQuota(1_000_000, 10).exceeded(List.of(temp))).isTrue();
    }

    @Test
    void manyEmptyDirectoriesCountToo() throws IOException {
        for (int i = 0; i < 12; i++) {
            Files.createDirectories(temp.resolve("d" + i));
        }
        assertThat(new WorkspaceQuota(1_000_000, 10).exceeded(List.of(temp))).isTrue();
    }

    @Test
    void severalDirectoriesShareOneBudget() throws IOException {
        Path one = Files.createDirectories(temp.resolve("one"));
        Path two = Files.createDirectories(temp.resolve("two"));
        Files.write(one.resolve("a"), new byte[600]);
        Files.write(two.resolve("b"), new byte[600]);
        assertThat(new WorkspaceQuota(1000, 100).exceeded(List.of(one))).isFalse();
        assertThat(new WorkspaceQuota(1000, 100).exceeded(List.of(one, two))).isTrue();
    }

    @Test
    void missingDirectoriesAreIgnored() {
        assertThat(new WorkspaceQuota(10, 1).exceeded(List.of(temp.resolve("nothing")))).isFalse();
        assertThat(new WorkspaceQuota(10, 1).exceeded(List.of())).isFalse();
    }
}
