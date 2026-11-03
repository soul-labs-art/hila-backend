package co.hila.api.shared;

public class ApiFailure extends RuntimeException {
  public final int status;
  public final String code;

  public ApiFailure(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public static ApiFailure missing() {
    return new ApiFailure(404, "RESOURCE_NOT_FOUND", "No encontramos este recurso.");
  }

  public static ApiFailure invalid(String message) {
    return new ApiFailure(422, "VALIDATION_FAILED", message);
  }
}
