import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { api, errorMessage } from './api';
import { Notice, PageTitle } from './ui';

export function AuthPage({ register = false, googleEnabled, onSignIn }: {
  register?: boolean; googleEnabled: boolean; onSignIn: () => void;
}) {
  const location = useLocation();
  const navigate = useNavigate();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const registered = location.state?.registered === true;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;
    const data = new FormData(event.currentTarget);
    const email = String(data.get('email')).trim();
    const password = String(data.get('password'));
    setPending(true);
    setError('');
    try {
      if (register) {
        await api.register(email, password);
        navigate('/login', { state: { registered: true } });
      } else {
        await api.login(email, password);
        onSignIn();
      }
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setPending(false);
    }
  }

  return <div className="auth-layout">
    <section className="auth-intro">
      <h2>Find a workstation for your next lab session</h2>
      <p>Browse laboratories, check availability, and manage reservations in one place.</p>
    </section>
    <section className="panel auth-panel" aria-labelledby="auth-heading">
      <div id="auth-heading"><PageTitle title={register ? 'Create an account' : 'Sign in'} /></div>
      {registered && <Notice tone="success">Account created. You can now sign in.</Notice>}
      {error && <Notice tone="error">{error}</Notice>}
      <form className="form" onSubmit={submit}>
        <label>Email address<input type="email" name="email" autoComplete="email" maxLength={254} required /></label>
        <label>Password
          <input type="password" name="password" required maxLength={128} minLength={register ? 12 : undefined}
            autoComplete={register ? 'new-password' : 'current-password'}
            aria-describedby={register ? 'password-hint' : undefined} />
        </label>
        {register && <p className="hint" id="password-hint">Use at least 12 characters.</p>}
        <button className="button" disabled={pending} type="submit">
          {pending ? 'Please wait…' : register ? 'Create account' : 'Sign in'}
        </button>
      </form>
      {googleEnabled && <a className="button secondary google" href="/oauth2/authorization/google">Continue with Google</a>}
      <p className="auth-switch">{register ? 'Already registered?' : 'New to the system?'}{' '}
        <Link to={register ? '/login' : '/register'}>{register ? 'Sign in' : 'Create an account'}</Link>
      </p>
    </section>
  </div>;
}
