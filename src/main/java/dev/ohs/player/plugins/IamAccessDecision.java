/*
 * Copyright 2021-2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ohs.player.plugins;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.base.Strings;
import com.google.fhir.gateway.JwtUtil;
import com.google.fhir.gateway.interfaces.AccessDecision;
import com.google.fhir.gateway.interfaces.RequestDetailsReader;
import com.google.fhir.gateway.interfaces.RequestMutation;
import org.apache.http.HttpResponse;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResourceType;
import org.jspecify.annotations.Nullable;

/**
 * A plain grant/deny {@link AccessDecision} for any IAM-issued token. Any {@link
 * com.google.fhir.gateway.interfaces.AccessChecker} can return this once it has made its yes/no
 * call, regardless of which IAM provider issued the token.
 *
 * <p>Beyond the plain grant/deny outcome, this supplies the audited actor identity via {@link
 * #getUserWho}, which is what the Gateway needs in order to emit an IHE BALP {@code AuditEvent} for
 * the request (a {@code null} return here would make the Gateway skip audit logging entirely, see
 * {@code BearerAuthorizationInterceptor}). Audit logging itself is a separate, server-wide switch:
 * set the {@code AUDIT_EVENT_ACTIONS_CONFIG} environment variable to the FHIR AuditEvent action
 * codes you want logged, written together with no separator, e.g. {@code "CRUDE"} for
 * create/read/update/delete/execute, or a subset like {@code "CUD"} to only audit writes.
 *
 * <p>Note an AuditEvent is written per REST request — including per entry within a Bundle
 * (batch/transaction) — so a busy deployment, or a broad config like {@code "CRUDE"} that also
 * covers reads and searches, can generate a large volume of AuditEvent resources quickly. Prefer a
 * narrower config (e.g. {@code "CUD"}) unless read/search auditing is actually needed.
 */
public class IamAccessDecision implements AccessDecision {

  private static final String CLAIM_PREFERRED_USERNAME = "preferred_username";

  private final boolean accessGranted;

  public IamAccessDecision(boolean accessGranted) {
    this.accessGranted = accessGranted;
  }

  @Override
  public boolean canAccess() {
    return accessGranted;
  }

  /**
   * Always {@code null}: this decision does not mutate the outgoing request beyond the plain
   * grant/deny made by {@link #canAccess}.
   *
   * <p>This is the extension point for finer-grained AuthZ than the role check in {@link
   * OhsPlayerAccessChecker} can express, e.g. constraining a search to only the caller's assigned
   * compartment. Implement this here to return a {@link RequestMutation} adding query parameters
   * such as {@code organization}, {@code location}, or {@code general-practitioner} — read from the
   * caller's token or IAM group — so a user only ever sees data scoped to their assigned
   * organization(s), location(s), or practitioner. (A subclass, or a different {@link
   * AccessDecision} entirely, is also an option if that logic should not live on every instance of
   * this class.) Note the Gateway currently only applies this to the query parameters of a GET
   * request.
   */
  @Override
  public @Nullable RequestMutation getRequestMutation(RequestDetailsReader requestDetailsReader) {
    return null;
  }

  /**
   * Always {@code null}: this decision does no post-processing of the FHIR store's response.
   *
   * <p>This is the extension point for AuthZ that can only be decided after the fact, once the
   * actual resource is known — {@link #getRequestMutation} can constrain a search's query
   * parameters, but a direct read by id (e.g. {@code GET Encounter/enc-1234}) has no query
   * parameters to constrain. Implement this here to parse the returned resource from {@code
   * response} and verify it belongs to an authorized compartment (e.g. its {@code
   * Encounter.serviceProvider}/{@code subject} resolves to one of the caller's assigned
   * organizations or patients), denying access post-hoc (e.g. by throwing, or by returning a
   * redacted/empty body) when it does not. (A subclass, or a different {@link AccessDecision}
   * entirely, is also an option if that logic should not live on every instance of this class.)
   */
  @Override
  public @Nullable String postProcess(RequestDetailsReader request, HttpResponse response) {
    return null;
  }

  /**
   * Prefers the standard OIDC {@code preferred_username} claim (falling back to the generic {@code
   * name} claim) as the audited actor's display name; {@code sub}/{@code iss} are always used to
   * identify the actor.
   */
  @Override
  public @Nullable Reference getUserWho(RequestDetailsReader request) {
    DecodedJWT jwt = JwtUtil.getDecodedJwtFromRequestDetails(request);
    if (jwt == null) {
      return null;
    }
    String username = JwtUtil.getClaimOrDefault(jwt, CLAIM_PREFERRED_USERNAME, "");
    String display =
        Strings.isNullOrEmpty(username)
            ? JwtUtil.getClaimOrDefault(jwt, JwtUtil.CLAIM_NAME, "")
            : username;
    String subject = JwtUtil.getClaimOrDefault(jwt, JwtUtil.CLAIM_SUBJECT, "");
    String issuer = JwtUtil.getClaimOrDefault(jwt, JwtUtil.CLAIM_ISSUER, "");
    return new Reference()
        .setType(ResourceType.Practitioner.name())
        .setDisplay(display)
        .setIdentifier(new Identifier().setSystem(issuer).setValue(subject));
  }
}
