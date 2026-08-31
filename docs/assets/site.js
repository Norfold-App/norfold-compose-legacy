(() => {
  const root = document.documentElement;
  const stored = localStorage.getItem('norfold-theme-mode') || 'system';
  const systemDark = () => matchMedia('(prefers-color-scheme: dark)').matches;

  const applyTheme = mode => {
    const resolved = mode === 'system' ? (systemDark() ? 'dark' : 'light') : mode;
    root.dataset.theme = resolved;
    root.dataset.themeMode = mode;
    localStorage.setItem('norfold-theme-mode', mode);
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.content = resolved === 'dark' ? '#070A12' : '#F7F8FC';
    document.querySelectorAll('[data-theme-toggle]').forEach(btn => {
      btn.dataset.mode = mode;
      btn.textContent = mode === 'system' ? '◐' : mode === 'dark' ? '☾' : '☀';
      btn.setAttribute('aria-label', `Theme: ${mode}. Click to change.`);
    });
  };
  applyTheme(stored);
  matchMedia('(prefers-color-scheme: dark)').addEventListener?.('change', () => {
    if ((localStorage.getItem('norfold-theme-mode') || 'system') === 'system') applyTheme('system');
  });
  document.querySelectorAll('[data-theme-toggle]').forEach(btn => btn.addEventListener('click', () => {
    const cur = localStorage.getItem('norfold-theme-mode') || 'system';
    applyTheme(cur === 'system' ? 'light' : cur === 'light' ? 'dark' : 'system');
  }));

  // pointer glow
  const glow = document.querySelector('.pointer-glow');
  if (glow && matchMedia('(pointer:fine)').matches) {
    addEventListener('pointermove', e => {
      glow.style.left = `${e.clientX}px`;
      glow.style.top = `${e.clientY}px`;
    }, { passive: true });
  }

  // reveal on scroll
  const observer = new IntersectionObserver(entries => {
    entries.forEach(e => { if (e.isIntersecting) e.target.classList.add('visible'); });
  }, { threshold: .07, rootMargin: '0px 0px -4% 0px' });
  document.querySelectorAll('.reveal').forEach(el => observer.observe(el));

  // tilt on cards
  document.querySelectorAll('[data-tilt]').forEach(card => {
    card.addEventListener('pointermove', e => {
      if (!matchMedia('(pointer:fine)').matches || matchMedia('(prefers-reduced-motion:reduce)').matches) return;
      const r = card.getBoundingClientRect();
      const x = (e.clientX - r.left) / r.width - .5;
      const y = (e.clientY - r.top) / r.height - .5;
      card.style.transform = `perspective(1100px) rotateX(${(-y*2.6).toFixed(2)}deg) rotateY(${(x*3.2).toFixed(2)}deg) translateY(-2px)`;
    }, { passive: true });
    card.addEventListener('pointerleave', () => card.style.transform = '');
  });

  // command palette
  const command = document.querySelector('.command');
  const input = command?.querySelector('input');
  const results = command?.querySelector('.command-results');
  const pages = [
    ['Home','index.html','Private workspace overview'],
    ['Product','product.html','Notes, documents, tasks, chat'],
    ['Privacy','privacy.html','Data practices summary'],
    ['Security','security.html','E2EE and workspace modes'],
    ['Data & Recovery','data.html','Relay, archive, lifecycle'],
    ['Privacy Controls','controls.html','Deletion, blocking, lifespan'],
    ['Development','development.html','Beta status and limits'],
    ['Support Boundaries','support.html','What support can and cannot do'],
    ['Infrastructure','infrastructure.html','Providers and dependencies'],
    ['Account Deletion','account-deletion.html','Deletion process'],
    ['Brand','brand.html','Logo and motion identity'],
  ];
  const render = q => {
    if (!results) return;
    const m = pages.filter(x => x.join(' ').toLowerCase().includes((q || '').toLowerCase()));
    results.innerHTML = m.map(x => `<a href="${x[1]}"><span>${x[0]}</span><small>${x[2]}</small></a>`).join('');
  };
  const open = () => { if (!command) return; command.classList.add('open'); render(''); setTimeout(() => input?.focus(), 0); };
  const close = () => command?.classList.remove('open');
  document.querySelectorAll('[data-command]').forEach(el => el.addEventListener('click', open));
  input?.addEventListener('input', e => render(e.target.value));
  command?.addEventListener('click', e => { if (e.target === command) close(); });
  addEventListener('keydown', e => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); command?.classList.contains('open') ? close() : open(); }
    if (e.key === 'Escape') close();
  });

  // active nav link
  const path = location.pathname.split('/').pop() || 'index.html';
  document.querySelectorAll('.nav-links>a').forEach(a => {
    if (a.getAttribute('href') === path) a.classList.add('active');
  });
})();
