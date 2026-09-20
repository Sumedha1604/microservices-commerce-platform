import { Bell } from 'lucide-react';
import { notificationApi } from '../api/services';
import { useAuth } from '../features/auth/AuthContext';
import { useAsync } from '../hooks/useAsync';
import { EmptyState, ErrorState, Loader, Status } from '../components/Feedback';
import { date } from '../utils/format';

export function NotificationsPage() { const { user } = useAuth(); const result = useAsync(() => notificationApi.forUser(user!.userId), [user!.userId]); if (result.loading) return <Loader label="Loading notifications" />; if (result.error) return <ErrorState message={result.error} retry={() => void result.reload()} />; return <section className="page-section narrow"><div className="page-title"><p className="eyebrow">Payment events</p><h1>Notifications</h1><p>These are internal records; the backend does not support read/unread state.</p></div>{!result.data?.items.length ? <EmptyState title="No notifications" text="Payment event notifications will appear here." /> : <div className="notification-list">{result.data.items.map(item => <article key={item.notificationId}><Bell /><div><div className="notification-heading"><h2>{item.subject}</h2><Status value={item.status} /></div><p>{item.message}</p><time dateTime={item.createdAt}>{date(item.createdAt)}</time></div></article>)}</div>}</section>; }
