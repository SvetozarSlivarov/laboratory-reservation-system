// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { BookingPage } from './BookingPage';
import { MyBookings } from './MyBookings';
import { bookingWindow, calendarDay } from './bookingTime';

const lab = { id: 1, name: 'Engineering Lab', location: 'North building', enabled: true };
const workstation = { id: 2, laboratoryId: 1, code: 'PC-02', capabilities: 'Java', enabled: true };
const start = new Date(Math.ceil((Date.now() + 60 * 60 * 1000) / 3_600_000) * 3_600_000);
const slot = {
  workstation, startsAt: start.toISOString(), endsAt: new Date(start.getTime() + 3_600_000).toISOString(),
};
const booking = {
  id: 3, workstationId: 2, startsAt: slot.startsAt, endsAt: slot.endsAt,
  status: 'CONFIRMED', createdAt: new Date().toISOString(), cancelledAt: null,
};

function mockApi(overrides: Record<string, () => Response | Promise<Response>> = {}) {
  const routes: Record<string, () => Response | Promise<Response>> = {
    'GET /api/auth/me': () => Response.json({ id: 1, email: 'student@example.test', role: 'USER' }),
    'GET /api/auth/csrf': () => Response.json({ token: 'csrf', headerName: 'X-CSRF-TOKEN', googleEnabled: false }),
    'GET /api/laboratories': () => Response.json([lab]),
    'GET /api/laboratories/1': () => Response.json(lab),
    'GET /api/laboratories/1/workstations': () => Response.json([workstation]),
    'GET /api/availability': () => Response.json([slot]),
    'POST /api/bookings': () => Response.json(booking, { status: 201 }),
    'GET /api/bookings/me': () => Response.json([booking]),
    ...overrides,
  };
  const fetch = vi.fn(async (input: string, init?: RequestInit) => {
    const path = new URL(input, 'http://localhost').pathname;
    const handler = routes[(init?.method ?? 'GET') + ' ' + path];
    if (!handler) throw new Error('Unexpected API request: ' + path);
    return handler();
  });
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

function showBookingForm() {
  render(<MemoryRouter initialEntries={['/laboratories/1/book?workstation=2']}>
    <Routes><Route path="/laboratories/:id/book" element={<BookingPage />} /></Routes>
  </MemoryRouter>);
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('reservation flow', () => {
  it('confirms a booking using keyboard navigation and a CSRF-protected request', async () => {
    const fetch = mockApi();
    const user = userEvent.setup();
    showBookingForm();
    await screen.findByLabelText('Date');

    await user.tab();
    expect(screen.getByRole('link', { name: 'Back to laboratory' })).toHaveFocus();
    await user.tab();
    const date = screen.getByLabelText('Date');
    expect(date).toHaveFocus();
    if ((date as HTMLSelectElement).value !== calendarDay(start)) {
      await user.keyboard('{ArrowDown}');
      // jsdom does not implement the native select's keyboard menu.
      if ((date as HTMLSelectElement).value !== calendarDay(start)) {
        await user.selectOptions(date, calendarDay(start));
      }
    }
    await user.tab();
    expect(screen.getByLabelText('Workstation')).toHaveFocus();
    await user.tab();
    const time = screen.getByLabelText('Time slot');
    expect(time).toHaveFocus();
    await user.selectOptions(time, slot.startsAt);
    await user.tab();
    expect(screen.getByRole('button', { name: 'Confirm booking' })).toHaveFocus();
    await user.keyboard('{Enter}');

    expect(await screen.findByText('Booking confirmed')).toBeVisible();
    expect(screen.getByRole('link', { name: 'View my bookings' })).toBeVisible();
    expect(fetch).toHaveBeenCalledWith('/api/bookings', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ workstationId: 2, startsAt: slot.startsAt, endsAt: slot.endsAt }),
    }));
    const request = fetch.mock.calls.find(([path]) => path === '/api/bookings')?.[1];
    expect(new Headers(request?.headers).get('X-CSRF-TOKEN')).toBe('csrf');
  });

  it('clears a conflicting selection and reloads availability', async () => {
    const fetch = mockApi({ 'POST /api/bookings': () => Response.json({ detail: 'Conflict' }, { status: 409 }) });
    const user = userEvent.setup();
    showBookingForm();
    await user.selectOptions(await screen.findByLabelText('Date'), calendarDay(start));
    await user.selectOptions(screen.getByLabelText('Time slot'), slot.startsAt);
    await user.click(screen.getByRole('button', { name: 'Confirm booking' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('That slot is no longer available');
    expect(await screen.findByLabelText('Time slot')).toHaveValue('');
    expect(fetch.mock.calls.filter(([path]) => path.startsWith('/api/availability?'))).toHaveLength(2);
  });

  it('asks before cancelling and keeps cancellation history', async () => {
    let cancelled = false;
    const fetch = mockApi({
      'GET /api/bookings/me': () => Response.json([{ ...booking, status: cancelled ? 'CANCELLED' : 'CONFIRMED' }]),
      'POST /api/bookings/3/cancel': () => {
        cancelled = true;
        return Response.json({ ...booking, status: 'CANCELLED' });
      },
    });
    const user = userEvent.setup();
    render(<MemoryRouter><MyBookings /></MemoryRouter>);
    await user.click(await screen.findByRole('button', { name: 'Cancel reservation #3' }));
    expect(fetch).not.toHaveBeenCalledWith('/api/bookings/3/cancel', expect.anything());
    await user.click(screen.getByRole('button', { name: 'Confirm cancellation' }));
    expect(await screen.findByText('Booking cancelled. The slot is available again.')).toBeVisible();
    expect(await screen.findByText('Cancelled', { exact: true })).toBeVisible();
  });

  it('shows the fallback and lets the user prefill a slot without booking it', async () => {
    const fetch = mockApi({
      'POST /api/recommendations': () => Response.json({
        source: 'FALLBACK', slot, explanation: 'This is the earliest available slot.',
      }),
    });
    const user = userEvent.setup();
    showBookingForm();
    await user.type(await screen.findByLabelText('What do you need?'), 'Java in the morning');
    await user.click(screen.getByRole('button', { name: 'Find a recommendation' }));
    expect(await screen.findByText('Earliest available slot')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Use this slot' }));
    expect(await screen.findByLabelText('Time slot')).toHaveValue(slot.startsAt);
    expect(screen.getByRole('button', { name: 'Confirm booking' })).toBeEnabled();
    expect(fetch).not.toHaveBeenCalledWith('/api/bookings', expect.anything());
  });

  it('shows errors from recommendation requests as readable messages', async () => {
    mockApi({
      'POST /api/recommendations': () => Response.json({ detail: 'Choose a different laboratory.' }, { status: 404 }),
    });
    const user = userEvent.setup();
    showBookingForm();
    await user.type(await screen.findByLabelText('What do you need?'), 'Python');
    await user.click(screen.getByRole('button', { name: 'Find a recommendation' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Choose a different laboratory.');
  });

  it('shows loading and then the model explanation', async () => {
    let reply: (response: Response) => void = () => {};
    const deferred = new Promise<Response>((resolve) => { reply = resolve; });
    mockApi({ 'POST /api/recommendations': () => deferred });
    const user = userEvent.setup();
    showBookingForm();
    await user.type(await screen.findByLabelText('What do you need?'), 'Java');
    await user.click(screen.getByRole('button', { name: 'Find a recommendation' }));
    expect(screen.getByText('Checking available options…')).toBeVisible();
    reply(Response.json({ source: 'GEMINI', slot, explanation: 'This workstation has Java.' }));
    expect(await screen.findByText('Gemini recommendation')).toBeVisible();
    expect(screen.getByText('This workstation has Java.')).toBeVisible();
  });
});

it('protects admin routes and displays navigation for the signed-in role', async () => {
  mockApi();
  render(<MemoryRouter initialEntries={['/admin']}><App /></MemoryRouter>);
  expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeVisible();
  const nav = screen.getByRole('navigation', { name: 'Main navigation' });
  expect(within(nav).getByRole('link', { name: 'My bookings' })).toBeVisible();
  expect(within(nav).queryByRole('link', { name: 'Manage catalog' })).not.toBeInTheDocument();
});

it('keeps availability search inside the server horizon', () => {
  const now = new Date('2026-11-20T12:00:00Z').getTime();
  const { from, to } = bookingWindow(now);
  expect(from.getTime()).toBeGreaterThan(now);
  expect(to.getTime()).toBeLessThan(now + 7 * 86_400_000);
});
