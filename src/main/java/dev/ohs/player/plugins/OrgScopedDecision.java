package dev.ohs.player.plugins;

import com.google.common.annotations.VisibleForTesting;
import com.google.fhir.gateway.interfaces.RequestDetailsReader;
import com.google.fhir.gateway.interfaces.RequestMutation;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of {@link OrgScopedAccessChecker}: a grant or deny, plus the search filter to inject
 * for a granted search. It keeps {@link IamAccessDecision#getUserWho} so AuditEvents still name the
 * caller.
 *
 * <p><b>Extension point — more dimensions.</b> Only the organization is filtered. Location,
 * care-team or practitioner filters can be added as further entries in the mutation's {@code
 * additionalQueryParams}; the upstream store ANDs them with the organization filter.
 */
public class OrgScopedDecision extends IamAccessDecision {

  private final @Nullable RequestMutation mutation;
  private final OrgScopedAccessChecker.@Nullable DenyReason denyReason;

  private OrgScopedDecision(
      boolean granted,
      @Nullable RequestMutation mutation,
      OrgScopedAccessChecker.@Nullable DenyReason denyReason) {
    super(granted);
    this.mutation = mutation;
    this.denyReason = denyReason;
  }

  static OrgScopedDecision grant() {
    return new OrgScopedDecision(true, null, null);
  }

  static OrgScopedDecision grant(RequestMutation mutation) {
    return new OrgScopedDecision(true, mutation, null);
  }

  static OrgScopedDecision deny(OrgScopedAccessChecker.DenyReason reason) {
    return new OrgScopedDecision(false, null, reason);
  }

  /** The filter to apply to a granted search; {@code null} when nothing is injected. */
  @Override
  public @Nullable RequestMutation getRequestMutation(RequestDetailsReader requestDetailsReader) {
    return mutation;
  }

  /** Why access was denied; {@code null} for a grant. */
  @VisibleForTesting
  OrgScopedAccessChecker.@Nullable DenyReason getDenyReason() {
    return denyReason;
  }
}
