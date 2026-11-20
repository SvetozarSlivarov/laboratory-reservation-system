import { useState } from 'react';
import { Link } from 'react-router';
import { api, errorMessage } from './api';
import { dayLabel, timeRange } from './bookingTime';
import { Notice, PageTitle, Resource, useResource } from './ui';

export function MyBookings() {
  const bookings = useResource(api.bookings);
  const [confirm, setConfirm] = useState<number>();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');

  async function cancel(id: number) {
    if (pending) return;
    setPending(true);
    setError('');
    setMessage('');
    try {
      await api.cancel(id);
      setConfirm(undefined);
      setMessage('Booking cancelled. The slot is available again.');
      bookings.reload();
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setPending(false);
    }
  }

  return <>
    <PageTitle title="My bookings" description="View upcoming reservations and booking history." />
    {error && <Notice tone="error">{error}</Notice>}
    {message && <Notice tone="success">{message}</Notice>}
    <Resource resource={bookings} loading="Loading your bookings…">
      {(items) => items.length ? <ul className="record-list">
        {items.map((item) => {
          const future = new Date(item.startsAt).getTime() > Date.now();
          const canCancel = item.status === 'CONFIRMED' && future;
          return <li className="panel record" key={item.id}>
            <div>
              <h2>Reservation #{item.id}</h2>
              <p>Workstation #{item.workstationId}</p>
              <p className="muted">{dayLabel(item.startsAt)} · {timeRange(item.startsAt, item.endsAt)}</p>
            </div>
            <div className="record-actions">
              <span className={'badge ' + (canCancel ? 'positive' : '')}>
                {item.status === 'CANCELLED' ? 'Cancelled' : future ? 'Confirmed' : 'Past'}
              </span>
              {canCancel && (confirm === item.id ? <div className="confirm-actions">
                <button className="button danger" type="button" disabled={pending} onClick={() => void cancel(item.id)}>
                  {pending ? 'Cancelling…' : 'Confirm cancellation'}
                </button>
                <button className="button secondary" type="button" disabled={pending}
                  onClick={() => setConfirm(undefined)}>Keep booking</button>
              </div> : <button className="button secondary" type="button" disabled={pending}
                onClick={() => setConfirm(item.id)} aria-label={'Cancel reservation #' + item.id}>Cancel booking</button>)}
            </div>
          </li>;
        })}
      </ul> : <Notice><p>You have no bookings yet.</p><Link to="/laboratories">Browse laboratories</Link></Notice>}
    </Resource>
  </>;
}
