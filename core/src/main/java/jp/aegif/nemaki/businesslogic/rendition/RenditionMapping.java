package jp.aegif.nemaki.businesslogic.rendition;

import java.util.List;

/**
 * Model class for rendition mapping configuration.
 * Defines which source formats should have renditions generated
 * and what output format to produce.
 */
public class RenditionMapping {
    private List<String> sourceMediaTypes;
    private String converter;
    private String targetMediaType;
    private String kind;
    
    public List<String> getSourceMediaTypes() { 
        return sourceMediaTypes; 
    }
    
    public void setSourceMediaTypes(List<String> sourceMediaTypes) { 
        this.sourceMediaTypes = sourceMediaTypes; 
    }
    
    public String getConverter() { 
        return converter; 
    }
    
    public void setConverter(String converter) { 
        this.converter = converter; 
    }
    
    public String getTargetMediaType() { 
        return targetMediaType; 
    }
    
    public void setTargetMediaType(String targetMediaType) { 
        this.targetMediaType = targetMediaType; 
    }
    
    public String getKind() { 
        return kind; 
    }
    
    public void setKind(String kind) { 
        this.kind = kind; 
    }
}
