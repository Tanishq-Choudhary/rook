package dev.rook.workflow;

import java.util.UUID;

public class LeaseLostException extends RuntimeException {

    public LeaseLostException(UUID jobId) {
        super("lease lost for job " + jobId);
    }
}
