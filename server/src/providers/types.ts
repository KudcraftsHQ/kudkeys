// A streaming transcription provider. OpenAI is the only one today; others plug in here
// without the keyboard noticing.

export type ProviderOptions = {
  languages: string[];
  keywords?: string[];
  prompt?: string;
};

export type ProviderEvents = {
  /** New text for a segment. Segments are numbered from 0 in the order they started. */
  onDelta(segment: number, text: string): void;
  /** The settled text of a segment. */
  onFinal(segment: number, text: string): void;
  /** Something went wrong upstream; the session should end. */
  onError(message: string): void;
};

export interface StreamingTranscriber {
  /** The rate `appendAudio` expects. */
  readonly sampleRate: number;
  readonly model: string;
  /** Resolves once the provider is ready for audio; audio sent earlier is queued. */
  ready(): Promise<void>;
  appendAudio(samples: Int16Array): void;
  /** End the current segment. A no-op when no audio arrived since the last commit. */
  commit(): void;
  /** Commit what is left and resolve once every segment has its final (or the timeout passes). */
  finish(timeoutMs: number): Promise<void>;
  close(): void;
}

export type TranscriberFactory = (opts: ProviderOptions, events: ProviderEvents) => StreamingTranscriber;
