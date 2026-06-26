# OHS Player Reference Backend

[![CI](https://github.com/ohs-foundation/ohs-player-reference-backend/actions/workflows/ci.yml/badge.svg)](https://github.com/ohs-foundation/ohs-player-reference-backend/actions/workflows/ci.yml)
[![CodeQL](https://github.com/ohs-foundation/ohs-player-reference-backend/actions/workflows/codeql.yml/badge.svg)](https://github.com/ohs-foundation/ohs-player-reference-backend/actions/workflows/codeql.yml)
[![codecov](https://codecov.io/gh/ohs-foundation/ohs-player-reference-backend/branch/main/graph/badge.svg)](https://codecov.io/gh/ohs-foundation/ohs-player-reference-backend)
[![License](https://img.shields.io/github/license/ohs-foundation/ohs-player-reference-backend)](LICENSE)
[![Dependabot](https://img.shields.io/badge/dependabot-enabled-025E8C?logo=dependabot)](https://github.com/ohs-foundation/ohs-player-reference-backend/network/updates)

OHS Player backend extensions for OHS Player clients (KMP and Web). Provides custom endpoints and access checker plugins loaded into the FHIR Gateway at runtime.

## Developer Setup

**JDK 21** is required to build this project. The build tooling (Spotless / google-java-format, Error Prone) depends on internal JDK APIs that are only available in JDK 21+. The compiled output still targets Java 11 bytecode, so the JAR can be loaded into any JDK 11+ runtime.

Verify your version before building:

```sh
java -version  # must be 21+
```

## Building

```sh
mvn clean package
```

Output: `target/ohs-player-backend-extensions-1.0-SNAPSHOT.jar`

## Static Analysis

To run Error Prone and NullAway checks locally (the same checks that run in CI):

```sh
mvn clean verify -Perror-prone
```

Violations will fail the build with compiler errors indicating the rule and location.

## Deploying

Load the plugin into the FHIR Gateway via `-Dloader.path`:

```sh
java -Dloader.path="PATH_TO_PLUGIN/ohs-player-backend-extensions-1.0-SNAPSHOT.jar" \
  -jar fhir-gateway.jar --server.port=8081
```

See the [FHIR Gateway documentation](https://github.com/ohs-foundation/fhir-gateway#modules) for full deployment details.

The plugin does not bundle FHIR Gateway classes — they are declared `provided` in `pom.xml` and supplied by the host at runtime.

## Configuration

### Environment variables

| Variable | Source | Description |
| --- | --- | --- |
| `PROXY_TO` | env var | FHIR server base URL |
| `IAM_PROVIDER` | env var | IAM provider to use. Defaults to `keycloak` if unset. |
| `TOKEN_ISSUER` | env var | *(Keycloak)* Issuer URL, e.g. `http://keycloak:8080/realms/my-realm`. Server URL and realm are parsed from this. |
| `IAM_PROVIDER_CLIENT_ID` | env var or `iam.provider.client-id` in `application.properties` | *(Keycloak)* Admin client ID |
| `IAM_PROVIDER_CLIENT_SECRET` | env var or `iam.provider.client-secret` in `application.properties` | *(Keycloak)* Admin client secret |

### Application properties

| Property | Default | Description |
| --- | --- | --- |
| `location-hierarchy.max-part-of-batch-size` | `100` | Number of parent Location ids to search for in one `Location.partOf` request. |
| `location-hierarchy.upstream-page-size` | `200` | Number of child Locations to ask the FHIR server for per page. |
| `location-hierarchy.max-depth` | `25` | Deepest child level to return below the requested root. `0` returns only the root. |
| `location-hierarchy.max-nodes` | `10000` | Maximum number of Location nodes to return in one response, including the root. |
| `location-hierarchy.cache-ttl-seconds` | `86400` | How long to keep a successful hierarchy response in the local Caffeine cache. |
| `location-hierarchy.cache-max-total-nodes` | `100000` | Approximate maximum number of Location nodes held across all cached hierarchy responses in one JVM. |

### IAM providers

The `IAM_PROVIDER` variable selects the identity provider used for user management. Supported values:

| Value | Provider |
| --- | --- |
| `keycloak` | Keycloak (default) |

An unknown value causes the application to fail at startup with a clear error message.

## User Management API

| Method | Endpoint | Description |
| --- | --- | --- |
| `POST` | `/api/users` | Create user in Keycloak and a matching FHIR Practitioner |
| `GET` | `/api/users` | List all Practitioners. Supports any FHIR query parameter, e.g. `?name=John&_sort=family&_count=10` |
| `GET` | `/api/users/{id}` | Get a single Practitioner by FHIR ID |
| `PUT` | `/api/users/{id}` | Update user in Keycloak and Practitioner |
| `PUT` | `/api/users/{id}/password` | Set a user's password in Keycloak |
| `DELETE` | `/api/users/{id}` | Delete user from Keycloak and FHIR |

**Request body** (POST / PUT `/api/users` and `/api/users/{id}`):

```json
{
  "username": "charity",
  "firstName": "Charity",
  "lastName": "Otala",
  "email": "charity@ohs.dev",
  "enabled": true,
  "dob": "1990-05-15",
  "gender": "female",
  "national_id": "NID-12345678",
  "phone": "+254700000000",
  "groupIds": ["group-uuid-1", "group-uuid-2"]
}
```

`username` and `email` are required. `enabled` defaults to `false` if omitted.

`dob`, `gender`, `national_id`, and `phone` are optional. When provided they are written to the FHIR Practitioner resource:

| Field | FHIR mapping |
| --- | --- |
| `dob` | `Practitioner.birthDate` (format: `YYYY-MM-DD`) |
| `gender` | `Practitioner.gender` — accepted values: `male`, `female`, `other`, `unknown` |
| `national_id` | `Practitioner.identifier` with system `http://ohs.dev/identifiers/national-id` |
| `phone` | `Practitioner.telecom` with `system=phone`, `use=mobile` |

The `email` field is always synced to `Practitioner.telecom` with `system=email`, `use=work`.

`groupIds` is optional. When present, group membership is set to exactly the listed IDs: missing memberships are added and extra ones removed. An empty array (`[]`) removes the user from all groups. Omitting the field leaves memberships unchanged. Group assignment failures are logged as warnings and do not fail the overall request.

**Request body** (PUT `/api/users/{id}/password`):

```json
{
  "password": "secret123",
  "temporary": false
}
```

`password` is required. `temporary` is optional and defaults to `false`. When `true`, Keycloak marks the credential as temporary and the user must change it on next login. Returns `204 No Content` on success.

## Group Management API

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/groups` | List all groups (id, name, path) |
| `POST` | `/api/groups` | Create a group |
| `GET` | `/api/groups/{id}` | Get a group by ID (includes role assignments) |
| `PUT` | `/api/groups/{id}` | Update a group (replaces name and role assignments) |
| `DELETE` | `/api/groups/{id}` | Delete a group |
| `POST` | `/api/groups/{groupId}/members/{userId}` | Add a user to a group |
| `DELETE` | `/api/groups/{groupId}/members/{userId}` | Remove a user from a group |

**Request body** (POST / PUT `/api/groups` and `/api/groups/{id}`):

```json
{
  "name": "clinicians",
  "realmRoles": ["GET_PATIENT"],
  "clientRoles": {
    "my-client": ["web.manage-locations", "web.manage-orgs"]
  }
}
```

`name` is required. `realmRoles` and `clientRoles` are optional. On PUT, role assignments are fully replaced by whatever is supplied.

## Roles API

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/roles` | List all realm roles and client roles available for assignment |

**Response:**

```json
{
  "realmRoles": [
    { "id": "...", "name": "offline_access", "description": "..." }
  ],
  "clients": [
    {
      "clientId": "my-client",
      "clientName": "My Client",
      "roles": [
        { "id": "...", "name": "view-records", "description": "..." }
      ]
    }
  ]
}
```

## Location Hierarchy API

| Method | Endpoint | Required role | Description |
| --- | --- | --- | --- |
| `GET` | `/api/location-hierarchy/{rootId}` | `location-hierarchy.view` | Return the `Location.partOf` tree starting from the requested FHIR Location id. |

The caller must be authenticated by the `/api/*` JWT filter and must have the `location-hierarchy.view` role. Authorization is checked before validating the path or reading from the cache/FHIR server.

`rootId` must be a valid FHIR id: 1–64 characters using only letters, numbers, `-`, or `.`. The values `.` and `..` are rejected.

**Response:**

```json
{
  "root": {
    "id": "root-location-id",
    "name": "Root Location",
    "partOf": null,
    "hasMoreChildren": false,
    "children": [
      {
        "id": "child-location-id",
        "name": "Child Location",
        "partOf": "root-location-id",
        "hasMoreChildren": false,
        "children": []
      }
    ]
  },
  "meta": {
    "nodeCount": 2,
    "depth": 1,
    "truncated": false,
    "builtAt": "2026-06-26T12:00:00Z"
  }
}
```

The service builds the tree one level at a time. It starts with the root, fetches its children, then fetches the next level of children, and continues until it reaches `max-depth`, `max-nodes`, or the end of the tree. Results are ordered consistently so the same stored hierarchy returns the same response order.

`max-depth` controls how many child levels can be returned. `max-nodes` controls how many Locations can be returned in one response, including the root. If the response limit is reached, the service stops at a clean parent boundary instead of returning only some children for the same parent. Nodes that may still have children are marked with `hasMoreChildren=true`. When `hasMoreChildren=false`, the returned child list for that node is complete.

Successful responses are cached in-process with Caffeine. The default TTL is one day, so the endpoint accepts up to one day of Location hierarchy staleness. The cache is local to each backend JVM; each replica can build the same root independently after startup or expiry. Shared Redis caching and distributed miss coordination are planned for v2.

| Status | Meaning |
| --- | --- |
| `200` | Hierarchy returned successfully. |
| `400` | Missing or malformed `{rootId}` path segment. |
| `401` | Missing or invalid authentication. |
| `403` | Authenticated caller lacks `location-hierarchy.view`. |
| `404` | Requested root Location was not found. |
| `500` | Unexpected backend failure. |
| `502` | Upstream FHIR server failure while building the hierarchy. |

## Bulk Import API

### Bulk User Import

| Method | Endpoint | Content-Type | Description |
| --- | --- | --- | --- |
| `POST` | `/api/bulk-import/users` | `multipart/form-data` | Import users from a CSV file with SSE progress |

Upload a CSV file in the `file` field. The server streams [Server-Sent Events](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events) back as each row is processed.

```bash
curl -N --form file=@users.csv http://localhost:8081/api/bulk-import/users
```

**CSV columns** (header row required; column order does not matter):

| Column | Required | Description |
| --- | --- | --- |
| `id` | No | FHIR Practitioner ID. If present, the row is an **update**. |
| `username` | Yes | IAM username |
| `first_name` | No | |
| `last_name` | No | |
| `email` | Yes | |
| `group` | No | Group **name** (resolved to ID at import time). |
| `password` | No | Defaults to `{username}123` if omitted. |
| `is_password_temp` | No | `true` or `1` to mark password as temporary. |
| `dob` | No | Date of birth (`YYYY-MM-DD`), maps to `Practitioner.birthDate`. |
| `gender` | No | `male`, `female`, `other`, or `unknown`. |
| `national_id` | No | Maps to `Practitioner.identifier` with system `http://ohs.dev/identifiers/national-id`. |
| `phone` | No | Maps to `Practitioner.telecom` (`phone/mobile`). |
| `source_id` | No | External reference ID. Maps to `Practitioner.identifier` with system `http://ohs.dev/identifiers/source-id`. Also used as an alternate lookup key for updates when `id` is absent. |

**Create vs update resolution:** if `id` is present it takes precedence; otherwise `source_id` is used to look up an existing Practitioner (update if found, create if not); if neither is present a new user is created.

**SSE event format:**

```
data: {"processed": 5, "total": 100}

data: {"error": "Group not found: unknown-group", "row": 6}
```

A progress event is emitted after each successful row. On failure the error event is emitted and the stream closes — subsequent rows are not processed.

**Note:** This endpoint is intended for initial bulk imports and is not meant for updates in production. It does not perform upsert logic beyond the simple `id` and `source_id` resolution described above. To avoid losing data, use the User Management API for ongoing user maintenance.

### Bulk Organization Import

| Method | Endpoint | Content-Type | Description |
| --- | --- | --- | --- |
| `POST` | `/api/bulk-import/organizations` | `multipart/form-data` | Import organizations from a CSV file with SSE progress |

Upload a CSV file in the `file` field. The server streams [Server-Sent Events](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events) back as each row is processed.

```bash
curl -N --form file=@organizations.csv http://localhost:8081/api/bulk-import/organizations
```

**CSV columns** (header row required; column order does not matter):

| Column | Required | Description |
| --- | --- | --- |
| `id` | No | FHIR Organization ID. If present, the row is an **update**. |
| `name` | Yes | Organization name |
| `source_id` | No | External reference ID. Maps to `Organization.identifier` with system `http://ohs.dev/identifiers/source-id`. Also used as an alternate lookup key for updates when `id` is absent. |
| `is_team` | No | `true` or `1` to add `Organization.type` with code `team` (`http://terminology.hl7.org/CodeSystem/organization-type`). Defaults to false. |
| `parent_id` | No | FHIR ID of the parent Organization. Sets `Organization.partOf`. |
| `parent_name` | No | Name of the parent Organization. Resolved by FHIR name search. Used when `parent_id` is absent. |
| `source_parent_id` | No | Source ID of the parent Organization. Resolved by identifier lookup. Takes precedence over `parent_name`. |
| `phone` | No | Maps to `Organization.telecom` (`phone/work`). |
| `email` | No | Maps to `Organization.telecom` (`email/work`). |
| `physical_address` | No | Maps to `Organization.address` with `use=work`, `type=physical`. |
| `postal_address` | No | Maps to `Organization.address` with `use=work`, `type=postal`. |

**Create vs update resolution:** rows are submitted to the FHIR server as a BATCH bundle. If `id` is present the entry is a direct `PUT Organization/{id}`. If `source_id` is present the entry is a conditional `PUT Organization?identifier=…` (creates if absent, updates if found — idempotent). If neither is present the entry is a `POST Organization` (not idempotent; re-running creates a duplicate).

**Parent resolution precedence:** `parent_id` > `source_parent_id` > `parent_name`. If any parent column is provided but no matching Organization is found, the row is skipped with an error event and processing continues. Rows without parent columns are imported without a `partOf` reference.

**Ordering note:** Parent organizations must appear in earlier rows than their children, or be pre-existing in the FHIR server. When a child's parent appears earlier in the same batch, the batch is flushed early (committing the parent), then the child is retried in a new batch — no FHIR indexing lag and no manual ordering of the CSV required beyond parents preceding their children.

**Idempotent re-runs:** when rows fail at the FHIR level the error is emitted per row and processing continues (remaining rows and batches are not skipped). Fix the failing rows and re-POST the full CSV — rows with `id` or `source_id` are safe to re-run without creating duplicates.

**SSE event format:**

```
data: {"processed":50,"total":100}

data: {"error":"Parent organization not found with name: Unknown","row":53}

data: {"processed":95,"total":100}

data: {"done":true,"processed":95,"failed":5,"total":100}
```

One progress event is emitted per completed batch (not per row). Error events are emitted per failed row. A terminal `done` event is always emitted when processing finishes — use `done` to detect stream end and `failed` to decide whether to re-run. Processing continues after errors so all failures are visible in a single pass.

**Configuration:**

| Environment variable | Default | Description |
| --- | --- | --- |
| `BULK_IMPORT_BATCH_SIZE` | `5` | Number of rows per FHIR BATCH bundle. The default is intentionally small for testing. **Change to `50` for production** to reduce FHIR HTTP round-trips significantly (e.g. 50K rows → ~1 000 bundle calls instead of ~50 000). |

---

### Bulk Location Import

| Method | Endpoint | Content-Type | Description |
| --- | --- | --- | --- |
| `POST` | `/api/bulk-import/locations` | `multipart/form-data` | Import locations from a CSV file with SSE progress |

Upload a CSV file in the `file` field. The server streams [Server-Sent Events](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events) back as each row is processed.

```bash
curl -N --form file=@locations.csv http://localhost:8081/api/bulk-import/locations
```

**CSV columns** (header row required; column order does not matter):

| Column | Required | Description |
| --- | --- | --- |
| `id` | No | FHIR Location ID. If present, the row is an **update**. |
| `name` | Yes | Location name |
| `physical_type` | No | Physical type display name (e.g. `building`, `ward`, `room`). See table below. |
| `level` | No | Administrative level code (e.g. `country`, `county`). Maps to `Location.type` with system `http://ohs.dev/codes/administrative-level`. |
| `longitude` | No | Decimal longitude. Set together with `latitude` to populate `Location.position`. |
| `latitude` | No | Decimal latitude. Set together with `longitude` to populate `Location.position`. |
| `source_id` | No | External reference ID. Maps to `Location.identifier` with system `http://ohs.dev/identifiers/source-id`. Also used as an alternate lookup key for updates when `id` is absent. |
| `parent_id` | No | FHIR ID of the parent Location. Sets `Location.partOf`. |
| `source_parent_id` | No | Source ID of the parent Location. Resolved by identifier lookup. |
| `org_id` | No | FHIR ID of the managing Organization. Sets `Location.managingOrganization`. |
| `source_org_id` | No | Source ID of the managing Organization. Resolved by identifier lookup. |

**physical_type values:** accepts any display name from the [FHIR R4 location-physical-type valueset](https://hl7.org/fhir/R4/valueset-location-physical-type.html) (e.g. `building`, `ward`, `room`). Non-empty values not in the valueset are mapped to code `other` with the entered text (capitalized) as the display. Blank values are ignored.

**Materialized path aliases:** each imported Location gets two `alias` entries — a `/`-separated path of ancestor names ending with the current location's name (e.g. `Country/County/Clinic A`), and the equivalent path of FHIR IDs (e.g. `fhir-id-country/fhir-id-county/fhir-id-clinic`).

**Parent resolution precedence:** `parent_id` > `source_parent_id`. If a parent column is provided but no matching Location is found, the row is skipped with an error event and processing continues.

**Managing organization resolution:** `org_id` > `source_org_id`. If provided but not found, the row is skipped with an error event and processing continues.

**Create vs update resolution:** same as org import — `id` → direct `PUT Location/{id}`; `source_id` → conditional `PUT Location?identifier=…` (idempotent); neither → `POST Location` (not idempotent on re-run).

**Ordering note:** Parent locations must appear in earlier rows than their children, or be pre-existing in the FHIR server. When a child's parent appears earlier in the same batch, the batch is flushed early, then the child is retried in a new batch.

**SSE event format:** identical to the org import format above.

**Configuration:** uses the same `BULK_IMPORT_BATCH_SIZE` environment variable as the org import.

---

### Bulk User Assignment Import

| Method | Endpoint | Content-Type | Description |
| --- | --- | --- | --- |
| `POST` | `/api/bulk-import/user-assignments` | `multipart/form-data` | Create PractitionerRole resources from a CSV file with SSE progress |

Upload a CSV file in the `file` field. The server streams [Server-Sent Events](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events) back as each row is processed.

```bash
curl -N --form file=@assignments.csv http://localhost:8081/api/bulk-import/user-assignments
```

**CSV columns** (header row required; column order does not matter):

| Column | Required | Description |
| --- | --- | --- |
| `practitioner_id` | One of these two | Direct FHIR ID of the Practitioner |
| `practitioner_source_id` | One of these two | Source ID of the Practitioner — resolved via `Practitioner.identifier` with system `http://ohs.dev/identifiers/source-id` |
| `org_id` | No | Direct FHIR ID of the Organization. Sets `PractitionerRole.organization`. |
| `org_source_id` | No | Source ID of the Organization — resolved via identifier lookup. Used when `org_id` is absent. |
| `location_id` | No | Semicolon-separated list of FHIR Location IDs (e.g. `loc-1;loc-2`). Each ID is added to `PractitionerRole.location`. |
| `location_source_id` | No | Semicolon-separated list of Location source IDs — each resolved via identifier lookup. Used when `location_id` is absent. |

Each row creates one `PractitionerRole` resource linking the practitioner to an optional organization and zero or more locations. Multiple locations are specified as a semicolon-separated list in `location_id` or `location_source_id` (e.g. `LOC-A;LOC-B;LOC-C`). At least one of `practitioner_id` or `practitioner_source_id` must be present — rows missing both are skipped with an error event.

**FHIR resource produced:**

```json
{
  "resourceType": "PractitionerRole",
  "active": true,
  "practitioner": { "reference": "Practitioner/{id}" },
  "organization": { "reference": "Organization/{id}" },
  "location": [{ "reference": "Location/{id}" }]
}
```

`organization` and `location` are omitted when the corresponding CSV columns are blank.

**SSE event format:** identical to the org and location import format above.

**Configuration:** uses the same `BULK_IMPORT_BATCH_SIZE` environment variable as the org and location imports.

> **Re-run warning:** this endpoint is create-only. Each row always issues a `POST PractitionerRole`, so re-running the same CSV will create duplicate `PractitionerRole` resources. There is no `id` or `source_id` column for the role itself. Deduplicate or filter the CSV before re-submitting if you need to avoid duplicates.

---

## Practitioner Details API

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/practitioner-details?iam-id=<id>` | Fetch full practitioner context by Keycloak ID |
| `GET` | `/api/practitioner-details?practitioner-id=<id>` | Fetch full practitioner context by FHIR Practitioner ID |

**Optional query parameters:** `organisation-id`, `location-id` — filter results to PractitionerRoles that reference the given organisation or location.

**Response:**

```json
{
  "practitioner": {},
  "practitionerRoles": [
    {
      "practitionerRole": {},
      "organization": {},
      "locations": [],
      "careTeams": []
    }
  ]
}
```

Each field is a FHIR R4 resource serialised as JSON. `organization` is `null` when the PractitionerRole has no affiliated organisation. `careTeams` contains only CareTeam resources whose `participant.member` references that specific PractitionerRole.

When `iam-id` is supplied the endpoint first resolves the FHIR Practitioner ID (`Step 1`), then fetches the full context in a single FHIR call using `_include` and `_revinclude` (`Step 2`). When `practitioner-id` is supplied Step 1 is skipped.

Returns `404` when no matching practitioner or roles are found, and `400` when neither `iam-id` nor `practitioner-id` is provided.

## Authentication & Authorization

All `/api/*` endpoints require a valid JWT Bearer token.

```
Authorization: Bearer <access_token>
```

### Token validation

On startup the plugin performs OIDC discovery at `{TOKEN_ISSUER}/.well-known/openid-configuration` to obtain the `jwks_uri`, then validates incoming tokens offline using the provider's public keys. Tokens are checked for valid signature, correct issuer, and expiry.

### Role model

Authorization uses a per-resource, three-level hierarchy. Higher levels satisfy lower-level checks: **manage ⊇ edit ⊇ view**.

| Role | Grants access to                                                                                   |
| --- |----------------------------------------------------------------------------------------------------|
| `users.view` | `GET /api/users/*`                                                                                 |
| `users.edit` | `users.view` + `POST /api/users`, `PUT /api/users/{id}`, `PUT /api/users/{id}/password`            |
| `users.manage` | `users.edit` + `DELETE /api/users/{id}`                                                            |
| `groups.view` | `GET /api/groups/*`                                                                                |
| `groups.edit` | `groups.view` + `POST /api/groups`, `PUT /api/groups/{id}`, `POST /api/groups/{gid}/members/{uid}` |
| `groups.manage` | `groups.edit` + `DELETE /api/groups/{id}`, `DELETE /api/groups/{gid}/members/{uid}`                             |
| `bulk-import.manage` | `POST /api/bulk-import/*`                                                                          |
| `roles.view` | `GET /api/roles`                                                                                   |
| `practitioner-details.view` | `GET /api/practitioner-details`                                                                    |

Roles are read from the JWT claim path returned by the configured IAM provider. For Keycloak this is `realm_access.roles`. A token missing the required role receives `403 Forbidden`; a missing or invalid token receives `401 Unauthorized`.

---

## Keycloak Setup

A set up of a Keycloak OAuth2 client with client credentials grant type is required to manage users. 
The admin client requires a service account with user management permissions.

1. In the Keycloak Admin Console, go to your realm → **Clients** → select your client
2. Under **Settings**, enable **Service accounts enabled**
3. Go to the **Service accounts roles** tab → **Assign role**
4. Filter by **clients** → search `realm-management` → assign:

| Role | Purpose |
| --- | --- |
| `manage-users` | Create, update, delete users; add/remove group members |
| `view-users` | Read users |
| `manage-realm` | Create, update, delete groups; assign roles to groups |
| `view-realm` | List realm roles and clients for role discovery |

Without the relevant roles, requests will return `403 Forbidden`.
