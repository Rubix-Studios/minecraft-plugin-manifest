package eu.rubixcube.manifest;

import java.util.LinkedHashMap;
import java.util.Map;

/** A setting a server has to provide, as configured in the pom. */
public class Setting {

    /** Name the hosting side knows the setting by, e.g. LICENSE_KEY. */
    private String name;
    private boolean required = true;
    /** Never shown back once entered. */
    private boolean secret;
    /** Where the plugin reads it in its config.yml, e.g. LICENSE.KEY. */
    private String configPath;
    private String description;

    Map<String, Object> toMap() {
        if (name == null || name.isEmpty()) throw new IllegalStateException("A setting has no name");
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("required", required);
        map.put("secret", secret);
        if (configPath != null) map.put("configPath", configPath);
        if (description != null) map.put("description", description.trim());
        return map;
    }
}
