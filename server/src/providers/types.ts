// A streaming transcription provider. Groq is the default; OpenAI is kept as an alternative.
// Others plug in here without the keyboard noticing.

export type ProviderOptions = {
  languages: string[];
  keywords?: string[];
  prompt?: string;
};

export type ProviderEvents = {
  /** The whole text of a phrase so far; replaces any earlier partial. Phrases are numbered from 0. */
  onPartial(segment: number, text: string): void;
  /** The settled text of a phrase. `confidence` is 0–1 when the provider gives one. */
  onFinal(segment: number, text: string, confidence?: number): void;
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
  /** End the current phrase. A no-op when there is nothing to end. */
  commit(): void;
  /** End the current phrase and resolve once every phrase has its final (or the timeout passes). */
  finish(timeoutMs: number): Promise<void>;
  close(): void;
}

export type TranscriberFactory = (opts: ProviderOptions, events: ProviderEvents) => StreamingTranscriber;
