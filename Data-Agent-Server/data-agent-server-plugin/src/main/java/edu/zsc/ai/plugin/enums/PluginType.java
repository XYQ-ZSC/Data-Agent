package edu.zsc.ai.plugin.enums;

/**
 * Plugin type enumeration
 */
public enum PluginType {
    
    /**
     * SQL database plugin
     */
    SQL("SQL Database"),
    
    /**
     * NoSQL database plugin
     */
    NOSQL("NoSQL Database");
    
    private final String description;
    
    PluginType(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
}

