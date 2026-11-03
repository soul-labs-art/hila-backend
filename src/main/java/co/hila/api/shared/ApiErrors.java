package co.hila.api.shared;

import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiErrors {
  public record FieldError(String field, String code, String message) {}

  public record Error(String code, String message, List<FieldError> fieldErrors, String traceId) {}

  public static Error body(String code, String message) {
    return new Error(code, message, List.of(), UUID.randomUUID().toString());
  }

  @ExceptionHandler(ApiFailure.class)
  ResponseEntity<Error> failure(ApiFailure e) {
    var response = ResponseEntity.status(e.status).cacheControl(CacheControl.noStore());
    if (e.status == 429) response.header("Retry-After", "900");
    return response.body(body(e.code, e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Error> validation(MethodArgumentNotValidException e) {
    var fields =
        e.getBindingResult().getFieldErrors().stream()
            .map(
                f ->
                    new FieldError(
                        f.getField(),
                        switch (f.getCode() == null ? "" : f.getCode()) {
                          case "NotBlank", "NotNull" -> "REQUIRED";
                          case "Size" -> "TOO_LONG";
                          case "AssertTrue" -> "MUST_BE_TRUE";
                          default -> "INVALID_FORMAT";
                        },
                        f.getDefaultMessage()))
            .toList();
    return ResponseEntity.unprocessableEntity()
        .body(
            new Error(
                "VALIDATION_FAILED",
                "Revisa los campos indicados.",
                fields,
                UUID.randomUUID().toString()));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<Error> large() {
    return ResponseEntity.status(413).body(body("FILE_TOO_LARGE", "La imagen supera 10 MiB."));
  }

  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    IllegalArgumentException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<Error> malformed(Exception e) {
    return ResponseEntity.badRequest()
        .body(body("MALFORMED_REQUEST", "Revisa el formato de la solicitud."));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Error> unexpected(Exception e) {
    org.slf4j.LoggerFactory.getLogger(ApiErrors.class)
        .error("API failure: {}", e.getClass().getName());
    return ResponseEntity.internalServerError()
        .body(body("INTERNAL_ERROR", "No fue posible completar la operación."));
  }
}
