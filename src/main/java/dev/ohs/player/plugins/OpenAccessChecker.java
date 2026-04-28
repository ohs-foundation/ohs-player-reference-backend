package dev.ohs.player.plugins;

import ca.uhn.fhir.context.FhirContext;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.fhir.gateway.FhirUtil;
import com.google.fhir.gateway.HttpFhirClient;
import com.google.fhir.gateway.JwtUtil;
import com.google.fhir.gateway.interfaces.*;

import jakarta.inject.Named;

import java.util.Objects;

public class OpenAccessChecker implements AccessChecker {

    // We're not using any of the parameters here, but real access checkers
    // would likely use some/all.
    private OpenAccessChecker(
            HttpFhirClient httpFhirClient,
            String claim,
            FhirContext fhirContext,
            PatientFinder patientFinder) {
        Objects.requireNonNull(httpFhirClient);
        Objects.requireNonNull(claim);
        Objects.requireNonNull(fhirContext);
        Objects.requireNonNull(patientFinder);
    }

    @Override
    public AccessDecision checkAccess(RequestDetailsReader requestDetails) {
        return NoOpAccessDecision.accessGranted();
    }

    //Note, when loading this project as an external jar with loader.path the @Named annotation is redundant because
    // this jar uses spring auto-configuration to create the beans.
    // The real name of the bean can be found in the class dev.ohs.player.configs.OtherConfigs.java
    @Named(value = "open-sesame")
    public static class Factory implements AccessCheckerFactory {

        static final String CLAIM = "sub";

        private String getClaim(DecodedJWT jwt) {
            return FhirUtil.checkIdOrFail(JwtUtil.getClaimOrDie(jwt, CLAIM));
        }

        @Override
        public AccessChecker create(
                DecodedJWT jwt,
                HttpFhirClient httpFhirClient,
                FhirContext fhirContext,
                PatientFinder patientFinder) {
            String claim = getClaim(jwt);
            return new OpenAccessChecker(httpFhirClient, claim, fhirContext, patientFinder);
        }
    }
}
