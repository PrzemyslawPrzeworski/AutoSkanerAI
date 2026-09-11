import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';

/**
 * The three endpoints that must go out **without** a bearer and must never trigger a
 * refresh-on-401.
 *
 * <p>`/api/auth/me` is deliberately absent: it is under the same prefix but it reads the principal, so
 * it needs the header like any other call. Matching the prefix instead of these three paths is the
 * mistake worth naming — `/refresh` answering 401 would then trigger a refresh, whose own 401 would
 * trigger another, and the app would spin until the browser gave up.
 */
const PUBLIC_AUTH_PATHS = ['/api/auth/register', '/api/auth/login', '/api/auth/refresh'];

function isPublicAuthCall(url: string): boolean {
  return PUBLIC_AUTH_PATHS.some((path) => url.endsWith(path));
}

function withBearer<T>(request: HttpRequest<T>, token: string): HttpRequest<T> {
  return request.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
}

/**
 * Attaches the access token, and on a 401 spends the refresh token once before giving up.
 *
 * <p>The retry is issued through `next` rather than by re-entering the chain, so a 401 on the retry
 * propagates to the caller instead of starting a second refresh. One 401 buys at most one refresh
 * attempt per request, and `AuthService.refresh` shares a single in-flight call across all of them.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (isPublicAuthCall(request.url)) {
    return next(request);
  }

  const token = auth.currentAccessToken();
  const attempt = token === null ? request : withBearer(request, token);

  return next(attempt).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401) {
        return throwError(() => error);
      }
      if (!auth.hasStoredSession()) {
        // No refresh token to spend — this is a plain "not logged in", and calling refresh() would
        // only produce a synthetic error to swallow.
        auth.logout();
        void router.navigate(['/login']);
        return throwError(() => error);
      }
      return auth.refresh().pipe(
        switchMap((fresh) => next(withBearer(request, fresh))),
        // Catches both a refused refresh and a retry that came back 401 anyway. Either way the
        // session is over, and the error surfaced is the original one — the caller asked about its
        // own request, not about the refresh it never made.
        catchError((retryError: unknown) => {
          auth.logout();
          void router.navigate(['/login']);
          return throwError(() => (retryError instanceof HttpErrorResponse ? retryError : error));
        }),
      );
    }),
  );
};
