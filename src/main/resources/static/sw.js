const fallbackIcon = '/logo.svg';

self.addEventListener('push', event => {
  let data = {};
  try { data = event.data ? event.data.json() : {}; } catch (ignored) { data = { body: event.data ? event.data.text() : '' }; }
  const title = data.title || 'District ticket alert';
  const options = {
    body: data.body || 'Tickets matching your watch are available.',
    icon: data.icon || fallbackIcon,
    badge: data.badge || fallbackIcon,
    tag: data.tag || 'district-ticket-alert',
    renotify: true,
    data: { url: data.url || '/' }
  };
  event.waitUntil(self.registration.showNotification(title, options));
});

self.addEventListener('notificationclick', event => {
  event.notification.close();
  const target = event.notification.data && event.notification.data.url ? event.notification.data.url : '/';
  event.waitUntil(clients.matchAll({ type: 'window', includeUncontrolled: true }).then(existing => {
    const current = existing.find(client => 'focus' in client);
    return current ? current.focus() : clients.openWindow(target);
  }));
});
