package dev.rook.core.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class JobStateTest {

    @Test
    void theHappyPathIsAValidChain() {
        assertThat(JobState.PENDING.canTransitionTo(JobState.INDEXING)).isTrue();
        assertThat(JobState.INDEXING.canTransitionTo(JobState.PROPOSING)).isTrue();
        assertThat(JobState.PROPOSING.canTransitionTo(JobState.VERIFYING)).isTrue();
        assertThat(JobState.VERIFYING.canTransitionTo(JobState.PUBLISHING)).isTrue();
        assertThat(JobState.PUBLISHING.canTransitionTo(JobState.COMPLETED)).isTrue();
    }

    @Test
    void approvalIsAnOptionalDetourBeforePublishing() {
        assertThat(JobState.VERIFYING.canTransitionTo(JobState.AWAITING_APPROVAL)).isTrue();
        assertThat(JobState.AWAITING_APPROVAL.canTransitionTo(JobState.PUBLISHING)).isTrue();
        assertThat(JobState.AWAITING_APPROVAL.canTransitionTo(JobState.REJECTED)).isTrue();
        assertThat(JobState.AWAITING_APPROVAL.canTransitionTo(JobState.COMPLETED)).isFalse();
    }

    @Test
    void impactAnalysisCanEndTheJobWithoutAnyEdit() {
        assertThat(JobState.INDEXING.canTransitionTo(JobState.NO_IMPACT)).isTrue();
        assertThat(JobState.PROPOSING.canTransitionTo(JobState.NO_IMPACT)).isFalse();
    }

    @Test
    void stepsCannotBeSkipped() {
        assertThat(JobState.PENDING.canTransitionTo(JobState.VERIFYING)).isFalse();
        assertThat(JobState.INDEXING.canTransitionTo(JobState.PUBLISHING)).isFalse();
        assertThat(JobState.PROPOSING.canTransitionTo(JobState.COMPLETED)).isFalse();
    }

    @Test
    void publishingCannotBeCancelled() {
        assertThat(JobState.PUBLISHING.canTransitionTo(JobState.CANCELLED)).isFalse();
        assertThat(JobState.PROPOSING.canTransitionTo(JobState.CANCELLED)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(
            value = JobState.class,
            names = {
                "COMPLETED",
                "NO_IMPACT",
                "REJECTED",
                "FAILED",
                "DEAD",
                "SUPERSEDED",
                "CANCELLED"
            })
    void terminalStatesHaveNoWayOut(JobState state) {
        assertThat(state.isTerminal()).isTrue();
        assertThat(state.successors()).isEmpty();
        for (JobState other : JobState.values()) {
            assertThat(state.canTransitionTo(other)).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = JobState.class,
            names = {
                "PENDING",
                "INDEXING",
                "PROPOSING",
                "VERIFYING",
                "AWAITING_APPROVAL",
                "PUBLISHING"
            })
    void liveStatesAreNotTerminal(JobState state) {
        assertThat(state.isTerminal()).isFalse();
    }

    @Test
    void runningStatesAreTheOnesThatHoldTheRepository() {
        assertThat(EnumSet.allOf(JobState.class).stream().filter(JobState::isRunning))
                .containsExactlyInAnyOrder(
                        JobState.INDEXING,
                        JobState.PROPOSING,
                        JobState.VERIFYING,
                        JobState.PUBLISHING);
        assertThat(JobState.AWAITING_APPROVAL.isRunning()).isFalse();
        assertThat(JobState.PENDING.isRunning()).isFalse();
    }

    @Test
    void onlyPendingAndRunningJobsCanBeClaimed() {
        assertThat(EnumSet.allOf(JobState.class).stream().filter(JobState::isClaimable))
                .containsExactlyInAnyOrder(
                        JobState.PENDING,
                        JobState.INDEXING,
                        JobState.PROPOSING,
                        JobState.VERIFYING,
                        JobState.PUBLISHING);
    }

    @Test
    void everyStateCanReachATerminalState() {
        for (JobState start : JobState.values()) {
            Set<JobState> seen = EnumSet.noneOf(JobState.class);
            Deque<JobState> queue = new ArrayDeque<>();
            queue.add(start);
            boolean reached = start.isTerminal();
            while (!queue.isEmpty() && !reached) {
                JobState current = queue.poll();
                if (!seen.add(current)) {
                    continue;
                }
                for (JobState next : current.successors()) {
                    if (next.isTerminal()) {
                        reached = true;
                    }
                    queue.add(next);
                }
            }
            assertThat(reached).as(start.name()).isTrue();
        }
    }

    @Test
    void noStateTransitionsToItself() {
        for (JobState state : JobState.values()) {
            assertThat(state.canTransitionTo(state)).as(state.name()).isFalse();
        }
    }
}
