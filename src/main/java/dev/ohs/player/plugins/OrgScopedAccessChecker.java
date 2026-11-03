package dev.ohs.player.plugins;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.patch.JsonPatchUtils;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.base.Splitter;
import com.google.fhir.gateway.FhirUtil;
import com.google.fhir.gateway.HttpFhirClient;
import com.google.fhir.gateway.interfaces.AccessChecker;
import com.google.fhir.gateway.interfaces.AccessCheckerFactory;
import com.google.fhir.gateway.interfaces.AccessDecision;
import com.google.fhir.gateway.interfaces.PatientFinder;
import com.google.fhir.gateway.interfaces.RequestDetailsReader;
import com.google.fhir.gateway.interfaces.RequestMutation;
import dev.ohs.player.fhir.GatewayFhirSearch;
import dev.ohs.player.fhir.OrgScope;
import dev.ohs.player.fhir.OrgScopeService;
import dev.ohs.player.iam.IamProviderService;
import dev.ohs.player.plugins.OrgScopeRules.ScopeParam;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.inject.Named;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A sample access checker that limits each caller to the data of their organizations, on top of the
 * role checks of {@link OhsPlayerAccessChecker}. Select it with {@code
 * ACCESS_CHECKER=org_scoped_access}.
 *
 * <p>A caller's organizations are those of their {@code PractitionerRole}s, plus every descendant
 * organization (see {@link dev.ohs.player.fhir.PartOfOrganizationScopeExpander}). Data belongs to
 * an organization through {@code Patient.managingOrganization}, and everything in a Patient's
 * compartment belongs where its Patient does. {@link OrgScopeRules} maps each resource type to the
 * search parameter that expresses this.
 *
 * <p>Supported interactions:
 *
 * <ul>
 *   <li>Search: allowed, with the organization filter injected into the query.
 *   <li>Read, vread, instance history and delete by id: allowed if the resource is in scope.
 *   <li>Create: allowed if the new resource is in scope (a Patient's managing organization, or the
 *       Patient a compartment resource references).
 *   <li>Update by id: as create, and the existing resource (if any) must also be in scope.
 *   <li>JSON Patch by id (as sent by the Android FHIR SDK): the existing resource must be in scope,
 *       and the patched result must pass the create rule. FHIRPath Patch is denied.
 *   <li>Transaction and batch Bundles of the above, except searches. A Patient created earlier in
 *       the same Bundle counts as in scope for later entries. A PATCH entry carries its patch in a
 *       {@code Binary}, and is denied if another entry targets the same resource.
 * </ul>
 *
 * <p>Everything else is denied. Each denial names an extension point:
 *
 * <ul>
 *   <li><b>Extension point — operations and system calls.</b> {@code $operations}, {@code POST
 *       _search}, type-level history and system-level requests are denied. {@code $everything}
 *       could be allowed through an allow-list plus the read-by-id check on the instance id.
 *   <li><b>Extension point — Bundle searches.</b> A request mutation only changes the top-level
 *       query, never a Bundle body, so searches inside Bundles are denied.
 *   <li><b>Extension point — organization-anchored writes.</b> Writes to Organization, Location and
 *       other types scoped by {@code organization} are denied. Add a per-type map of the element
 *       that anchors each type to an organization, e.g. {@code Location.managingOrganization}.
 *   <li><b>Paging.</b> The Gateway lets {@code _getpages} continuation requests through its allowed
 *       queries list before any checker runs. They only return pages of a search this checker
 *       already filtered, but a page id can be replayed by anyone holding it. Leave {@code
 *       ALLOWED_QUERIES_FILE} unset for strict mode, at the cost of paging.
 * </ul>
 */
public class OrgScopedAccessChecker implements AccessChecker {

  private static final Logger logger = LoggerFactory.getLogger(OrgScopedAccessChecker.class);

  /** Role that bypasses org scoping. Role checks still apply. */
  static final String EXEMPT_ROLE = "ORG_SCOPE_EXEMPT";

  private static final String PATIENT = "Patient";
  private static final String ORGANIZATION = "Organization";
  private static final String HISTORY = "_history";
  private static final String CONTENT_TYPE = "Content-Type";
  private static final String JSON_PATCH_MEDIA_TYPE = "application/json-patch+json";

