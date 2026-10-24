package edu.zsc.ai.domain.model.dto.response.ai;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Option item for model selection (exposed to frontend).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelOptionResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Legacy selector field: model name on the old route, scoped configuration ID on the new route.
     */
    private String modelName;
    private String modelConfigId;
    private String name;
    private String provider;
    private String apiStyle;
    private boolean defaultModel;

    /**
     * Whether this model supports thinking/reasoning mode (e.g. for displaying brain icon).
     */
    private boolean supportThinking;

    /**
     * Token threshold used by server-side memory compression heuristics.
     */
    private int memoryThreshold;

    /**
     * Maximum context window supported by the model.
     */
    private int maxContextTokens;
}
