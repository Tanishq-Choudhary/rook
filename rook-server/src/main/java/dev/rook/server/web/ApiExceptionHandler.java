package dev.rook.server.web;

import dev.rook.core.error.ErrorCode;
import dev.rook.core.error.RookException;
import java.net.URI;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(RookException.class)
    ResponseEntity<ProblemDetail> handleRook(RookException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status(e.code()), e.getMessage());
        problem.setTitle(e.code().name());
        problem.setType(URI.create("urn:rook:error:" + e.code().name().toLowerCase()));
        if (e.code() == ErrorCode.INTERNAL || e.code() == ErrorCode.UNAVAILABLE) {
            log.error("request failed", e);
        }
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleIllegal(IllegalArgumentException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle(ErrorCode.INVALID.name());
        problem.setType(URI.create("urn:rook:error:invalid"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
        String reference = UUID.randomUUID().toString();
        log.error("unhandled failure reference={}", reference, e);
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "unexpected failure, reference " + reference);
        problem.setTitle(ErrorCode.INTERNAL.name());
        problem.setType(URI.create("urn:rook:error:internal"));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    private static HttpStatus status(ErrorCode code) {
        return HttpStatus.valueOf(code.httpStatus());
    }
}