  /** Why a request was denied; logged, and exposed to tests. */
  enum DenyReason {
    ROLE_MISSING,
    NO_ORGANIZATION,
    UNSUPPORTED,
    TYPE_NOT_SCOPABLE,
    OUT_OF_SCOPE,
    NO_PATIENT_REFERENCE,
    INVALID_PATCH,
    UPSTREAM_FAILURE
  }

  /** What a request (or Bundle entry) does. */
  enum Kind {
    BUNDLE,
    SEARCH,
    BY_ID,
    CREATE,
    UPDATE,
    PATCH,
    UNSUPPORTED
  }

  /** One request, or one Bundle entry, reduced to what the rules need. */
  static final class Target {
    final Kind kind;
    final @Nullable String type;
    final @Nullable String id;
    final @Nullable Resource body;

    /** The JSON Patch document of a {@link Kind#PATCH} target. */
    final @Nullable String patch;

    final String description;

    Target(
        Kind kind,
        @Nullable String type,
        @Nullable String id,
        @Nullable Resource body,
        String description) {
      this(kind, type, id, body, null, description);
    }

    private Target(
        Kind kind,
        @Nullable String type,
        @Nullable String id,
        @Nullable Resource body,
        @Nullable String patch,
        String description) {
      this.kind = kind;
      this.type = type;
      this.id = id;
      this.body = body;
      this.patch = patch;
      this.description = description;
    }

    static Target unsupported(String description) {
      return new Target(Kind.UNSUPPORTED, null, null, null, description);
    }

    /** A JSON Patch of {@code type/id}, or an unsupported target if there is no JSON Patch. */
    static Target patch(
        String type, @Nullable String id, @Nullable String patch, String description) {
      return id == null || patch == null
          ? unsupported(description)
          : new Target(Kind.PATCH, type, id, null, patch, description);
    }
  }

  private final AccessChecker roleChecker;
  private final OrgScope scope;
  private final OrgScopeRules rules;
  private final OrgScopeService scopeService;
  private final GatewayFhirSearch search;
  private final PatientFinder patientFinder;
  private final FhirContext fhirContext;

  OrgScopedAccessChecker(
      AccessChecker roleChecker,
      OrgScope scope,
      OrgScopeRules rules,
      OrgScopeService scopeService,
      GatewayFhirSearch search,
      PatientFinder patientFinder,
      FhirContext fhirContext) {
    this.roleChecker = roleChecker;
    this.scope = scope;
    this.rules = rules;
    this.scopeService = scopeService;
    this.search = search;
    this.patientFinder = patientFinder;
    this.fhirContext = fhirContext;
  }

  @Override
  public AccessDecision checkAccess(RequestDetailsReader request) {
    String description = request.getRequestType() + " " + request.getRequestPath();
    if (!roleChecker.checkAccess(request).canAccess()) {
      return deny(description, DenyReason.ROLE_MISSING);
    }
    if (scope.isExempt()) {
      return OrgScopedDecision.grant();
    }
    if (scope.isEmpty()) {
      return deny(description, DenyReason.NO_ORGANIZATION);
    }
    Target target = topLevelTarget(request, description);
    try {
      switch (target.kind) {
        case BUNDLE:
          return checkBundle(request, description);
        case SEARCH:
          return checkSearch(target);
        default:
          DenyReason reason = check(target, Set.of(), Map.of());
          return reason == null ? OrgScopedDecision.grant() : deny(target.description, reason);
      }
    } catch (GatewayFhirSearch.UpstreamException e) {
      logger.warn("Org scope check failed upstream for {}", description, e);
      return deny(description, DenyReason.UPSTREAM_FAILURE);
    }
  }

  // ---- Target classification ----

