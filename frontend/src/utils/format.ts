export const money = (value: number, currency: string) => { try { return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(value); } catch { return `${value.toFixed(2)} ${currency}`; } };
export const date = (value: string) => new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
