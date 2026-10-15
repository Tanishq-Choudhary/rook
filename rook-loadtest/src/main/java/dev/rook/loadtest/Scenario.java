package dev.rook.loadtest;

import java.net.http.HttpRequest;

public interface Scenario {

    String name();

    HttpRequest request(long sequence);

    boolean accepted(int status);
}
