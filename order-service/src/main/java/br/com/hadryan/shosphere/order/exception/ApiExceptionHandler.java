package br.com.hadryan.shosphere.order.exception;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var detail = defaultDetail(status.value());
        if (status.value() < 500 && exception instanceof ResponseStatusException response
                && response.getReason() != null) {
            detail = response.getReason();
        }
        List<Map<String, String>> errors = new ArrayList<>();
        if (status.value() == 400 && exception instanceof MethodArgumentNotValidException validation) {
            validation.getBindingResult().getFieldErrors().forEach(error -> errors.add(
                    Map.of("field", error.getField(), "message", Objects.requireNonNullElse(error.getDefaultMessage(), "Invalid value"))));
        } else if (status.value() == 400 && exception instanceof HandlerMethodValidationException validation) {
            validation.getParameterValidationResults().forEach(result -> result.getResolvableErrors().forEach(error -> errors.add(
                    Map.of("field", Objects.requireNonNullElse(result.getMethodParameter().getParameterName(), "parameter"),
                            "message", Objects.requireNonNullElse(error.getDefaultMessage(), "Invalid value")))));
        }
        return super.handleExceptionInternal(exception, problem(status, detail, request, errors), headers, status, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail validationFailure(ConstraintViolationException exception, WebRequest request) {
        var errors = exception.getConstraintViolations().stream().map(error ->
                Map.of("field", error.getPropertyPath().toString(), "message", error.getMessage())).toList();
        return problem(HttpStatus.BAD_REQUEST, "Invalid request.", request, errors);
    }

    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class,
            PessimisticLockingFailureException.class})
    public ProblemDetail persistenceConflict(Exception exception, WebRequest request) {
        return problem(HttpStatus.CONFLICT, "Request conflicts with stored data.", request, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpectedFailure(Exception exception, WebRequest request) throws Exception {
        if (exception instanceof AccessDeniedException || exception instanceof AuthenticationException) {
            throw exception;
        }
        logger.error("Unhandled API exception: " + exception.getClass().getName());
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.", request, List.of());
    }

    protected ProblemDetail problem(HttpStatusCode status, String detail, WebRequest request,
                                     List<Map<String, String>> errors) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("about:blank"));
        if (request instanceof ServletWebRequest servlet) {
            problem.setInstance(URI.create(servlet.getRequest().getRequestURI()));
        }
        problem.setProperty("errors", errors);
        return problem;
    }

    private String defaultDetail(int status) {
        return switch (status) {
            case 400 -> "Invalid request.";
            case 404 -> "Resource not found.";
            case 500 -> "An unexpected error occurred.";
            case 502 -> "Upstream service request failed.";
            case 503 -> "Upstream service unavailable.";
            default -> Objects.requireNonNullElse(HttpStatus.resolve(status), HttpStatus.INTERNAL_SERVER_ERROR).getReasonPhrase();
        };
    }
}

