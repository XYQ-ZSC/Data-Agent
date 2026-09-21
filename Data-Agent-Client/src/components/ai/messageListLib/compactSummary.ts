import type { Message } from './types';

export function isCompactSummaryMessage(message: Message): boolean {
  return message.messageStatus === 'COMPRESSION_SUMMARY';
}
