package edu.zsc.ai.plugin.model.sql;

import edu.zsc.ai.plugin.model.metadata.ParameterInfo;
import java.util.List;

/** An overload of a callable package member. */
public record CompletionRoutine(String name, List<ParameterInfo> parameters, String returnType, boolean procedure,
                                String overload) {}
