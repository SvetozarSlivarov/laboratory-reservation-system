export type Account = { id: number; email: string; role: 'USER' | 'ADMIN' };
export type Laboratory = { id: number; name: string; location: string; enabled: boolean };
export type Workstation = { id: number; laboratoryId: number; code: string; capabilities: string | null; enabled: boolean };
export type Slot = { workstation: Workstation; startsAt: string; endsAt: string };
export type Recommendation =
  | { source: 'GEMINI' | 'FALLBACK'; slot: Slot; explanation: string }
  | { source: 'NO_AVAILABILITY'; slot: null; explanation: string };
export type Booking = {
  id: number; workstationId: number; startsAt: string; endsAt: string;
  status: 'CONFIRMED' | 'CANCELLED'; createdAt: string; cancelledAt: string | null;
};

type Csrf = { headerName: string; token: string; googleEnabled: boolean };
type LabFields = Pick<Laboratory, 'name' | 'location' | 'enabled'>;
type StationFields = { code: string; capabilities: string; enabled: boolean };

export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

export function errorMessage(error: unknown) {
  return error instanceof ApiError ? error.message : 'Something went wrong. Please try again.';
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch('/api' + path, init);
  } catch {
    throw new ApiError(0, 'Cannot reach the server. Check your connection and try again.');
  }
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    const detail = response.status < 500 && typeof problem?.detail === 'string'
      && !['[', '{', '<'].includes(problem.detail.trimStart()[0]) ? problem.detail : null;
    const fallback = response.status === 401 ? 'Please sign in again.'
      : response.status === 403 ? 'You do not have access to this action.'
      : response.status === 404 ? 'This item could not be found.'
      : 'The request could not be completed. Please try again.';
    throw new ApiError(response.status, detail || fallback);
  }
  if (response.status === 204) return undefined as T;
  try {
    return await response.json();
  } catch {
    throw new ApiError(response.status, 'The server returned an unexpected response. Please try again.');
  }
}

const csrf = () => request<Csrf>('/auth/csrf');

async function write<T>(path: string, method: 'POST' | 'PATCH', fields?: object | URLSearchParams) {
  const token = await csrf();
  const headers = new Headers({ [token.headerName]: token.token });
  if (fields && !(fields instanceof URLSearchParams)) headers.set('Content-Type', 'application/json');
  return request<T>(path, {
    method, headers, body: fields instanceof URLSearchParams ? fields : JSON.stringify(fields),
  });
}

export const api = {
  async session() {
    const account = await request<Account>('/auth/me').catch((error: unknown) => {
      if (error instanceof ApiError && error.status === 401) return null;
      throw error;
    });
    return { account, googleEnabled: (await csrf()).googleEnabled };
  },
  login: (email: string, password: string) => write<void>('/auth/login', 'POST', new URLSearchParams({ email, password })),
  register: (email: string, password: string) => write<Account>('/auth/register', 'POST', { email, password }),
  logout: () => write<void>('/auth/logout', 'POST'),
  laboratories: () => request<Laboratory[]>('/laboratories'),
  laboratory: (id: string) => request<Laboratory>('/laboratories/' + encodeURIComponent(id)),
  workstations: (id: string) => request<Workstation[]>('/laboratories/' + encodeURIComponent(id) + '/workstations'),
  availability: (id: string, from: Date, to: Date) => {
    const params = new URLSearchParams({ laboratoryId: id, from: from.toISOString(), to: to.toISOString() });
    return request<Slot[]>('/availability?' + params);
  },
  recommend: (laboratoryId: number, from: Date, to: Date, preference: string) =>
    write<Recommendation>('/recommendations', 'POST', {
      laboratoryId, from: from.toISOString(), to: to.toISOString(), preference,
    }),
  bookings: () => request<Booking[]>('/bookings/me'),
  book: (slot: Slot) => write<Booking>('/bookings', 'POST', {
    workstationId: slot.workstation.id, startsAt: slot.startsAt, endsAt: slot.endsAt,
  }),
  cancel: (id: number) => write<Booking>('/bookings/' + id + '/cancel', 'POST'),
  createLab: (fields: LabFields) => write<Laboratory>('/laboratories', 'POST', fields),
  updateLab: (id: number, fields: LabFields) => write<Laboratory>('/laboratories/' + id, 'PATCH', fields),
  createStation: (labId: number, fields: StationFields) =>
    write<Workstation>('/laboratories/' + labId + '/workstations', 'POST', fields),
  updateStation: (id: number, fields: StationFields) => write<Workstation>('/workstations/' + id, 'PATCH', fields),
};
