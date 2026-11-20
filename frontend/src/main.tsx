import { StrictMode, useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { createRoot } from 'react-dom/client';
import './styles.css';

type Account = { email: string; role: 'USER' | 'ADMIN' };
type Csrf = { headerName: string; token: string; googleEnabled: boolean };

async function csrfToken(): Promise<Csrf> {
  const response = await fetch('/api/auth/csrf');
  if (!response.ok) throw new Error('Could not contact the authentication service.');
  return response.json();
}

async function currentAccount(): Promise<Account | null> {
  const response = await fetch('/api/auth/me');
  if (response.status === 401) return null;
  if (!response.ok) throw new Error('Could not load your account.');
  return response.json();
}

async function authPost(path: string, body?: BodyInit, contentType?: string) {
  const csrf = await csrfToken();
  const headers = new Headers({ [csrf.headerName]: csrf.token });
  if (contentType) headers.set('Content-Type', contentType);
  const response = await fetch('/api/auth/' + path, { method: 'POST', headers, body });
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new Error(problem?.detail ?? 'The request could not be completed.');
  }
}

function App() {
  const [account, setAccount] = useState<Account | null>(null);
  const [googleEnabled, setGoogleEnabled] = useState(false);
  const [register, setRegister] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    Promise.all([csrfToken(), currentAccount()])
      .then(([csrf, signedIn]) => {
        if (!active) return;
        setGoogleEnabled(csrf.googleEnabled);
        setAccount(signedIn);
      })
      .catch(() => {
        if (active) setError('Could not load authentication settings.');
      });
    return () => { active = false; };
  }, []);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const fields = new FormData(form);
    const email = String(fields.get('email'));
    const password = String(fields.get('password'));
    setBusy(true);
    setError('');
    setMessage('');
    try {
      if (register) {
        await authPost('register', JSON.stringify({ email, password }), 'application/json');
        setRegister(false);
        setMessage('Account created. You can now sign in.');
      } else {
        await authPost('login', new URLSearchParams({ email, password }));
        setAccount(await currentAccount());
      }
      form.reset();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Authentication failed.');
    } finally {
      setBusy(false);
    }
  }

  async function logout() {
    setBusy(true);
    setError('');
    try {
      await authPost('logout');
      setAccount(null);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not sign out.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <main>
      <h1>Laboratory Reservation System</h1>
      {error && <p role="alert">{error}</p>}
      {message && <p role="status">{message}</p>}
      {account ? (
        <section aria-label="Account">
          <p>Signed in as {account.email} ({account.role})</p>
          <button disabled={busy} onClick={logout}>Sign out</button>
          {googleEnabled && <p><a href="/oauth2/authorization/google">Link Google account</a></p>}
        </section>
      ) : (
        <section aria-label="Authentication">
          <form onSubmit={submit}>
            <label>Email <input name="email" type="email" autoComplete="email" maxLength={254} required /></label>
            <label>
              Password
              <input name="password" type="password" minLength={register ? 12 : undefined}
                maxLength={128} autoComplete={register ? 'new-password' : 'current-password'} required />
            </label>
            <button disabled={busy} type="submit">{register ? 'Create account' : 'Sign in'}</button>
          </form>
          <button disabled={busy} type="button" onClick={() => {
            setRegister(!register);
            setError('');
            setMessage('');
          }}>
            {register ? 'Back to sign in' : 'Register'}
          </button>
          {googleEnabled && <p><a href="/oauth2/authorization/google">Continue with Google</a></p>}
        </section>
      )}
      <p><a href="/actuator/health">Check backend health</a></p>
    </main>
  );
}

const root = document.getElementById('root');
if (!root) {
  throw new Error('Missing root element');
}

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
