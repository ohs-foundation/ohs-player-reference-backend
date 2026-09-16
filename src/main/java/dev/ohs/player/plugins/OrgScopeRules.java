package dev.ohs.player.plugins;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.parser.DataFormatException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Decides, per FHIR resource type, which search parameter ties a resource to an organization.
 *
 * <p>Rules, first match wins:
 *
 * <ol start="0">
 *   <li>Type in {@link #UNSCOPED_TYPES}: shared reference data, not scoped.
 *   <li>{@code Patient}: {@code organization} ({@code Patient.managingOrganization}).
 *   <li>{@code Organization}: {@code _id}.
 *   <li>Has a {@code patient} search parameter that only targets Patient: {@code
 *       patient.organization}, i.e. the resource is in scope when its Patient is.
 *   <li>Otherwise in the Patient compartment: denied, because there is no Patient-only chain.
 *   <li>Has an {@code organization} search parameter targeting Organization: {@code organization}.
 *   <li>Anything else: denied.
 * </ol>
 *
 * <p><b>Extension point — denied compartment types.</b> Rule 4 covers AdverseEvent,
 * DeviceUseStatement, Group, Schedule and SupplyRequest. They could be allowed with {@code
 * subject:Patient.organization}, at the cost of hiding rows whose subject is not a Patient.
 *
 * <p><b>Extension point — chained search cost.</b> {@code patient.organization} is a chained
 * search. For large stores, denormalise the organization onto each resource (a tag or a custom
 * SearchParameter) and return that parameter here instead.
 */
public final class OrgScopeRules {

  /**
   * Resource types that are shared across organizations and never scoped.
   *
   * <p><b>Extension point — unscoped types.</b> A constant to keep configuration small. Move it to
   * an {@code org-scope.*} property if deployments need different lists.
   */
  static final Set<String> UNSCOPED_TYPES =
      Set.of(
          "Practitioner",
          "ValueSet",
          "CodeSystem",
          "ConceptMap",
          "Questionnaire",
          "StructureDefinition",
          "SearchParameter",
          "CapabilityStatement",
          "OperationDefinition",
          "NamingSystem",
          "Medication",
          "Substance");

  /**
   * Search parameters removed from every scoped search, because they can pull in resources the
   * injected filter does not constrain.
   */
  static final List<String> DISCARD_PARAMS =
      List.of("_include", "_revinclude", "_filter", "_contained", "_containedType");

  static final String PATIENT_ORGANIZATION = "patient.organization";

  private static final String PATIENT = "Patient";
  private static final String ORGANIZATION = "Organization";

  private final FhirContext fhirContext;

  public OrgScopeRules(FhirContext fhirContext) {
    this.fhirContext = fhirContext;
  }

  /** Returns how {@code resourceType} is scoped; unknown types are denied. */
  public ScopeParam scopeParamFor(String resourceType) {
    if (UNSCOPED_TYPES.contains(resourceType)) {
      return ScopeParam.NONE;
    }
    if (PATIENT.equals(resourceType)) {
      return ScopeParam.of("organization");
    }
    if (ORGANIZATION.equals(resourceType)) {
      return ScopeParam.of("_id");
    }
    RuntimeResourceDefinition definition;
    try {
      definition = fhirContext.getResourceDefinition(resourceType);
    } catch (DataFormatException e) {
      return ScopeParam.DENY;
    }
    if (targetsOnly(definition.getSearchParam("patient"), PATIENT)) {
      return ScopeParam.of(PATIENT_ORGANIZATION);
    }
    if (!definition.getSearchParamsForCompartmentName(PATIENT).isEmpty()) {
      return ScopeParam.DENY;
    }
    RuntimeSearchParam organization = definition.getSearchParam("organization");
    if (organization != null && organization.getTargets().contains(ORGANIZATION)) {
      return ScopeParam.of("organization");
    }
    return ScopeParam.DENY;
  }

  private static boolean targetsOnly(@Nullable RuntimeSearchParam param, String target) {
    return param != null
        && !param.getTargets().isEmpty()
        && param.getTargets().stream().allMatch(target::equals);
  }

  /** The result of {@link #scopeParamFor}: not scoped, denied, or scoped by one parameter. */
  public static final class ScopeParam {

    /** The type is shared reference data; no filter is applied. */
    public static final ScopeParam NONE = new ScopeParam(Kind.NONE, null);

    /** The type cannot be scoped and is denied. */
    public static final ScopeParam DENY = new ScopeParam(Kind.DENY, null);

    /** What kind of result this is. */
    public enum Kind {
      NONE,
      DENY,
      PARAM
    }

    private final Kind kind;
    private final @Nullable String param;

    private ScopeParam(Kind kind, @Nullable String param) {
      this.kind = kind;
      this.param = param;
    }

    public static ScopeParam of(String param) {
      return new ScopeParam(Kind.PARAM, Objects.requireNonNull(param));
    }

    public Kind kind() {
      return kind;
    }

    /**
     * The search parameter that scopes the type.
     *
     * @throws IllegalStateException if {@link #kind()} is not {@link Kind#PARAM}
     */
    public String param() {
      if (param == null) {
        throw new IllegalStateException("No scope parameter for " + kind);
      }
      return param;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof ScopeParam)) {
        return false;
      }
      ScopeParam other = (ScopeParam) o;
      return kind == other.kind && Objects.equals(param, other.param);
    }

    @Override
    public int hashCode() {
      return Objects.hash(kind, param);
    }

    @Override
    public String toString() {
      return kind == Kind.PARAM ? "ScopeParam(" + param + ")" : "ScopeParam." + kind;
    }
  }
}