  private Target topLevelTarget(RequestDetailsReader request, String description) {
    RequestTypeEnum method = request.getRequestType();
    String type = request.getResourceName();
    if (method == RequestTypeEnum.POST && type == null) {
      return new Target(Kind.BUNDLE, null, null, null, description);
    }
    IIdType idType = request.getId();
    String id = idType == null ? null : idType.getIdPart();
    String operation = request.getOperation();
    boolean instanceHistory = HISTORY.equals(operation) && id != null;
    if (type == null || (operation != null && !instanceHistory)) {
      return Target.unsupported(description);
    }
    if (method == RequestTypeEnum.GET) {
      return id == null
          ? new Target(Kind.SEARCH, type, null, null, description)
          : new Target(Kind.BY_ID, type, id, null, description);
    }
    if (method == RequestTypeEnum.DELETE && id != null) {
      return new Target(Kind.BY_ID, type, id, null, description);
    }
    if (method == RequestTypeEnum.POST && id == null) {
      return new Target(Kind.CREATE, type, null, parseBody(request), description);
    }
    if (method == RequestTypeEnum.PUT && id != null) {
      return new Target(Kind.UPDATE, type, id, parseBody(request), description);
    }
    if (method == RequestTypeEnum.PATCH && operation == null) {
      String patch = isJsonPatch(request.getHeader(CONTENT_TYPE)) ? readText(request) : null;
      return Target.patch(type, id, patch, description);
    }
    return Target.unsupported(description);
  }

  private static String readText(RequestDetailsReader request) {
    Charset charset = request.getCharset();
    return new String(
        request.loadRequestContents(), charset == null ? StandardCharsets.UTF_8 : charset);
  }

  /**
   * The JSON Patch carried by a Bundle entry: a {@code Binary} whose content type is JSON Patch.
   * Anything else, such as a FHIRPath Patch {@code Parameters}, yields {@code null}.
   */
  private static @Nullable String jsonPatchOf(@Nullable Resource body) {
    if (!(body instanceof Binary)) {
      return null;
    }
    Binary binary = (Binary) body;
    return isJsonPatch(binary.getContentType()) && binary.hasData()
        ? new String(binary.getData(), StandardCharsets.UTF_8)
        : null;
  }

  private static boolean isJsonPatch(@Nullable String contentType) {
    if (contentType == null) {
      return false;
    }
    int parameters = contentType.indexOf(';');
    String mediaType = parameters < 0 ? contentType : contentType.substring(0, parameters);
    return JSON_PATCH_MEDIA_TYPE.equalsIgnoreCase(mediaType.trim());
  }

  private Resource parseBody(RequestDetailsReader request) {
    IBaseResource resource;
    try {
      resource = FhirUtil.createResourceFromRequest(fhirContext, request);
    } catch (DataFormatException e) {
      throw new InvalidRequestException("Request body is not a valid FHIR JSON resource", e);
    }
    if (!(resource instanceof Resource)) {
      throw new InvalidRequestException("Request body is not a FHIR R4 resource");
    }
    return (Resource) resource;
  }

  /**
   * Classifies a Bundle entry from its method, URL and resource. Parsing mirrors {@code
   * OhsPlayerAccessChecker}: the path gives the type and id, a query string makes a GET a search.
   */
  private static Target entryTarget(Bundle.BundleEntryComponent entry, int index) {
    Bundle.BundleEntryRequestComponent request = entry.getRequest();
    String url = request.getUrl();
    String description = "Bundle entry " + index + " " + request.getMethod() + " " + url;
    if (request.getMethod() == null || url == null || url.isBlank()) {
      return Target.unsupported(description);
    }
    int queryStart = url.indexOf('?');
    boolean hasQuery = queryStart >= 0;
    String path = hasQuery ? url.substring(0, queryStart) : url;
    List<String> segments = Splitter.on('/').splitToList(stripSlashes(path));
    String type = segments.get(0);
    if (type.isEmpty() || type.startsWith("$") || type.startsWith("_")) {
      return Target.unsupported(description);
    }
    for (String segment : segments.subList(1, segments.size())) {
      if (segment.startsWith("$") || segment.equals("_search")) {
        return Target.unsupported(description);
      }
    }
    boolean validInstancePath =
        segments.size() == 2
            || (segments.size() >= 3 && segments.size() <= 4 && HISTORY.equals(segments.get(2)));
    String id = segments.size() >= 2 ? segments.get(1) : null;
    Resource body = entry.getResource();
    switch (request.getMethod()) {
      case GET:
        if (segments.size() == 1 || hasQuery) {
          return new Target(Kind.SEARCH, type, null, null, description);
        }
        return validInstancePath
            ? new Target(Kind.BY_ID, type, id, null, description)
            : Target.unsupported(description);
      case DELETE:
        return segments.size() == 2 && !hasQuery
            ? new Target(Kind.BY_ID, type, id, null, description)
            : Target.unsupported(description);
      case POST:
        return segments.size() == 1 && !hasQuery
            ? new Target(Kind.CREATE, type, null, body, description)
            : Target.unsupported(description);
      case PUT:
        return segments.size() == 2 && !hasQuery
            ? new Target(Kind.UPDATE, type, id, body, description)
            : Target.unsupported(description);
      case PATCH:
        return segments.size() == 2 && !hasQuery
            ? Target.patch(type, id, jsonPatchOf(body), description)
            : Target.unsupported(description);
      default:
        return Target.unsupported(description);
    }
  }

