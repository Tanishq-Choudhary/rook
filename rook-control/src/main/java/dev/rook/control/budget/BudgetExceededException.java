package dev.rook.control.budget;

import dev.rook.core.error.ErrorCode;
import dev.rook.core.error.RookException;

public class BudgetExceededException extends RookException {

    public enum Scope {
        MONTHLY,
        JOB
    }

    private final Scope scope;

    public BudgetExceededException(Scope scope, String message) {
        super(ErrorCode.BUDGET_EXCEEDED, message);
        this.scope = scope;
    }

    public Scope scope() {
        return scope;
    }
}
