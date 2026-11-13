package edu.zsc.ai.plugin.model.sql;

import java.util.List;

public record CompletionFunctionCatalog(List<CompletionFunction> functions, CompletionEnvironment environment,
                                        boolean resolved) {}
