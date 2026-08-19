package dev.rook.workflow.fanout;

public interface FanoutMetrics {

    FanoutMetrics NOOP =
            new FanoutMetrics() {
                @Override
                public void handled(FanoutResult result) {}

                @Override
                public void failed() {}
            };

    void handled(FanoutResult result);

    void failed();
}
