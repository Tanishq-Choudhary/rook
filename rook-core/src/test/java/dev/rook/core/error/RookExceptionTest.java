package dev.rook.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RookExceptionTest {

    @Test
    void notFoundNamesTheKindAndId() {
        RookException e = RookException.notFound("job", "abc");
        assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(e).hasMessage("job abc not found");
    }

    @Test
    void factoriesMapToTheExpectedCodes() {
        assertThat(RookException.conflict("x").code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(RookException.invalid("x").code()).isEqualTo(ErrorCode.INVALID);
        assertThat(RookException.forbidden("x").code()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(RookException.unauthorized("x").code()).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThat(RookException.budgetExceeded("x").code()).isEqualTo(ErrorCode.BUDGET_EXCEEDED);
        assertThat(RookException.policyDenied("x").code()).isEqualTo(ErrorCode.POLICY_DENIED);
    }

    @Test
    void unavailableKeepsTheCause() {
        Throwable cause = new java.io.IOException("down");
        RookException e = RookException.unavailable("upstream", cause);
        assertThat(e.code()).isEqualTo(ErrorCode.UNAVAILABLE);
        assertThat(e).hasCause(cause);
    }

    @Test
    void httpStatusesFollowConvention() {
        assertThat(ErrorCode.NOT_FOUND.httpStatus()).isEqualTo(404);
        assertThat(ErrorCode.RATE_LIMITED.httpStatus()).isEqualTo(429);
        assertThat(ErrorCode.INTERNAL.httpStatus()).isEqualTo(500);
    }
}
