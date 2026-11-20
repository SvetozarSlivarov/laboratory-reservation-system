import { useCallback } from 'react';
import { Link, useParams } from 'react-router';
import { api } from './api';
import { Notice, PageTitle, Resource, useResource } from './ui';

export function LabList() {
  const labs = useResource(api.laboratories);
  return <>
    <PageTitle title="Laboratories" description="Browse university laboratories and find a workstation." />
    <Resource resource={labs} loading="Loading laboratories…">
      {(items) => items.length ? <ul className="card-grid">
        {items.map((lab) => <li className="panel lab-card" key={lab.id}>
          <div className="lab-icon" aria-hidden="true"><span /></div>
          <h2>{lab.name}</h2>
          <p className="muted">{lab.location}</p>
          {!lab.enabled && <span className="badge">Disabled</span>}
          <Link className="button secondary" to={'/laboratories/' + lab.id}
            aria-label={'View laboratory: ' + lab.name}>View laboratory</Link>
        </li>)}
      </ul> : <Notice>No laboratories are available yet.</Notice>}
    </Resource>
  </>;
}

export function LabDetails({ canBook }: { canBook: boolean }) {
  const { id = '' } = useParams();
  const load = useCallback(async () => {
    const [laboratory, workstations] = await Promise.all([api.laboratory(id), api.workstations(id)]);
    return { laboratory, workstations };
  }, [id]);
  const details = useResource(load);
  const back = { to: '/laboratories', label: 'All laboratories' };
  return <Resource resource={details} loading="Loading laboratory…">
    {({ laboratory, workstations }) => <>
      <PageTitle title={laboratory.name} description={laboratory.location} back={back} />
      <h2 className="section-title">Workstations</h2>
      {workstations.length ? <ul className="record-list">
        {workstations.map((station) => <li className="panel record" key={station.id}>
          <div>
            <h3>{station.code}</h3>
            {station.capabilities && <p className="muted">{station.capabilities}</p>}
            {!station.enabled && <span className="badge">Disabled</span>}
          </div>
          {canBook && <Link className="button secondary" aria-label={'Check availability for ' + station.code}
            to={'/laboratories/' + id + '/book?workstation=' + station.id}>Check availability</Link>}
        </li>)}
      </ul> : <Notice>No workstations are listed for this laboratory.</Notice>}
    </>}
  </Resource>;
}
