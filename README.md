# OHS Player Reference Backend

OHS Player Backend for the KMP and Web portal clients. It contains any custom logic required for all OHS Player clients, e.g. custom endpoints (non FHIR), access checker plugins e.t.c

## CustomPlugins

### Building the jar artifact

```sh
mvn clean package
```

The artifact will be located at `target/custom-backend-plugins-1.0-SNAPSHOT.jar`.

### Deploying the plugin

This plugin is loaded into the FHIR Gateway with `-Dloader.path`. This is documented in
the [OHS FHIR Gateway documentation](https://github.com/ohs-foundation/fhir-gateway#modules)

```sh
java -Dloader.path="PATH-TO-ADDITIONAL-PLUGINS/custom-backend-plugins-1.0-SNAPSHOT.jar" \
  -jar exec/target/exec-0.1.0.jar --server.port=8081
```

### Note on FHIR Gateway dependency

For this setup, the FHIR Gateway dependency (required for the AccessChecker interface) is intentionally declared with
`provided` scope in `pom.xml`, so that the plugin can be built without the FHIR Gateway artifact. This means that the
plugin will not include the FHIR Gateway dependency in its artifact, and it will rely on the FHIR Gateway to provide it
at runtime.

