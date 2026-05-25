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
mvn verify -Perror-prone
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

| Variable | Source | Description |
| --- | --- | --- |
| `PROXY_TO` | env var | FHIR server base URL |
| `IAM_PROVIDER` | env var | IAM provider to use. Defaults to `keycloak` if unset. |
| `TOKEN_ISSUER` | env var | *(Keycloak)* Issuer URL, e.g. `http://keycloak:8080/realms/my-realm`. Server URL and realm are parsed from this. |
| `IAM_PROVIDER_CLIENT_ID` | env var or `iam.provider.client-id` in `application.properties` | *(Keycloak)* Admin client ID |
| `IAM_PROVIDER_CLIENT_SECRET` | env var or `iam.provider.client-secret` in `application.properties` | *(Keycloak)* Admin client secret |

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
  "groupIds": ["group-uuid-1", "group-uuid-2"]
}
```

`username` and `email` are required. `enabled` defaults to `false` if omitted.

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