  private static String stripSlashes(String path) {
    int start = 0;
    int end = path.length();
    while (start < end && path.charAt(start) == '/') {
      start++;
    }
    while (end > start && path.charAt(end - 1) == '/') {
      end--;
    }
    return path.substring(start, end);
  }

  // ---- Rules ----

  private AccessDecision checkSearch(Target target) {
    ScopeParam scopeParam = rules.scopeParamFor(requireType(target));
    switch (scopeParam.kind()) {
      case NONE:
        return OrgScopedDecision.grant();
      case DENY:
        return deny(target.description, DenyReason.TYPE_NOT_SCOPABLE);
      default:
        String param = scopeParam.param();
        List<String> discard = new ArrayList<>(OrgScopeRules.DISCARD_PARAMS);
        discard.add(param);
        RequestMutation mutation =
            RequestMutation.builder()
                .additionalQueryParams(Map.of(param, List.of(scope.filterValue(param))))
                .discardQueryParams(discard)
                .build();
        return OrgScopedDecision.grant(mutation);
    }
  }

  private AccessDecision checkBundle(RequestDetailsReader request, String description) {
    Bundle bundle;
    try {
      bundle = FhirUtil.parseRequestToBundle(fhirContext, request);
    } catch (DataFormatException | IllegalArgumentException e) {
      throw new InvalidRequestException("Request body is not a valid FHIR Bundle", e);
    }
    Map<String, String> typesByFullUrl = new HashMap<>();
    for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
      if (entry.hasFullUrl() && entry.hasResource()) {
        typesByFullUrl.put(entry.getFullUrl(), entry.getResource().fhirType());
      }
    }
    List<Bundle.BundleEntryComponent> entries = bundle.getEntry();
    List<Target> targets = new ArrayList<>();
    Map<String, Integer> entriesPerResource = new HashMap<>();
    for (int i = 0; i < entries.size(); i++) {
      Target target = entryTarget(entries.get(i), i);
      targets.add(target);
      if (target.type != null && target.id != null) {
        entriesPerResource.merge(target.type + "/" + target.id, 1, Integer::sum);
      }
    }
    Set<String> bundlePatients = new HashSet<>();
    for (int i = 0; i < entries.size(); i++) {
      Bundle.BundleEntryComponent entry = entries.get(i);
      Target target = targets.get(i);
      DenyReason reason;
      if (target.kind == Kind.SEARCH) {
        reason = DenyReason.UNSUPPORTED;
      } else if (target.kind == Kind.PATCH
          && entriesPerResource.getOrDefault(target.type + "/" + target.id, 0) > 1) {
        // The patch would apply to whatever the other entry leaves, not to the stored version
        // checked here.
        reason = DenyReason.UNSUPPORTED;
      } else {
        reason = check(target, bundlePatients, typesByFullUrl);
      }
      if (reason != null) {
        return deny(description + " (" + target.description + ")", reason);
      }
      if (target.body instanceof Patient) {
        rememberBundlePatient(entry, target, bundlePatients);
      }
    }
    return OrgScopedDecision.grant();
  }

  /** Records every way a later entry may reference this in-scope Patient. */
  private static void rememberBundlePatient(
      Bundle.BundleEntryComponent entry, Target target, Set<String> bundlePatients) {
    if (entry.hasFullUrl()) {
      bundlePatients.add(entry.getFullUrl());
    }
    if (target.id != null) {
      bundlePatients.add(target.id);
    }
    IdType bodyId = Objects.requireNonNull(target.body).getIdElement();
    if (bodyId.hasIdPart()) {
      bundlePatients.add(bodyId.getIdPart());
    }
  }

  /** Returns why {@code target} is denied, or {@code null} if it is allowed. */
  private @Nullable DenyReason check(
      Target target, Set<String> bundlePatients, Map<String, String> typesByFullUrl) {
    switch (target.kind) {
      case BY_ID:
        return checkById(target);
      case CREATE:
        return checkBody(target, bundlePatients, typesByFullUrl);
      case UPDATE:
        DenyReason bodyReason = checkBody(target, bundlePatients, typesByFullUrl);
        return bodyReason != null ? bodyReason : checkExistingForUpdate(target);
      case PATCH:
        return checkPatch(target, bundlePatients, typesByFullUrl);
      default:
        return DenyReason.UNSUPPORTED;
    }
  }

  /**
   * A patch must target a resource that is in scope, and the patched resource must pass the same
   * body rule as an update. The patch is applied in memory with HAPI's own JSON Patch code, so the
   * result is what the store would write.
   *
   * <p>The check reads the current version, so a concurrent write between this check and the store
   * applying the patch is not seen. The same holds for updates.
   */
  private @Nullable DenyReason checkPatch(
      Target target, Set<String> bundlePatients, Map<String, String> typesByFullUrl) {
    String type = requireType(target);
    String id = requireId(target);
    ScopeParam scopeParam = rules.scopeParamFor(type);
    switch (scopeParam.kind()) {
      case NONE:
        return null;
      case DENY:
        return DenyReason.TYPE_NOT_SCOPABLE;
      default:
        Resource current = scopeService.findInScope(type, id, scopeParam.param(), scope, search);
        if (current == null) {
          return DenyReason.OUT_OF_SCOPE;
        }
        Resource patched;
        try {
          patched =
              JsonPatchUtils.apply(fhirContext, current, Objects.requireNonNull(target.patch));
        } catch (InvalidRequestException | DataFormatException e) {
          logger.info(
              "JSON Patch cannot be applied for {}: {}", target.description, e.getMessage());
          return DenyReason.INVALID_PATCH;
        }
        return checkBody(
            new Target(Kind.UPDATE, type, id, patched, target.description),
            bundlePatients,
            typesByFullUrl);
    }
  }

  private @Nullable DenyReason checkById(Target target) {
    String type = requireType(target);
    String id = requireId(target);
    ScopeParam scopeParam = rules.scopeParamFor(type);
    switch (scopeParam.kind()) {
      case NONE:
        return null;
      case DENY:
        return DenyReason.TYPE_NOT_SCOPABLE;
      default:
        boolean inScope =
            ORGANIZATION.equals(type)
                ? scope.organizationIds().contains(id)
                : scopeService.existsInScope(type, id, scopeParam.param(), scope, search);
        return inScope ? null : DenyReason.OUT_OF_SCOPE;
    }
  }

  /**
   * An update may target a resource that is in scope, or create one under a client-chosen id. It
   * must not overwrite a resource that belongs to another organization.
   */
  private @Nullable DenyReason checkExistingForUpdate(Target target) {
    String type = requireType(target);
    String id = requireId(target);
    ScopeParam scopeParam = rules.scopeParamFor(type);
    if (scopeParam.kind() != ScopeParam.Kind.PARAM) {
      // Unscoped types; checkBody has already denied types that cannot be scoped.
      return null;
    }
    boolean allowed =
        scopeService.existsInScope(type, id, scopeParam.param(), scope, search)
            || !scopeService.exists(type, id, search);
    return allowed ? null : DenyReason.OUT_OF_SCOPE;
  }

  /** Checks that the resource being written belongs to the caller's organizations. */
  private @Nullable DenyReason checkBody(
      Target target, Set<String> bundlePatients, Map<String, String> typesByFullUrl) {
    String type = requireType(target);
    Resource body = target.body;
    if (body == null || !type.equals(body.fhirType())) {
      return DenyReason.UNSUPPORTED;
    }
    if (body instanceof Patient) {
      IIdType organization = ((Patient) body).getManagingOrganization().getReferenceElement();
      boolean inScope =
          ORGANIZATION.equals(organization.getResourceType())
              && !organization.isAbsolute()
              && scope.organizationIds().contains(organization.getIdPart());
      return inScope ? null : DenyReason.OUT_OF_SCOPE;
    }
    if (OrgScopeRules.UNSCOPED_TYPES.contains(type)) {
      return null;
    }
    ScopeParam scopeParam = rules.scopeParamFor(type);
    if (scopeParam.kind() != ScopeParam.Kind.PARAM
        || !OrgScopeRules.PATIENT_ORGANIZATION.equals(scopeParam.param())
        || !(body instanceof DomainResource)) {
      return DenyReason.TYPE_NOT_SCOPABLE;
    }
    Set<String> patients = patientReferences((DomainResource) body, typesByFullUrl);
    if (patients.isEmpty()) {
      return DenyReason.NO_PATIENT_REFERENCE;
    }
    for (String patient : patients) {
      if (!isPatientInScope(patient, bundlePatients)) {
        return DenyReason.OUT_OF_SCOPE;
      }
    }
    return null;
  }

  /**
   * The Patients a resource belongs to: bare ids for {@code Patient/<id>} references, and the raw
   * value for {@code urn:} references to a Patient in the same Bundle. HAPI's compartment lookup
   * also returns non-Patient references (e.g. {@code Observation.performer}), which are dropped.
   */
  private Set<String> patientReferences(DomainResource body, Map<String, String> typesByFullUrl) {
    Set<String> patients = new LinkedHashSet<>();
    for (String value : patientFinder.findPatientIds(body)) {
      if (value.startsWith("urn:")) {
        String targetType = typesByFullUrl.get(value);
        if (targetType == null || PATIENT.equals(targetType)) {
          patients.add(value);
        }
        continue;
      }
      IdType reference = new IdType(value);
      if (PATIENT.equals(reference.getResourceType()) && reference.hasIdPart()) {
        // Absolute references keep their full value so they never match a local Patient.
        patients.add(reference.isAbsolute() ? value : reference.getIdPart());
      }
    }
    return patients;
  }

  private boolean isPatientInScope(String patient, Set<String> bundlePatients) {
    if (bundlePatients.contains(patient)) {
      return true;
    }
    if (patient.contains(":") || patient.contains("/")) {
      // An unresolved urn: or an absolute reference cannot be checked against the store.
      return false;
    }
    return scopeService.existsInScope(PATIENT, patient, "organization", scope, search);
  }

  private static String requireType(Target target) {
    if (target.type == null) {
      throw new IllegalStateException("No resource type for " + target.description);
    }
    return target.type;
  }

  private static String requireId(Target target) {
    if (target.id == null || target.id.isBlank()) {
      throw new InvalidRequestException("No resource id in " + target.description);
    }
    return target.id;
  }

  private static OrgScopedDecision deny(String description, DenyReason reason) {
    logger.info("Org-scoped access denied ({}): {}", reason, description);
    return OrgScopedDecision.deny(reason);
  }

  /** Builds an {@link OrgScopedAccessChecker} for each request. */
  @Named("org_scoped_access")
  public static class Factory implements AccessCheckerFactory {

    private final IamProviderService iamProviderService;
    private final OrgScopeService orgScopeService;

    @Autowired
    public Factory(IamProviderService iamProviderService, OrgScopeService orgScopeService) {
      this.iamProviderService = iamProviderService;
      this.orgScopeService = orgScopeService;
    }

    /**
     * Never throws for scope problems (a throw here becomes a 401); an unresolvable scope denies
     * every request instead.
     */
    @Override
    public AccessChecker create(
        DecodedJWT jwt,
        HttpFhirClient httpFhirClient,
        FhirContext fhirContext,
        PatientFinder patientFinder)
        throws AuthenticationException {
      AccessChecker roleChecker =
          new OhsPlayerAccessChecker.Factory(iamProviderService)
              .create(jwt, httpFhirClient, fhirContext, patientFinder);
      Map<String, Object> claims = OhsPlayerAccessChecker.extractClaims(jwt);
      GatewayFhirSearch search = new GatewayFhirSearch(httpFhirClient, fhirContext);
      OrgScope scope =
          isExempt(claims)
              ? OrgScope.exempt()
              : orgScopeService.resolveScope(
                  iamProviderService.extractUserIdFromToken(claims), search);
      return new OrgScopedAccessChecker(
          roleChecker,
          scope,
          new OrgScopeRules(fhirContext),
          orgScopeService,
          search,
          patientFinder,
          fhirContext);
    }

    private boolean isExempt(Map<String, Object> claims) {
      return iamProviderService.extractRolesFromToken(claims).stream()
          .anyMatch(role -> EXEMPT_ROLE.equals(role.toUpperCase(Locale.ROOT)));
    }
  }
}
