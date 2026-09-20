import { AlertCircle, Inbox } from 'lucide-react';
export function Loader({ label = 'Loading' }: { label?: string }) { return <div className="loader" role="status"><span className="spinner" />{label}…</div>; }
export function ErrorState({ message, retry }: { message: string; retry?: () => void }) { return <div className="state error-state" role="alert"><AlertCircle /><h2>Something went wrong</h2><p>{message}</p>{retry && <button className="button secondary" onClick={retry}>Try again</button>}</div>; }
export function EmptyState({ title, text }: { title: string; text: string }) { return <div className="state"><Inbox /><h2>{title}</h2><p>{text}</p></div>; }
export function Status({ value }: { value: string }) { return <span className={`badge ${value.toLowerCase()}`}>{value.replaceAll('_', ' ')}</span>; }
