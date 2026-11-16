# plugin-manifest-maven-plugin

Writes `target/plugin-manifest.json` for a Minecraft plugin: what it supports and what a server
has to provide. Nothing in it knows about Java versions or images.

The shape below is Locus's contract (Spraxs/locus#197): Locus reads this file to decide which
server images to build and which options an instance offers. Change the shape here and in Locus
together.

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
    <id>jitpack</id>
    <url>https://jitpack.io</url>
  </pluginRepository>
</pluginRepositories>

<plugin>
  <groupId>com.github.Spraxs</groupId>
  <artifactId>minecraft-plugin-manifest</artifactId>
  <version>v1.0.4</version>
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

## Releasing

Push a tag `vX.Y.Z`. [JitPack](https://jitpack.io/#Spraxs/minecraft-plugin-manifest) builds it the
first time someone asks for that version; no token is needed anywhere, because this repository is
public.
