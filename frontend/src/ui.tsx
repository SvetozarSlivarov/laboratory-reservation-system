import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { errorMessage } from './api';

type Result<T> = { kind: 'loading' } | { kind: 'error'; message: string } | { kind: 'ready'; data: T };

export function useResource<T>(load: () => Promise<T>) {
  const [version, setVersion] = useState(0);
  const [result, setResult] = useState<{ load: typeof load; version: number; value: Result<T> }>();
  useEffect(() => {
    let active = true;
    function set(value: Result<T>) {
      if (active) setResult({ load, version, value });
    }
    load().then((data) => set({ kind: 'ready', data }))
      .catch((error: unknown) => set({ kind: 'error', message: errorMessage(error) }));
    return () => { active = false; };
  }, [load, version]);
  const value: Result<T> = result?.load === load && result.version === version
    ? result.value : { kind: 'loading' };
  return { value, reload: () => setVersion((current) => current + 1) };
}

export function Notice({ children, tone = 'plain' }: { children: ReactNode; tone?: 'plain' | 'error' | 'success' }) {
  return <div className={'notice ' + tone} role={tone === 'error' ? 'alert' : 'status'}>{children}</div>;
}

export function Resource<T>({ resource, loading, children }: {
  resource: ReturnType<typeof useResource<T>>; loading: string; children: (data: T) => ReactNode;
}) {
  if (resource.value.kind === 'loading') return <Notice>{loading}</Notice>;
  if (resource.value.kind === 'error') {
    return <Notice tone="error"><p>{resource.value.message}</p>
      <button className="button secondary" type="button" onClick={resource.reload}>Try again</button>
    </Notice>;
  }
  return children(resource.value.data);
}

export function PageTitle({ title, description, back }: {
  title: string; description?: string; back?: { to: string; label: string };
}) {
  return <div className="page-title">
    {back && <Link className="back" to={back.to}>{back.label}</Link>}
    <h1>{title}</h1>
    {description && <p>{description}</p>}
  </div>;
}
