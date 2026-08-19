package dev.rook.workflow.outbox;

public interface RelayMetrics {

    RelayMetrics NOOP =
            new RelayMetrics() {
                @Override
                public void published(String topic) {}

                @Override
                public void failed(String topic) {}

                @Override
                public void parked(String topic) {}

                @Override
                public void leader(boolean leading) {}
            };

    void published(String topic);

    void failed(String topic);

    void parked(String topic);

    void leader(boolean leading);
}
