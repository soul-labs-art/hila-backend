package co.hila.api.shared;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request types for the additional MVP operations. */
public final class MvpRequests {
  private MvpRequests() {}

  public record AccountStatusUpdate(
      @NotNull @Pattern(regexp = "^(?:ACTIVE|SUSPENDED)$") String status,
      @NotNull @NotBlank @Size(max = 1000) String reason) {}

  public record ApplicationCreate(@Size(max = 1200) String message) {}

  public record ApplicationDecision(
      @NotNull @Pattern(regexp = "^(?:ACCEPTED|REJECTED)$") String decision,
      @Size(max = 1000) String reason) {}

  public record Availability(
      @NotNull @Pattern(regexp = "^(?:America/Bogota)$") String timezone,
      @NotNull @Size(max = 100) List<@NotNull @Valid AvailabilitySlot> items) {}

  public record AvailabilitySlot(
      @NotNull @Min(1) @Max(7) Integer weekday,
      @NotNull @Pattern(regexp = "^([01][0-9]|2[0-3]):[0-5][0-9]$") String startsAt,
      @NotNull @Pattern(regexp = "^([01][0-9]|2[0-3]):[0-5][0-9]$") String endsAt) {}

  public record CauseWrite(
      @NotNull @NotBlank @Size(max = 180) String title,
      @NotNull @NotBlank @Size(max = 500) String summary,
      @Size(max = 10000) String description,
      @NotNull @Pattern(regexp = "^(?:DRAFT|ACTIVE|ARCHIVED)$") String status) {}

  public record CloseNeedRequest(
      @NotNull @Pattern(regexp = "^(?:FULFILLED|PARTIAL|NOT_FULFILLED)$") String outcomeStatus,
      @NotNull @NotBlank @Size(max = 2000) String summary) {}

  public record CommitmentCancellationRequest(@Size(max = 1000) String reason) {}

  public record FeedbackCreate(
      @NotNull @Min(1) @Max(5) Integer rating, @Size(max = 1200) String comment) {}

  public record MembershipCreate(
      @NotNull @NotBlank @Email @Size(max = 320) String email,
      @NotNull @Pattern(regexp = "^(?:ORG_OWNER|ORG_COORDINATOR)$") String role) {}

  public record MembershipUpdate(
      @NotNull @Pattern(regexp = "^(?:ORG_OWNER|ORG_COORDINATOR)$") String role,
      @NotNull @Pattern(regexp = "^(?:ACTIVE|REVOKED)$") String status) {}

  public record NeedResourceInput(
      @NotNull UUID resourceId, @NotNull Boolean required, @Size(max = 280) String details) {}

  public record NeedSkillInput(
      @NotNull UUID skillId,
      @NotNull Boolean required,
      @Pattern(regexp = "^(?:BASIC|INTERMEDIATE|ADVANCED)$") String minimumProficiency) {}

  public record NeedWrite(
      @NotNull @NotBlank @Size(max = 180) String title,
      @NotNull @NotBlank @Size(max = 500) String summary,
      @NotNull @NotBlank @Size(max = 10000) String description,
      @NotNull @NotBlank @Size(max = 1000) String expectedOutcome,
      @NotNull @Pattern(regexp = "^(?:IN_PERSON|REMOTE|HYBRID)$") String modality,
      @NotNull @Pattern(regexp = "^(?:LOW|MODERATE)$") String riskLevel,
      @NotNull OffsetDateTime startsAt,
      @NotNull OffsetDateTime endsAt,
      @NotNull @Pattern(regexp = "^(?:America/Bogota)$") String timezone,
      UUID territoryId,
      @Size(max = 180) String publicLocationLabel,
      @Size(max = 10000) String conditions,
      @NotNull @Min(1) @Max(32767) Integer slotsRequired,
      @Size(max = 1000) String privateLocationInstructions,
      @NotNull @Size(max = 100) List<@NotNull @Valid NeedSkillInput> skills,
      @NotNull @Size(max = 100) List<@NotNull @Valid NeedResourceInput> resources) {}

  public record NotificationRead(@NotNull @AssertTrue Boolean read) {}

  public record ParticipationVerification(
      @NotNull @Pattern(regexp = "^(?:VERIFIED|DISPUTED)$") String decision,
      @Size(max = 1200) String reason) {}

  public record ParticipationWrite(
      @DecimalMin("0") @DecimalMax("999.99") @Digits(integer = 3, fraction = 2)
          BigDecimal actualHours,
      @Size(max = 1200) String notes,
      @NotNull @Pattern(regexp = "^(?:COMPLETED|NO_SHOW)$") String attendance) {}

  public record ReasonRequest(@NotNull @NotBlank @Size(max = 1000) String reason) {}

  public record ResourceWrite(
      @NotNull @NotBlank @Size(max = 100) @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$")
          String slug,
      @NotNull @NotBlank @Size(max = 140) String name,
      @NotNull @NotBlank @Size(max = 100) String category,
      @NotNull Boolean active) {}

  public record ResourcesUpdate(
      @NotNull @Size(max = 100) List<@NotNull @Valid VolunteerResourceInput> items) {}

  public record ServiceAreasUpdate(@NotNull @Size(max = 100) List<@NotNull UUID> territoryIds) {}

  public record SkillWrite(
      @NotNull @NotBlank @Size(max = 100) @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$")
          String slug,
      @NotNull @NotBlank @Size(max = 140) String name,
      @NotNull @NotBlank @Size(max = 100) String category,
      @NotNull Boolean active) {}

  public record SkillsUpdate(
      @NotNull @Size(max = 100) List<@NotNull @Valid VolunteerSkillInput> items) {}

  public record TerritoryWrite(
      @NotNull @NotBlank @Size(max = 100) @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$")
          String slug,
      @NotNull @NotBlank @Size(max = 140) String name,
      @NotNull @Pattern(regexp = "^(?:VALLEY|MUNICIPALITY|COMMUNE|NEIGHBORHOOD)$") String type,
      UUID parentId,
      @NotNull Boolean active) {}

  public record VolunteerResourceInput(@NotNull UUID resourceId, @Size(max = 280) String details) {}

  public record VolunteerSkillInput(
      @NotNull UUID skillId,
      @Pattern(regexp = "^(?:BASIC|INTERMEDIATE|ADVANCED)$") String proficiency,
      @Min(0) @Max(100) Integer yearsExperience) {}
}
