import { useCallback, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { Link, useParams } from 'react-router';
import { api, errorMessage } from './api';
import type { Laboratory, Workstation } from './api';
import { Notice, PageTitle, Resource, useResource } from './ui';

export function AdminLabs() {
  const labs = useResource(api.laboratories);
  const [message, setMessage] = useState('');

  return <>
    <PageTitle title="Manage catalog" description="Create laboratories and manage workstation access." />
    {message && <Notice tone="success">{message}</Notice>}
    <div className="management-grid">
      <section className="panel inset">
        <h2>Add laboratory</h2>
        <LabForm submitLabel="Create laboratory" onSave={async (fields) => {
          const lab = await api.createLab(fields);
          setMessage(lab.name + ' was created.');
          labs.reload();
        }} />
      </section>
      <section aria-labelledby="admin-labs">
        <h2 id="admin-labs" className="section-title">All laboratories</h2>
        <Resource resource={labs} loading="Loading catalog…">
          {(items) => items.length ? <ul className="record-list">
            {items.map((lab) => <li className="panel record" key={lab.id}>
              <div><h3>{lab.name}</h3><p className="muted">{lab.location}</p>
                <span className={'badge ' + (lab.enabled ? 'positive' : '')}>
                  {lab.enabled ? 'Enabled' : 'Disabled'}
                </span>
              </div>
              <Link className="button secondary" to={'/admin/laboratories/' + lab.id}
                aria-label={'Manage ' + lab.name}>Manage</Link>
            </li>)}
          </ul> : <Notice>No laboratories have been created.</Notice>}
        </Resource>
      </section>
    </div>
  </>;
}

export function AdminLab() {
  const { id = '' } = useParams();
  const load = useCallback(async () => {
    const [lab, stations] = await Promise.all([api.laboratory(id), api.workstations(id)]);
    return { lab, stations };
  }, [id]);
  const details = useResource(load);
  const [message, setMessage] = useState('');

  function saved(message: string) {
    setMessage(message);
    details.reload();
  }

  return <>
    <PageTitle title="Manage laboratory" back={{ to: '/admin', label: 'Back to manage catalog' }} />
    {message && <Notice tone="success">{message}</Notice>}
    <Resource resource={details} loading="Loading laboratory settings…">
      {({ lab, stations }) => <>
        <div className="management-grid">
          <section className="panel inset">
            <h2>Laboratory settings</h2>
            <LabForm lab={lab} submitLabel="Save laboratory" onSave={async (fields) => {
              const updated = await api.updateLab(lab.id, fields);
              saved(updated.name + ' was updated.');
            }} />
          </section>
          <section className="panel inset">
            <h2>Add workstation</h2>
            <StationForm submitLabel="Create workstation" onSave={async (fields) => {
              const station = await api.createStation(lab.id, fields);
              saved(station.code + ' was created.');
            }} />
          </section>
        </div>
        <h2 className="section-title spaced">Workstations</h2>
        {stations.length ? <ul className="record-list">
          {stations.map((station) => <li className="panel inset" key={station.id}>
            <details>
              <summary><strong>{station.code}</strong> <span className={'badge ' + (station.enabled ? 'positive' : '')}>
                {station.enabled ? 'Enabled' : 'Disabled'}
              </span></summary>
              <StationForm station={station} submitLabel="Save workstation" onSave={async (fields) => {
                const updated = await api.updateStation(station.id, fields);
                saved(updated.code + ' was updated.');
              }} />
            </details>
          </li>)}
        </ul> : <Notice>No workstations have been added.</Notice>}
      </>}
    </Resource>
  </>;
}

type LabFields = Pick<Laboratory, 'name' | 'location' | 'enabled'>;
type StationFields = { code: string; capabilities: string; enabled: boolean };

function LabForm({ lab, submitLabel, onSave }: {
  lab?: Laboratory; submitLabel: string; onSave: (fields: LabFields) => Promise<void>;
}) {
  return <CatalogForm submitLabel={submitLabel} editing={Boolean(lab)} enabled={lab?.enabled}
    onSave={(data) => onSave({
      name: String(data.get('name')).trim(), location: String(data.get('location')).trim(),
      enabled: data.has('enabled'),
    })}>
    <label>Laboratory name<input required name="name" maxLength={120} defaultValue={lab?.name} /></label>
    <label>Location<input required name="location" maxLength={255} defaultValue={lab?.location} /></label>
  </CatalogForm>;
}

function StationForm({ station, submitLabel, onSave }: {
  station?: Workstation; submitLabel: string; onSave: (fields: StationFields) => Promise<void>;
}) {
  return <CatalogForm submitLabel={submitLabel} editing={Boolean(station)} enabled={station?.enabled}
    onSave={(data) => onSave({
      code: String(data.get('code')).trim(), capabilities: String(data.get('capabilities')).trim(),
      enabled: data.has('enabled'),
    })}>
    <label>Workstation code<input required name="code" maxLength={64} defaultValue={station?.code} /></label>
    <label>Capabilities <span className="hint">(optional)</span>
      <textarea name="capabilities" rows={2} maxLength={500} defaultValue={station?.capabilities ?? ''} />
    </label>
  </CatalogForm>;
}

function CatalogForm({ submitLabel, editing, enabled = true, onSave, children }: {
  submitLabel: string; editing: boolean; enabled?: boolean; onSave: (data: FormData) => Promise<void>;
  children: ReactNode;
}) {
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;
    const form = event.currentTarget;
    const data = new FormData(form);
    if (['name', 'location', 'code'].some((key) => data.has(key) && !String(data.get(key)).trim())) {
      setError('Enter a value in each required field.');
      return;
    }
    setPending(true);
    setError('');
    try {
      await onSave(data);
      if (!editing) form.reset();
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setPending(false);
    }
  }

  return <form className="form" onSubmit={submit}>
    {error && <Notice tone="error">{error}</Notice>}
    <fieldset disabled={pending}>
      <legend className="sr-only">{submitLabel}</legend>
      {children}
      <label className="checkbox"><input type="checkbox" name="enabled" defaultChecked={enabled} /> Enabled</label>
      <button className="button" type="submit" disabled={pending}>{pending ? 'Saving…' : submitLabel}</button>
    </fieldset>
  </form>;
}
