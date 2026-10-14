package io.github.enryold.mcp.sample;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class FixtureExceptionHandler {
    @ExceptionHandler(EchoService.FixtureFailure.class)
    ResponseEntity<ErrorBody> fixtureFailure(EchoService.FixtureFailure failure) {
        return ResponseEntity.status(failure.status()).body(new ErrorBody(failure.code(), failure.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ErrorBody> unexpectedFailure() {
        return ResponseEntity.internalServerError().body(new ErrorBody("internal_error", "Unexpected fixture failure"));
    }

    record ErrorBody(String code, String message) {}
}
