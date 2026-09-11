import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import {
  ActivatedRouteSnapshot,
  Router,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { authGuard, guestGuard } from './auth.guard';
import { AuthService } from '../services/auth.service';
import { AuthTokensResponse } from '../../shared/models/auth.models';

/**
 * The reload case is the reason this guard is not a one-line `isAuthenticated` check: the access token
 * lives in memory, so a logged-in user reloading the page arrives with nothing in hand and a naive
 * guard would bounce them. `bouncesTo…` / `restores…` are the two halves of that.
 *
 * <p>A real `Router` here, not a stub, because the assertion is the `UrlTree` the guard returns and
 * building one honestly is the point.
 */
describe('authGuard / guestGuard', () => {
  const tokens: AuthTokensResponse = {
    accessToken: 'access-new',
    refreshToken: 'refresh-new',
    expiresIn: 900,
    email: 'owner@example.pl',
  };

  let httpMock: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  /**
   * Returns a mutable holder rather than the value: the guard only emits once the pending refresh is
   * flushed, which happens after this call returns. Reading the value here would always see `null`.
   */
  type Outcome = { value: boolean | UrlTree | null };

  function runAuthGuard(url: string): Outcome {
    const outcome: Outcome = { value: null };
    TestBed.runInInjectionContext(() => {
      const decision = authGuard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot);
      (decision as { subscribe: (fn: (v: boolean | UrlTree) => void) => void }).subscribe((v) => {
        outcome.value = v;
      });
    });
    return outcome;
  }

  function runGuestGuard(): Outcome {
    const outcome: Outcome = { value: null };
    TestBed.runInInjectionContext(() => {
      const decision = guestGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot);
      (decision as { subscribe: (fn: (v: boolean | UrlTree) => void) => void }).subscribe((v) => {
        outcome.value = v;
      });
    });
    return outcome;
  }

  function serialized(outcome: Outcome): string {
    expect(outcome.value).toBeInstanceOf(UrlTree);
    return router.serializeUrl(outcome.value as UrlTree);
  }

  it('bounces an anonymous visitor to the login form, carrying where they wanted to go', () => {
    const outcome = runAuthGuard('/');

    expect(serialized(outcome)).toBe('/login?returnUrl=%2F');
    httpMock.expectNone('/api/auth/refresh');
  });

  it('restores the session from the stored refresh token instead of bouncing after a reload', () => {
    localStorage.setItem(AuthService.REFRESH_TOKEN_KEY, 'refresh-old');

    const outcome = runAuthGuard('/');
    httpMock.expectOne('/api/auth/refresh').flush(tokens);

    expect(outcome.value).toBe(true);
  });

  it('bounces when the stored refresh token is dead', () => {
    localStorage.setItem(AuthService.REFRESH_TOKEN_KEY, 'refresh-dead');

    const outcome = runAuthGuard('/');
    httpMock
      .expectOne('/api/auth/refresh')
      .flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(serialized(outcome)).toBe('/login?returnUrl=%2F');
  });

  it('lets an anonymous visitor reach the login form', () => {
    expect(runGuestGuard().value).toBe(true);
    httpMock.expectNone('/api/auth/refresh');
  });

  it('sends an already-authenticated visitor away from the login form', () => {
    localStorage.setItem(AuthService.REFRESH_TOKEN_KEY, 'refresh-old');

    const outcome = runGuestGuard();
    httpMock.expectOne('/api/auth/refresh').flush(tokens);

    expect(serialized(outcome)).toBe('/');
  });
});
