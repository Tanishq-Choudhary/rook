package dev.rook.agent.tools;

import java.util.List;

public interface ReferenceSearch {

    List<Passage> search(String query, int limit);

    record Passage(String path, int line, String text) {

        public Passage {
            text = text == null ? "" : text;
        }
    }
}
