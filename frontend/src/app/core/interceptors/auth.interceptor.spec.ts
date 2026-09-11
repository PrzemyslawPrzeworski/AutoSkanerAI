import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from '../services/auth.service';
import { AuthTokensResponse } from '../../shared/models/auth.models';

/**
 * The refresh-on-401 retry, and the three ways it must not be allowed to loop or leak.
 *
 * <p>`Router` is a stub because the assertion is *that* the bounce happens, not what the router does
 * with it; a real router would need routes and a location strategy for a fact this spec does not test.
 */
describe('authInterceptor', () => {
  const tokens: AuthTokensResponse = {
    accessToken: 'access-new',
    refreshToken: 'refresh-new',
    expiresIn: 900,
    email: 'owner@example.pl',
  };

  let http: HttpClient;
  let httpMock: HttpTestingController;
  let auth: AuthService;
  let navigate: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    localStorage.clear();
    navigate = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: Router, useValue: { navigate } },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  /** Signs in through the real service so the interceptor reads the token the way production does. */
  function signIn(): void {
    auth.login('owner@example.pl', 'correct-horse').subscribe();
    httpMock
      .expectOne('/api/auth/login')
      .flush({ ...tokens, accessToken: 'access-1', refreshToken: 'refresh-1' });
    expect(auth.currentAccessToken()).toBe('access-1');
  }

  it('attaches the access token to an API call', () => {
    signIn();

    http.post('/api/analyses', {}).subscribe();

    const req = httpMock.expectOne('/api/analyses');
    expect(req.request.headers.get('Authorization')).toBe('Bearer access-1');
    req.flush({});
  });

  it('sends no Authorization header when there is no token', () => {
    http.post('/api/analyses', {}).subscribe({ error: () => undefined });

    const req = httpMock.expectOne('/api/analyses');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });

  it('leaves the three public auth endpoints unauthenticated even when signed in', () => {
    signIn();

    for (const path of ['/api/auth/register', '/api/auth/login', '/api/auth/refresh']) {
      http.post(path, {}).subscribe();
      const req = httpMock.expectOne(path);
      expect(req.request.headers.has('Authorization')).toBe(false);
      req.flush(tokens);
    }
  });

  /**
   * The prefix trap: `/api/auth/me` reads the principal, so matching on `/api/auth/` instead of the
   * three literal paths would send it out with no token and guarantee a 401.
   */
  it('does attach the token to /api/auth/me', () => {
    signIn();

    http.get('/api/auth/me').subscribe();

    const req = httpMock.expectOne('/api/auth/me');
    expect(req.request.headers.get('Authorization')).toBe('Bearer access-1');
    req.flush({ userId: 1, email: 'owner@example.pl' });
  });

  it('refreshes once on a 401 and retries the original request with the new token', () => {
    signIn();

    let body: unknown = null;
    http.post('/api/analyses', { listingText: 'x' }).subscribe((res) => (body = res));

    httpMock.expectOne('/api/analyses').flush(null, { status: 401, statusText: 'Unauthorized' });
    httpMock.expectOne('/api/auth/refresh').flush(tokens);

    const retry = httpMock.expectOne('/api/analyses');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer access-new');
    expect(retry.request.body).toEqual({ listingText: 'x' });
    retry.flush({ fetchStatus: 'text' });

    expect(body).toEqual({ fetchStatus: 'text' });
    expect(navigate).not.toHaveBeenCalled();
  });

  it('gives up after one retry rather than refreshing again', () => {
    signIn();

    let status: number | null = null;
    http.post('/api/analyses', {}).subscribe({ error: (e) => (status = e.status) });

    httpMock.expectOne('/api/analyses').flush(null, { status: 401, statusText: 'Unauthorized' });
    httpMock.expectOne('/api/auth/refresh').flush(tokens);
    httpMock.expectOne('/api/analyses').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(status).toBe(401);
    expect(auth.isAuthenticated()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/login']);
  });

  it('bounces to the login form without a refresh call when there is no stored session', () => {
    let status: number | null = null;
    http.post('/api/analyses', {}).subscribe({ error: (e) => (status = e.status) });

    httpMock.expectOne('/api/analyses').flush(null, { status: 401, statusText: 'Unauthorized' });

    httpMock.expectNone('/api/auth/refresh');
    expect(status).toBe(401);
    expect(navigate).toHaveBeenCalledWith(['/login']);
  });

  /**
   * The loop case. `/api/auth/refresh` answering 401 must be an error the caller sees, not a trigger
   * for another refresh — otherwise a dead refresh token spins the browser until it gives up.
   */
  it('does not try to refresh a failed refresh', () => {
    localStorage.setItem(AuthService.REFRESH_TOKEN_KEY, 'refresh-dead');

    let failed = false;
    auth.refresh().subscribe({ error: () => (failed = true) });

    httpMock
      .expectOne('/api/auth/refresh')
      .flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(failed).toBe(true);
    httpMock.expectNone('/api/auth/refresh');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('passes a non-401 error through untouched', () => {
    signIn();

    let status: number | null = null;
    http.post('/api/analyses', {}).subscribe({ error: (e) => (status = e.status) });

    httpMock.expectOne('/api/analyses').flush(null, { status: 502, statusText: 'Bad Gateway' });

    expect(status).toBe(502);
    httpMock.expectNone('/api/auth/refresh');
    expect(auth.isAuthenticated()).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('spends one refresh for two requests that both come back 401', () => {
    signIn();

    http.get('/api/analyses/1').subscribe({ error: () => undefined });
    http.get('/api/analyses/2').subscribe({ error: () => undefined });

    httpMock.expectOne('/api/analyses/1').flush(null, { status: 401, statusText: 'Unauthorized' });
    httpMock.expectOne('/api/analyses/2').flush(null, { status: 401, statusText: 'Unauthorized' });

    httpMock.expectOne('/api/auth/refresh').flush(tokens);

    for (const path of ['/api/analyses/1', '/api/analyses/2']) {
      const retry = httpMock.expectOne(path);
      expect(retry.request.headers.get('Authorization')).toBe('Bearer access-new');
      retry.flush({});
    }
  });
});
