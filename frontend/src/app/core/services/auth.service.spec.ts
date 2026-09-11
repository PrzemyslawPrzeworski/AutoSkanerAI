import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';
import { AuthTokensResponse } from '../../shared/models/auth.models';

/**
 * The token-storage contract. Two properties here are security assertions rather than behaviour:
 * **the access token never reaches `localStorage`** (the reason the split exists at all), and a
 * refused refresh **clears** the stored token instead of leaving a dead session to be retried on
 * every navigation.
 */
describe('AuthService', () => {
  const tokens = (suffix: string): AuthTokensResponse => ({
    accessToken: `access-${suffix}`,
    refreshToken: `refresh-${suffix}`,
    expiresIn: 900,
    email: 'owner@example.pl',
  });

  let service: AuthService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  function stored(): string | null {
    return localStorage.getItem(AuthService.REFRESH_TOKEN_KEY);
  }

  it('logs in, holds the access token in memory and the refresh token in localStorage', () => {
    service.login('owner@example.pl', 'correct-horse').subscribe();

    const req = httpMock.expectOne('/api/auth/login');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ email: 'owner@example.pl', password: 'correct-horse' });
    req.flush(tokens('1'));

    expect(service.isAuthenticated()).toBe(true);
    expect(service.currentAccessToken()).toBe('access-1');
    expect(service.email()).toBe('owner@example.pl');
    expect(stored()).toBe('refresh-1');
  });

  /**
   * The whole point of the in-memory half. If this ever fails, an injected script can read a bearer
   * for the API out of storage without touching the running app.
   */
  it('never writes the access token to localStorage', () => {
    service.login('owner@example.pl', 'correct-horse').subscribe();
    httpMock.expectOne('/api/auth/login').flush(tokens('1'));

    const everything = Object.keys(localStorage)
      .map((key) => `${key}=${localStorage.getItem(key)}`)
      .join('|');
    expect(everything).not.toContain('access-1');
    expect(everything).toContain('refresh-1');
  });

  it('registers the same way it logs in', () => {
    service.register('new@example.pl', 'correct-horse').subscribe();

    const req = httpMock.expectOne('/api/auth/register');
    req.flush({ ...tokens('reg'), email: 'new@example.pl' });

    expect(service.currentAccessToken()).toBe('access-reg');
    expect(service.email()).toBe('new@example.pl');
    expect(stored()).toBe('refresh-reg');
  });

  it('sends the stored refresh token and replaces it with the rotated one', () => {
    localStorage.setItem(AuthService.REFRESH_TOKEN_KEY, 'refresh-old');

    let emitted: string | null = null;
    service.refresh().subscribe((token) => (emitted = token));

    const req = httpMock.expectOne('/api/auth/refresh');
    expect(req.request.body).toEqual({ refreshToken: 'refresh-old' });
    req.flush(tokens('new'));

    expect(emitted).toBe('access-new');
    expect(stored()).toBe('refresh-new');
  });

  /**
   * Without the shared in-flight call, two requests failing 401 together would each rotate the pair
   * and the loser's token would be the one left in storage — a logout at a random later moment.
   */
  it('shares one in-flight refresh between concurrent callers', () => {
    localStorage.setItem(AuthService.REFRESH_TOKEN_KEY, 'refresh-old');

    const first: string[] = [];
    const second: string[] = [];
    service.refresh().subscribe((t) => first.push(t));
    service.refresh().subscribe((t) => second.push(t));

    httpMock.expectOne('/api/auth/refresh').flush(tokens('new'));

    expect(first).toEqual(['access-new']);
    expect(second).toEqual(['access-new']);
  });

  it('starts a fresh call once the previous refresh has finished', () => {
    localStorage.setItem(AuthService.REFRESH_TOKEN_KEY, 'refresh-old');

    service.refresh().subscribe();
    httpMock.expectOne('/api/auth/refresh').flush(tokens('a'));

    service.refresh().subscribe();
    const second = httpMock.expectOne('/api/auth/refresh');
    expect(second.request.body).toEqual({ refreshToken: 'refresh-a' });
    second.flush(tokens('b'));

    expect(stored()).toBe('refresh-b');
  });

  it('errors without a request when there is nothing stored to refresh with', () => {
    let failed = false;
    service.refresh().subscribe({ error: () => (failed = true) });

    expect(failed).toBe(true);
    httpMock.expectNone('/api/auth/refresh');
  });

  it('clears the stored token when the server refuses it', () => {
    localStorage.setItem(AuthService.REFRESH_TOKEN_KEY, 'refresh-dead');

    let failed = false;
    service.refresh().subscribe({ error: () => (failed = true) });
    httpMock
      .expectOne('/api/auth/refresh')
      .flush(
        { status: 401, error: 'x', messages: [], timestamp: '' },
        { status: 401, statusText: 'Unauthorized' },
      );

    expect(failed).toBe(true);
    expect(stored()).toBeNull();
    expect(service.isAuthenticated()).toBe(false);
  });

  it('restores a session from storage after a reload dropped the access token', () => {
    localStorage.setItem(AuthService.REFRESH_TOKEN_KEY, 'refresh-old');
    expect(service.isAuthenticated()).toBe(false);

    let restored: boolean | null = null;
    service.restoreSession().subscribe((ok) => (restored = ok));
    httpMock.expectOne('/api/auth/refresh').flush(tokens('new'));

    expect(restored).toBe(true);
    expect(service.isAuthenticated()).toBe(true);
    expect(service.email()).toBe('owner@example.pl');
  });

  it('answers false with no request when there is no stored session', () => {
    let restored: boolean | null = null;
    service.restoreSession().subscribe((ok) => (restored = ok));

    expect(restored).toBe(false);
    httpMock.expectNone('/api/auth/refresh');
  });

  it('answers false rather than erroring when the stored session is dead', () => {
    localStorage.setItem(AuthService.REFRESH_TOKEN_KEY, 'refresh-dead');

    let restored: boolean | null = null;
    let errored = false;
    service.restoreSession().subscribe({
      next: (ok) => (restored = ok),
      error: () => (errored = true),
    });
    httpMock
      .expectOne('/api/auth/refresh')
      .flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(restored).toBe(false);
    expect(errored).toBe(false);
  });

  it('does not spend a round trip restoring a session it already has', () => {
    service.login('owner@example.pl', 'correct-horse').subscribe();
    httpMock.expectOne('/api/auth/login').flush(tokens('1'));

    let restored: boolean | null = null;
    service.restoreSession().subscribe((ok) => (restored = ok));

    expect(restored).toBe(true);
    httpMock.expectNone('/api/auth/refresh');
  });

  it('drops both tokens on logout', () => {
    service.login('owner@example.pl', 'correct-horse').subscribe();
    httpMock.expectOne('/api/auth/login').flush(tokens('1'));

    service.logout();

    expect(service.isAuthenticated()).toBe(false);
    expect(service.currentAccessToken()).toBeNull();
    expect(service.email()).toBeNull();
    expect(stored()).toBeNull();
    expect(service.hasStoredSession()).toBe(false);
  });
});
