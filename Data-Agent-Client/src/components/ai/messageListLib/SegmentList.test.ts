import { describe, expect, it } from 'vitest';
import { shouldExpandThoughtSegment } from './SegmentList';
import { MessageAccumulator } from './MessageAccumulator';
import { SegmentKind, ToolExecutionState, type Segment } from './types';
import { MessageBlockType, type ChatResponseBlock } from '../../../types/chat';

describe('shouldExpandThoughtSegment', () => {
  it('keeps thought visible while the assistant message is still streaming even after text arrives', () => {
    const thought: Segment = { kind: SegmentKind.THOUGHT, data: 'reasoning...' };
    const segments: Segment[] = [
      thought,
      { kind: SegmentKind.TEXT, data: 'final answer starts' },
    ];

    expect(shouldExpandThoughtSegment(thought, segments, true)).toBe(true);
  });

  it('collapses thought after streaming finishes', () => {
    const thought: Segment = { kind: SegmentKind.THOUGHT, data: 'reasoning...' };
    const segments: Segment[] = [thought];

    expect(shouldExpandThoughtSegment(thought, segments, false)).toBe(false);
  });
});

describe('MessageAccumulator tool metadata', () => {
  function block(type: string, data: unknown): ChatResponseBlock {
    return {
      type: type as ChatResponseBlock['type'],
      data: JSON.stringify(data),
      done: false,
    };
  }

  it('merges tool description and timing into TOOL_RUN segments', () => {
    const acc = new MessageAccumulator();

    acc.pushBlock(block(MessageBlockType.TOOL_CALL, {
      id: 'call-1',
      toolName: 'getDatabases',
      arguments: JSON.stringify({
        connectionId: 8,
        description: '查询可用数据库',
      }),
      startedAt: 1_000,
    }));
    acc.pushBlock(block(MessageBlockType.TOOL_RESULT, {
      id: 'call-1',
      toolName: 'getDatabases',
      result: JSON.stringify({ success: true }),
      finishedAt: 4_200,
      error: false,
    }));

    const segments = acc.getSegments();
    const toolRuns = segments[0]?.kind === SegmentKind.TOOL_GROUP ? segments[0].nestedToolRuns : segments;

    expect(toolRuns).toEqual([
      expect.objectContaining({
        kind: SegmentKind.TOOL_RUN,
        toolName: 'getDatabases',
        description: '查询可用数据库',
        startedAt: 1_000,
        finishedAt: 4_200,
        executionState: ToolExecutionState.COMPLETE,
      }),
    ]);
  });

  it('falls back safely when description is missing', () => {
    const acc = new MessageAccumulator();

    acc.pushBlock(block(MessageBlockType.TOOL_CALL, {
      id: 'call-1',
      toolName: 'getDatabases',
      arguments: JSON.stringify({ connectionId: 8 }),
    }));

    const segments = acc.getSegments();
    const firstToolRun = segments[0]?.kind === SegmentKind.TOOL_GROUP
      ? segments[0].nestedToolRuns[0]
      : segments[0];

    expect(firstToolRun).toEqual(expect.objectContaining({
      kind: SegmentKind.TOOL_RUN,
      toolName: 'getDatabases',
    }));
    expect((firstToolRun as Extract<Segment, { kind: SegmentKind.TOOL_RUN }>).description).toBeUndefined();
  });

  it('returns background tool groups directly from the accumulator', () => {
    const acc = new MessageAccumulator();

    acc.pushBlock(block(MessageBlockType.TOOL_CALL, {
      id: 'call-1',
      toolName: 'getDatabases',
      arguments: JSON.stringify({
        connectionId: 8,
        description: '查询可用数据库',
      }),
      startedAt: 1_000,
    }));
    acc.pushBlock(block(MessageBlockType.TOOL_RESULT, {
      id: 'call-1',
      toolName: 'getDatabases',
      result: JSON.stringify({ success: true }),
      finishedAt: 2_000,
      error: false,
    }));
    acc.pushBlock(block(MessageBlockType.TOOL_CALL, {
      id: 'call-2',
      toolName: 'getSchemas',
      arguments: JSON.stringify({
        connectionId: 8,
        databaseName: 'app',
        description: '检查 schema',
      }),
      startedAt: 2_000,
    }));
    acc.pushBlock(block(MessageBlockType.TOOL_RESULT, {
      id: 'call-2',
      toolName: 'getSchemas',
      result: JSON.stringify({ success: true }),
      finishedAt: 4_200,
      error: false,
    }));

    const segments = acc.getSegments();

    expect(segments).toHaveLength(1);
    expect(segments[0]).toMatchObject({
      kind: SegmentKind.TOOL_GROUP,
      groupType: 'background-tools',
      pending: false,
      startedAt: 1_000,
      finishedAt: 4_200,
    });
    expect(segments[0]?.kind === SegmentKind.TOOL_GROUP ? segments[0].nestedToolRuns : []).toHaveLength(2);
  });
});
