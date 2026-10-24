package edu.zsc.ai.plugin.model.sql;

/** Dialect-owned insertion and signature; special expressions need no parentheses. */
public record CompletionFunction(String name, String insertText, String signature, boolean snippet) {
}
