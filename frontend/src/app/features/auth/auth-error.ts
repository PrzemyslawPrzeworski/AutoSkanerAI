import { HttpErrorResponse } from '@angular/common/http';
import { ApiErrorBody } from '../../shared/models/auth.models';

/**
 * Turns a failed auth call into one Polish sentence for the form.
 *
 * <p>The server's own `messages` are preferred, because they are the copy the backend tests pin — the
 * 401 in particular says only that the pair was wrong, never which half, and re-wording it here would
 * be the place that accidentally reintroduces "no such user". The fallbacks exist for the two cases
 * where there is no envelope to read: a status the API does not produce, and a request that never
 * arrived.
 */
export function authErrorMessage(error: unknown): string {
  if (!(error instanceof HttpErrorResponse)) {
    return 'Nie udało się wykonać operacji. Spróbuj ponownie.';
  }
  if (error.status === 0) {
    return 'Brak połączenia z serwerem. Sprawdź internet i spróbuj ponownie.';
  }
  const body = error.error as ApiErrorBody | null;
  const messages = Array.isArray(body?.messages) ? body.messages.filter((m) => !!m) : [];
  if (messages.length > 0) {
    return messages.join(' ');
  }
  switch (error.status) {
    case 401:
      return 'Nieprawidłowy e-mail lub hasło.';
    case 409:
      return 'Konto z tym adresem e-mail już istnieje.';
    case 400:
      return 'Popraw dane w formularzu i spróbuj ponownie.';
    default:
      return 'Serwer nie odpowiedział poprawnie. Spróbuj ponownie za chwilę.';
  }
}
