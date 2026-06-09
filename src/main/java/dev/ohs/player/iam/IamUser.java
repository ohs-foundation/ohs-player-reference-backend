package dev.ohs.player.iam;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

@NullUnmarked
@Data
public class IamUser {

  private String username;
  private String firstName;
  private String lastName;
  private String email;
  private boolean enabled;
  private boolean temporaryPassword;

  /** Optional. Not supported by all IAM providers. Maps to {@code Practitioner.birthDate}. */
  @Nullable private String dob;

  /** Optional. Not supported by all IAM providers. Maps to {@code Practitioner.gender}. */
  @Nullable private String gender;

  /**
   * Optional. Not supported by all IAM providers. Maps to a {@code Practitioner.identifier} with
   * system {@code http://ohs.dev/identifiers/national-id}.
   */
  @JsonProperty("national_id")
  @Nullable
  private String nationalId;

  /**
   * Optional. Not supported by all IAM providers. Maps to {@code Practitioner.telecom}
   * (phone/mobile).
   */
  @Nullable private String phone;

  /**
   * Desired group memberships. Null = leave unchanged (on update). Empty list = remove from all
   * groups. Non-empty = set exact membership.
   */
  private List<String> groupIds;
}
