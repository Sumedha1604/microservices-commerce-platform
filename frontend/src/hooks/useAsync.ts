import { useCallback, useEffect, useRef, useState } from 'react';

export function useAsync<T>(loader: () => Promise<T>, dependencies: readonly unknown[]) {
  const loaderRef = useRef(loader);
  loaderRef.current = loader; // eslint-disable-line react-hooks/refs
  const dependencyKey = JSON.stringify(dependencies);
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const run = useCallback(async () => { void dependencyKey; setLoading(true); setError(''); try { const value = await loaderRef.current(); setData(value); return value; } catch (cause) { setError(cause instanceof Error ? cause.message : 'Something went wrong'); return null; } finally { setLoading(false); } }, [dependencyKey]);
  useEffect(() => { void run(); }, [run]); // eslint-disable-line react-hooks/set-state-in-effect
  return { data, setData, loading, error, reload: run };
}
