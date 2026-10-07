import Editor, { loader } from '@monaco-editor/react';
import { useQueryClient } from '@tanstack/react-query';
import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';
import type { SqlSyntaxError } from '../../types/sql';
import type { IdentifierQuoteStyle } from '../../types/dbType';
import { useTheme } from '../../hooks/useTheme';
import {
  fetchExplorerColumns,
  fetchExplorerFunctions,
  fetchExplorerTables,
  fetchExplorerViews,
} from '../../lib/explorerMetadataCache';
import {
  buildSuggestions,
  completionQuoteStyle,
  completionTarget,
  type SqlCompletionItem,
} from '../../utils/sqlCompletion';

// Configure Monaco CDN
loader.config({ paths: { vs: 'https://cdn.jsdelivr.net/npm/monaco-editor@0.43.0/min/vs' } });

export interface MonacoEditorHandle {
  /**
   * Returns the selected text if selection exists, otherwise returns full editor content
   */
  getSelectionOrAllContent(): string;

  /**
   * Programmatically set editor content
   */
  setValue(value: string): void;
}

export interface SqlCompletionScope {
  connectionId?: number | null;
  catalog?: string | null;
  schema?: string | null;
  dbType?: string | null;
  quoteStyle?: IdentifierQuoteStyle;
}

interface MonacoEditorProps {
  value: string;
  onChange?: (value: string | undefined) => void;
  language?: string;
  readOnly?: boolean;
  height?: string | number;
  diagnostics?: SqlSyntaxError[];
  completion?: SqlCompletionScope;
}

