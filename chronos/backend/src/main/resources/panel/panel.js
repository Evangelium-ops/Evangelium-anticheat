async function loadServers() {
  const res = await fetch('/api/servers');
  if (res.status === 401) { window.location.href = '/login.html'; return; }
  const servers = await res.json();
  const container = document.getElementById('servers');
  container.innerHTML = '';

  if (servers.length === 0) {
    container.innerHTML = '<div class="empty">No servers have connected yet.</div>';
    return;
  }

  for (const s of servers) {
    const row = document.createElement('div');
    row.className = 'server-row';

    const lastSeen = s.lastSeenMillis ? new Date(Number(s.lastSeenMillis)).toLocaleString() : 'never';
    row.innerHTML = `
      <div class="name">${s.serverName || '(unnamed)'} <span class="badge state-${s.state.toLowerCase()}">${s.state}</span></div>
      <div class="meta">
        <span>code <code>${s.code}</code></span>
        <span>uid <code>${s.instanceId || '(none)'}</code></span>
        <span>${s.mcVersion || 'unknown version'}</span>
        <span>last seen ${lastSeen}</span>
      </div>
    `;

    const actions = document.createElement('div');
    actions.className = 'actions';
    for (const action of ['accept', 'decline', 'disable', 'rebind', 'expire']) {
      const btn = document.createElement('button');
      btn.textContent = action[0].toUpperCase() + action.slice(1) + (action === 'expire' ? ' plugin' : '');
      if (action === 'disable' || action === 'decline') { btn.classList.add('danger'); }
      btn.onclick = () => act(s.code, action);
      actions.appendChild(btn);
    }
    row.appendChild(actions);
    container.appendChild(row);
  }
}

async function act(code, action) {
  await fetch(`/api/servers/${code}/${action}`, { method: 'POST' });
  loadServers();
}

loadServers();
setInterval(loadServers, 5000);
