# plugin-manifest-maven-plugin

Writes `target/plugin-manifest.json` for a Minecraft plugin: what it supports and what a server
has to provide. Hosting (Locus) reads it; nothing in it knows about Java versions or images.

```json
{
  "name": "CP",
  "version": "1.0",
  "minecraftVersions": ["1.8.8", "1.21.8"],
  "commands": [{ "name": "matrixpearls" }],
  "settings": [{ "name": "LICENSE_KEY", "required": true, "secret": true, "configPath": "LICENSE.KEY", "description": "..." }]
}
```

| Field | Source |
|---|---|
| `name`, `version` | the plugin's `plugin.yml` on the module's classpath |
| `minecraftVersions` | directories `<versionModulePrefix><version>` holding a `pom.xml`; optionally checked against an enum (`versionCheckClass`), the build fails when they differ |
| `commands` | `plugin.yml` `commands:`, plus classes carrying `commandAnnotation` (its `value()`) |
| `settings` | the `<settings>` configured below |

## Use

```xml
<pluginRepositories>
  <pluginRepository>
    <id>github</id>
    <url>https://maven.pkg.github.com/Rubix-Studios/minecraft-plugin-manifest</url>
  </pluginRepository>
</pluginRepositories>

<plugin>
  <groupId>eu.rubixcube</groupId>
  <artifactId>plugin-manifest-maven-plugin</artifactId>
  <version>1.0.0</version>
  <executions><execution><goals><goal>write</goal></goals></execution></executions>
  <configuration>
    <versionModulePrefix>my-plugin_v</versionModulePrefix>
    <versionCheckClass>com.example.ServerVersion</versionCheckClass>  <!-- optional -->
    <versionCheckMethod>getDisplayName</versionCheckMethod>          <!-- default -->
    <versionCheckFilter>hasNativeAdapter</versionCheckFilter>         <!-- optional -->
    <commandAnnotation>com.example.Command</commandAnnotation>        <!-- optional -->
    <commandPackage>com.example</commandPackage>
    <settings>
      <setting><name>LICENSE_KEY</name><secret>true</secret><configPath>LICENSE.KEY</configPath></setting>
    </settings>
  </configuration>
</plugin>
```

Run it in the module that bundles the plugin (the one whose classpath holds `plugin.yml` and the
version enum). The version modules are looked for next to it (`versionModulesDirectory`, default
`${project.basedir}/..`).

## Reading the package

The package is private. Maven needs a server `github` in `~/.m2/settings.xml` with a GitHub token
that has `read:packages`:

```xml
<servers><server><id>github</id><username>GITHUB_USER</username><password>TOKEN</password></server></servers>
```

In GitHub Actions of another Rubix-Studios repo, give that repo read access under the package's
*Manage Actions access* and use `actions/setup-java` with `server-id: github`; `GITHUB_TOKEN` then
suffices.

## Publishing

Push a tag `vX.Y.Z`: `.github/workflows/publish.yml` sets that version and runs `mvn deploy` to
GitHub Packages.
