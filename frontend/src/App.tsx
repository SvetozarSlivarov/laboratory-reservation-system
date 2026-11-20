import { useEffect, useRef, useState } from 'react';
import { Link, Navigate, NavLink, Route, Routes, useLocation } from 'react-router';
import { api, errorMessage } from './api';
import type { Account } from './api';
import { AuthPage } from './AuthPage';
import { LabList, LabDetails } from './Laboratories';
import { BookingPage } from './BookingPage';
import { MyBookings } from './MyBookings';
import { AdminLabs, AdminLab } from './Admin';
import { Notice, PageTitle, Resource, useResource } from './ui';

export function App() {
  const session = useResource(api.session);
  const { pathname } = useLocation();
  const main = useRef<HTMLElement>(null);
  const lastPage = useRef(pathname);
  const [error, setError] = useState('');
  const [signingOut, setSigningOut] = useState(false);

  useEffect(() => {
    if (lastPage.current !== pathname) {
      lastPage.current = pathname;
      main.current?.focus();
    }
  }, [pathname]);

  const account = session.value.kind === 'ready' ? session.value.data.account : null;

  async function signOut() {
    setSigningOut(true);
    setError('');
    try {
      await api.logout();
      session.reload();
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setSigningOut(false);
    }
  }

  return <>
    <a className="skip-link" href="#main">Skip to content</a>
    <header className="header">
      <div className="header-inner">
        <Link className="brand" to="/">
          <span className="brand-mark" aria-hidden="true"><span /><span /><span /><span /></span>
          <span>Laboratory <span className="brand-secondary">Reservations</span></span>
        </Link>
        {account && <nav aria-label="Main navigation">
          <NavLink to="/laboratories">Laboratories</NavLink>
          {account.role === 'USER' ? <NavLink to="/bookings">My bookings</NavLink>
            : <NavLink to="/admin">Manage catalog</NavLink>}
        </nav>}
        {account && <div className="account">
          <span className="account-email">{account.email}</span>
          <button className="button secondary" type="button" disabled={signingOut} onClick={signOut}>
            {signingOut ? 'Signing out…' : 'Sign out'}
          </button>
        </div>}
      </div>
    </header>
    <main id="main" className="container" tabIndex={-1} ref={main}>
      {error && <Notice tone="error">{error}</Notice>}
      <Resource resource={session} loading="Checking your session…">
        {({ account, googleEnabled }) => <Routes>
          <Route index element={<Navigate replace to={account ? '/laboratories' : '/login'} />} />
          <Route path="/login" element={account ? <Navigate replace to="/laboratories" />
            : <AuthPage key="login" googleEnabled={googleEnabled} onSignIn={session.reload} />} />
          <Route path="/register" element={account ? <Navigate replace to="/laboratories" />
            : <AuthPage key="register" register googleEnabled={googleEnabled} onSignIn={session.reload} />} />
          <Route path="/laboratories" element={account ? <LabList /> : <Navigate replace to="/login" />} />
          <Route path="/laboratories/:id" element={account ? <LabDetails canBook={account.role === 'USER'} />
            : <Navigate replace to="/login" />} />
          <Route path="/laboratories/:id/book" element={<RequireRole account={account} role="USER">
            <BookingPage />
          </RequireRole>} />
          <Route path="/bookings" element={<RequireRole account={account} role="USER"><MyBookings /></RequireRole>} />
          <Route path="/admin" element={<RequireRole account={account} role="ADMIN"><AdminLabs /></RequireRole>} />
          <Route path="/admin/laboratories/:id" element={<RequireRole account={account} role="ADMIN"><AdminLab /></RequireRole>} />
          <Route path="*" element={<PageTitle title="Page not found" back={{ to: '/', label: 'Back to the home page' }} />} />
        </Routes>}
      </Resource>
      {account && session.value.kind === 'ready' && session.value.data.googleEnabled &&
        <footer><a href="/oauth2/authorization/google">Link Google account</a></footer>}
    </main>
  </>;
}

function RequireRole({ account, role, children }: {
  account: Account | null; role: Account['role']; children: React.ReactNode;
}) {
  if (!account) return <Navigate replace to="/login" />;
  if (account.role !== role) return <PageTitle title="Access denied"
    back={{ to: '/laboratories', label: 'Back to laboratories' }} />;
  return children;
}
