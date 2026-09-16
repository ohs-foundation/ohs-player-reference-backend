package dev.ohs.player.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.uhn.fhir.context.FhirContext;
import dev.ohs.player.plugins.OrgScopeRules.ScopeParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrgScopeRulesTest {

  private final OrgScopeRules rules = new OrgScopeRules(FhirContext.forR4Cached());

  @Test
  void scopeParamFor_UnscopedType_ReturnsNone() {
    assertEquals(ScopeParam.NONE, rules.scopeParamFor("Practitioner"));
    assertEquals(ScopeParam.NONE, rules.scopeParamFor("ValueSet"));
  }

  @Test
  void scopeParamFor_Patient_ReturnsOrganization() {
    assertEquals(ScopeParam.of("organization"), rules.scopeParamFor("Patient"));
  }

  @Test
  void scopeParamFor_Organization_ReturnsId() {
    assertEquals(ScopeParam.of("_id"), rules.scopeParamFor("Organization"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Encounter", "Observation", "Condition", "Device", "Coverage"})
  void scopeParamFor_PatientOnlyPatientParam_ReturnsPatientOrganization(String type) {
    assertEquals(ScopeParam.of("patient.organization"), rules.scopeParamFor(type));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"AdverseEvent", "DeviceUseStatement", "Group", "Schedule", "SupplyRequest"})
  void scopeParamFor_CompartmentTypeWithoutPatientOnlyParam_ReturnsDeny(String type) {
    assertEquals(ScopeParam.DENY, rules.scopeParamFor(type));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Endpoint", "HealthcareService", "Location", "PractitionerRole"})
  void scopeParamFor_OrganizationParamType_ReturnsOrganization(String type) {
    assertEquals(ScopeParam.of("organization"), rules.scopeParamFor(type));
  }

  @Test
  void scopeParamFor_EveryUnscopedType_ReturnsNone() {
    // Rule 0 is checked first, so no later rule can scope or deny these types.
    for (String type : OrgScopeRules.UNSCOPED_TYPES) {
      assertEquals(ScopeParam.NONE, rules.scopeParamFor(type), type);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"Library", "Bundle", "NotAType"})
  void scopeParamFor_OtherOrUnknownType_ReturnsDeny(String type) {
    assertEquals(ScopeParam.DENY, rules.scopeParamFor(type));
  }

  @Test
  void param_OnNonParamResult_Throws() {
    assertThrows(IllegalStateException.class, ScopeParam.DENY::param);
  }
}
