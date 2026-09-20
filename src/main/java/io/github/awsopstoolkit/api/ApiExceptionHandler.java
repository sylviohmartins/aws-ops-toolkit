package io.github.awsopstoolkit.api;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid() {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Invalid operation request");
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict() {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "Operation state or capacity does not permit this request");
    }

    @ExceptionHandler(NoSuchFileException.class)
    ProblemDetail missing() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Operation not found");
    }

    @ExceptionHandler(IOException.class)
    ProblemDetail storage() {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Local storage unavailable; inspect before resuming");
    }
}
