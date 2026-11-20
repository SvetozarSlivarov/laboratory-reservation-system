import { useState } from 'react';
import type { FormEvent } from 'react';
import { api, errorMessage } from './api';
import type { Recommendation, Slot } from './api';
import { bookingWindow, dayLabel, timeRange } from './bookingTime';
import { Notice } from './ui';

export function RecommendationPanel({ laboratoryId, onPick }: {
  laboratoryId: number; onPick: (slot: Slot) => void;
}) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const [recommendation, setRecommendation] = useState<Recommendation>();

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;
    const preference = String(new FormData(event.currentTarget).get('preference')).trim();
    if (!preference) {
      setError('Describe what you need in a workstation.');
      return;
    }
    setPending(true);
    setError('');
    setRecommendation(undefined);
    try {
      const { from, to } = bookingWindow();
      setRecommendation(await api.recommend(laboratoryId, from, to, preference));
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setPending(false);
    }
  }

  return <section className="panel inset" aria-labelledby="recommendation-title">
    <h2 id="recommendation-title">Workstation recommendation</h2>
    <form className="form" onSubmit={submit}>
      <label htmlFor="workstation-preference">What do you need?</label>
      <textarea id="workstation-preference" name="preference" rows={3} maxLength={250}
        placeholder="For example, a workstation with Java in the morning"
        aria-describedby="recommendation-hint" required disabled={pending} />
      <p id="recommendation-hint" className="hint">Include equipment or timing preferences only. Preferences may be sent to Gemini.</p>
      <button className="button secondary" type="submit" disabled={pending}>
        {pending ? 'Finding a slot…' : 'Find a recommendation'}
      </button>
    </form>
    {pending && <Notice>Checking available options…</Notice>}
    {error && <Notice tone="error">{error}</Notice>}
    {recommendation && <RecommendationResult result={recommendation} onPick={onPick} />}
  </section>;
}

function RecommendationResult({ result, onPick }: { result: Recommendation; onPick: (slot: Slot) => void }) {
  if (result.source === 'NO_AVAILABILITY') return <Notice>{result.explanation}</Notice>;
  const { slot } = result;
  return <Notice tone={result.source === 'GEMINI' ? 'success' : 'plain'}>
    <p><strong>{result.source === 'GEMINI' ? 'Gemini recommendation' : 'Earliest available slot'}</strong></p>
    <p>{slot.workstation.code} · {dayLabel(slot.startsAt)} · {timeRange(slot.startsAt, slot.endsAt)}</p>
    <p>{result.source === 'FALLBACK' ? 'Gemini could not provide a recommendation.' : result.explanation}</p>
    <button className="button secondary" type="button" onClick={() => onPick(slot)}>Use this slot</button>
  </Notice>;
}
