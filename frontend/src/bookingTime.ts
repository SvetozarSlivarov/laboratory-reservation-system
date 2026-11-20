const zone = 'Europe/Sofia';
const day = new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' });
const label = new Intl.DateTimeFormat('en-GB', {
  timeZone: zone, weekday: 'short', month: 'short', day: 'numeric',
});
const time = new Intl.DateTimeFormat('en-GB', {
  timeZone: zone, hour: '2-digit', minute: '2-digit', timeZoneName: 'shortOffset',
});

export function calendarDay(value: string | Date) {
  return day.format(new Date(value));
}

export function dayLabel(value: string | Date) {
  return label.format(new Date(value));
}

export function timeRange(startsAt: string, endsAt: string) {
  return time.format(new Date(startsAt)) + ' – ' + time.format(new Date(endsAt));
}

export function bookingWindow(now = Date.now()) {
  // Keep both bounds inside the server's rolling window, allowing for request time.
  return { from: new Date(now + 30_000), to: new Date(now + 7 * 86_400_000 - 30_000) };
}

export function bookingDates(from: Date, to: Date) {
  const dates = new Map<string, string>();
  for (let timestamp = from.getTime(); timestamp <= to.getTime(); timestamp += 3_600_000) {
    const date = new Date(timestamp);
    dates.set(calendarDay(date), dayLabel(date));
  }
  dates.set(calendarDay(to), dayLabel(to));
  return dates;
}
