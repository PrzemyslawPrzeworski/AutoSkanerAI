import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of, shareReplay, tap, throwError } from 'rxjs';
import { catchError, map, finalize } from 'rxjs/operators';
import { environment } from '../../../environments/environment';
import { AuthTokensResponse } from '../../shared/models/auth.models';

/**
 * The only place either token is written.
 *
 * <p>The split is deliberate and it is the whole security posture of this feature: the **access
 * token lives in a signal**, so a page reload drops it and an injected script has to reach into a
 * running Angular instance to find it, while the **refresh token lives in `localStorage`**, so the
 * session survives a reload. That is the trade the user chose — an httpOnly cookie was ruled out
 * because `autoskaner-ai.pages.dev` and `autoskanerai.onrender.com` are different sites, which makes
 * a session cookie a third-party cookie that Safari blocks by default.
 *
 * <p>The cost is named rather than waved away: an XSS reads `localStorage` and walks away with a
 * 14-day refresh token, and nothing here can revoke it. See
 * `context/changes/auth-scaffold/change.md` § "Left undone".
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  /**
   * Chosen over `autoskaner.refreshToken` so a stale key from a rename cannot masquerade as a
   * session: anything under a different key is simply not found and the user logs in again.
   */
  static readonly REFRESH_TOKEN_KEY = 'autoskaner.refresh';

  private readonly http = inject(HttpClient);

  private readonly accessToken = signal<string | null>(null);
  private readonly userEmail = signal<string | null>(null);

  /**
   * True only when an access token is in hand. On a fresh page load this is `false` even for a user
   * with a valid refresh token — which is why the guard calls {@link restoreSession} rather than
   * reading this directly.
   */
  readonly isAuthenticated = computed(() => this.accessToken() !== null);
  readonly email = computed(() => this.userEmail());

  /**
   * One shared refresh, so N requests failing 401 at once produce one call rather than N. Without it
   * the losing calls would each rotate the pair and the last write to `localStorage` would win, which
   * is a logout at a random moment rather than an error anyone could read.
   */
  private inFlightRefresh: Observable<string> | null = null;

  currentAccessToken(): string | null {
    return this.accessToken();
  }

  hasStoredSession(): boolean {
    return this.storedRefreshToken() !== null;
  }

  register(email: string, password: string): Observable<AuthTokensResponse> {
    return this.http
      .post<AuthTokensResponse>(`${environment.apiUrl}/api/auth/register`, { email, password })
      .pipe(tap((response) => this.accept(response)));
  }

  login(email: string, password: string): Observable<AuthTokensResponse> {
    return this.http
      .post<AuthTokensResponse>(`${environment.apiUrl}/api/auth/login`, { email, password })
      .pipe(tap((response) => this.accept(response)));
  }

  /**
   * Exchanges the stored refresh token for a new pair. Errors are **not** mapped to a friendly
   * message: the only caller that shows anything is the interceptor, and every failure here means the
   * same thing to a user — the session is over.
   */
  refresh(): Observable<string> {
    if (this.inFlightRefresh) {
      return this.inFlightRefresh;
    }
    const refreshToken = this.storedRefreshToken();
    if (refreshToken === null) {
      return throwError(() => new Error('no stored refresh token'));
    }
    this.inFlightRefresh = this.http
      .post<AuthTokensResponse>(`${environment.apiUrl}/api/auth/refresh`, { refreshToken })
      .pipe(
        tap((response) => this.accept(response)),
        map((response) => response.accessToken),
        // The stored token is cleared here rather than left for the caller: a refresh token the
        // server refused will be refused again, and keeping it means every subsequent navigation
        // spends a round trip rediscovering that.
        catchError((error) => {
          this.clear();
          return throwError(() => error);
        }),
        finalize(() => {
          this.inFlightRefresh = null;
        }),
        shareReplay({ bufferSize: 1, refCount: false }),
      );
    return this.inFlightRefresh;
  }

  /**
   * What the guard calls before deciding. A reload leaves a valid session with no access token, so
   * "not authenticated" and "not yet restored" are different states and only this call tells them
   * apart. Emits `false` instead of erroring, because a dead session is a routing decision here and
   * not an error to report.
   */
  restoreSession(): Observable<boolean> {
    if (this.isAuthenticated()) {
      return of(true);
    }
    if (!this.hasStoredSession()) {
      return of(false);
    }
    return this.refresh().pipe(
      map(() => true),
      catchError(() => of(false)),
    );
  }

  /**
   * Client-side only, and that is a real limitation rather than a simplification: the refresh token
   * being discarded here stays valid at the server for its full 14 days. Revocation needs
   * server-side state — see the change note.
   */
  logout(): void {
    this.clear();
  }

  private accept(response: AuthTokensResponse): void {
    this.accessToken.set(response.accessToken);
    this.userEmail.set(response.email);
    this.writeRefreshToken(response.refreshToken);
  }

  private clear(): void {
    this.accessToken.set(null);
    this.userEmail.set(null);
    this.writeRefreshToken(null);
  }

  /**
   * Guarded because `localStorage` is not always there to be written: Safari in private mode throws
   * on `setItem` once the quota is reached, and a thrown exception in the middle of {@link accept}
   * would leave an access token set and no way to renew it. Failing to persist costs the user a
   * re-login after a reload, which is not worth a broken login.
   */
  private writeRefreshToken(token: string | null): void {
    try {
      if (token === null) {
        localStorage.removeItem(AuthService.REFRESH_TOKEN_KEY);
      } else {
        localStorage.setItem(AuthService.REFRESH_TOKEN_KEY, token);
      }
    } catch {
      // Deliberately silent — see above.
    }
  }

  private storedRefreshToken(): string | null {
    try {
      const stored = localStorage.getItem(AuthService.REFRESH_TOKEN_KEY);
      return stored && stored.length > 0 ? stored : null;
    } catch {
      return null;
    }
  }
}