export const MonacoEditor = forwardRef<MonacoEditorHandle, MonacoEditorProps>(
  ({
    value,
    onChange,
    language = 'sql',
    readOnly = false,
    height = '100%',
    diagnostics = [],
    completion,
  }, ref) => {

    const { theme: appTheme } = useTheme();
    const queryClient = useQueryClient();
    const editorRef = useRef<any>(null);
    const monacoRef = useRef<any>(null);
    const completionRef = useRef(completion);
    const queryClientRef = useRef(queryClient);
    const providerRef = useRef<{ dispose: () => void } | null>(null);
    completionRef.current = completion;
    queryClientRef.current = queryClient;

    useEffect(() => {
      const model = editorRef.current?.getModel();
      if (!model || !monacoRef.current) return;
      monacoRef.current.editor.setModelMarkers(model, 'dm-sql-parser', diagnostics.map(error => ({
        startLineNumber: error.line,
        startColumn: error.column + 1,
        endLineNumber: error.line,
        endColumn: error.column + 2,
        message: error.message,
        severity: monacoRef.current.MarkerSeverity.Error,
      })));
    }, [diagnostics, value]);

    useEffect(() => () => providerRef.current?.dispose(), []);

    const handleEditorWillMount = (monaco: any) => {
      // Define JetBrains-like dark theme
      monaco.editor.defineTheme('jetbrains-dark', {
        base: 'vs-dark',
        inherit: true,
        rules: [
          { token: 'keyword', foreground: 'cc7832', fontStyle: 'bold' },
          { token: 'string', foreground: '6a8759' },
          { token: 'function', foreground: 'ffc66d' },
          { token: 'number', foreground: '6897bb' },
          { token: 'comment', foreground: '808080', fontStyle: 'italic' },
          { token: 'operator', foreground: 'a9b7c6' },
          { token: 'delimiter', foreground: 'a9b7c6' },
        ],
        colors: {
          'editor.background': '#1e1f22',
          'editor.foreground': '#bcbec4',
          'editor.lineHighlightBackground': '#2e2f33',
          'editor.selectionBackground': '#2e436e',
          'editorCursor.foreground': '#a9b7c6',
          'editorLineNumber.foreground': '#606366',
          'editorLineNumber.activeForeground': '#a9b7c6',
          'editorIndentGuide.background': '#373737',
          'editor.border': '#4e5155',
        }
      });

      // Define JetBrains-like light theme
      monaco.editor.defineTheme('jetbrains-light', {
        base: 'vs',
        inherit: true,
        rules: [
          { token: 'keyword', foreground: 'd81e1e', fontStyle: 'bold' },
          { token: 'string', foreground: '067d17' },
          { token: 'function', foreground: 'be860b' },
          { token: 'number', foreground: '0b5394' },
          { token: 'comment', foreground: '8c8c8c', fontStyle: 'italic' },
          { token: 'operator', foreground: '001080' },
          { token: 'delimiter', foreground: '001080' },
        ],
        colors: {
          'editor.background': '#ffffff',
          'editor.foreground': '#000000',
          'editor.lineHighlightBackground': '#f5f5f5',
          'editor.selectionBackground': '#add6ff',
          'editorCursor.foreground': '#000000',
          'editorLineNumber.foreground': '#999999',
          'editorLineNumber.activeForeground': '#000000',
          'editorIndentGuide.background': '#e0e0e0',
          'editor.border': '#d0d0d0',
        }
      });
    };

    const getCurrentTheme = () => {
      return appTheme === 'light' ? 'jetbrains-light' : 'jetbrains-dark';
    };

    useImperativeHandle(ref, () => ({
      getSelectionOrAllContent(): string {
        const editor = editorRef.current;
        if (!editor) return '';
        const selection = editor.getSelection();
        if (selection && !selection.isEmpty()) {
          return editor.getModel()?.getValueInRange(selection) ?? '';
        }
        return editor.getValue();
      },
      setValue(newValue: string): void {
        if (editorRef.current) {
          editorRef.current.setValue(newValue);
        }
      }
    }), []);

    return (
      <Editor
        height={height}
        language={language}
        value={value}
        theme={getCurrentTheme()}
        onChange={onChange}
        beforeMount={handleEditorWillMount}
        onMount={(editor, monaco) => {
          editorRef.current = editor;
          monacoRef.current = monaco;
          monaco.editor.setModelMarkers(editor.getModel(), 'dm-sql-parser', diagnostics.map(error => ({
            startLineNumber: error.line,
            startColumn: error.column + 1,
            endLineNumber: error.line,
            endColumn: error.column + 2,
            message: error.message,
            severity: monaco.MarkerSeverity.Error,
          })));
          providerRef.current?.dispose();
          if (language !== 'sql') return;
          providerRef.current = monaco.languages.registerCompletionItemProvider('sql', {
            triggerCharacters: ['.'],
            provideCompletionItems: async (model: { uri: { toString: () => string }; getValue: () => string; getOffsetAt: (position: { lineNumber: number; column: number }) => number; getWordUntilPosition: (position: { lineNumber: number; column: number }) => { startColumn: number; endColumn: number } }, position: { lineNumber: number; column: number }) => {
              if (editor.getModel()?.uri.toString() !== model.uri.toString()) {
                return { suggestions: [] };
              }
              const offset = model.getOffsetAt(position);
              const fullText = model.getValue();
              const target = completionTarget(fullText.slice(0, offset), fullText.slice(offset));
              if (!target) return { suggestions: [] };

              const scope = completionRef.current;
              const tables: string[] = [];
              const views: string[] = [];
              const functions: string[] = [];
              const columns: string[] = [];
              if (scope?.connectionId) {
                const connectionId = String(scope.connectionId);
                const catalog = scope.catalog || undefined;
                const schema = scope.schema || undefined;
                const client = queryClientRef.current;
                try {
                  if (target.kind === 'column') {
                    const rows = await fetchExplorerColumns(client, connectionId, target.table, catalog, schema);
                    columns.push(...rows.map((row) => row.name));
                  } else {
                    const [tableNames, viewNames, functionRows] = await Promise.all([
                      fetchExplorerTables(client, connectionId, catalog, schema).catch(() => [] as string[]),
                      fetchExplorerViews(client, connectionId, catalog, schema).catch(() => [] as string[]),
                      fetchExplorerFunctions(client, connectionId, catalog, schema).catch(() => []),
                    ]);
                    tables.push(...tableNames);
                    views.push(...viewNames);
                    functions.push(...functionRows.map((row) => row.name));
                  }
                } catch {
                  // Keywords and functions still complete when metadata cannot be loaded.
                }
              }

              const word = model.getWordUntilPosition(position);
              const range = {
                startLineNumber: position.lineNumber,
                endLineNumber: position.lineNumber,
                startColumn: word.startColumn,
                endColumn: word.endColumn,
              };
              const kinds = monaco.languages.CompletionItemKind;
              const kindByName: Record<SqlCompletionItem['kind'], number> = {
                keyword: kinds.Keyword,
                function: kinds.Function,
                table: kinds.Class,
                view: kinds.Interface,
                column: kinds.Field,
              };
              return {
                suggestions: buildSuggestions({
                  dbType: scope?.dbType,
                  quoteStyle: scope?.quoteStyle ?? completionQuoteStyle(scope?.dbType),
                  target,
                  tables,
                  views,
                  functions,
                  columns,
                }).map((item) => ({
                  label: item.label,
                  kind: kindByName[item.kind],
                  insertText: item.insertText,
                  filterText: item.label,
                  sortText: item.sortText,
                  range,
                })),
              };
            },
          });
        }}
        options={{
          readOnly,
          wordBasedSuggestions: false,
          minimap: { enabled: false },
          fontSize: 13,
          fontFamily: "'JetBrains Mono', monospace",
          lineHeight: 1.5,
          scrollBeyondLastLine: false,
          automaticLayout: true,
          padding: { top: 10, bottom: 10 },
          renderLineHighlight: 'all',
          scrollbar: {
            vertical: 'visible',
            horizontal: 'visible',
            verticalScrollbarSize: 10,
            horizontalScrollbarSize: 10,
          }
        }}
      />
    );
  }
);

MonacoEditor.displayName = 'MonacoEditor';
