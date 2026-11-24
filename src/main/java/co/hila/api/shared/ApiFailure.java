package co.hila.api.shared;

import java.util.List;

public class ApiFailure extends RuntimeException {
  public final int status;
  public final String code;
  public final List<ApiErrors.FieldError> fields;

  public ApiFailure(int status, String code, String message) {
    this(status, code, message, List.of());
  }

  private ApiFailure(int status, String code, String message, List<ApiErrors.FieldError> fields) {
    super(message);
    this.status = status;
    this.code = code;
    this.fields = fields;
  }

  public static ApiFailure missing() {
    return new ApiFailure(404, "RESOURCE_NOT_FOUND", "No encontramos este recurso.");
  }

  public static ApiFailure invalid(String message) {
    return new ApiFailure(422, "VALIDATION_FAILED", message);
  }

  public static ApiFailure invalidField(String field, String code, String message) {
    return new ApiFailure(
        422, "VALIDATION_FAILED", message, List.of(new ApiErrors.FieldError(field, code, message)));
  }
}
