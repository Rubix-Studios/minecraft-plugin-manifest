package eu.rubixcube.manifest;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Writes plugin-manifest.json: what a Minecraft plugin is, which Minecraft versions it supports,
 * its commands and the settings a server has to provide.
 *
 * Shape (read by Locus):
 * <pre>
 * {
 *   "name": "...", "version": "...",
 *   "minecraftVersions": ["1.8.8", ...],
 *   "commands": [{ "name": "..." }],
 *   "settings": [{ "name": "...", "required": true, "secret": false, "configPath": "...", "description": "..." }]
 * }
 * </pre>
 *
 * Nothing here knows about Java versions, images or hosting: only what the plugin itself states.
 */
@Mojo(name = "write", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class WriteManifestMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.directory}/plugin-manifest.json")
    private File outputFile;

    /** Where the version modules live; by default next to this module. */
    @Parameter(defaultValue = "${project.basedir}/..")
    private File versionModulesDirectory;

    /**
     * Directory-name prefix of the one-module-per-Minecraft-version modules, e.g. "my-plugin_v".
     * "my-plugin_v1.8.8" then supports 1.8.8. Only directories holding a pom.xml count.
     */
    @Parameter(required = true)
    private String versionModulePrefix;

    /**
     * Optional enum that also lists the versions; the build fails when it disagrees with the
     * modules. Each constant gives its version through {@link #versionCheckMethod}, and is
     * skipped when {@link #versionCheckFilter} is set and returns false.
     */
    @Parameter
    private String versionCheckClass;

    @Parameter(defaultValue = "getDisplayName")
    private String versionCheckMethod;

    @Parameter
    private String versionCheckFilter;

    /**
     * Optional annotation on command classes whose value() is the command name, for plugins that
     * register commands at runtime instead of in plugin.yml. Commands in plugin.yml are always
     * included.
     */
    @Parameter
    private String commandAnnotation;

    /** Class-name prefix to look for command classes under, e.g. "eu.rubixcube". */
    @Parameter
    private String commandPackage;

    /** The settings a server has to provide for the plugin to run. */
    @Parameter
    private List<Setting> settings = new ArrayList<>();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try (URLClassLoader loader = projectClassLoader()) {
            final Map<String, String> pluginYml = readPluginYml(loader);
            final List<String> versions = moduleVersions();
            if (versionCheckClass != null) checkVersions(loader, versions);

            final TreeSet<String> commands = new TreeSet<>(pluginYmlCommands(loader));
            if (commandAnnotation != null) commands.addAll(annotatedCommands(loader));

            final Json json = new Json();
            json.open("{");
            json.field("name", pluginYml.get("name"));
            json.field("version", pluginYml.get("version"));
            json.stringArray("minecraftVersions", versions);
            json.open("\"commands\": [");
            for (String command : commands) json.object(Collections.singletonMap("name", command));
            json.close("]");
            json.open("\"settings\": [");
            for (Setting setting : settings) json.object(setting.toMap());
            json.close("]");
            json.close("}");

            Files.createDirectories(outputFile.getParentFile().toPath());
            Files.write(outputFile.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
            getLog().info("Wrote " + outputFile);
        } catch (IOException e) {
            throw new MojoExecutionException("Could not write " + outputFile, e);
        }
    }

    private URLClassLoader projectClassLoader() throws MojoExecutionException {
        try {
            final List<URL> urls = new ArrayList<>();
            for (String element : project.getRuntimeClasspathElements()) {
                urls.add(new File(element).toURI().toURL());
            }
            return new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());
        } catch (Exception e) {
            throw new MojoExecutionException("Could not build the project classpath", e);
        }
    }

    private List<String> pluginYmlLines(ClassLoader loader) throws IOException, MojoFailureException {
        try (InputStream in = loader.getResourceAsStream("plugin.yml")) {
            if (in == null) throw new MojoFailureException("plugin.yml is not on the classpath of " + project.getArtifactId());
            final BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            final List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
            return lines;
        }
    }

    private Map<String, String> readPluginYml(ClassLoader loader) throws IOException, MojoFailureException {
        final Map<String, String> values = new LinkedHashMap<>();
        for (String line : pluginYmlLines(loader)) {
            if (line.isEmpty() || Character.isWhitespace(line.charAt(0)) || line.startsWith("#")) continue;
            final int colon = line.indexOf(':');
            if (colon > 0) values.put(line.substring(0, colon).trim(), unquote(line.substring(colon + 1).trim()));
        }
        for (String key : Arrays.asList("name", "version")) {
            if (values.get(key) == null || values.get(key).isEmpty()) {
                throw new MojoFailureException("plugin.yml has no " + key);
            }
        }
        return values;
    }

    /** The keys directly under a top-level "commands:" block. */
    private List<String> pluginYmlCommands(ClassLoader loader) throws IOException, MojoFailureException {
        final List<String> commands = new ArrayList<>();
        boolean inCommands = false;
        int indent = -1;
        for (String line : pluginYmlLines(loader)) {
            if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;
            final int lineIndent = line.length() - line.replaceAll("^\\s+", "").length();
            if (lineIndent == 0) {
                inCommands = line.startsWith("commands:");
                indent = -1;
                continue;
            }
            if (!inCommands) continue;
            if (indent < 0) indent = lineIndent;
            if (lineIndent == indent && line.trim().contains(":")) {
                commands.add(unquote(line.trim().substring(0, line.trim().indexOf(':'))));
            }
        }
        return commands;
    }

    private List<String> moduleVersions() throws MojoFailureException {
        final File[] modules = versionModulesDirectory.listFiles((dir, name) ->
                name.startsWith(versionModulePrefix) && new File(new File(dir, name), "pom.xml").isFile());
        if (modules == null || modules.length == 0) {
            throw new MojoFailureException("No " + versionModulePrefix + "* modules under " + versionModulesDirectory);
        }
        final List<String> versions = new ArrayList<>();
        for (File module : modules) {
            final String version = module.getName().substring(versionModulePrefix.length());
            if (!version.matches("\\d+(\\.\\d+)*")) {
                throw new MojoFailureException("Module " + module.getName() + " does not end in a Minecraft version");
            }
            versions.add(version);
        }
        versions.sort(WriteManifestMojo::compareVersions);
        return versions;
    }

    private void checkVersions(ClassLoader loader, List<String> moduleVersions) throws MojoFailureException {
        final TreeSet<String> declared = new TreeSet<>(WriteManifestMojo::compareVersions);
        try {
            final Class<?> type = Class.forName(versionCheckClass, true, loader);
            if (!type.isEnum()) throw new MojoFailureException(versionCheckClass + " is not an enum");
            final Method version = type.getMethod(versionCheckMethod);
            final Method filter = versionCheckFilter == null ? null : type.getMethod(versionCheckFilter);
            for (Object constant : type.getEnumConstants()) {
                if (filter != null && !Boolean.TRUE.equals(filter.invoke(constant))) continue;
                declared.add(String.valueOf(version.invoke(constant)));
            }
        } catch (MojoFailureException e) {
            throw e;
        } catch (Exception | LinkageError e) {
            throw new MojoFailureException("Could not read the versions from " + versionCheckClass, e);
        }
        final TreeSet<String> modules = new TreeSet<>(WriteManifestMojo::compareVersions);
        modules.addAll(moduleVersions);
        if (!declared.equals(modules)) {
            final TreeSet<String> onlyModules = new TreeSet<>(modules);
            onlyModules.removeAll(declared);
            final TreeSet<String> onlyDeclared = new TreeSet<>(declared);
            onlyDeclared.removeAll(modules);
            throw new MojoFailureException(versionModulePrefix + "* modules and " + versionCheckClass
                    + " disagree. Module without a version in " + versionCheckClass + ": " + onlyModules
                    + "; version without a module: " + onlyDeclared);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> annotatedCommands(URLClassLoader loader) throws IOException, MojoFailureException {
        final Class<? extends Annotation> annotation;
        final Method value;
        try {
            annotation = (Class<? extends Annotation>) Class.forName(commandAnnotation, false, loader);
            value = annotation.getMethod("value");
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new MojoFailureException("Command annotation " + commandAnnotation + " not usable", e);
        }
        final String prefix = commandPackage == null ? "" : commandPackage.replace('.', '/');
        final List<String> commands = new ArrayList<>();
        for (URL url : loader.getURLs()) {
            for (String className : classNames(new File(url.getPath()), prefix)) {
                try {
                    final Annotation found = Class.forName(className, false, loader).getAnnotation(annotation);
                    if (found == null) continue;
                    final String name = String.valueOf(value.invoke(found));
                    if (!name.isEmpty()) commands.add(name);
                } catch (Exception | LinkageError ignored) {
                    // A class that cannot be loaded here is not one of the plugin's commands.
                }
            }
        }
        if (commands.isEmpty()) throw new MojoFailureException("No classes annotated with " + commandAnnotation);
        return commands;
    }

    private static List<String> classNames(File element, String prefix) throws IOException {
        final List<String> names = new ArrayList<>();
        if (element.isDirectory()) {
            final Path root = element.toPath();
            try (Stream<Path> files = Files.walk(root)) {
                files.map(path -> root.relativize(path).toString().replace(File.separatorChar, '/'))
                        .filter(name -> name.endsWith(".class") && name.startsWith(prefix))
                        .forEach(name -> names.add(toClassName(name)));
            }
        } else if (element.isFile() && element.getName().endsWith(".jar")) {
            try (JarFile jar = new JarFile(element)) {
                jar.stream().map(JarEntry::getName)
                        .filter(name -> name.endsWith(".class") && name.startsWith(prefix))
                        .forEach(name -> names.add(toClassName(name)));
            }
        }
        return names;
    }

    private static String toClassName(String path) {
        return path.substring(0, path.length() - ".class".length()).replace('/', '.');
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    static int compareVersions(String left, String right) {
        final int[] a = Arrays.stream(left.split("\\.")).mapToInt(Integer::parseInt).toArray();
        final int[] b = Arrays.stream(right.split("\\.")).mapToInt(Integer::parseInt).toArray();
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            final int x = i < a.length ? a[i] : 0;
            final int y = i < b.length ? b[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }
}
