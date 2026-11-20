import { useCallback, useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import { ApiError, api, errorMessage } from './api';
import type { Booking, Slot } from './api';
import { bookingDates, bookingWindow, calendarDay, dayLabel, timeRange } from './bookingTime';
import { Notice, PageTitle, Resource, useResource } from './ui';
import { RecommendationPanel } from './RecommendationPanel';

export function BookingPage() {
  const { id = '' } = useParams();
  const [params] = useSearchParams();
  const load = useCallback(async () => {
    const [lab, stations] = await Promise.all([api.laboratory(id), api.workstations(id)]);
    return { lab, stations };
  }, [id]);
  const details = useResource(load);
  const back = { to: '/laboratories/' + id, label: 'Back to laboratory' };

  return <>
    <PageTitle title="Book a workstation" back={back} />
    <Resource resource={details} loading="Loading laboratory…">
      {({ lab, stations }) => stations.length ? <BookingForm key={id} labId={id} labName={lab.name}
        stations={stations} initialStation={params.get('workstation')} />
        : <Notice>No workstations are available in this laboratory.</Notice>}
    </Resource>
  </>;
}

function BookingForm({ labId, labName, stations, initialStation }: {
  labId: string; labName: string; stations: { id: number; code: string }[]; initialStation: string | null;
}) {
  const load = useCallback(async () => {
    const { from, to } = bookingWindow();
    return { dates: bookingDates(from, to), slots: await api.availability(labId, from, to) };
  }, [labId]);
  const availability = useResource(load);
  const [date, setDate] = useState('');
  const [station, setStation] = useState(() => stations.some(({ id }) => String(id) === initialStation)
    ? initialStation! : String(stations[0].id));
  const [start, setStart] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const [confirmed, setConfirmed] = useState<Booking>();
  const feedback = useRef<HTMLDivElement>(null);
  const bookingHeading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    if (confirmed || error) feedback.current?.focus();
  }, [confirmed, error]);

  function useRecommendation(slot: Slot) {
    setError('');
    setDate(calendarDay(slot.startsAt));
    setStation(String(slot.workstation.id));
    setStart(slot.startsAt);
    availability.reload();
    bookingHeading.current?.focus();
  }

  async function book(event: FormEvent<HTMLFormElement>, slot: Slot | undefined) {
    event.preventDefault();
    if (!slot || pending) return;
    setPending(true);
    setError('');
    try {
      setConfirmed(await api.book(slot));
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 409) {
        setError('That slot is no longer available. Availability has been refreshed. Please choose another time.');
        setStart('');
        availability.reload();
      } else {
        setError(errorMessage(failure));
      }
    } finally {
      setPending(false);
    }
  }

  return <div className="booking-grid">
    <section className="panel booking-panel" aria-labelledby="booking-options">
      <h2 id="booking-options" tabIndex={-1} ref={bookingHeading}>{labName}</h2>
      {(error || confirmed) && <div ref={feedback} tabIndex={-1}>
        {error ? <Notice tone="error">{error}</Notice> : confirmed && <Notice tone="success">
          <p><strong>Booking confirmed</strong></p>
          <p>{stations.find(({ id }) => id === confirmed.workstationId)?.code} · {dayLabel(confirmed.startsAt)} · {timeRange(confirmed.startsAt, confirmed.endsAt)}</p>
          <Link to="/bookings">View my bookings</Link>
        </Notice>}
      </div>}
      {!confirmed && <Resource resource={availability} loading="Checking available times…">
        {({ dates, slots }) => {
          const selectedDay = dates.has(date) ? date : dates.keys().next().value;
          const times = slots.filter((slot) =>
            calendarDay(slot.startsAt) === selectedDay && String(slot.workstation.id) === station);
          const selected = times.find((slot) => slot.startsAt === start);
          return <form className="form" onSubmit={(event) => void book(event, selected)}>
            <fieldset disabled={pending}>
              <legend className="sr-only">Reservation options</legend>
              <div className="field"><label htmlFor="booking-date">Date</label>
                <select id="booking-date" value={selectedDay} onChange={(event) => {
                setDate(event.target.value);
                setStart('');
              }}>
                {Array.from(dates, ([value, label]) => <option value={value} key={value}>{label}</option>)}
              </select></div>
              <div className="field"><label htmlFor="booking-workstation">Workstation</label>
                <select id="booking-workstation" value={station} onChange={(event) => {
                setStation(event.target.value);
                setStart('');
              }}>
                {stations.map((item) => <option key={item.id} value={item.id}>{item.code}</option>)}
              </select></div>
              {times.length ? <div className="field"><label htmlFor="booking-time">Time slot</label>
                <select id="booking-time" required value={selected ? start : ''} onChange={(event) => {
                setStart(event.target.value);
              }}>
                <option value="">Select a time</option>
                {times.map((slot) => <option key={slot.startsAt} value={slot.startsAt}>
                  {timeRange(slot.startsAt, slot.endsAt)}
                </option>)}
              </select></div> : <Notice>No times are available for this workstation on the selected date.</Notice>}
              {start && !selected && <Notice>The suggested time is no longer available. Choose another slot.</Notice>}
              <button className="button" type="submit" disabled={!selected || pending}>
                {pending ? 'Confirming…' : 'Confirm booking'}
              </button>
            </fieldset>
          </form>;
        }}
      </Resource>}
    </section>
    <div className="booking-sidebar">
      {!confirmed && <RecommendationPanel laboratoryId={Number(labId)} onPick={useRecommendation} />}
      <aside className="panel booking-summary" aria-label="Reservation information">
      <h2>Reservation information</h2>
      <dl>
        <div><dt>Duration</dt><dd>60 minutes</dd></div>
        <div><dt>Time zone</dt><dd>Europe/Sofia</dd></div>
        <div><dt>Booking window</dt><dd>Next 7 days</dd></div>
      </dl>
      </aside>
    </div>
  </div>;
}
