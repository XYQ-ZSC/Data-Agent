package edu.zsc.ai.plugin.enums;

/**
 * Database type enumeration
 */
public enum DbType {
    
    /**
     * MySQL
     */
    MYSQL("mysql", "MySQL", PluginType.SQL);
    
    /**
     * Database type identifier (lowercase)
     */
    private final String code;
    
    /**
     * Database display name
     */
    private final String displayName;
    
    /**
     * Plugin type
     */
    private final PluginType pluginType;
    
    DbType(String code, String displayName, PluginType pluginType) {
        this.code = code;
        this.displayName = displayName;
        this.pluginType = pluginType;
    }
    
    public String getCode() {
        return code;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public PluginType getPluginType() {
        return pluginType;
    }
    
    /**
     * Get database type by code
     */
    public static DbType fromCode(String code) {
        for (DbType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown database type: " + code);
    }
}

